"""Where can mobs spawn? From blocks and the light the mod records.

Rules as in vanilla (1.21) for normal monsters (zombie, skeleton, creeper ...):

- Floor: top face full and solid (full blocks, top/double slabs, upside-down
    stairs), not glass/leaves/ice/bedrock, not light-emitting itself (glowstone ...).
- Space: the block above and the one above that are free (air, grass, flowers ...),
    without water, rails, carpet, pressure plates or redstone.
- Light: block light 0 (in the Nether at most 7, but many mobs spawn there
    regardless of light).

Result per spot: 2 = any time (dark even during the day), 1 = only at night
(sky light > 7, i.e. under open sky).
"""

from __future__ import annotations

import re

import numpy as np

from .core.chunk import AIR_NAMES, BLOCKS, base_name, state_props
from .core.models import MODELS

# Floor: doesn't count despite a full model
_NO_FLOOR = re.compile(r"(^|_)(glass|leaves|ice)$|^bedrock$|^barrier$|^magma_block$|^glowstone$|^sea_lantern$|"
                       r"^shroomlight$|^jack_o_lantern$|froglight$|^beacon$|^spawner$|^light$|^redstone_lamp$|"
                       r"^redstone_block$|^observer$|^target$|^tnt$|stained_glass$|^mangrove_roots$")
# Space: walkable (no collision box), doesn't prevent spawning
_PASSABLE = re.compile(r"^(short_grass|tall_grass|fern|large_fern|dead_bush|bush|firefly_bush|short_dry_grass|tall_dry_grass|"
                       r"dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|cornflower|lily_of_the_valley|"
                       r"torchflower|sunflower|lilac|rose_bush|peony|pitcher_plant|pink_petals|wildflowers|leaf_litter|"
                       r".*_sapling|sugar_cane|vine|glow_lichen|sculk_vein|hanging_roots|cave_vines(_plant)?|"
                       r"brown_mushroom|red_mushroom|crimson_fungus|warped_fungus|crimson_roots|warped_roots|nether_sprouts|"
                       r"twisting_vines(_plant)?|weeping_vines(_plant)?|.*_button|lever|.*_sign|.*_wall_sign|.*_hanging_sign|"
                       r".*_banner|.*_wall_banner|tripwire|tripwire_hook|string|cobweb|ladder|wheat|carrots|potatoes|beetroots|"
                       r"nether_wart|attached_.*_stem|.*_stem|seagrass|structure_void|cave_air|void_air|air)$")


class _Lut:
    """Per block state: 1 = valid floor, 2 = free (space), 4 = fluid."""

    def __init__(self):
        self.v = np.zeros(0, np.uint8)

    def get(self, n: int) -> np.ndarray:
        k = len(self.v)
        if k < n:
            v = np.zeros(n, np.uint8)
            v[:k] = self.v
            for i in range(k, n):
                v[i] = _classify(BLOCKS.names[i], i)
            self.v = v
        return self.v


def _classify(state: str, sid: int) -> int:
    name = base_name(state)
    short = name.split(":")[-1]
    if name in AIR_NAMES and short not in ("light", "barrier"):
        return 2
    props = state_props(state)
    if short in ("water", "lava", "bubble_column") or props.get("waterlogged") == "true":
        return 4
    if short == "snow":
        return 2 if props.get("layers", "1") == "1" else (1 if props.get("layers") == "8" else 0)
    if _PASSABLE.match(short):
        return 2
    if _NO_FLOOR.search(short):
        return 0
    if short.endswith("_slab"):
        return 1 if props.get("type") in ("top", "double") else 0
    if short.endswith("_stairs"):
        return 1 if props.get("half") == "top" else 0
    try:
        return 1 if MODELS.available and _top_full(MODELS.get(sid)) else 0
    except Exception:
        return 0


def _top_full(m) -> bool:
    """Does the block model have a full face on top (whole 16 × 16 at height 1)?

    Not ``full_opaque``: grass blocks have cutout side faces and
    don't count as full there, but are normal floor.
    """
    for q in range(len(m.cull)):
        if m.cull[q] != 1:
            continue
        v = m.verts[q]
        if np.allclose(v[:, 1], 1) and np.isclose(v[:, 0].min(), 0) and np.isclose(v[:, 0].max(), 1) \
                and np.isclose(v[:, 2].min(), 0) and np.isclose(v[:, 2].max(), 1):
            return True
    return False


LUT = _Lut()


def points(ch, dim_key: str = "overworld") -> dict:
    """Spawn spots of a chunk: {"light": bool, "pts": [x, y, z, kind, ...]} in world coordinates.

    ``y`` is the block the mob stands in (one above the floor).
    Without light data (old recording) ``light`` is False and the list empty.
    """
    lit = ch.light()
    if lit is None:
        return {"light": False, "pts": []}
    bl, sl = lit
    cls = LUT.get(len(BLOCKS.names))[ch.blocks]
    floor = cls[:-2] == 1
    free1 = cls[1:-1] == 2
    free2 = cls[2:] == 2
    limit = 7 if dim_key == "the_nether" else 0
    ok = floor & free1 & free2 & (bl[1:-1] <= limit)
    ys, zs, xs = np.nonzero(ok)
    sky = sl[1:-1][ys, zs, xs]
    # without sky (Nether, End) there is no "only at night"
    kind = np.where((sky <= 7) | (dim_key in ("the_nether", "the_end")), 2, 1).astype(np.int32)
    out = np.stack([xs + ch.cx * 16, ys + 1 + ch.min_y, zs + ch.cz * 16, kind], 1).astype(np.int32)
    return {"light": True, "pts": out.reshape(-1).tolist()}


def columns(ch, dim_key: str = "overworld", ymax: int | None = None) -> np.ndarray | None:
    """For the 2D map: per column (16 × 16, [z, x]) the kind of the topmost spawn spot
    that can be seen from above (nothing solid over it), 0 = none. None without light data.

    ``ymax``: layer cut, everything above counts as open and spots above it are ignored.
    """
    lit = ch.light()
    if lit is None:
        return None
    bl, sl = lit
    cls = LUT.get(len(BLOCKS.names))[ch.blocks]
    free = cls == 2
    top = ch.blocks.shape[0]
    if ymax is not None:
        cut = max(0, min(top, ymax - ch.min_y + 1))
        free[cut:] = True
    # open[i]: everything from i upwards is free (no roof)
    open_ = np.flip(np.cumprod(np.flip(free, 0), 0), 0).astype(bool)
    floor = cls[:-2] == 1
    limit = 7 if dim_key == "the_nether" else 0
    ok = floor & free[1:-1] & free[2:] & (bl[1:-1] <= limit) & open_[1:-1]
    if ymax is not None:
        ok[max(0, ymax - ch.min_y):] = False
    any_ = ok.any(0)
    out = np.zeros((16, 16), np.uint8)
    if not any_.any():
        return out
    # topmost spot per column
    h = ok.shape[0] - 1 - np.argmax(ok[::-1], 0)
    zs, xs = np.nonzero(any_)
    ys = h[zs, xs]
    sky = sl[1:-1][ys, zs, xs]
    night_only = (sky > 7) & (dim_key not in ("the_nether", "the_end"))
    out[zs, xs] = np.where(night_only, 1, 2)
    return out


def region_png(rec, dim, rx: int, rz: int, ymax: int | None = None) -> bytes | None:
    """512 × 512 overlay (1 px per block) of a region: red = any time, orange = only at night."""
    import io

    from PIL import Image

    rgba = np.zeros((512, 512, 4), np.uint8)
    found = False
    for lz in range(32):
        for lx in range(32):
            ch = rec.chunk(dim, rx * 32 + lx, rz * 32 + lz)
            if ch is None:
                continue
            k = columns(ch, dim.key, ymax)
            if k is None or not k.any():
                continue
            found = True
            tile = rgba[lz * 16:lz * 16 + 16, lx * 16:lx * 16 + 16]
            tile[k == 2] = (229, 72, 77, 200)
            tile[k == 1] = (245, 165, 36, 110)
    if not found:
        return None
    buf = io.BytesIO()
    Image.fromarray(rgba, "RGBA").save(buf, "PNG", optimize=False, compress_level=3)
    return buf.getvalue()


#: Torches light up to 13 blocks (Manhattan distance); a bit less because walls swallow light
TORCH_REACH = 11
# placed light sources: right after placing, the recording may still have the old light
_LIGHTS = re.compile(r"^minecraft:(.*torch|.*lantern)$")


def torch_plan(rec, dim, area: dict, near: tuple | None = None) -> dict:
    """Where torches would have to go so that no monster spawns in the area any more.

    Greedy: from the lowest uncovered spawn spot, one torch on it, everything within
    reach counts as lit. Light sources already in the area (even when the recording's
    light is not updated yet) count too. ``area`` as in farm (inclusive bounds).
    -> {"spots": n, "torches": [(x, y, z), ...], "chunks": {(cx, cz): torches}}
    """
    pts, lights = [], []
    for cz in range(area["z0"] >> 4, (area["z1"] >> 4) + 1):
        for cx in range(area["x0"] >> 4, (area["x1"] >> 4) + 1):
            ch = rec.chunk(dim, cx, cz)
            if ch is None:
                continue
            p = np.array(points(ch, dim.key)["pts"], np.int32).reshape(-1, 4)
            if len(p):
                m = ((p[:, 0] >= area["x0"]) & (p[:, 0] <= area["x1"]) & (p[:, 2] >= area["z0"]) & (p[:, 2] <= area["z1"])
                     & (p[:, 1] >= area["y0"]) & (p[:, 1] <= area["y1"]))
                pts.append(p[m, :3])
            lut = _light_lut()
            hit = lut[np.minimum(ch.blocks, len(lut) - 1)]
            ys, zs, xs = np.nonzero(hit)
            if len(ys):
                lights.append(np.stack([xs + cx * 16, ys + ch.min_y, zs + cz * 16], 1))
    p = np.concatenate(pts) if pts else np.zeros((0, 3), np.int32)
    out = {"spots": int(len(p)), "torches": [], "chunks": {}}
    if not len(p):
        return out
    lit = np.zeros(len(p), bool)
    for q in (np.concatenate(lights)[:4096] if lights else ()):
        lit |= np.abs(p - q).sum(1) <= TORCH_REACH
    order = np.lexsort((p[:, 2], p[:, 0], p[:, 1]))
    torches = []
    for i in order:
        if lit[i]:
            continue
        q = p[i]
        lit |= np.abs(p - q).sum(1) <= TORCH_REACH
        torches.append((int(q[0]), int(q[1]), int(q[2])))
    if near is not None:
        torches.sort(key=lambda t: (t[0] - near[0]) ** 2 + (t[2] - near[2]) ** 2 + 4 * (t[1] - near[1]) ** 2)
    for x, _y, z in torches:
        out["chunks"][(x >> 4, z >> 4)] = out["chunks"].get((x >> 4, z >> 4), 0) + 1
    out["torches"] = torches
    return out


_light_cache = [np.zeros(0, bool)]


def _light_lut() -> np.ndarray:
    n = len(BLOCKS.names)
    if len(_light_cache[0]) < n:
        _light_cache[0] = np.array([bool(_LIGHTS.match(base_name(s))) for s in BLOCKS.names] + [False], bool)
    return _light_cache[0]
