"""Block-State -> Liste von Flaechen (Quads), so wie Minecraft sie rendert.

Grundlage ist ``assets/blocks.json`` (siehe ``viewer.tools.build_assets``):
Blockstate-Varianten bzw. Multipart-Regeln waehlen Modelle aus, deren
Elemente (Quader mit Flaechen) hier in Bloeckkoordinaten 0..1 umgerechnet
werden, inklusive Element-Rotation und Varianten-Rotation (x/y).

Das Ergebnis wird je globaler Block-ID zwischengespeichert.
"""

from __future__ import annotations

import json
import math
import os
import threading
from dataclasses import dataclass

import numpy as np

from .chunk import BLOCKS, base_name, state_props

ASSETS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets")

DIRS = ["down", "up", "north", "south", "west", "east"]
DIR_VEC = np.array([[0, -1, 0], [0, 1, 0], [0, 0, -1], [0, 0, 1], [-1, 0, 0], [1, 0, 0]], np.int32)
SHADE = np.array([0.5, 1.0, 0.8, 0.8, 0.6, 0.6], np.float32)

PASS_OPAQUE, PASS_CUTOUT, PASS_TRANSLUCENT = 0, 1, 2
TINT_NONE, TINT_GRASS, TINT_FOLIAGE, TINT_WATER, TINT_FIXED = 0, 1, 2, 3, 4

_GRASS = {"grass_block", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern", "sugar_cane",
          "pink_petals", "wildflowers", "bush"}
_FOLIAGE = {"oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine",
            "leaf_litter"}
_FIXED = {"spruce_leaves": (0x61, 0x99, 0x61), "birch_leaves": (0x80, 0xa7, 0x55), "lily_pad": (0x20, 0x80, 0x30),
          "redstone_wire": (0xb0, 0x10, 0x10), "attached_melon_stem": (0xe0, 0xc7, 0x1c),
          "attached_pumpkin_stem": (0xe0, 0xc7, 0x1c), "melon_stem": (0x80, 0xb0, 0x30),
          "pumpkin_stem": (0x80, 0xb0, 0x30)}

# Block-Entities ohne Modell-Elemente: grobe Ersatzquader (in 1/16).
_ENTITY_BOXES = {
    "chest": [(1, 0, 1, 15, 14, 15)], "trapped_chest": [(1, 0, 1, 15, 14, 15)],
    "ender_chest": [(1, 0, 1, 15, 14, 15)], "shulker_box": [(0, 0, 0, 16, 16, 16)],
    "bed": [(0, 0, 0, 16, 9, 16)], "decorated_pot": [(1, 0, 1, 15, 16, 15)],
    "sign": [(7, 0, 7, 9, 10, 9), (0, 8, 7, 16, 16, 9)], "wall_sign": [(0, 4, 14, 16, 12, 16)],
    "hanging_sign": [(1, 0, 7, 15, 10, 9)], "wall_hanging_sign": [(1, 0, 7, 15, 10, 9)],
    "banner": [(7, 0, 7, 9, 16, 9), (1, 2, 7, 15, 16, 8)], "wall_banner": [(1, 0, 14, 15, 14, 16)],
    "skull": [(4, 0, 4, 12, 8, 12)], "head": [(4, 0, 4, 12, 8, 12)], "wall_skull": [(4, 4, 8, 12, 12, 16)],
    "wall_head": [(4, 4, 8, 12, 12, 16)], "conduit": [(5, 5, 5, 11, 11, 11)], "copper_golem_statue": [(4, 0, 4, 12, 14, 12)],
}

_ROT_Y = {0: lambda c: c, 90: lambda c: np.stack([-c[..., 2], c[..., 1], c[..., 0]], -1),
          180: lambda c: np.stack([-c[..., 0], c[..., 1], -c[..., 2]], -1),
          270: lambda c: np.stack([c[..., 2], c[..., 1], -c[..., 0]], -1)}
_ROT_X = {0: lambda c: c, 90: lambda c: np.stack([c[..., 0], c[..., 2], -c[..., 1]], -1),
          180: lambda c: np.stack([c[..., 0], -c[..., 1], -c[..., 2]], -1),
          270: lambda c: np.stack([c[..., 0], -c[..., 2], c[..., 1]], -1)}


def _face_corners(f, t, d):
    """Ecken (oben links, unten links, unten rechts, oben rechts) von aussen gesehen."""
    fx, fy, fz = f
    tx, ty, tz = t
    return {
        "down": [(fx, fy, tz), (fx, fy, fz), (tx, fy, fz), (tx, fy, tz)],
        "up": [(fx, ty, fz), (fx, ty, tz), (tx, ty, tz), (tx, ty, fz)],
        "north": [(tx, ty, fz), (tx, fy, fz), (fx, fy, fz), (fx, ty, fz)],
        "south": [(fx, ty, tz), (fx, fy, tz), (tx, fy, tz), (tx, ty, tz)],
        "west": [(fx, ty, fz), (fx, fy, fz), (fx, fy, tz), (fx, ty, tz)],
        "east": [(tx, ty, tz), (tx, fy, tz), (tx, fy, fz), (tx, ty, fz)],
    }[d]


def _default_uv(f, t, d):
    fx, fy, fz = f
    tx, ty, tz = t
    return {
        "down": [fx, 16 - tz, tx, 16 - fz], "up": [fx, fz, tx, tz],
        "north": [16 - tx, 16 - ty, 16 - fx, 16 - fy], "south": [fx, 16 - ty, tx, 16 - fy],
        "west": [fz, 16 - ty, tz, 16 - fy], "east": [16 - tz, 16 - ty, 16 - fz, 16 - fy],
    }[d]


def _dir_index(vec) -> int:
    v = np.round(vec).astype(int)
    for i, dv in enumerate(DIR_VEC):
        if (v == dv).all():
            return i
    return -1


@dataclass
class Compiled:
    verts: np.ndarray        # (Q,4,3) float32, Block-Koordinaten 0..1
    uv: np.ndarray           # (Q,4,2) float32, Atlas-Pixel
    normal: np.ndarray       # (Q,) int8 Richtung 0..5 (fuer Schattierung)
    cull: np.ndarray         # (Q,) int8 Richtung oder -1
    tint: np.ndarray         # (Q,) uint8
    shade: np.ndarray        # (Q,) float32 Helligkeit
    passes: np.ndarray       # (Q,) uint8
    full_opaque: bool        # verdeckt Nachbarflaechen vollstaendig
    self_cull: bool          # gleiche Bloecke nebeneinander: Zwischenflaechen weglassen
    fixed_tint: tuple        # Farbe fuer TINT_FIXED
    fluid: str = ""          # "water" / "lava" -> Sonderbehandlung im Mesher
    map_rgb: tuple = (110, 110, 110)
    map_alpha: float = 1.0

    @staticmethod
    def empty(**kw):
        z = np.zeros(0)
        return Compiled(np.zeros((0, 4, 3), np.float32), np.zeros((0, 4, 2), np.float32), z.astype(np.int8),
                        z.astype(np.int8), z.astype(np.uint8), z.astype(np.float32), z.astype(np.uint8),
                        False, False, (255, 255, 255), **kw)


class BlockModels:
    def __init__(self, assets: str = ASSETS):
        path = os.path.join(assets, "blocks.json")
        self.available = os.path.exists(path)
        self._lock = threading.Lock()
        self._cache: list = []
        self.version = "0"
        if not self.available:
            return
        st = os.stat(path)
        self.version = f"{int(st.st_mtime)}-{st.st_size}"   # aendert sich mit jedem Neubau der Assets
        with open(path) as f:
            meta = json.load(f)
        self.tile = meta["tile"]
        self.cols = meta["cols"]
        self.rows = meta["rows"]
        self.width = self.cols * self.tile
        self.height = self.rows * self.tile
        self.textures = {n: i for i, n in enumerate(meta["textures"])}
        self.kinds = meta["kinds"]
        self.colors = meta["colors"]
        self.blockstates = meta["blockstates"]
        self.models = meta["models"]

    # ------------------------------------------------------------------ Auswahl

    def _select(self, name: str, props: dict) -> list:
        bs = self.blockstates.get(name)
        if not bs:
            return []
        out = []
        if "variants" in bs:
            for key, v in bs["variants"].items():
                if key == "" or all(props.get(k) == val for k, _, val in
                                    (kv.partition("=") for kv in key.split(","))):
                    out.append(v[0] if isinstance(v, list) else v)
                    break
        for part in bs.get("multipart", []):
            if self._when(part.get("when"), props):
                a = part["apply"]
                out.append(a[0] if isinstance(a, list) else a)
        return out

    def _when(self, cond, props) -> bool:
        if cond is None:
            return True
        if "OR" in cond:
            return any(self._when(c, props) for c in cond["OR"])
        if "AND" in cond:
            return all(self._when(c, props) for c in cond["AND"])
        return all(props.get(k) in str(v).split("|") for k, v in cond.items())

    # ------------------------------------------------------------------ Kompilieren

    def get(self, sid: int) -> Compiled:
        c = self._cache
        if sid < len(c) and c[sid] is not None:
            return c[sid]
        with self._lock:
            while len(self._cache) <= sid:
                self._cache.append(None)
            if self._cache[sid] is None:
                self._cache[sid] = self._compile(BLOCKS.names[sid])
            return self._cache[sid]

    def _tile_uv(self, t: int, uv, rot: int):
        col, row = t % self.cols, t // self.cols
        u1, v1, u2, v2 = uv
        corners = [(u1, v1), (u1, v2), (u2, v2), (u2, v1)]
        k = (rot // 90) % 4
        corners = corners[k:] + corners[:k]
        return [(col * self.tile + u * self.tile / 16.0, row * self.tile + v * self.tile / 16.0) for u, v in corners]

    def _compile(self, state: str) -> Compiled:
        name = base_name(state)
        short = name.split(":", 1)[-1]
        props = state_props(state)
        if short in ("air", "cave_air", "void_air", "light", "structure_void", "barrier"):
            return Compiled.empty()
        if short in ("water", "bubble_column"):
            return Compiled.empty(fluid="water", map_rgb=(63, 118, 228), map_alpha=0.6)
        if short == "lava":
            return Compiled.empty(fluid="lava", map_rgb=(207, 92, 23))
        if not self.available:
            return Compiled.empty()

        V, UV, N, C, T, S, P = [], [], [], [], [], [], []
        tint_kind = (TINT_GRASS if short in _GRASS else TINT_FOLIAGE if short in _FOLIAGE
                     else TINT_FIXED if short in _FIXED else TINT_GRASS)
        entries = self._select(name, props)
        any_elements = False
        for entry in entries:
            model = self.models.get(entry.get("model", "").split(":", 1)[-1])
            if not model:
                continue
            xr, yr = int(entry.get("x", 0)) % 360, int(entry.get("y", 0)) % 360
            els = model["elements"]
            if not els:
                # Block-Entity (Truhe, Schild, ...): Ersatzquader mit Partikeltextur.
                boxes = _ENTITY_BOXES.get(short)
                if boxes is None:
                    for suffix, b in _ENTITY_BOXES.items():
                        if short.endswith("_" + suffix):
                            boxes = b
                            break
                if boxes is None or model.get("particle") is None:
                    continue
                els = [{"from": list(b[:3]), "to": list(b[3:]),
                        "faces": {d: {"t": model["particle"]} for d in DIRS}} for b in boxes]
                if "facing" in props and props["facing"] in ("north", "south", "east", "west"):
                    yr = {"north": 180, "east": 270, "south": 0, "west": 90}[props["facing"]]
            any_elements = True
            for el in els:
                f, t = el["from"], el["to"]
                erot = el.get("rotation")
                for d, face in el["faces"].items():
                    if d not in DIRS:
                        continue
                    corners = np.array(_face_corners(f, t, d), np.float32) / 16.0
                    normal = DIR_VEC[DIRS.index(d)].astype(np.float32)
                    if erot and erot.get("angle"):
                        ang = math.radians(float(erot["angle"]))
                        o = np.array(erot.get("origin", [8, 8, 8]), np.float32) / 16.0
                        ax = erot.get("axis", "y")
                        ca, sa = math.cos(ang), math.sin(ang)
                        if ax == "y":
                            R = np.array([[ca, 0, sa], [0, 1, 0], [-sa, 0, ca]], np.float32)
                        elif ax == "x":
                            R = np.array([[1, 0, 0], [0, ca, -sa], [0, sa, ca]], np.float32)
                        else:
                            R = np.array([[ca, -sa, 0], [sa, ca, 0], [0, 0, 1]], np.float32)
                        corners = (corners - o) @ R.T + o
                        normal = normal @ R.T
                    cull = face.get("cullface")
                    cull_vec = DIR_VEC[DIRS.index(cull)].astype(np.float32) if cull in DIRS else None
                    # Varianten-Rotation um die Blockmitte
                    c = corners - 0.5
                    c = _ROT_Y[yr](_ROT_X[xr](c))
                    corners = c + 0.5
                    normal = _ROT_Y[yr](_ROT_X[xr](normal))
                    if cull_vec is not None:
                        cull_vec = _ROT_Y[yr](_ROT_X[xr](cull_vec))
                    uv = face.get("uv") or _default_uv(f, t, d)
                    V.append(corners)
                    UV.append(self._tile_uv(face["t"], uv, int(face.get("rotation", 0))))
                    ni = _dir_index(normal)
                    N.append(max(ni, 0))
                    C.append(_dir_index(cull_vec) if cull_vec is not None else -1)
                    T.append(tint_kind if "tintindex" in face else TINT_NONE)
                    if el.get("shade") is False:
                        S.append(1.0)
                    elif ni >= 0:
                        S.append(float(SHADE[ni]))
                    else:
                        S.append(0.85)
                    k = self.kinds[face["t"]]
                    P.append(PASS_TRANSLUCENT if k == 2 else PASS_CUTOUT if k == 1 else PASS_OPAQUE)

        if not V:
            return Compiled.empty()

        verts = np.array(V, np.float32)
        full = False
        if len(V) >= 6 and all(p == PASS_OPAQUE for p in P):
            lo, hi = verts.reshape(-1, 3).min(0), verts.reshape(-1, 3).max(0)
            covered = set()
            for q in range(len(V)):
                if C[q] >= 0:
                    vq = verts[q]
                    axis = np.nonzero(DIR_VEC[C[q]])[0][0]
                    others = [a for a in range(3) if a != axis]
                    if all(np.isclose(vq[:, a].min(), 0) and np.isclose(vq[:, a].max(), 1) for a in others):
                        covered.add(C[q])
            full = len(covered) == 6 and np.allclose(lo, 0) and np.allclose(hi, 1)
        self_cull = (not full) and (short.endswith("glass") or short.endswith("leaves") or short in ("ice", "slime_block", "honey_block"))

        # Kartenfarbe: Textur der obersten Flaeche nach oben.
        ups = [i for i in range(len(V)) if N[i] == 1]
        pick = max(ups, key=lambda i: verts[i][:, 1].mean()) if ups else 0
        # Atlas-Index zurueckrechnen aus den UVs
        u0, v0 = np.array(UV[pick]).min(0)
        tidx = int(v0 // self.tile) * self.cols + int(u0 // self.tile)
        col = self.colors[tidx] if 0 <= tidx < len(self.colors) else [110, 110, 110, 1.0]
        rgb = tuple(col[:3])
        if short in _FIXED:
            rgb = tuple(a * b / 255.0 for a, b in zip(rgb, _FIXED[short]))
        alpha = col[3]
        if not full:
            # Kleine Dinge (Fackeln, Blumen) nur halb deckend auf der Karte.
            alpha = min(alpha, 0.999 if len(V) >= 5 else 0.6)

        return Compiled(verts, np.array(UV, np.float32), np.array(N, np.int8), np.array(C, np.int8),
                        np.array(T, np.uint8), np.array(S, np.float32), np.array(P, np.uint8),
                        full, self_cull, _FIXED.get(short, (255, 255, 255)),
                        map_rgb=rgb, map_alpha=float(alpha))


MODELS = BlockModels()
