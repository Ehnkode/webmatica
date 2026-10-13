"""Map colors: block state + biome -> RGB.

Base color = average of the top texture from the atlas. Gray textures
(grass, leaves, water) are tinted with the biome color as in the game.
"""

from __future__ import annotations

import json
import os
import threading

import numpy as np

from .chunk import AIR_NAMES, BIOMES, BLOCKS, base_name

ASSETS = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "assets")

# Biome -> (grass, foliage, water). Values from the game (Java Edition), rounded.
_BIOME_COLORS = {
    "plains": ("91bd59", "77ab2f", "3f76e4"),
    "sunflower_plains": ("91bd59", "77ab2f", "3f76e4"),
    "forest": ("79c05a", "59ae30", "3f76e4"),
    "flower_forest": ("79c05a", "59ae30", "3f76e4"),
    "birch_forest": ("88bb67", "6ba941", "3f76e4"),
    "old_growth_birch_forest": ("88bb67", "6ba941", "3f76e4"),
    "dark_forest": ("507a32", "59ae30", "3f76e4"),
    "pale_garden": ("778272", "878d76", "76889d"),
    "taiga": ("86b783", "68a464", "287082"),
    "old_growth_pine_taiga": ("86b87f", "6da36b", "2d6d77"),
    "old_growth_spruce_taiga": ("86b87f", "6da36b", "2d6d77"),
    "snowy_taiga": ("80b497", "60a17b", "205e83"),
    "jungle": ("59c93c", "30bb0b", "14a2c5"),
    "sparse_jungle": ("64c73f", "3eb80f", "0d8ae3"),
    "bamboo_jungle": ("59c93c", "30bb0b", "14a2c5"),
    "swamp": ("6a7039", "6a7039", "617b64"),
    "mangrove_swamp": ("6a7039", "8db127", "3a7a6a"),
    "savanna": ("bfb755", "aea42a", "2c8b9c"),
    "savanna_plateau": ("bfb755", "aea42a", "2590a8"),
    "windswept_savanna": ("bfb755", "aea42a", "2590a8"),
    "desert": ("bfb755", "aea42a", "32a598"),
    "badlands": ("90814d", "9e814d", "4e7f81"),
    "eroded_badlands": ("90814d", "9e814d", "497f99"),
    "wooded_badlands": ("90814d", "9e814d", "55809e"),
    "windswept_hills": ("8ab689", "6da36b", "007bf7"),
    "windswept_gravelly_hills": ("8ab689", "6da36b", "0e63ab"),
    "windswept_forest": ("8ab689", "6da36b", "0e63ab"),
    "meadow": ("83bb6d", "63a948", "0e4ecf"),
    "cherry_grove": ("b6db61", "b6db61", "5db7ef"),
    "grove": ("80b497", "60a17b", "3d57d6"),
    "snowy_plains": ("80b497", "60a17b", "3d57d6"),
    "ice_spikes": ("80b497", "60a17b", "3d57d6"),
    "snowy_slopes": ("80b497", "60a17b", "3d57d6"),
    "frozen_peaks": ("80b497", "60a17b", "3d57d6"),
    "jagged_peaks": ("80b497", "60a17b", "3d57d6"),
    "stony_peaks": ("9abe4b", "82ac1e", "3f76e4"),
    "stony_shore": ("8ab689", "6da36b", "0d67bb"),
    "beach": ("91bd59", "77ab2f", "157cab"),
    "snowy_beach": ("80b497", "60a17b", "1463a5"),
    "river": ("8eb971", "71a74d", "0084ff"),
    "frozen_river": ("80b497", "60a17b", "185390"),
    "ocean": ("8eb971", "71a74d", "1787d4"),
    "deep_ocean": ("8eb971", "71a74d", "1787d4"),
    "warm_ocean": ("8eb971", "71a74d", "02b0e5"),
    "lukewarm_ocean": ("8eb971", "71a74d", "0d96db"),
    "deep_lukewarm_ocean": ("8eb971", "71a74d", "0d96db"),
    "cold_ocean": ("8eb971", "71a74d", "2080c9"),
    "deep_cold_ocean": ("8eb971", "71a74d", "2080c9"),
    "frozen_ocean": ("80b497", "60a17b", "2570b5"),
    "deep_frozen_ocean": ("80b497", "60a17b", "2570b5"),
    "mushroom_fields": ("55c93f", "2bbb0f", "8a8997"),
    "dripstone_caves": ("91bd59", "77ab2f", "3f76e4"),
    "lush_caves": ("91bd59", "77ab2f", "3f76e4"),
    "deep_dark": ("91bd59", "77ab2f", "3f76e4"),
}
_DEFAULT_BIOME = _BIOME_COLORS["plains"]

# Textures the game tints with the biome color.
_GRASS_TINT = ("grass_block", "short_grass", "tall_grass", "fern", "large_fern",
               "potted_fern", "sugar_cane")
_FOLIAGE_TINT = ("oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
                 "mangrove_leaves", "vine")
_FIXED_TINT = {
    "spruce_leaves": "619961",
    "birch_leaves": "80a755",
    "lily_pad": "208030",
    "attached_melon_stem": "e0c71c",
    "attached_pumpkin_stem": "e0c71c",
}

# Fallbacks for blocks without an atlas tile (e.g. block entity renderers).
_FALLBACK = {
    "chest": (160, 120, 60), "trapped_chest": (160, 120, 60), "ender_chest": (40, 55, 55),
    "barrel": (125, 92, 51), "water": (128, 128, 128), "lava": (207, 92, 23),
    "glass_pane": (200, 220, 230), "chain": (60, 64, 75),
}
TINT_NONE, TINT_GRASS, TINT_FOLIAGE, TINT_WATER = 0, 1, 2, 3


def _hex(h: str) -> tuple:
    return int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)


class Palette:
    """Lookup tables that grow with the BLOCKS/BIOMES registry."""

    def __init__(self, assets: str = ASSETS):
        self._lock = threading.Lock()
        self._tile_rgb: dict[int, tuple] = {}
        self._atlas_blocks: dict = {}
        self._load_atlas(assets)
        self.block_rgb = np.zeros((0, 3), np.float32)
        self.block_alpha = np.zeros(0, np.float32)
        self.block_tint = np.zeros(0, np.uint8)
        self.block_empty = np.zeros(0, bool)
        self.block_water = np.zeros(0, bool)
        self.biome_rgb = np.zeros((0, 3, 3), np.float32)  # [biome, grass/foliage/water, rgb]

    def _load_atlas(self, assets: str):
        try:
            from PIL import Image
            with open(os.path.join(assets, "atlas.json")) as f:
                meta = json.load(f)
            img = np.asarray(Image.open(os.path.join(assets, "atlas.png")).convert("RGBA"), np.float32)
        except (OSError, ValueError):
            return
        t, cols = meta["tile"], meta["cols"]
        self._atlas_blocks = meta.get("blocks", {})
        for i in range(meta["count"]):
            r, c = divmod(i, cols)
            px = img[r * t:(r + 1) * t, c * t:(c + 1) * t].reshape(-1, 4)
            a = px[:, 3] / 255.0
            if a.sum() < 1:
                continue
            rgb = (px[:, :3] * a[:, None]).sum(0) / a.sum()
            self._tile_rgb[i] = (tuple(rgb), float(a.mean()))

    def _describe_model(self, sid: int, state: str):
        from .models import MODELS, TINT_FIXED, TINT_FOLIAGE as MF, TINT_GRASS as MG
        m = MODELS.get(sid)
        if m.fluid == "water":
            return (255, 255, 255), 0.6, TINT_WATER, False
        if m.fluid == "lava":
            return m.map_rgb, 1.0, TINT_NONE, False
        if not len(m.verts):
            return (0, 0, 0), 0.0, TINT_NONE, True
        ups = m.tint[m.normal == 1]
        kinds = set(int(k) for k in ups) or set(int(k) for k in m.tint)
        tint = TINT_GRASS if MG in kinds else TINT_FOLIAGE if MF in kinds else TINT_NONE
        return m.map_rgb, max(0.35, min(1.0, m.map_alpha)), tint, False

    def _describe(self, state: str, sid: int | None = None):
        from .models import MODELS
        if MODELS.available and sid is not None:
            return self._describe_model(sid, state)
        name = base_name(state)
        if name in AIR_NAMES:
            return (0, 0, 0), 0.0, TINT_NONE, True
        short = name.split(":", 1)[-1]
        if short in ("water", "bubble_column"):
            return (255, 255, 255), 0.6, TINT_WATER, False
        tint = TINT_NONE
        if short in _GRASS_TINT:
            tint = TINT_GRASS
        elif short in _FOLIAGE_TINT:
            tint = TINT_FOLIAGE
        entry = self._atlas_blocks.get(name)
        rgb, alpha = None, 1.0
        if entry is not None:
            tile = entry.get("top", entry.get("all", -1)) if isinstance(entry, dict) else int(entry)
            hit = self._tile_rgb.get(int(tile))
            if hit:
                rgb, alpha = hit
        if rgb is None:
            rgb = _FALLBACK.get(short, (110, 110, 110))
        if short in _FIXED_TINT:
            f = _hex(_FIXED_TINT[short])
            rgb = tuple(c * t / 255.0 for c, t in zip(rgb, f))
        if short == "grass_block":
            tint = TINT_GRASS
        # Thin blocks (glass panes, torches, flowers) should not fully cover
        # the ground, but stay visible.
        alpha = max(0.35, min(1.0, alpha))
        return rgb, alpha, tint, False

    def sync(self):
        """Grow the tables to the current registry size."""
        n, m = len(BLOCKS), len(BIOMES)
        if self.block_rgb.shape[0] >= n and self.biome_rgb.shape[0] >= m:
            return
        with self._lock:
            k = self.block_rgb.shape[0]
            if k < n:
                rgb = np.zeros((n, 3), np.float32)
                al = np.zeros(n, np.float32)
                ti = np.zeros(n, np.uint8)
                em = np.zeros(n, bool)
                wa = np.zeros(n, bool)
                rgb[:k], al[:k], ti[:k] = self.block_rgb, self.block_alpha, self.block_tint
                em[:k], wa[:k] = self.block_empty, self.block_water
                for i in range(k, n):
                    state = BLOCKS.names[i]
                    c, a, t, e = self._describe(state, i)
                    rgb[i], al[i], ti[i], em[i] = c, a, t, e
                    wa[i] = t == TINT_WATER
                self.block_rgb, self.block_alpha, self.block_tint = rgb, al, ti
                self.block_empty, self.block_water = em, wa
            j = self.biome_rgb.shape[0]
            if j < m:
                b = np.zeros((m, 3, 3), np.float32)
                b[:j] = self.biome_rgb
                for i in range(j, m):
                    key = BIOMES.names[i].split(":", 1)[-1]
                    g, f, w = _BIOME_COLORS.get(key, _DEFAULT_BIOME)
                    b[i] = [_hex(g), _hex(f), _hex(w)]
                self.biome_rgb = b


PALETTE = Palette()
