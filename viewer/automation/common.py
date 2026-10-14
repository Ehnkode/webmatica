"""Shared by every automation mode: timings, limits and small helpers."""

from __future__ import annotations

import re

FRESH_PLAYER = 6.0       # s: max age of the last position
STEP_EVERY = 2.0         # s
FETCH_TIMEOUT = 240.0    # s
SETTLE = 2.5             # s after fetching until the inventory is reported
STALL = 45.0             # s without progress while building
BAD_CHEST = 600.0        # s: skip a chest that failed for this long
MAIN_SLOTS = 36
NEAR = 20.0              # blocks: from here on Baritone may walk on its own for building
UNSEEN_WAIT = 8.0        # s at the site for the recording to catch never-seen chunks
RADAR_CLEAR = 15.0       # s without players nearby, then it continues
RUNNING = ("plan", "fetch", "build", "approach", "unpack", "return", "fix", "fplan", "mine", "deposit")
DEFAULTS = {"radar": True, "radarDist": 64, "friends": [], "eat": False, "tools": True, "leftovers": True, "fix": True,
            "dig": False}
FIX_ROUNDS = 2
MINE_BATCH = 48
MINE_TIMEOUT = 360.0     # s for one mining job
FREE_MIN = 3             # this many free slots, otherwise store items first
STORE_RADIUS = 128.0     # blocks: farther chests are not picked on their own for storing
FILL_BATCH = 128         # holes per fill job
LIGHT_BATCH = 8          # torches per job, then plan again with the new light
# stays in the inventory when storing
KEEP = re.compile(r"pickaxe|_axe|shovel|_hoe|sword|shears|chest$|shulker_box|totem|elytra|helmet|chestplate|leggings|boots|"
                  r"flint_and_steel|bucket|ender_pearl|golden_apple|golden_carrot|bread|cooked_|steak|baked_potato|firework")


def _full(it: str) -> str:
    return it if ":" in it else "minecraft:" + it
