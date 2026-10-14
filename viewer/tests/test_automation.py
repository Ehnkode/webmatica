"""Auto-build, layer automation and radar: logic without the game.

A fake app stands in for the server: the player position, the inventory and
the verify result are set by the test, commands land in a real ``Commands``.
"""

import tempfile
import time
import types
import unittest
from unittest import mock

import numpy as np

from viewer import autobuild
from viewer.autobuild import AutoBuild
from viewer.core import schematic as sch
from viewer.core.chunk import BLOCKS
from viewer.extras import Commands, Control, Radar

SRV = "demo.server"
PLACEMENT = {"id": "p1", "x": 100, "y": 64, "z": 100, "size": (5, 3, 5), "dim": "overworld"}


class FakePlacements:
    def __init__(self, result):
        self.result = result

    def get(self, pid):
        if pid != "p1":
            raise LookupError(pid)
        return dict(PLACEMENT)

    def _with_size(self, pl):
        return pl

    def verify(self, pid, rec, near=None):
        return self.result


def verify_result(missing_items=(), unknown=0, extra=0):
    return {"todo": [], "todoTotal": 0, "lowestMissing": None, "missingItems": list(missing_items),
            "stats": {"total": 75, "ok": 75 - unknown, "rotated": 0, "missing": 0, "wrong": 0,
                      "extra": extra, "unknown": unknown},
            "points": {"missing": [], "wrong": [], "extra": [], "rotated": []}}


def fake_app(result, x=100.0, z=100.0, recording=True):
    app = types.SimpleNamespace()
    app.player = {"server": SRV, "dim": "overworld", "x": x, "y": 64.0, "z": z, "name": "Tobi",
                  "recording": recording, "others": []}
    app.player_ts = time.monotonic()
    app.inventory = {"server": SRV, "items": []}
    app.commands = Commands()
    app.placements = FakePlacements(result)
    app.rec = None
    return app


class AutoBuildTest(unittest.TestCase):
    def make(self, result, **kw):
        app = fake_app(result, **kw)
        ab = AutoBuild(app)
        ab.settings["radar"] = False
        ab.start(SRV, "p1")
        return app, ab

    def step(self, app, ab):
        app.player_ts = time.monotonic()
        ab.state[SRV]["next"] = 0.0
        ab.step(SRV)
        return ab.state[SRV]

    def test_unrecorded_site_is_not_reported_as_done(self):
        # Nothing "missing" because the frame was never recorded: must not finish
        app, ab = self.make(verify_result(unknown=75), x=400.0, z=400.0)
        st = self.step(app, ab)
        self.assertEqual(st["phase"], "approach")
        cmds = app.commands.pop(SRV)
        self.assertEqual([c["kind"] for c in cmds], ["goto"])
        self.assertNotIn("fertig", " ".join(m for _t, m in st["log"]))

    def test_unrecorded_site_gives_up_after_waiting_there(self):
        app, ab = self.make(verify_result(unknown=75))
        st = self.step(app, ab)
        self.assertEqual(st["phase"], "plan")
        self.assertIn("Aufnahme", st["info"])
        with mock.patch.object(autobuild.time, "monotonic", return_value=time.monotonic() + autobuild.UNSEEN_WAIT + 1):
            st = self.step(app, ab)
        self.assertEqual(st["phase"], "error")
        self.assertIn("noch nie aufgezeichnet", st["log"][-1][1])

    def test_complete_site_finishes(self):
        app, ab = self.make(verify_result())
        ab.settings["fix"] = False
        ab.settings["leftovers"] = False
        st = self.step(app, ab)
        self.assertEqual(st["phase"], "done")
        self.assertIn("fertig", st["log"][-1][1])

    def test_pauses_while_recording_is_off(self):
        app, ab = self.make(verify_result(missing_items=[("stone", 10)]), recording=False)
        st = self.step(app, ab)
        self.assertEqual(st["phase"], "plan")
        self.assertIn("F9", st["info"])
        self.assertEqual(app.commands.pop(SRV), [])
        app.player["recording"] = True
        st = self.step(app, ab)
        self.assertNotIn("F9", st["info"] or "")

    def test_dig_switch_reaches_the_mod(self):
        app, ab = self.make(verify_result())
        self.assertFalse(ab.guard(SRV)["dig"])
        ab.set_settings({"dig": True})
        self.assertTrue(ab.guard(SRV)["dig"])
        ab.stop(SRV)
        self.assertTrue(ab.guard(SRV)["dig"], "applies to walking from the map too")


class FakeChunk:
    def __init__(self, blocks):
        self.blocks = blocks
        self.min_y = 0

    @property
    def height(self):
        return self.blocks.shape[0]


class FakeRec:
    """One chunk at (0, 0), 8 blocks high."""

    def __init__(self, fill):
        self.blocks = np.full((8, 16, 16), BLOCKS.id(fill), np.uint16)

    def chunk(self, dim, cx, cz):
        return FakeChunk(self.blocks) if (cx, cz) == (0, 0) else None


class VerifyTest(unittest.TestCase):
    def test_water_and_grass_are_not_extra(self):
        air = np.full((1, 2, 2), BLOCKS.id("minecraft:air"), np.uint16)
        for fill, extra in (("minecraft:water[level=0]", 0), ("minecraft:short_grass", 0), ("minecraft:stone", 4)):
            res = sch.verify(sch.Schem("a", air), FakeRec(fill), None, 0, 0, 0)
            self.assertEqual(res["stats"]["extra"], extra, fill)

    def test_lowest_missing_layer_ignores_the_todo_cap(self):
        # Layer 0 is complete but wrongly rotated in 500 spots, layer 1 is missing
        stairs = "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]"
        b = np.full((2, 16, 16), BLOCKS.id(stairs), np.uint16)
        rec = FakeRec("minecraft:air")
        rec.blocks[0] = BLOCKS.id(stairs.replace("north", "south"))
        res = sch.verify(sch.Schem("s", b), rec, None, 0, 0, 0, max_todo=100)
        self.assertTrue(all(t[4] == "rotated" for t in res["todo"]))
        self.assertEqual(res["lowestMissing"], {"y": 1, "count": 256})

    def test_unknown_blocks_count_as_unknown(self):
        b = np.full((1, 1, 1), BLOCKS.id("minecraft:stone"), np.uint16)
        res = sch.verify(sch.Schem("u", b), FakeRec("minecraft:air"), None, 5000, 0, 5000)
        self.assertEqual(res["stats"]["unknown"], 1)
        self.assertEqual(res["missingItems"], [])


class LayerAutomationTest(unittest.TestCase):
    def test_uses_lowest_missing_layer(self):
        c = Control()
        c.push({"srv": SRV, "auto": "p1"})
        res = {"todo": [[0, 0, 0, "oak_stairs", "rotated", "oak_stairs"]], "todoTotal": 900,
               "lowestMissing": {"y": 7, "count": 12}}
        c.auto_step(SRV, lambda pid: res)
        st = c.state[SRV]
        self.assertEqual(st["layer"], {"mode": "range", "min": 7, "max": 7})
        self.assertIn("Y 7", st["autoInfo"])


class RadarTest(unittest.TestCase):
    def setUp(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        self.root = tmp.name

    def test_many_players_do_not_flood_the_log(self):
        r = Radar(self.root)
        me = {"server": SRV, "dim": "overworld", "x": 0, "z": 0}
        others = [{"name": f"p{i}", "x": i, "y": 64, "z": 0} for i in range(60)]
        for _ in range(5):
            r.update(me, others)
        self.assertEqual(len(r.log), 60)

    def test_returning_after_two_minutes_is_a_new_entry(self):
        r = Radar(self.root)
        me = {"server": SRV, "dim": "overworld", "x": 0, "z": 0}
        p = [{"name": "a", "x": 1, "y": 64, "z": 0}]
        r.update(me, p)
        r.log[-1]["last"] -= 200
        r.update(me, p)
        self.assertEqual(len(r.log), 2)


if __name__ == "__main__":
    unittest.main()


class FakeContainers:
    def __init__(self, chests):
        self.chests = chests

    def list(self, srv):
        return list(self.chests)


def chest(x, z, items=(), slots=27, dim="overworld", block="minecraft:chest"):
    return {"key": f"{SRV}|{dim}|{x},64,{z}", "server": SRV, "dim": dim, "x": x, "y": 64, "z": z, "block": block,
            "title": "Chest", "slots": slots, "items": [{"id": i, "count": 64, "slot": n} for n, i in enumerate(items)]}


def full_inventory(used, extra=()):
    return [{"id": "minecraft:cobblestone", "count": 64, "slot": s} for s in range(used)] + list(extra)


class FarmDepositTest(unittest.TestCase):
    def make(self, chests=(), used=36, extra=()):
        app = fake_app(verify_result())
        app.containers = FakeContainers(chests)
        app.inventory = {"server": SRV, "items": full_inventory(used, extra)}
        ab = AutoBuild(app)
        ab.state[SRV] = {"pid": None, "mode": "farm", "phase": "fplan", "info": "", "log": [], "cmd": None,
                         "ts": time.monotonic(), "dim": "overworld", "chest": None, "place": True}
        return app, ab

    def test_nothing_to_deposit_into_keeps_mining(self):
        # 34 of 36 slots used, no chest anywhere: no reason to stop yet
        app, ab = self.make(used=34)
        self.assertFalse(ab._can_deposit(SRV, ab.state[SRV], app.player))

    def test_full_without_chest_says_how_full(self):
        app, ab = self.make(used=36)
        ab._farm_deposit(SRV, ab.state[SRV], 0.0, app.player, 0)
        st = ab.state[SRV]
        self.assertEqual(st["phase"], "error")
        self.assertIn("36/36", st["info"])

    def test_uses_a_known_chest_nearby(self):
        app, ab = self.make([chest(300, 300), chest(110, 105, ["minecraft:dirt"])], used=34)
        st = ab.state[SRV]
        self.assertTrue(ab._can_deposit(SRV, st, app.player))
        ab._farm_deposit(SRV, st, 0.0, app.player, 2)
        self.assertEqual(st["phase"], "deposit")
        self.assertEqual((st["chest"]["x"], st["chest"]["z"]), (110, 105))
        cmd = app.commands.queue[SRV][-1]
        self.assertEqual((cmd["kind"], cmd["x"], cmd["z"]), ("store", 110, 105))

    def test_far_full_and_ender_chests_are_not_used(self):
        far = chest(100 + 500, 100)
        stuffed = chest(105, 100, ["minecraft:dirt"] * 27)
        ender = chest(101, 100, block="minecraft:ender_chest", dim="overworld")
        app, ab = self.make([far, stuffed, ender], used=34)
        self.assertIsNone(ab._near_chest(SRV, ab.state[SRV], app.player))


class StorePlanTest(unittest.TestCase):
    def test_only_chests_nearby_with_coordinates(self):
        app = fake_app(verify_result())
        app.containers = FakeContainers([chest(120, 100, ["minecraft:cobblestone"]),
                                         chest(5000, 100, ["minecraft:cobblestone"] * 3)])
        app.inventory = {"server": SRV, "items": full_inventory(12)}
        plan = AutoBuild(app).store_plan(SRV)
        self.assertEqual([(s["x"], s["dist"]) for s in plan["stops"]], [(120, 20)])
        self.assertIn("120 64 100", plan["stops"][0]["name"])
