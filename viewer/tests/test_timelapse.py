"""Timelapse in 3D: chunks as they were at the start of a day (from the mod's daily backups)."""

import gzip
import os
import tempfile
import unittest

from viewer.core.world import Recording
from viewer.extras import History
from viewer.workers import _mesh_compute
from viewer.tests import make_world, support


def old_column(x, y, z):
    return "minecraft:gold_block" if y < 70 else "minecraft:air"


class TimelapseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.root = support.world_copy(cls.tmp.name)
        hdir = os.path.join(cls.root, support.SERVER, ".history", "2026-01-05", "region")
        make_world.write_region(os.path.join(hdir, "r.0.0.mca"),
                                {(0, 0): make_world.chunk_nbt(0, 0, old_column, "minecraft:plains")})
        cls.rec = Recording(cls.root)
        cls.dim = cls.rec.dimension(support.SERVER, "overworld")
        cls.hist = History(cls.root)

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def test_chunk_at_uses_backup_from_that_day_on(self):
        for date in ("2025-12-01", "2026-01-05"):
            ch = self.hist.chunk_at(self.rec, self.dim, date, 0, 0)
            self.assertEqual(ch.block(3, 60, 3), "minecraft:gold_block", date)

    def test_chunk_at_falls_back_to_today(self):
        today = self.rec.chunk(self.dim, 0, 0)
        later = self.hist.chunk_at(self.rec, self.dim, "2026-02-01", 0, 0)
        self.assertEqual(later.block(3, 60, 3), today.block(3, 60, 3))
        # chunks without a backup are today's state on every day
        self.assertEqual(self.hist.chunk_at(self.rec, self.dim, "2025-12-01", -1, -1).block(3, 60, 3),
                         self.rec.chunk(self.dim, -1, -1).block(3, 60, 3))

    @unittest.skipUnless(os.path.exists(os.path.join(os.path.dirname(__file__), "..", "assets", "blocks.json")),
                         "needs the block models (built from the Minecraft jar)")
    def test_mesh_differs_for_old_day(self):
        now = gzip.decompress(_mesh_compute(self.rec, self.dim, 0, 0, True))
        old = gzip.decompress(_mesh_compute(self.rec, self.dim, 0, 0, True, at="2026-01-05", hist=self.hist))
        self.assertNotEqual(now, old)


if __name__ == "__main__":
    unittest.main()
