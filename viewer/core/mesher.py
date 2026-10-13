"""Chunk -> Dreiecksnetz fuer die 3D-Ansicht.

Pro Block-State werden die vorkompilierten Flaechen (``models.py``) an alle
Positionen dieses States gelegt, verdeckte Flaechen (``cullface`` gegen einen
deckenden Nachbarn) fallen weg. Alles vektorisiert mit numpy.

Binaerformat (little endian), eine Antwort pro Chunk:

    "MSH3"  int32 cx, int32 cz
    3x Pass (deckend, Ausschnitt, durchscheinend):
        uint32 quads
        int16  pos[quads*4*3]     Position in 1/16 Block, x/z relativ zur Chunk-Ecke, y absolut
        uint16 uv[quads*4*2]      Atlas-Pixel * 16
        uint8  rgb[quads*4*3]     Helligkeit x Biomfarbe x Eckenschatten
        uint8  light[quads*4*2]   Blocklicht, Himmelslicht (0..15 * 17), weich ueber die Ecke gemittelt

Eckenschatten (Ambient Occlusion) und weiches Licht wie in Vanilla: je Ecke einer
Flaeche zaehlen die zwei Nachbarbloecke an den Seiten und der in der Ecke davor.
Ohne Lichtdaten (alte Aufnahme) ist alles voll vom Himmel beleuchtet.
"""

from __future__ import annotations

import struct

import numpy as np

from .chunk import Chunk
from .colors import PALETTE
from .models import (DIR_VEC, MODELS, PASS_CUTOUT, PASS_OPAQUE, PASS_TRANSLUCENT, SHADE, TINT_FIXED,
                     TINT_FOLIAGE, TINT_GRASS, TINT_NONE, TINT_WATER)

MAGIC = b"MSH3"

# Hoehlen unterhalb dieser Hoehe, in die kein Licht faellt, werden nicht gezeichnet
# (Oberwelt, nur ohne Schichten-Schnitt). Spart viele Flaechen, die man nie sieht.
CAVE_Y = 55
# Eckenschatten: 0 (beide Seiten zu) .. 3 (frei) -> Helligkeit
AO_LEVEL = np.array([0.5, 0.65, 0.82, 1.0], np.float32)


class _Lut:
    """Pro Block-ID: deckt voll ab? / Fluessigkeit? (waechst mit der Registry)."""

    def __init__(self):
        self.full = np.zeros(0, bool)
        self.water = np.zeros(0, bool)
        self.lava = np.zeros(0, bool)

    def sync(self, n: int):
        k = self.full.shape[0]
        if k >= n:
            return
        full, water, lava = np.zeros(n, bool), np.zeros(n, bool), np.zeros(n, bool)
        full[:k], water[:k], lava[:k] = self.full, self.water, self.lava
        for i in range(k, n):
            m = MODELS.get(i)
            full[i] = m.full_opaque
            water[i] = m.fluid == "water"
            lava[i] = m.fluid == "lava"
        self.full, self.water, self.lava = full, water, lava


LUT = _Lut()


def _padded(ch: Chunk, neighbors: dict, y0: int, y1: int, get=lambda c: c.blocks, below=0, above=0,
            missing=0, dtype=np.int64) -> np.ndarray:
    """Werte fuer y0..y1 (inklusive) mit einem Rand von 1 Block aus den Nachbar-Chunks.

    ``get`` liefert das Feld eines Chunks (Bloecke oder Licht), ``below``/``above``
    fuellt ausserhalb der Welthoehe, ``missing`` fehlende Nachbarn.
    """
    h = y1 - y0 + 1

    def take(c: Chunk | None, ys, zs, xs):
        shape = (ys.stop - ys.start, zs.stop - zs.start, xs.stop - xs.start)
        arr = get(c) if c is not None else None
        if arr is None:
            return np.full(shape, missing, dtype)
        out = np.empty(shape, dtype)
        lo = ys.start + y0 - 1 - c.min_y
        rows = np.arange(shape[0]) + lo
        out[rows < 0] = below
        out[rows >= c.height] = above
        ok = (rows >= 0) & (rows < c.height)
        if ok.any():
            out[ok] = arr[rows[ok], zs, xs]
        return out

    full_y = slice(0, h + 2)
    P = np.full((h + 2, 18, 18), missing, dtype)
    P[:, 1:17, 1:17] = take(ch, full_y, slice(0, 16), slice(0, 16))
    P[:, 0, 1:17] = take(neighbors.get((0, -1)), full_y, slice(15, 16), slice(0, 16))[:, 0]
    P[:, 17, 1:17] = take(neighbors.get((0, 1)), full_y, slice(0, 1), slice(0, 16))[:, 0]
    P[:, 1:17, 0] = take(neighbors.get((-1, 0)), full_y, slice(0, 16), slice(15, 16))[:, :, 0]
    P[:, 1:17, 17] = take(neighbors.get((1, 0)), full_y, slice(0, 16), slice(0, 1))[:, :, 0]
    return P


def _padded_light(ch: Chunk, neighbors: dict, y0: int, y1: int):
    """(Blocklicht, Himmelslicht) mit Rand wie ``_padded``, oder None ohne Lichtdaten."""
    cache = {}

    def lit(c):
        if id(c) not in cache:
            cache[id(c)] = c.light()
        return cache[id(c)]

    if lit(ch) is None:
        return None
    bl = _padded(ch, neighbors, y0, y1, lambda c: (lit(c) or (None, None))[0], 0, 0, 0, np.uint8)
    sl = _padded(ch, neighbors, y0, y1, lambda c: (lit(c) or (None, None))[1], 0, 15, 15, np.uint8)
    return bl, sl


def _tints(ch: Chunk, ys, zs, xs, kinds, fixed):
    """Farbe je Quad nach Tint-Art (Biom an der Blockposition)."""
    out = np.full((len(kinds), 3), 255.0, np.float32)
    if not len(kinds):
        return out
    need = kinds != TINT_NONE
    if need.any():
        PALETTE.sync()
        bi = ch.biomes[np.clip((ys[need] - ch.min_y) >> 2, 0, ch.biomes.shape[0] - 1), zs[need] >> 2, xs[need] >> 2]
        brgb = PALETTE.biome_rgb[bi.astype(np.intp)]          # (n,3,3)
        k = kinds[need]
        sub = out[need]
        for kind, slot in ((TINT_GRASS, 0), (TINT_FOLIAGE, 1), (TINT_WATER, 2)):
            m = k == kind
            sub[m] = brgb[m, slot]
        m = k == TINT_FIXED
        if m.any():
            sub[m] = fixed[need][m] if fixed.ndim == 2 else fixed
        out[need] = sub
    return out


def mesh_chunk(ch: Chunk, neighbors: dict, y_min: int | None = None, y_max: int | None = None,
               open_bottom: bool = False, caves: bool = False) -> bytes:
    return serialize(mesh_parts(ch, neighbors, y_min, y_max, open_bottom, caves), ch.cx, ch.cz)


def mesh_parts(ch: Chunk, neighbors: dict, y_min: int | None = None, y_max: int | None = None,
               open_bottom: bool = False, caves: bool = False) -> dict:
    """-> {Pass: (pos (n,4,3), uv (n,4,2), rgb (n,4,3), light (n,4,2))} oder fehlende Passes.

    ``caves=True`` laesst dunkle Hoehlen unter ``CAVE_Y`` weg (nur mit Lichtdaten).
    """
    if not MODELS.available:
        raise RuntimeError("assets/blocks.json fehlt: python -m viewer.tools.build_assets --jar <client.jar>")
    from .chunk import BLOCKS
    LUT.sync(len(BLOCKS))

    y0 = ch.min_y if y_min is None else max(ch.min_y, y_min)
    y1 = ch.min_y + ch.height - 1
    if y_max is not None:
        y1 = min(y1, y_max)
    if y1 < y0:
        return {}
    P = _padded(ch, neighbors, y0, y1)
    L = _padded_light(ch, neighbors, y0, y1)
    # Schichten-Schnitt: oberhalb (und bei "nur Ebene" unterhalb) ist Luft, damit die
    # Schnittflaechen geschlossen gezeichnet werden statt hohl. Die Schnittflaechen
    # bekommen volles Licht, sonst waeren sie schwarz.
    if y_max is not None:
        P[-1] = 0
        if L is not None:
            L[1][-1] = 15
    if open_bottom:
        P[0] = 0
        if L is not None:
            L[1][0] = 15
    if L is None:
        L = (np.zeros(P.shape, np.uint8), np.full(P.shape, 15, np.uint8))
        caves = False
    bl, sl = L
    core = P[1:-1, 1:-1, 1:-1]
    full = LUT.full
    occ = full[P]                                   # deckt fuer Eckenschatten
    cave_below = CAVE_Y - y0 if caves else -1       # lokale y-Zeile

    passes = {PASS_OPAQUE: [], PASS_CUTOUT: [], PASS_TRANSLUCENT: []}

    for sid in np.unique(core):
        sid = int(sid)
        if sid == 0:
            continue
        m = MODELS.get(sid)
        ys, zs, xs = np.nonzero(core == sid)
        if m.fluid:
            _fluid(m.fluid, sid, ch, P, ys, zs, xs, y0, passes, L)
            continue
        if not len(m.verts):
            continue
        for q in range(len(m.verts)):
            keep = np.ones(len(ys), bool)
            cd = int(m.cull[q])
            if cd >= 0:
                dx, dy, dz = DIR_VEC[cd]
                nb = P[ys + 1 + dy, zs + 1 + dz, xs + 1 + dx]
                keep = ~full[nb]
                if m.self_cull:
                    keep &= nb != sid
            if not keep.any():
                continue
            kx, ky, kz = xs[keep], ys[keep], zs[keep]
            ao, lt = _shade(m, q, cd, kx, ky, kz, occ, bl, sl)
            if cave_below > 0:
                lit = (lt.max(axis=(1, 2)) > 0) | (ky >= cave_below)
                if not lit.all():
                    kx, ky, kz, ao, lt = kx[lit], ky[lit], kz[lit], ao[lit], lt[lit]
                    if not len(kx):
                        continue
            base = np.stack([kx, ky + y0, kz], -1).astype(np.float32)          # (n,3)
            pos = base[:, None, :] + m.verts[q][None]                         # (n,4,3)
            uv = np.broadcast_to(m.uv[q][None], (len(kx), 4, 2))
            tint = _tints(ch, ky + y0, kz, kx, np.full(len(kx), m.tint[q], np.uint8),
                          np.array(m.fixed_tint, np.float32))
            rgb = (tint * m.shade[q])[:, None, :] * ao[:, :, None]
            passes[int(m.passes[q])].append(_flip(pos, uv, rgb, lt, ao))

    out = {}
    for p, parts in passes.items():
        if parts:
            out[p] = tuple(np.concatenate([x[k] for x in parts]) for k in range(4))
    return out


def _corners(m, q, axis_n):
    """Je Ecke einer Flaeche: Richtung (-1/+1) entlang der beiden anderen Achsen."""
    t = [a for a in range(3) if a != axis_n]
    v = m.verts[q]
    return t, np.where(v[:, t] > 0.5, 1, -1)         # (4,2)


def _shade(m, q, cd, xs, ys, zs, occ, bl, sl):
    """-> Eckenschatten (n,4) float 0.5..1 und Licht (n,4,2) uint8 (Block, Himmel)."""
    n = len(xs)
    nd = cd if cd >= 0 else int(m.normal[q])
    d = DIR_VEC[nd]
    # Zelle vor der Flaeche (fuer Flaechen innerhalb des Blocks: der Block selbst und davor)
    fy, fz, fx = ys + 1 + d[1], zs + 1 + d[2], xs + 1 + d[0]
    if cd < 0:
        b = np.maximum(bl[fy, fz, fx], bl[ys + 1, zs + 1, xs + 1])
        s = np.maximum(sl[fy, fz, fx], sl[ys + 1, zs + 1, xs + 1])
        lt = np.repeat(np.stack([b, s], -1)[:, None, :], 4, 1)
        return np.ones((n, 4), np.float32), (lt.astype(np.uint16) * 17).astype(np.uint8)
    axis_n = int(np.nonzero(d)[0][0])
    t, sg = _corners(m, q, axis_n)
    ao = np.empty((n, 4), np.float32)
    lt = np.empty((n, 4, 2), np.float32)
    b0, s0 = bl[fy, fz, fx].astype(np.float32), sl[fy, fz, fx].astype(np.float32)
    for v in range(4):
        o1 = np.zeros(3, np.int64); o1[t[0]] = sg[v, 0]
        o2 = np.zeros(3, np.int64); o2[t[1]] = sg[v, 1]
        # Achsen-Reihenfolge der Felder: [y, z, x]
        def at(arr, o):
            return arr[fy + o[1], fz + o[2], fx + o[0]]
        s1, s2, c = at(occ, o1), at(occ, o2), at(occ, o1 + o2)
        lvl = np.where(s1 & s2, 0, 3 - s1.astype(np.int8) - s2 - c)
        ao[:, v] = AO_LEVEL[lvl]
        # weiches Licht: Mittel ueber die freien Zellen (die vor der Flaeche zaehlt immer)
        bs, ss, k = b0.copy(), s0.copy(), np.ones(n, np.float32)
        for o, blocked in ((o1, s1), (o2, s2), (o1 + o2, c | (s1 & s2))):
            free = ~blocked
            bs += np.where(free, at(bl, o), 0)
            ss += np.where(free, at(sl, o), 0)
            k += free
        lt[:, v, 0] = bs / k
        lt[:, v, 1] = ss / k
    return ao, np.round(lt * 17).astype(np.uint8)


_ROLL = np.array([1, 2, 3, 0])


def _flip(pos, uv, rgb, lt, ao):
    """Quad um eine Ecke drehen, wo die andere Diagonale die Schatten sauberer teilt."""
    f = ao[:, 0] + ao[:, 2] < ao[:, 1] + ao[:, 3]
    if f.any():
        pos, uv, rgb, lt = np.array(pos), np.array(uv), np.array(rgb), np.array(lt)
        for a in (pos, uv, rgb, lt):
            a[f] = a[f][:, _ROLL]
    return pos, uv, rgb, lt


def serialize(parts: dict, cx: int = 0, cz: int = 0) -> bytes:
    out = bytearray(MAGIC + struct.pack("<ii", cx, cz))
    for p in (PASS_OPAQUE, PASS_CUTOUT, PASS_TRANSLUCENT):
        if p not in parts:
            out += struct.pack("<I", 0)
            continue
        pos, uv, rgb, lt = parts[p]
        out += struct.pack("<I", pos.shape[0])
        out += np.round(pos * 16).astype("<i2").tobytes()
        out += np.round(uv * 16).astype("<u2").tobytes()
        out += np.clip(rgb, 0, 255).astype(np.uint8).tobytes()
        out += np.ascontiguousarray(lt, np.uint8).tobytes()
    return bytes(out)


# Wasseroberflaeche 14/16 hoch, wenn darueber kein Wasser ist.
_FLUID_TOP = 14 / 16


def _fluid(kind, sid, ch, P, ys, zs, xs, y0, passes, L):
    tex = MODELS.textures.get("block/water_still" if kind == "water" else "block/lava_still")
    if tex is None:
        return
    same = LUT.water if kind == "water" else LUT.lava
    full = LUT.full
    col, row = tex % MODELS.cols, tex // MODELS.cols
    t = MODELS.tile
    u0, v0 = col * t, row * t
    uv_face = np.array([(u0, v0), (u0, v0 + t), (u0 + t, v0 + t), (u0 + t, v0)], np.float32)
    above = P[ys + 2, zs + 1, xs + 1]
    top_h = np.where(same[above], 1.0, _FLUID_TOP).astype(np.float32)
    target = PASS_TRANSLUCENT if kind == "water" else PASS_OPAQUE
    from .models import _face_corners  # gleiche Eckenreihenfolge wie Modelle
    for d in range(6):
        dx, dy, dz = DIR_VEC[d]
        nb = P[ys + 1 + dy, zs + 1 + dz, xs + 1 + dx]
        keep = ~same[nb] & ~full[nb]
        if d == 1:
            keep = ~same[nb] & ~(full[nb] & (top_h >= 1.0))
        if not keep.any():
            continue
        kx, ky, kz, h = xs[keep], ys[keep], zs[keep], top_h[keep]
        name = ["down", "up", "north", "south", "west", "east"][d]
        corners = np.array(_face_corners((0, 0, 0), (1, 1, 1), name), np.float32)   # (4,3)
        pos = np.stack([kx, ky + y0, kz], -1).astype(np.float32)[:, None, :] + corners[None]
        # obere Kante auf Fluessigkeitshoehe absenken
        is_top = corners[:, 1] > 0.5
        pos[:, is_top, 1] = (ky + y0).astype(np.float32)[:, None] + h[:, None]
        uv = np.broadcast_to(uv_face[None], (len(kx), 4, 2))
        if kind == "water":
            tint = _tints(ch, ky + y0, kz, kx, np.full(len(kx), TINT_WATER, np.uint8), np.zeros(3, np.float32))
        else:
            tint = np.full((len(kx), 3), 255.0, np.float32)
        rgb = tint * SHADE[d]
        bl, sl = L
        fy, fz, fx = ky + 1 + dy, kz + 1 + dz, kx + 1 + dx
        b = np.maximum(bl[fy, fz, fx], bl[ky + 1, kz + 1, kx + 1])
        if kind == "lava":
            b = np.full_like(b, 15)
        s = np.maximum(sl[fy, fz, fx], sl[ky + 1, kz + 1, kx + 1])
        lt = np.repeat((np.stack([b, s], -1).astype(np.uint16) * 17).astype(np.uint8)[:, None, :], 4, 1)
        passes[target].append((pos, uv, np.broadcast_to(rgb[:, None, :], (len(kx), 4, 3)), lt))


def mesh_volume(blocks: np.ndarray) -> bytes:
    """Freistehender Block-Quader (Schematic), blocks uint16 (H, L, W) [y, z, x].

    Wird in 16x16-Saeulen zerlegt und wie Chunks vermascht; Positionen relativ
    zur Ecke (0, 0, 0). Kanten bis 2047 Bloecke passen ins int16-Format.
    """
    from .chunk import BIOMES
    H, L, W = blocks.shape
    nz, nx = (L + 15) // 16, (W + 15) // 16
    plains = BIOMES.id("minecraft:plains")
    cols = {}
    for j in range(nz):
        for i in range(nx):
            b = np.zeros((H, 16, 16), np.uint16)
            part = blocks[:, j * 16:(j + 1) * 16, i * 16:(i + 1) * 16]
            b[:, :part.shape[1], :part.shape[2]] = part
            if b.any():
                cols[(i, j)] = Chunk(i, j, 0, b, np.full(((H + 3) // 4, 4, 4), plains, np.uint16))
    merged = {}
    for (i, j), ch in cols.items():
        nb = {(dx, dz): cols.get((i + dx, j + dz)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))}
        for p, (pos, uv, rgb, lt) in mesh_parts(ch, nb, 0).items():
            pos = pos + np.array([i * 16, 0, j * 16], np.float32)
            merged.setdefault(p, []).append((pos, uv, rgb, lt))
    out = {p: tuple(np.concatenate([x[k] for x in v]) for k in range(4)) for p, v in merged.items()}
    return serialize(out)
