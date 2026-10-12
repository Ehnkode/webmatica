"""Schematics laden, drehen/spiegeln, Materialliste und Abgleich mit der Welt.

Formate: Sponge .schem (v1-v3, WorldEdit/FAWE/Axiom), Litematica .litematic,
Vanilla-Strukturen .nbt. Intern ist eine Schematic ein Quader globaler
Block-IDs ``blocks[y, z, x]`` (siehe :data:`chunk.BLOCKS`).
"""

from __future__ import annotations

import gzip
import zlib
from dataclasses import dataclass, field

import numpy as np

from . import nbt
from .chunk import AIR_NAMES, BLOCKS, base_name, state_key, state_props

MAX_VOLUME = 64 * 1024 * 1024


@dataclass
class Schem:
    name: str
    blocks: np.ndarray                    # uint16 (H, L, W)
    block_entities: list = field(default_factory=list)   # [{"pos": [x, y, z], "id": str, "data": dict}]
    # Lage der Ecke blocks[0,0,0] im Koordinatensystem der Datei (Litematica: Regionen
    # koennen negativ liegen). Braucht die Bruecke zu Litematica fuer den Ursprung.
    offset: tuple = (0, 0, 0)

    @property
    def size(self) -> tuple:
        H, L, W = self.blocks.shape
        return W, H, L


# --------------------------------------------------------------------------- Laden

def _decompress(data: bytes) -> bytes:
    if data[:2] == b"\x1f\x8b":
        return gzip.decompress(data)
    try:
        return zlib.decompress(data)
    except zlib.error:
        return data


def _norm_state(s: str) -> str:
    """'stone' / 'minecraft:oak_stairs[half=bottom,facing=north]' -> kanonischer Schluessel."""
    s = s.strip()
    name, _, rest = s.partition("[")
    if ":" not in name:
        name = "minecraft:" + name
    if not rest:
        return name
    props = dict(kv.partition("=")[::2] for kv in rest.rstrip("]").split(",") if kv)
    return state_key({"Name": name, "Properties": props})


def _varints(buf: np.ndarray, count: int) -> np.ndarray:
    b = np.asarray(buf, dtype=np.uint8)
    if not len(b):
        return np.zeros(count, np.int64)
    if b.max() < 128:
        out = b.astype(np.int64)
    else:
        ends = (b & 0x80) == 0
        group = np.concatenate([[0], np.cumsum(ends)[:-1]])
        start = np.flatnonzero(np.concatenate([[True], ends[:-1]]))
        k = np.arange(len(b)) - start[group]
        vals = (b & 0x7F).astype(np.int64) << (7 * k)
        out = np.bincount(group, weights=vals.astype(np.float64)).astype(np.int64)
    if len(out) < count:
        out = np.concatenate([out, np.zeros(count - len(out), np.int64)])
    return out[:count]


def _check_volume(W, H, L):
    if W <= 0 or H <= 0 or L <= 0:
        raise ValueError("leere Schematic")
    if W * H * L > MAX_VOLUME:
        raise ValueError(f"Schematic zu gross ({W}x{H}x{L})")


def _sponge(root: dict, name: str) -> Schem:
    if "Schematic" in root and isinstance(root["Schematic"], dict):
        root = root["Schematic"]
    W, H, L = int(root["Width"]) & 0xFFFF, int(root["Height"]) & 0xFFFF, int(root["Length"]) & 0xFFFF
    _check_volume(W, H, L)
    if "Blocks" in root and isinstance(root["Blocks"], dict):          # v3
        pal, data = root["Blocks"].get("Palette", {}), root["Blocks"].get("Data")
        bes = root["Blocks"].get("BlockEntities") or []
    else:                                                               # v1/v2
        pal, data = root.get("Palette", {}), root.get("BlockData")
        bes = root.get("BlockEntities") or root.get("TileEntities") or []
    if data is None:
        raise ValueError("MCEdit-.schematic (alte Block-IDs) wird nicht unterstuetzt")
    lut = np.zeros(max(pal.values(), default=0) + 1, np.uint16)
    for st, i in pal.items():
        lut[int(i)] = BLOCKS.id(_norm_state(st))
    idx = np.clip(_varints(np.asarray(data).view(np.uint8), W * H * L), 0, len(lut) - 1)
    blocks = lut[idx].reshape(H, L, W)
    out_be = []
    for be in bes:
        pos = be.get("Pos")
        if pos is None or len(pos) < 3:
            continue
        d = be.get("Data") if isinstance(be.get("Data"), dict) else {k: v for k, v in be.items() if k not in ("Pos", "Id")}
        out_be.append({"pos": [int(pos[0]), int(pos[1]), int(pos[2])], "id": str(be.get("Id", "")), "data": d})
    return Schem(name, blocks, out_be)


def _unpack_spanning(longs: np.ndarray, bits: int, count: int) -> np.ndarray:
    """Litematica-Bitpacking: Werte duerfen ueber Long-Grenzen gehen."""
    L = np.asarray(longs, dtype=np.uint64)
    L = np.concatenate([L, np.zeros(1, np.uint64)])
    start = np.arange(count, dtype=np.int64) * bits
    li, off = start >> 6, (start & 63).astype(np.uint64)
    lo = L[li] >> off
    spill = (off.astype(np.int64) + bits) > 64
    hi = np.zeros(count, np.uint64)
    sh = (np.uint64(64) - off[spill])
    hi[spill] = L[li[spill] + 1] << sh
    mask = np.uint64((1 << bits) - 1)
    return ((lo | hi) & mask).astype(np.int64)


def _litematic(root: dict, name: str) -> Schem:
    regions = root.get("Regions") or {}
    if not regions:
        raise ValueError("Litematic ohne Regionen")
    boxes = []
    for rname, r in regions.items():
        p, s = r["Position"], r["Size"]
        sx, sy, sz = int(s["x"]), int(s["y"]), int(s["z"])
        px, py, pz = int(p["x"]), int(p["y"]), int(p["z"])
        lo = (px + min(sx + 1, 0), py + min(sy + 1, 0), pz + min(sz + 1, 0))
        boxes.append((r, lo, (abs(sx), abs(sy), abs(sz))))
    mn = [min(b[1][i] for b in boxes) for i in range(3)]
    mx = [max(b[1][i] + b[2][i] for b in boxes) for i in range(3)]
    W, H, L = mx[0] - mn[0], mx[1] - mn[1], mx[2] - mn[2]
    _check_volume(W, H, L)
    blocks = np.zeros((H, L, W), np.uint16)
    out_be = []
    for r, lo, (w, h, l) in boxes:
        pal = r.get("BlockStatePalette") or []
        lut = np.array([BLOCKS.id(state_key(e)) for e in pal] or [0], np.uint16)
        bits = max(2, (len(pal) - 1).bit_length())
        idx = np.clip(_unpack_spanning(r.get("BlockStates"), bits, w * h * l), 0, len(lut) - 1)
        ox, oy, oz = lo[0] - mn[0], lo[1] - mn[1], lo[2] - mn[2]
        vol = lut[idx].reshape(h, l, w)
        tgt = blocks[oy:oy + h, oz:oz + l, ox:ox + w]
        np.copyto(tgt, vol, where=vol != 0)
        for te in r.get("TileEntities") or []:
            try:
                x, y, z = int(te["x"]) + ox, int(te["y"]) + oy, int(te["z"]) + oz
            except (KeyError, TypeError, ValueError):
                continue
            out_be.append({"pos": [x, y, z], "id": str(te.get("id", "")),
                           "data": {k: v for k, v in te.items() if k not in ("x", "y", "z", "id")}})
    meta = root.get("Metadata") or {}
    return Schem(str(meta.get("Name") or name), blocks, out_be, tuple(mn))


def _structure(root: dict, name: str) -> Schem:
    size = root["size"]
    W, H, L = int(size[0]), int(size[1]), int(size[2])
    _check_volume(W, H, L)
    pal = root.get("palette") or (root.get("palettes") or [[]])[0]
    lut = np.array([BLOCKS.id(state_key(e)) for e in pal] or [0], np.uint16)
    blocks = np.zeros((H, L, W), np.uint16)
    out_be = []
    for b in root.get("blocks") or []:
        x, y, z = (int(v) for v in b["pos"])
        sid = int(b.get("state", 0))
        if 0 <= sid < len(lut) and 0 <= x < W and 0 <= y < H and 0 <= z < L:
            blocks[y, z, x] = lut[sid]
            if isinstance(b.get("nbt"), dict):
                d = b["nbt"]
                out_be.append({"pos": [x, y, z], "id": str(d.get("id", "")),
                               "data": {k: v for k, v in d.items() if k not in ("x", "y", "z", "id")}})
    return Schem(name, blocks, out_be)


def load(data: bytes, filename: str) -> Schem:
    raw = _decompress(data)
    root = nbt.read(raw)
    name = filename.rsplit("/", 1)[-1].rsplit("\\", 1)[-1].rsplit(".", 1)[0]
    if "Regions" in root:
        return _litematic(root, name)
    if "palette" in root or "palettes" in root:
        return _structure(root, name)
    return _sponge(root, name)


# --------------------------------------------------------------------------- Drehen / Spiegeln

_H = ["north", "east", "south", "west"]
_RAIL = {"north_south": "east_west", "east_west": "north_south",
         "ascending_north": "ascending_east", "ascending_east": "ascending_south",
         "ascending_south": "ascending_west", "ascending_west": "ascending_north",
         "north_east": "south_east", "south_east": "south_west", "south_west": "north_west",
         "north_west": "north_east"}
_MIRROR_X = {"east": "west", "west": "east"}


def _rot_dir(d: str, steps: int) -> str:
    return _H[(_H.index(d) + steps) % 4] if d in _H else d


def transform_state(state: str, rot: int, mirror: bool) -> str:
    """Block-State drehen (rot in 90-Grad-Schritten im Uhrzeigersinn) und an X spiegeln."""
    props = state_props(state)
    if not props:
        return state
    p = dict(props)
    if mirror:
        for k in ("facing", "horizontal_facing"):
            if k in p:
                p[k] = _MIRROR_X.get(p[k], p[k])
        if "rotation" in p:
            p["rotation"] = str((16 - int(p["rotation"])) % 16)
        if "east" in props and "west" in props:
            p["east"], p["west"] = props["west"], props["east"]
        if "hinge" in p:
            p["hinge"] = "right" if p["hinge"] == "left" else "left"
        if p.get("type") in ("left", "right") and "chest" in state:
            p["type"] = "right" if p["type"] == "left" else "left"
        if "shape" in p and p["shape"] in ("inner_left", "inner_right", "outer_left", "outer_right"):
            sh = p["shape"]
            p["shape"] = sh.replace("left", "X").replace("right", "left").replace("X", "right")
        if "shape" in p and p["shape"].startswith(("north_", "south_")) and p["shape"] not in ("north_south",):
            a, _, b = p["shape"].partition("_")
            p["shape"] = a + "_" + _MIRROR_X.get(b, b)
        if p.get("shape") in ("ascending_east", "ascending_west"):
            p["shape"] = "ascending_" + _MIRROR_X[p["shape"].split("_")[1]]
    steps = (rot // 90) % 4
    if steps:
        for k in ("facing", "horizontal_facing"):
            if k in p:
                p[k] = _rot_dir(p[k], steps)
        if "rotation" in p:
            p["rotation"] = str((int(p["rotation"]) + 4 * steps) % 16)
        if "axis" in p and steps % 2 == 1:
            p["axis"] = {"x": "z", "z": "x"}.get(p["axis"], p["axis"])
        if all(d in p for d in _H):
            old = {d: p[d] for d in _H}
            for d in _H:
                p[_rot_dir(d, steps)] = old[d]
        if "shape" in p and p["shape"] in _RAIL:
            for _ in range(steps):
                p["shape"] = _RAIL.get(p["shape"], p["shape"])
    return state_key({"Name": base_name(state), "Properties": p})


def transform(s: Schem, rot: int, mirror: bool) -> Schem:
    rot %= 360
    if not rot and not mirror:
        return s
    b = s.blocks
    H, L, W = b.shape
    ids = np.unique(b)
    lut = np.arange(int(ids.max()) + 1, dtype=np.uint16)
    for sid in ids:
        lut[sid] = BLOCKS.id(transform_state(BLOCKS.names[int(sid)], rot, mirror))
    b = lut[b]
    bes = [dict(be, pos=list(be["pos"])) for be in s.block_entities]
    if mirror:
        b = b[:, :, ::-1]
        for be in bes:
            be["pos"][0] = W - 1 - be["pos"][0]
    for _ in range((rot // 90) % 4):
        Hh, Lc, Wc = b.shape
        b = b.transpose(0, 2, 1)[:, :, ::-1]
        for be in bes:
            x, y, z = be["pos"]
            be["pos"] = [Lc - 1 - z, y, x]
    return Schem(s.name, np.ascontiguousarray(b), bes)


# --------------------------------------------------------------------------- Materialliste

_SKIP = {"air", "cave_air", "void_air", "structure_void", "light", "barrier", "piston_head", "moving_piston",
         "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway", "frosted_ice", "bubble_column"}
_ITEM = {"wall_torch": "torch", "soul_wall_torch": "soul_torch", "redstone_wall_torch": "redstone_torch",
         "copper_wall_torch": "copper_torch", "redstone_wire": "redstone", "tripwire": "string",
         "cave_vines": "glow_berries", "cave_vines_plant": "glow_berries", "kelp_plant": "kelp",
         "twisting_vines_plant": "twisting_vines", "weeping_vines_plant": "weeping_vines",
         "bamboo_sapling": "bamboo", "carrots": "carrot", "potatoes": "potato", "beetroots": "beetroot_seeds",
         "wheat": "wheat_seeds", "melon_stem": "melon_seeds", "attached_melon_stem": "melon_seeds",
         "pumpkin_stem": "pumpkin_seeds", "attached_pumpkin_stem": "pumpkin_seeds", "cocoa": "cocoa_beans",
         "sweet_berry_bush": "sweet_berries", "water": "water_bucket", "lava": "lava_bucket",
         "powder_snow": "powder_snow_bucket", "big_dripleaf_stem": "big_dripleaf", "tall_seagrass": "seagrass",
         "dirt_path": "dirt", "farmland": "dirt", "torchflower_crop": "torchflower_seeds",
         "pitcher_crop": "pitcher_pod", "player_wall_head": "player_head"}
_UPPER_HALF = ("half=upper",)


def item_for(state: str) -> tuple[str | None, int]:
    """Block-State -> (Item, Anzahl) fuer die Materialliste; (None, 0) = nicht zaehlen."""
    name = base_name(state).split(":", 1)[-1]
    if name in _SKIP:
        return None, 0
    props = state_props(state)
    if props.get("half") == "upper" or props.get("part") == "head":
        return None, 0
    if name in ("water", "lava") and props.get("level", "0") != "0":
        return None, 0
    if name.startswith("potted_"):
        return "flower_pot", 1
    item = _ITEM.get(name)
    if item is None:
        if name.endswith("_wall_sign"):
            item = name.replace("_wall_sign", "_sign")
        elif name.endswith("_wall_hanging_sign"):
            item = name.replace("_wall_hanging_sign", "_hanging_sign")
        elif name.endswith("_wall_banner"):
            item = name.replace("_wall_banner", "_banner")
        elif name.endswith("_wall_skull") or name.endswith("_wall_head"):
            item = name.replace("_wall_", "_")
        elif name.endswith("_wall_fan"):
            item = name.replace("_wall_fan", "_fan")
        elif name.endswith("candle_cake"):
            item = "cake"
        else:
            item = name
    n = 1
    if props.get("type") == "double" and name.endswith("_slab"):
        n = 2
    for k in ("layers", "pickles", "candles", "eggs", "flower_amount", "segment_amount"):
        if k in props:
            n = int(props[k])
    return item, n


def materials(blocks: np.ndarray) -> dict:
    ids, counts = np.unique(blocks, return_counts=True)
    out: dict[str, int] = {}
    for sid, c in zip(ids, counts):
        item, n = item_for(BLOCKS.names[int(sid)])
        if item:
            out[item] = out.get(item, 0) + int(c) * n
    return out


def _is_air(state: str) -> bool:
    return base_name(state) in AIR_NAMES


def verify(s: Schem, rec, dim, x0: int, y0: int, z0: int, max_points: int = 60000,
           near: tuple | None = None, max_todo: int = 400) -> dict:
    """Vergleicht die platzierte Schematic mit der aufgezeichneten Welt."""
    b = s.blocks
    H, L, W = b.shape
    world = np.zeros_like(b)
    known = np.zeros(b.shape, bool)
    for cz in range((z0 >> 4), ((z0 + L - 1) >> 4) + 1):
        for cx in range((x0 >> 4), ((x0 + W - 1) >> 4) + 1):
            ch = rec.chunk(dim, cx, cz)
            if ch is None:
                continue
            ax, bx = max(x0, cx * 16), min(x0 + W - 1, cx * 16 + 15)
            az, bz = max(z0, cz * 16), min(z0 + L - 1, cz * 16 + 15)
            ay, by = max(y0, ch.min_y), min(y0 + H - 1, ch.min_y + ch.height - 1)
            if ay > by:
                continue
            world[ay - y0:by - y0 + 1, az - z0:bz - z0 + 1, ax - x0:bx - x0 + 1] = \
                ch.blocks[ay - ch.min_y:by - ch.min_y + 1, az & 15:(bz & 15) + 1, ax & 15:(bx & 15) + 1]
            known[ay - y0:by - y0 + 1, az - z0:bz - z0 + 1, ax - x0:bx - x0 + 1] = True

    # Vergleich auf Ebene des Blocknamens (Ausrichtung zaehlt als "falsch gedreht")
    names = BLOCKS.names
    n = len(names)
    base_id = np.zeros(n, np.int32)
    air = np.zeros(n, bool)
    seen: dict[str, int] = {}
    for i in range(n):
        bn = base_name(names[i])
        base_id[i] = seen.setdefault(bn, len(seen))
        air[i] = bn in AIR_NAMES
    want = b.astype(np.intp)
    have = world.astype(np.intp)
    need = ~air[want]
    same_block = base_id[want] == base_id[have]
    exact = want == have
    missing = need & known & air[have]
    wrong = need & known & ~air[have] & ~same_block
    rotated = need & known & same_block & ~exact
    ok = need & known & same_block
    extra = ~need & known & ~air[have]
    unknown = need & ~known

    miss_items: dict[str, int] = {}
    for sid, c in zip(*np.unique(b[missing | wrong], return_counts=True)):
        item, k = item_for(names[int(sid)])
        if item:
            miss_items[item] = miss_items.get(item, 0) + int(c) * k

    def pts(mask):
        ys, zs, xs = np.nonzero(mask)
        if len(xs) > max_points:
            sel = np.linspace(0, len(xs) - 1, max_points).astype(np.intp)
            xs, ys, zs = xs[sel], ys[sel], zs[sel]
        return np.stack([xs, ys, zs], -1).astype(np.int32).reshape(-1).tolist()

    # Bauliste: was fehlt oder falsch ist, mit Weltkoordinaten. Von unten nach oben,
    # innerhalb einer Ebene das Naechste zuerst (so baut man auch von Hand).
    todo = []
    ys, zs, xs = np.nonzero(missing | wrong | rotated)
    if len(xs):
        if near is not None:
            d = (xs + x0 - near[0]) ** 2 + (zs + z0 - near[2]) ** 2
        else:
            d = np.zeros(len(xs))
        order = np.lexsort((d, ys))[:max_todo]
        for i in order:
            x, y, z = int(xs[i]), int(ys[i]), int(zs[i])
            st = names[int(b[y, z, x])]
            kind = "missing" if missing[y, z, x] else "wrong" if wrong[y, z, x] else "rotated"
            entry = [x + x0, y + y0, z + z0, item_for(st)[0] or base_name(st), kind]
            if kind != "missing":
                entry.append(base_name(names[int(world[y, z, x])]))
            todo.append(entry)

    return {
        "todo": todo, "todoTotal": int(len(xs)),
        "stats": {"total": int(need.sum()), "ok": int(ok.sum()), "rotated": int(rotated.sum()),
                  "missing": int(missing.sum()), "wrong": int(wrong.sum()), "extra": int(extra.sum()),
                  "unknown": int(unknown.sum())},
        "missingItems": sorted(miss_items.items(), key=lambda kv: -kv[1]),
        "points": {"missing": pts(missing), "wrong": pts(wrong), "extra": pts(extra), "rotated": pts(rotated)},
    }
