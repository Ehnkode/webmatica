import gzip
import tempfile
import unittest

import numpy as np

from viewer.core import nbt
from viewer.core import schematic as sch
from viewer.core.chunk import BLOCKS
from viewer.core.schem import export, write
from viewer.core.world import Recording
from viewer.placements import Placements
from viewer.tests import make_world, support


def ids_of(*states):
    return [BLOCKS.id(s) for s in states]


def random_blocks(shape, states, seed=1):
    rng = np.random.default_rng(seed)
    lut = np.array([0] + ids_of(*states), np.int64)
    return lut[rng.integers(0, len(lut), shape)]


class SpongeRoundTripTest(unittest.TestCase):
    def test_write_load_round_trip(self):
        ids = random_blocks((4, 5, 6), ["minecraft:stone", "minecraft:oak_stairs[facing=east,half=bottom]"])
        s = sch.load(write(ids, "Demo"), "Demo.schem")
        self.assertEqual(s.size, (6, 4, 5))                  # (W, H, L)
        np.testing.assert_array_equal(s.blocks, ids)
        self.assertEqual(s.name, "Demo")

    def test_large_palette_uses_multibyte_varints(self):
        # More than 127 palette entries: indices need two VarInt bytes in the Sponge format.
        states = [f"minecraft:test_wool_{i}" for i in range(300)]
        ids = random_blocks((3, 10, 20), states, seed=7)
        s = sch.load(write(ids, "big"), "big.schem")
        np.testing.assert_array_equal(s.blocks, ids)

    def test_metadata_and_block_entities(self):
        ids = np.zeros((1, 1, 2), np.int64)
        ids[0, 0, 1] = BLOCKS.id("minecraft:chest[facing=north,type=single,waterlogged=false]")
        be = [{"Pos": nbt.IntArray([1, 0, 0]), "Id": "minecraft:chest",
               "Data": {"Items": [{"Slot": nbt.Byte(0), "id": "minecraft:diamond", "count": nbt.Int(3)}]}}]
        data = write(ids, "chest", be, data_version=1234, origin=(-5, 64, 9))
        root = nbt.read(gzip.decompress(data))["Schematic"]
        self.assertEqual(int(root["Version"]), 3)
        self.assertEqual(int(root["DataVersion"]), 1234)
        self.assertEqual(root["Metadata"]["WorldOrigin"].tolist(), [-5, 64, 9])
        self.assertEqual(root["Blocks"]["Palette"]["minecraft:air"], 0)   # air is always index 0
        s = sch.load(data, "chest.schem")
        self.assertEqual(len(s.block_entities), 1)
        self.assertEqual(s.block_entities[0]["pos"], [1, 0, 0])
        self.assertEqual(s.block_entities[0]["id"], "minecraft:chest")
        self.assertEqual(s.block_entities[0]["data"]["Items"][0]["id"], "minecraft:diamond")

    def test_state_names_are_normalised(self):
        # Property order and missing namespace must not create a different block id.
        root = {"Version": nbt.Int(2), "Width": nbt.Short(1), "Height": nbt.Short(1), "Length": nbt.Short(1),
                "Palette": {"oak_stairs[half=bottom,facing=north]": nbt.Int(0)},
                "BlockData": nbt.ByteArray(b"\x00")}
        s = sch.load(gzip.compress(nbt.write(root)), "v2.schem")
        self.assertEqual(BLOCKS.names[s.blocks[0, 0, 0]], "minecraft:oak_stairs[facing=north,half=bottom]")

    def test_empty_schematic_rejected(self):
        root = {"Width": nbt.Short(0), "Height": nbt.Short(1), "Length": nbt.Short(1),
                "Palette": {}, "BlockData": nbt.ByteArray(b"")}
        with self.assertRaises(ValueError):
            sch.load(nbt.write(root), "empty.schem")


def pack_spanning(values, bits):
    """Litematica packing (entries may cross long boundaries), inverse of _unpack_spanning."""
    n = (len(values) * bits + 63) // 64
    out = [0] * n
    for i, v in enumerate(values):
        start = i * bits
        li, off = start >> 6, start & 63
        out[li] |= (v << off) & (2**64 - 1)
        if off + bits > 64:
            out[li + 1] |= v >> (64 - off)
    return nbt.LongArray(out)


class OtherFormatsTest(unittest.TestCase):
    def test_litematic(self):
        pal = ["minecraft:air", "minecraft:stone", "minecraft:dirt", "minecraft:glass", "minecraft:sand"]
        W, H, L = 3, 3, 3
        rng = np.random.default_rng(3)
        idx = rng.integers(0, len(pal), W * H * L)
        region = {
            "Position": {"x": nbt.Int(0), "y": nbt.Int(0), "z": nbt.Int(0)},
            "Size": {"x": nbt.Int(W), "y": nbt.Int(H), "z": nbt.Int(L)},
            "BlockStatePalette": [{"Name": n} for n in pal],
            "BlockStates": pack_spanning([int(v) for v in idx], 3),     # 3 bits: crosses longs
            "TileEntities": [{"x": nbt.Int(1), "y": nbt.Int(2), "z": nbt.Int(0), "id": "minecraft:chest"}],
        }
        root = {"Metadata": {"Name": "Lite"}, "Regions": {"main": region}}
        s = sch.load(gzip.compress(nbt.write(root)), "x.litematic")
        self.assertEqual(s.name, "Lite")
        want = np.array(ids_of(*pal))[idx].reshape(H, L, W)
        np.testing.assert_array_equal(s.blocks, want)
        self.assertEqual(s.block_entities[0]["pos"], [1, 2, 0])

    def test_vanilla_structure(self):
        root = {"size": [nbt.Int(2), nbt.Int(1), nbt.Int(1)],
                "palette": [{"Name": "minecraft:stone"}, {"Name": "minecraft:oak_log", "Properties": {"axis": "x"}}],
                "blocks": [{"pos": [nbt.Int(0), nbt.Int(0), nbt.Int(0)], "state": nbt.Int(1)},
                           {"pos": [nbt.Int(1), nbt.Int(0), nbt.Int(0)], "state": nbt.Int(0)}]}
        s = sch.load(gzip.compress(nbt.write(root)), "s.nbt")
        self.assertEqual([BLOCKS.names[i] for i in s.blocks[0, 0]], ["minecraft:oak_log[axis=x]", "minecraft:stone"])


class TransformTest(unittest.TestCase):
    def setUp(self):
        ids = random_blocks((2, 3, 4), ["minecraft:stone", "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]",
                                        "minecraft:oak_log[axis=x]"])
        self.s = sch.Schem("t", ids.astype(np.uint16),
                           [{"pos": [0, 1, 2], "id": "minecraft:chest", "data": {}}])

    def test_rotation_swaps_width_and_length(self):
        self.assertEqual(sch.transform(self.s, 90, False).size, (3, 2, 4))

    def test_four_rotations_are_identity(self):
        t = self.s
        for _ in range(4):
            t = sch.transform(t, 90, False)
        np.testing.assert_array_equal(t.blocks, self.s.blocks)
        self.assertEqual(t.block_entities[0]["pos"], [0, 1, 2])

    def test_double_mirror_is_identity(self):
        t = sch.transform(sch.transform(self.s, 0, True), 0, True)
        np.testing.assert_array_equal(t.blocks, self.s.blocks)

    def test_rotation_moves_block_entities(self):
        t = sch.transform(self.s, 90, False)
        x, y, z = t.block_entities[0]["pos"]
        self.assertEqual(BLOCKS.names[t.blocks[y, z, x]],
                         sch.transform_state(BLOCKS.names[self.s.blocks[1, 2, 0]], 90, False))

    def test_transform_state(self):
        st = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight]"
        self.assertEqual(sch.transform_state(st, 90, False), "minecraft:oak_stairs[facing=east,half=bottom,shape=straight]")
        self.assertEqual(sch.transform_state("minecraft:oak_log[axis=x]", 90, False), "minecraft:oak_log[axis=z]")
        self.assertEqual(sch.transform_state("minecraft:oak_door[facing=east,hinge=left]", 0, True),
                         "minecraft:oak_door[facing=west,hinge=right]")
        self.assertEqual(sch.transform_state("minecraft:oak_sign[rotation=4]", 0, True), "minecraft:oak_sign[rotation=12]")


class MaterialsTest(unittest.TestCase):
    def test_item_for(self):
        self.assertEqual(sch.item_for("minecraft:wall_torch[facing=north]"), ("torch", 1))
        self.assertEqual(sch.item_for("minecraft:stone_slab[type=double,waterlogged=false]"), ("stone_slab", 2))
        self.assertEqual(sch.item_for("minecraft:oak_door[half=upper]"), (None, 0))   # counted on the lower half
        self.assertEqual(sch.item_for("minecraft:sea_pickle[pickles=3]"), ("sea_pickle", 3))
        self.assertEqual(sch.item_for("minecraft:water[level=3]"), (None, 0))         # flowing water
        self.assertEqual(sch.item_for("minecraft:air"), (None, 0))

    def test_materials_counts(self):
        b = np.array(ids_of("minecraft:stone", "minecraft:stone", "minecraft:white_bed[part=foot]",
                            "minecraft:white_bed[part=head]", "minecraft:air"), np.uint16).reshape(1, 1, 5)
        self.assertEqual(sch.materials(b), {"stone": 2, "white_bed": 1})


class ExportFromWorldTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.rec = Recording(support.shared_world())
        cls.dim = cls.rec.dimension(support.SERVER, "overworld")

    def test_export_matches_world(self):
        data, info = export(self.rec, self.dim, -4, 60, -4, 15, 90, 9, name="area")
        s = sch.load(data, "area.schem")
        self.assertEqual(s.size, (20, 31, 14))
        self.assertEqual(info["size"], [20, 31, 14])
        self.assertEqual(info["coverage"], 1.0)
        want = np.array([[[BLOCKS.id(make_world.overworld(x, y, z)) for x in range(-4, 16)]
                          for z in range(-4, 10)] for y in range(60, 91)])
        np.testing.assert_array_equal(s.blocks, want)

    def test_corner_order_does_not_matter(self):
        a, _ = export(self.rec, self.dim, 0, 70, 0, 5, 80, 5)
        b, _ = export(self.rec, self.dim, 5, 80, 5, 0, 70, 0)
        np.testing.assert_array_equal(sch.load(a, "a.schem").blocks, sch.load(b, "b.schem").blocks)

    def test_export_includes_block_entities_relative(self):
        data, info = export(self.rec, self.dim, 5, 70, 5, 12, 80, 9)
        self.assertEqual(info["blockEntities"], 1)
        s = sch.load(data, "s.schem")
        be = s.block_entities[0]
        self.assertEqual(be["pos"], [10 - 5, 75 - 70, 7 - 5])
        self.assertEqual(be["id"], "minecraft:sign")
        self.assertEqual(be["data"]["front_text"]["messages"][2], '"Demo-Base"')

    def test_partial_coverage(self):
        _, info = export(self.rec, self.dim, 0, 70, 0, 31, 71, 15)   # x 16..31 was never recorded
        self.assertEqual(info["coverage"], 0.5)

    def test_unrecorded_area_raises(self):
        with self.assertRaises(LookupError):
            export(self.rec, self.dim, 1000, 0, 1000, 1010, 10, 1010)

    def test_too_large_area_raises(self):
        with self.assertRaises(ValueError):
            export(self.rec, self.dim, 0, -64, 0, 2000, 319, 2000)

    def test_verify_exported_copy_is_complete(self):
        data, _ = export(self.rec, self.dim, -8, 70, -8, 7, 85, 7)
        s = sch.load(data, "v.schem")
        res = sch.verify(s, self.rec, self.dim, -8, 70, -8)
        st = res["stats"]
        self.assertGreater(st["total"], 0)
        self.assertEqual((st["ok"], st["missing"], st["wrong"], st["unknown"]), (st["total"], 0, 0, 0))
        self.assertEqual(res["todo"], [])

    def test_verify_reports_missing_blocks(self):
        b = np.zeros((1, 1, 1), np.uint16)
        b[0, 0, 0] = BLOCKS.id("minecraft:diamond_block")
        res = sch.verify(sch.Schem("d", b), self.rec, self.dim, 0, 200, 0)   # y=200 is air
        self.assertEqual(res["stats"]["missing"], 1)
        self.assertEqual(res["todo"][0][:5], [0, 200, 0, "diamond_block", "missing"])


class PlacementsLibraryTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.pl = Placements(support.world_copy(self.tmp.name))

    def test_upload_and_library(self):
        ids = random_blocks((2, 2, 2), ["minecraft:stone"])
        res = self.pl.upload("my house.schem", write(ids, "x"))
        self.assertEqual(res["file"], "hochgeladen/my house.schem")
        files = [e["file"] for e in self.pl.library()]
        self.assertEqual(files, ["hochgeladen/my house.schem"])
        np.testing.assert_array_equal(self.pl.schem(res["file"]).blocks, ids)

    def test_upload_rejects_bad_files(self):
        with self.assertRaises(ValueError):
            self.pl.upload("notes.txt", b"hello")
        with self.assertRaises(Exception):
            self.pl.upload("broken.schem", b"not nbt at all")
        self.assertEqual(self.pl.library(), [])

    def test_resolve_blocks_path_traversal(self):
        with self.assertRaises(LookupError):
            self.pl.resolve("hochgeladen/../placements.json")
        with self.assertRaises(LookupError):
            self.pl.resolve("unknown/x.schem")


if __name__ == "__main__":
    unittest.main()
