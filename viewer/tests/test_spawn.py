"""Mob spawns: torch planning for "Bereich ausleuchten" and the 2D overlay columns."""

import unittest
from types import SimpleNamespace
from unittest import mock

import numpy as np

from viewer import spawn
from viewer.core.chunk import BLOCKS


class FakeRec:
    def __init__(self, pts, blocks=None):
        self.pts, self.blocks = pts, blocks

    def chunk(self, dim, cx, cz):
        if (cx, cz) != (0, 0):
            return None
        b = self.blocks if self.blocks is not None else np.zeros((4, 16, 16), np.int32)
        return SimpleNamespace(cx=0, cz=0, min_y=0, blocks=b)


DIM = SimpleNamespace(key="overworld")
AREA = {"x0": 0, "z0": 0, "x1": 15, "z1": 15, "y0": -64, "y1": 319}


class TorchPlanTest(unittest.TestCase):
    def plan(self, pts, blocks=None, area=AREA):
        flat = [v for p in pts for v in (*p, 2)]
        with mock.patch.object(spawn, "points", lambda ch, key: {"light": True, "pts": flat}):
            return spawn.torch_plan(FakeRec(pts, blocks), DIM, area)

    def test_one_torch_covers_its_reach(self):
        pts = [(x, 1, z) for x in range(4) for z in range(4)]
        r = self.plan(pts)
        self.assertEqual(r["spots"], 16)
        self.assertEqual(r["torches"], [(0, 1, 0)])
        self.assertEqual(r["chunks"], {(0, 0): 1})

    def test_far_spots_need_more_torches(self):
        r = self.plan([(0, 1, 0), (15, 1, 15)])
        self.assertEqual(len(r["torches"]), 2)

    def test_area_bounds_and_empty(self):
        r = self.plan([(0, 1, 0)], area=dict(AREA, x0=5))
        self.assertEqual((r["spots"], r["torches"]), (0, []))

    def test_existing_torch_counts_as_lit(self):
        torch = BLOCKS.id("minecraft:torch")
        blocks = np.zeros((4, 16, 16), np.int32)
        blocks[1, 0, 0] = torch
        spawn._light_cache[0] = np.zeros(0, bool)
        r = self.plan([(1, 1, 1), (15, 1, 15)], blocks)
        self.assertEqual(r["torches"], [(15, 1, 15)])


if __name__ == "__main__":
    unittest.main()
