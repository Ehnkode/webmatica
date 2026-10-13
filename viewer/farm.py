"""Bloecke in einem Bereich der Aufnahme finden (Farmen, falsche Bloecke).

Ein Bereich ist ``{"x0", "z0", "x1", "z1", "y0", "y1"}`` in Weltkoordinaten,
alle Grenzen einschliesslich. Gelesen wird aus der Aufnahme, also so, wie die
Mod die Chunks zuletzt gesehen hat.
"""

from __future__ import annotations

import re

import numpy as np

from .core.chunk import AIR_NAMES, BLOCKS, base_name

MAX_CHUNKS = 64 * 64      # 1024 x 1024 Bloecke reichen fuer eine Farm
# kann man nicht abbauen (oder nicht einsammeln): in der Auswahl weglassen
UNFARMABLE = frozenset("minecraft:" + n for n in (
    "water", "lava", "bedrock", "barrier", "end_portal", "end_portal_frame", "nether_portal", "end_gateway",
    "bubble_column", "fire", "soul_fire", "command_block", "structure_block", "jigsaw", "reinforced_deepslate"))


def norm_area(a: dict) -> dict:
    out = {k: int(a[k]) for k in ("x0", "z0", "x1", "z1")}
    out["x0"], out["x1"] = sorted((out["x0"], out["x1"]))
    out["z0"], out["z1"] = sorted((out["z0"], out["z1"]))
    out["y0"], out["y1"] = sorted((int(a.get("y0", -64)), int(a.get("y1", 319))))
    n = ((out["x1"] >> 4) - (out["x0"] >> 4) + 1) * ((out["z1"] >> 4) - (out["z0"] >> 4) + 1)
    if n > MAX_CHUNKS:
        raise ValueError("Bereich zu groß (höchstens 1024 × 1024 Blöcke)")
    return out


#: Platzhalter fuer "alle abbaubaren Bloecke" (Ebnen)
ANY = "*"


def solid(name: str) -> bool:
    return name not in AIR_NAMES and name not in UNFARMABLE


class _Mask:
    """Block-State-ID -> True, wenn der Blockname passt. Waechst mit, wenn neue States auftauchen.

    ``names`` ist eine Menge von Blocknamen; enthaelt sie :data:`ANY`, passt jeder abbaubare Block.
    """

    def __init__(self, names):
        self.test = solid if ANY in names else names.__contains__
        self.m = np.zeros(0, bool)

    def __call__(self, part: np.ndarray) -> np.ndarray:
        top = int(part.max()) + 1 if part.size else 0
        if top > len(self.m):
            self.m = np.array([self.test(base_name(s)) for s in BLOCKS.names], bool)
            if top > len(self.m):
                self.m = np.pad(self.m, (0, top - len(self.m)))
        return self.m[part]


def _chunks(rec, dim, a: dict):
    """(Chunk, Ausschnitt [y, z, x], Weltursprung) fuer jeden aufgezeichneten Chunk im Bereich."""
    for cz in range(a["z0"] >> 4, (a["z1"] >> 4) + 1):
        for cx in range(a["x0"] >> 4, (a["x1"] >> 4) + 1):
            ch = rec.chunk(dim, cx, cz)
            if ch is None:
                continue
            ax, bx = max(a["x0"], cx * 16), min(a["x1"], cx * 16 + 15)
            az, bz = max(a["z0"], cz * 16), min(a["z1"], cz * 16 + 15)
            ay, by = max(a["y0"], ch.min_y), min(a["y1"], ch.min_y + ch.height - 1)
            if ay > by:
                continue
            part = ch.blocks[ay - ch.min_y:by - ch.min_y + 1, az & 15:(bz & 15) + 1, ax & 15:(bx & 15) + 1]
            yield part, (ax, ay, az)


def scan(rec, dim, area: dict, top: int = 60) -> dict:
    """Welche Bloecke es im Bereich gibt, mit Anzahl (haeufigste zuerst)."""
    a = norm_area(area)
    counts = np.zeros(len(BLOCKS.names) + 1, np.int64)
    chunks = 0
    for part, _o in _chunks(rec, dim, a):
        chunks += 1
        c = np.bincount(part.reshape(-1), minlength=len(counts))
        if len(c) > len(counts):
            counts = np.pad(counts, (0, len(c) - len(counts)))
        counts[:len(c)] += c
    by: dict[str, int] = {}
    for sid in np.nonzero(counts)[0]:
        name = base_name(BLOCKS.names[int(sid)])
        if name not in AIR_NAMES and name not in UNFARMABLE:
            by[name] = by.get(name, 0) + int(counts[sid])
    blocks = sorted(by.items(), key=lambda kv: -kv[1])[:top]
    return {"blocks": blocks, "chunks": chunks, "area": a}


def count(rec, dim, area: dict, names: set[str]) -> dict[str, int]:
    """Wie viele Bloecke je Name noch im Bereich stehen."""
    a = norm_area(area)
    want = _Mask(names)
    out = {n: 0 for n in names}
    for part, _o in _chunks(rec, dim, a):
        flat = part.reshape(-1)
        hit = flat[want(flat)]
        if ANY in names:
            out[ANY] += int(hit.size)
            continue
        for sid, c in zip(*np.unique(hit, return_counts=True)):
            out[base_name(BLOCKS.names[int(sid)])] += int(c)
    return out


# zaehlen beim Auffuellen als Loch (werden ersetzt)
_FILLABLE = re.compile(r"^minecraft:(water|lava|bubble_column|short_grass|tall_grass|fern|large_fern|dead_bush|snow|"
                       r"seagrass|tall_seagrass|kelp|kelp_plant|vine|glow_lichen)$")


def holes(rec, dim, area: dict, level: int, near: tuple | None = None, limit: int | None = None) -> list[tuple[int, int, int]]:
    """Leere Stellen unter ``level``, die zum Ebnen aufgefuellt werden muessen.

    Je Spalte vom obersten festen Block (oder vom Boden des Bereichs, ``y0``) bis
    ``level`` hinauf. Unten zuerst, damit Baritone immer auf etwas Festes setzt.
    """
    a = norm_area(area)
    a["y1"] = min(a["y1"], level)
    if a["y0"] > a["y1"]:
        return []
    empty = _Mask(set())
    empty.test = lambda n: n in AIR_NAMES or bool(_FILLABLE.match(n))
    pts = []
    for part, (ox, oy, oz) in _chunks(rec, dim, a):
        e = empty(part)
        # Zelle ist ein Loch, wenn sie leer ist und darueber bis level nichts Festes kommt
        above_solid = np.cumsum(~e[::-1], 0)[::-1] > 0
        hole = e & ~above_solid
        ys, zs, xs = np.nonzero(hole)
        pts.extend(zip((xs + ox).tolist(), (ys + oy).tolist(), (zs + oz).tolist()))
    # Spalten ohne aufgezeichneten Chunk bleiben aussen vor (nicht gesehen)
    if near is not None:
        pts.sort(key=lambda p: (p[1], (p[0] - near[0]) ** 2 + (p[2] - near[2]) ** 2))
    else:
        pts.sort(key=lambda p: p[1])
    return pts[:limit] if limit else pts


def positions(rec, dim, area: dict, names: set[str], near: tuple, limit: int = 48,
              skip: set | None = None) -> list[tuple[int, int, int]]:
    """Die ``limit`` naechsten passenden Bloecke zu ``near`` (x, y, z); oben zuerst bei gleichem Abstand.

    Nimmt bevorzugt Bloecke, die an Luft grenzen (freiliegend), damit Baritone
    nicht quer durch den Bereich graebt.
    """
    a = norm_area(area)
    want = _Mask(names)
    air = _Mask(AIR_NAMES)
    cand = []
    for part, (ox, oy, oz) in _chunks(rec, dim, a):
        m = want(part)
        if not m.any():
            continue
        free = np.zeros_like(m)
        ai = air(part)
        free[1:] |= ai[:-1]
        free[:-1] |= ai[1:]
        free[:, 1:] |= ai[:, :-1]
        free[:, :-1] |= ai[:, 1:]
        free[:, :, 1:] |= ai[:, :, :-1]
        free[:, :, :-1] |= ai[:, :, 1:]
        ys, zs, xs = np.nonzero(m)
        wx, wy, wz = xs + ox, ys + oy, zs + oz
        d = (wx - near[0]) ** 2 + (wz - near[2]) ** 2 + 4 * (wy - near[1]) ** 2
        key = d + np.where(free[ys, zs, xs], 0, 1 << 40)      # freiliegende zuerst
        k = min(len(key), limit * 4 + (len(skip) if skip else 0))
        sel = np.argpartition(key, k - 1)[:k] if k < len(key) else np.arange(len(key))
        for i in sel:
            p = (int(wx[i]), int(wy[i]), int(wz[i]))
            if skip and p in skip:
                continue
            cand.append((int(key[i]), -p[1], p))
    cand.sort()
    out = [c[2] for c in cand[:limit * 4]]
    # um den ersten Treffer herum sammeln, damit ein Auftrag eine Stelle abarbeitet
    if out:
        f = out[0]
        out.sort(key=lambda p: (p[0] - f[0]) ** 2 + (p[2] - f[2]) ** 2 + (p[1] - f[1]) ** 2)
    return out[:limit]


def boxes(points, cap: int = 1500) -> list[list[int]]:
    """Einzelne Bloecke zu Reihen entlang x zusammenfassen: [x0, y0, z0, x1, y1, z1]."""
    pts = sorted({(int(x), int(y), int(z)) for x, y, z in points}, key=lambda p: (p[1], p[2], p[0]))
    out: list[list[int]] = []
    for x, y, z in pts:
        if out and out[-1][1] == y and out[-1][2] == z and out[-1][3] == x - 1:
            out[-1][3] = x
        else:
            out.append([x, y, z, x, y, z])
        if len(out) > cap:
            out.pop()
            break
    return out


def flat_points(arr: list) -> list[tuple[int, int, int]]:
    return [(arr[i], arr[i + 1], arr[i + 2]) for i in range(0, len(arr) - 2, 3)]
