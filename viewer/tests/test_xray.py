"""X-ray: filtering a chunk down to the searched blocks, and the position trailer."""

import struct
import unittest

import numpy as np

from viewer import xray
from viewer.core.chunk import BLOCKS, base_name
from viewer.core.world import Recording
from viewer.tests import support


def read_trailer(data: bytes):
    assert data[:4] == xray.TRAILER
    m = struct.unpack_from("<I", data, 4)[0]
    counts = np.frombuffer(data, "<u4", m, 8)
    o = 8 + 4 * m
    n = struct.unpack_from("<I", data, o)[0]
    pts = np.frombuffer(data, "<i2", n * 4, o + 4).reshape(-1, 4)
    assert o + 4 + n * 8 == len(data)
    return counts, pts


class XrayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        rec = Recording(support.shared_world())
        dim = rec.dimension(support.SERVER, "overworld")
        cls.ch = rec.chunk(dim, 0, 0)

    def names_in_chunk(self):
        return {base_name(BLOCKS.names[int(i)]) for i in np.unique(self.ch.blocks)} - {"minecraft:air"}

    def test_parse_names(self):
        self.assertEqual(xray.parse_names(" Diamond_Ore, minecraft:chest,,air"), ("minecraft:chest", "minecraft:diamond_ore"))
        self.assertEqual(xray.parse_names(""), ())
        with self.assertRaises(ValueError):
            xray.parse_names(",".join(f"b{i}" for i in range(xray.MAX_NAMES + 1)))

    def test_only_keeps_searched_blocks(self):
        present = sorted(self.names_in_chunk())
        names = (present[0], "minecraft:diamond_ore")
        names = tuple(sorted(names))
        out = xray.only(self.ch, xray.Lookup(names))
        left = {base_name(BLOCKS.names[int(i)]) for i in np.unique(out.blocks)} - {"minecraft:air"}
        self.assertEqual(left, {present[0]})
        self.assertEqual(out.light_raw, {})
        self.assertEqual(out.blocks.shape, self.ch.blocks.shape)

    def test_trailer_positions_and_counts(self):
        present = sorted(self.names_in_chunk())
        names = tuple(sorted(present[:2]))
        counts, pts = read_trailer(xray.trailer(self.ch, xray.Lookup(names), len(names)))
        for k, name in enumerate(names):
            want = sum(1 for s in self.ch.blocks.reshape(-1) if base_name(BLOCKS.names[int(s)]) == name)
            self.assertEqual(int(counts[k]), want)
        self.assertLessEqual(len(pts), xray.MAX_POINTS)
        for x, y, z, k in pts[:50]:
            state = self.ch.block(int(x), int(y), int(z))
            self.assertEqual(base_name(state), names[k])

    def test_trailer_caps_points(self):
        # the most common block: more finds than MAX_POINTS, counts still complete
        ids, n = np.unique(self.ch.blocks, return_counts=True)
        top = max((c, base_name(BLOCKS.names[int(i)])) for i, c in zip(ids, n) if i)
        counts, pts = read_trailer(xray.trailer(self.ch, xray.Lookup((top[1],)), 1))
        self.assertGreaterEqual(int(counts[0]), top[0])
        if counts[0] > xray.MAX_POINTS:
            self.assertEqual(len(pts), xray.MAX_POINTS)


if __name__ == "__main__":
    unittest.main()
