import os
import tempfile
import unittest

import numpy as np

from viewer.core import nbt
from viewer.core import schematic as sch
from viewer.core.chunk import BLOCKS
from viewer.core.schem import write
from viewer.core.world import Recording
from viewer.editor import MAX_EDGE, Editor
from viewer.placements import Placements
from viewer.tests import make_world, support

STONE = "minecraft:stone"


class EditorTestCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        # Litematica-style folder next to the recording: files there must never be overwritten
        self.lm_dir = os.path.join(self.tmp.name, "schematics")
        os.makedirs(self.lm_dir)
        self.root = support.world_copy(self.tmp.name)
        self.pl = Placements(self.root)
        self.rec = Recording(self.root)
        self.ed = Editor(self.pl, self.rec)

    def disk_blocks(self, file_id):
        """Blocks as stored in the file, bypassing the editor session."""
        with open(self.pl.resolve(file_id), "rb") as f:
            return sch.load(f.read(), "x.schem").blocks


class EditorNewTest(EditorTestCase):
    def test_new_creates_empty_file(self):
        info = self.ed.new({"name": "box", "size": [4, 3, 2]})
        self.assertEqual(info["file"], "hochgeladen/box.schem")
        self.assertEqual(info["size"], [4, 3, 2])
        self.assertEqual((info["undo"], info["redo"], info["dirty"]), (0, 0, False))
        b = self.disk_blocks(info["file"])
        self.assertEqual(b.shape, (3, 2, 4))                 # [y, z, x]
        self.assertFalse(b.any())

    def test_new_does_not_overwrite_existing_names(self):
        a = self.ed.new({"name": "box"})
        b = self.ed.new({"name": "box"})
        self.assertEqual((a["file"], b["file"]), ("hochgeladen/box.schem", "hochgeladen/box (2).schem"))
        self.assertEqual(a["size"], [16, 16, 16])            # default size

    def test_new_sanitises_name_and_clamps_size(self):
        info = self.ed.new({"name": "../evil/name", "size": [0, MAX_EDGE + 100, 5]})
        self.assertEqual(info["size"], [1, MAX_EDGE, 5])
        self.assertNotIn("/", info["file"].split("/", 1)[1])
        self.assertTrue(os.path.isfile(self.pl.resolve(info["file"])))


class EditorEditTest(EditorTestCase):
    def setUp(self):
        super().setUp()
        self.file = self.ed.new({"name": "e", "size": [4, 4, 4]})["file"]

    def test_edit_is_kept_in_memory_until_save(self):
        res = self.ed.edit({"file": self.file, "ops": [{"op": "set", "block": STONE, "pts": [[1, 2, 3]]}]})
        self.assertEqual((res["changed"], res["dirty"], res["undo"]), (1, True, 1))
        self.assertFalse(self.disk_blocks(self.file).any())  # not written yet
        self.assertTrue(self.ed.save({"file": self.file})["saved"])
        b = self.disk_blocks(self.file)
        self.assertEqual(BLOCKS.names[b[2, 3, 1]], STONE)
        self.assertEqual(int((b != 0).sum()), 1)
        self.assertFalse(self.ed.info(self.file)["dirty"])

    def test_saved_file_survives_new_editor(self):
        self.ed.edit({"file": self.file, "ops": [{"op": "fill", "block": STONE, "box": [0, 0, 0, 3, 0, 3]}]})
        self.ed.save({"file": self.file})
        fresh = Editor(self.pl, self.rec)
        vox = fresh.voxels(self.file)
        self.assertEqual(vox["size"], [4, 4, 4])
        self.assertEqual(sorted(vox["palette"]), ["minecraft:air", STONE])

    def test_discard_reverts_to_file(self):
        self.ed.edit({"file": self.file, "ops": [{"op": "fill", "block": STONE, "box": [0, 0, 0, 3, 3, 3]}]})
        res = self.ed.discard({"file": self.file})
        self.assertFalse(res["dirty"])
        self.assertEqual(self.ed.voxels(self.file)["palette"], ["minecraft:air"])

    def test_undo_redo(self):
        op = {"op": "set", "block": STONE, "pts": [[0, 0, 0]]}
        self.ed.edit({"file": self.file, "ops": [op]})
        self.assertEqual(self.ed.undo({"file": self.file})["undo"], 0)
        self.assertEqual(self.ed.voxels(self.file)["palette"], ["minecraft:air"])
        res = self.ed.undo({"file": self.file}, redo=True)
        self.assertEqual((res["undo"], res["redo"]), (1, 0))
        self.assertIn(STONE, self.ed.voxels(self.file)["palette"])
        # nothing left to redo: no-op
        self.assertEqual(self.ed.undo({"file": self.file}, redo=True)["changed"], 0)

    def test_edit_outside_grows_and_shifts(self):
        res = self.ed.edit({"file": self.file, "ops": [{"op": "set", "block": STONE, "pts": [[-2, 0, 5]]}]})
        self.assertEqual(res["shift"], [2, 0, 0])
        self.assertEqual(res["size"], [6, 4, 6])
        self.ed.save({"file": self.file})
        b = self.disk_blocks(self.file)
        self.assertEqual(BLOCKS.names[b[0, 5, 0]], STONE)

    def test_grow_moves_placements_on_save(self):
        # Growing to negative coordinates shifts the array; the placement must move so the
        # existing blocks stay where they were in the world.
        pl = self.pl.place({"file": self.file, "server": "s", "dim": "overworld", "x": 100, "y": 64, "z": 100})
        self.ed.edit({"file": self.file, "ops": [{"op": "set", "block": STONE, "pts": [[-1, -2, -3]]}]})
        self.assertEqual(self.pl.get(pl["id"])["x"], 100)    # unchanged until save
        self.ed.save({"file": self.file})
        moved = self.pl.get(pl["id"])
        self.assertEqual((moved["x"], moved["y"], moved["z"]), (99, 62, 97))

    def test_replace_and_hollow(self):
        f = self.file
        self.ed.edit({"file": f, "ops": [{"op": "fill", "block": STONE, "box": [0, 0, 0, 3, 3, 3]}]})
        res = self.ed.edit({"file": f, "ops": [{"op": "replace", "from": "stone", "block": "minecraft:dirt",
                                                 "box": [0, 0, 0, 3, 0, 3]}]})
        self.assertEqual(res["changed"], 16)
        res = self.ed.edit({"file": f, "ops": [{"op": "hollow", "block": STONE, "box": [0, 0, 0, 3, 3, 3]}]})
        self.ed.save({"file": f})
        b = self.disk_blocks(f)
        self.assertEqual(int((b == 0).sum()), 8)             # 2x2x2 inside is cleared
        self.assertEqual(int((b == BLOCKS.id("minecraft:dirt")).sum()), 0)

    def test_failed_op_rolls_back(self):
        with self.assertRaises(ValueError):
            self.ed.edit({"file": self.file, "ops": [{"op": "set", "block": STONE, "pts": [[0, 0, 0]]},
                                                      {"op": "nonsense"}]})
        info = self.ed.info(self.file)
        self.assertEqual(info["undo"], 0)
        self.assertEqual(self.ed.voxels(self.file)["palette"], ["minecraft:air"])

    def test_save_keeps_block_entities_with_int_arrays(self):
        # Regression: schematic.load reads NBT untyped, so arrays in block entity data
        # come back as numpy arrays, which nbt.write used to reject (e.g. a player
        # head's UUID made the schematic unsaveable).
        ids = np.zeros((1, 1, 2), np.int64)
        ids[0, 0, 0] = BLOCKS.id("minecraft:player_head[powered=false,rotation=0]")
        be = [{"Pos": nbt.IntArray([0, 0, 0]), "Id": "minecraft:skull",
               "Data": {"profile": {"id": nbt.IntArray([1, 2, 3, 4])}}}]
        self.pl.upload("head.schem", write(ids, "head", be))
        self.ed.edit({"file": "hochgeladen/head.schem",
                      "ops": [{"op": "set", "block": STONE, "pts": [[1, 0, 0]]}]})
        self.ed.save({"file": "hochgeladen/head.schem"})


class EditorOpenTest(EditorTestCase):
    def test_open_foreign_file_makes_a_copy(self):
        ids = np.zeros((2, 2, 2), np.int64)
        ids[0, 0, 0] = BLOCKS.id(STONE)
        src = os.path.join(self.lm_dir, "farm.schem")
        with open(src, "wb") as f:
            f.write(write(ids, "farm"))
        before = os.path.getmtime(src)
        info = self.ed.open({"file": "schematics/farm.schem"})
        self.assertEqual(info["file"], "hochgeladen/farm bearbeitet.schem")
        self.ed.edit({"file": info["file"], "ops": [{"op": "fill", "block": STONE, "box": [0, 0, 0, 1, 1, 1]}]})
        self.ed.save({"file": info["file"]})
        self.assertEqual(os.path.getmtime(src), before)      # original untouched
        self.assertEqual(int((self.disk_blocks("schematics/farm.schem") != 0).sum()), 1)

    def test_open_rotated_placement_bakes_rotation(self):
        ids = np.zeros((1, 1, 3), np.int64)
        ids[0, 0, :] = BLOCKS.id(STONE)
        self.pl.upload("row.schem", write(ids, "row"))
        pl = self.pl.place({"file": "hochgeladen/row.schem", "server": "s", "dim": "overworld", "x": 0, "y": 0, "z": 0})
        self.pl.update({"id": pl["id"], "rot": 90})
        info = self.ed.open({"pid": pl["id"]})
        self.assertEqual(info["size"], [1, 1, 3])
        after = self.pl.get(pl["id"])
        self.assertEqual((after["rot"], after["file"]), (0, info["file"]))

    def test_rename_updates_placements(self):
        f = self.ed.new({"name": "old"})["file"]
        pl = self.pl.place({"file": f, "server": "s", "dim": "overworld", "x": 0, "y": 0, "z": 0})
        info = self.ed.rename({"file": f, "name": "new name"})
        self.assertEqual(info["file"], "hochgeladen/new name.schem")
        self.assertEqual(self.pl.get(pl["id"])["file"], info["file"])
        self.assertFalse(os.path.exists(os.path.join(self.pl.upload_dir, "old.schem")))
        with self.assertRaises(ValueError):
            self.ed.rename({"file": "schematics/x.schem", "name": "y"})


class EditorCutTest(EditorTestCase):
    def test_cut_copies_world_and_crops_air(self):
        # House at x/z 8..14 with a roof at h+4; cut generously above it.
        res = self.ed.cut({"srv": support.SERVER, "dim": "overworld", "name": "house",
                           "x0": 14, "y0": 70, "z0": 8, "x1": 8, "y1": 150, "z1": 14})
        self.assertEqual(res["origin"], [8, 70, 8])
        top = max(support.top_y(x, z) for x in range(8, 15) for z in range(8, 15))
        self.assertEqual(res["size"], [7, top - 70 + 1, 7])
        b = self.disk_blocks(res["file"])
        want = np.array([[[BLOCKS.id(make_world.overworld(x, y, z)) for x in range(8, 15)]
                          for z in range(8, 15)] for y in range(70, top + 1)])
        np.testing.assert_array_equal(b, want)

    def test_cut_keeps_block_entities(self):
        res = self.ed.cut({"srv": support.SERVER, "dim": "overworld",
                           "x0": 9, "y0": 74, "z0": 6, "x1": 11, "y1": 76, "z1": 8})
        with open(self.pl.resolve(res["file"]), "rb") as f:
            s = sch.load(f.read(), "c.schem")
        self.assertEqual([(be["id"], be["pos"]) for be in s.block_entities], [("minecraft:sign", [1, 1, 1])])

    def test_cut_errors(self):
        with self.assertRaises(LookupError):
            self.ed.cut({"srv": support.SERVER, "dim": "the_end", "x0": 0, "y0": 0, "z0": 0, "x1": 1, "y1": 1, "z1": 1})
        with self.assertRaises(LookupError):
            self.ed.cut({"srv": support.SERVER, "dim": "overworld",
                         "x0": 500, "y0": 0, "z0": 500, "x1": 501, "y1": 1, "z1": 501})
        with self.assertRaises(ValueError):                  # wider than the editor allows
            self.ed.cut({"srv": support.SERVER, "dim": "overworld",
                         "x0": -16, "y0": 0, "z0": 0, "x1": 600, "y1": 100, "z1": 0})


if __name__ == "__main__":
    unittest.main()
