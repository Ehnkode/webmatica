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
