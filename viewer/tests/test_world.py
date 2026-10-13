import os
import struct
import tempfile
import unittest
import zlib

import numpy as np

from viewer.core import nbt
from viewer.core.chunk import BIOMES, BLOCKS
from viewer.core.tiles import Layer, render_chunk
from viewer.core.world import LEGACY_SERVER, ChunkCache, Recording
from viewer.tests import make_world, support


class RecordingTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rec = Recording(support.shared_world())
        cls.ow = cls.rec.dimension(support.SERVER, "overworld")
        cls.ne = cls.rec.dimension(support.SERVER, "the_nether")

    def test_dimensions_found(self):
        ids = sorted(d.id for d in self.rec.dimensions())
        self.assertEqual(ids, [f"{support.SERVER}/overworld", f"{support.SERVER}/the_nether"])
        self.assertIsNone(self.rec.dimension(support.SERVER, "the_end"))
        self.assertIsNone(self.rec.dimension("other.server", "overworld"))

    def test_region_chunk_counts(self):
        # Overworld: chunks -1..0 -> four regions with one chunk each; Nether: -2..1 -> four per region
        ow = sorted((r.rx, r.rz, len(r.chunk_indices())) for r in self.ow.regions())
        self.assertEqual(ow, [(-1, -1, 1), (-1, 0, 1), (0, -1, 1), (0, 0, 1)])
        self.assertEqual(sum(len(r.chunk_indices()) for r in self.ne.regions()), 16)

    def test_missing_chunk_is_none(self):
        self.assertIsNone(self.rec.chunk(self.ow, 5, 5))       # region missing
        self.assertIsNone(self.rec.chunk(self.ow, 1, 0))       # region exists, chunk missing
        self.assertIsNone(self.rec.chunk(self.ow, 40, 40))

    def test_blocks_match_generator(self):
        ch = self.rec.chunk(self.ow, 0, 0)
        self.assertEqual((ch.min_y, ch.height), (-64, 384))
        # whole columns, including the house (8..14) and a tree trunk (x=3, z=5)
        for x, z in ((0, 0), (3, 5), (8, 8), (10, 10), (15, 15)):
            for y in range(-64, 320):
                self.assertEqual(ch.block(x, y, z), make_world.overworld(x, y, z), (x, y, z))
        self.assertEqual(ch.block(0, 400, 0), "minecraft:air")

    def test_negative_chunk_coordinates(self):
        ch = self.rec.chunk(self.ow, -1, -1)
        for x, z in ((-1, -1), (-16, -16), (-7, -12)):
            for y in range(40, 100):
                self.assertEqual(ch.block(x, y, z), make_world.overworld(x, y, z), (x, y, z))

    def test_surface_heights_match_generator(self):
        for cx, cz in ((0, 0), (-1, 0)):
            ch = self.rec.chunk(self.ow, cx, cz)
            _, h, has = render_chunk(ch, Layer())
            self.assertTrue(has.all())
            want = np.array([[support.top_y(cx * 16 + x, cz * 16 + z) for x in range(16)] for z in range(16)])
            np.testing.assert_array_equal(h, want)

    def test_nether_cave_layer_skips_roof(self):
        ch = self.rec.chunk(self.ne, 0, 0)
        _, top, _ = render_chunk(ch, Layer.parse("surface320"))
        _, cave, has = render_chunk(ch, Layer.parse("cave127"))
        self.assertTrue((top == 127).all())                  # bedrock roof
        self.assertTrue(has.all())
        # Under the roof the first block is the netherrack floor (or the lava lake at y <= 31)
        for z in range(16):
            for x in range(16):
                floor = int(40 + 6 * np.sin(x / 9.0) * np.cos(z / 11.0))
                self.assertEqual(cave[z, x], max(floor, 31), (x, z))

    def test_biomes(self):
        ow = self.rec.chunk(self.ow, 0, 0)
        ne = self.rec.chunk(self.ne, 0, 0)
        self.assertEqual({BIOMES.names[i] for i in np.unique(ow.biomes)}, {"minecraft:plains"})
        self.assertEqual({BIOMES.names[i] for i in np.unique(ne.biomes)}, {"minecraft:nether_wastes"})

    def test_sky_light(self):
        ch = self.rec.chunk(self.ow, 0, 0)
        bl, sl = ch.light()
        self.assertEqual(int(bl.max()), 0)
        ly = support.top_y(0, 0) - ch.min_y
        self.assertEqual(int(sl[ly + 1, 0, 0]), 15)          # open sky above the surface
        self.assertEqual(int(sl[ly - 1, 0, 0]), 0)

    def test_raw_chunk_keeps_block_entities_typed(self):
        raw = self.rec.raw_chunk(self.ow, 0, 0)
        sign = raw["block_entities"][0]
        self.assertEqual(sign["id"], "minecraft:sign")
        self.assertIs(type(sign["x"]), nbt.Int)
        self.assertEqual((sign["x"], sign["y"], sign["z"]), (10, 75, 7))

    def test_chunk_cache_reuses_decoded_chunk(self):
        self.assertIs(self.rec.chunk(self.ow, 0, 0), self.rec.chunk(self.ow, 0, 0))


class RecordingLiveTest(unittest.TestCase):
    """The viewer must see new chunks and new dimensions without a restart."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)

    def _chunk(self, cx, cz, block="minecraft:stone"):
        return make_world.chunk_nbt(cx, cz, lambda x, y, z: block if y == 0 else "minecraft:air",
                                    "minecraft:plains", min_sy=0, max_sy=0)

    def test_region_rewrite_is_picked_up(self):
        path = os.path.join(self.tmp.name, "srv", "region", "r.0.0.mca")
        make_world.write_region(path, {(0, 0): self._chunk(0, 0)})
        rec = Recording(self.tmp.name)
        dim = rec.dimension("srv", "overworld")
        self.assertIsNone(rec.chunk(dim, 1, 0))
        make_world.write_region(path, {(0, 0): self._chunk(0, 0), (1, 0): self._chunk(1, 0, "minecraft:dirt")})
        # Size changes with the new chunk, so the cached header is refreshed even within one mtime tick
        self.assertEqual(rec.chunk(dim, 1, 0).block(16, 0, 0), "minecraft:dirt")

    def test_new_dimension_is_picked_up(self):
        rec = Recording(self.tmp.name)
        self.assertEqual(rec.dimensions(), [])
        make_world.write_region(os.path.join(self.tmp.name, "srv", "DIM1", "region", "r.0.0.mca"),
                                {(0, 0): self._chunk(0, 0)})
        self.assertIsNotNone(rec.dimension("srv", "the_end"))

    def test_legacy_layout(self):
        make_world.write_region(os.path.join(self.tmp.name, "region", "r.0.0.mca"), {(0, 0): self._chunk(0, 0)})
        rec = Recording(self.tmp.name)
        self.assertEqual([d.id for d in rec.dimensions()], [f"{LEGACY_SERVER}/overworld"])

    def test_external_oversized_chunk(self):
        # Compression byte with 0x80: payload lives in c.<x>.<z>.mcc next to the region file.
        rdir = os.path.join(self.tmp.name, "srv", "region")
        os.makedirs(rdir)
        header = bytearray(8192)
        struct.pack_into(">I", header, 0, (2 << 8) | 1)
        blob = struct.pack(">IB", 1, 0x82)
        with open(os.path.join(rdir, "r.0.0.mca"), "wb") as f:
            f.write(header + blob + bytes(4096 - len(blob)))
        with open(os.path.join(rdir, "c.0.0.mcc"), "wb") as f:
            f.write(zlib.compress(nbt.write(self._chunk(0, 0, "minecraft:gold_block"))))
        rec = Recording(self.tmp.name)
        ch = rec.chunk(rec.dimension("srv", "overworld"), 0, 0)
        self.assertEqual(ch.block(3, 0, 3), "minecraft:gold_block")


class ChunkCacheTest(unittest.TestCase):
    def test_lru_eviction(self):
        c = ChunkCache(max_items=2)
        c.put("a", 1)
        c.put("b", 2)
        self.assertEqual(c.get("a"), 1)      # "a" is now most recent
        c.put("c", 3)
        self.assertIsNone(c.get("b"))
        self.assertEqual((c.get("a"), c.get("c")), (1, 3))


class RegistryTest(unittest.TestCase):
    def test_air_is_id_zero_and_ids_are_stable(self):
        self.assertEqual(BLOCKS.id("minecraft:air"), 0)
        a = BLOCKS.id("minecraft:test_block_for_registry")
        self.assertEqual(BLOCKS.id("minecraft:test_block_for_registry"), a)
        self.assertEqual(BLOCKS.names[a], "minecraft:test_block_for_registry")


if __name__ == "__main__":
    unittest.main()
