"""HTTP API tests: a real server on a free local port against the synthetic recording."""

import base64
import json
import os
import tempfile
import threading
import unittest
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer
from urllib.parse import quote, urlencode

import numpy as np

from viewer.core import schematic as sch
from viewer.core.chunk import BLOCKS
from viewer.core.schem import write
from viewer.server import App, make_handler
from viewer.tests import make_world, support

SRV = support.SERVER
# Bypass any HTTP(S)_PROXY from the environment: the server is local.
_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


class ServerTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        root = support.world_copy(cls.tmp.name)
        cache = os.path.join(cls.tmp.name, "cache")
        os.makedirs(cache)
        cls.app = App(root, cache)              # no process pools: meshes/tiles are computed in-thread
        cls.httpd = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(cls.app))
        cls.httpd.daemon_threads = True
        cls.base = f"http://127.0.0.1:{cls.httpd.server_address[1]}"
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(5)
        cls.tmp.cleanup()

    # ------------------------------------------------------------------ helpers

    def request(self, path, body=None, raw=None, headers=None):
        """-> (status, headers, body bytes). POST when body/raw is given."""
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self.base + path, data=data, headers=headers or {},
                                     method="POST" if data is not None else "GET")
        try:
            with _OPENER.open(req, timeout=20) as r:
                return r.status, r.headers, r.read()
        except urllib.error.HTTPError as e:
            with e:
                return e.code, e.headers, e.read()

    def get_json(self, path, status=200):
        code, _, data = self.request(path)
        self.assertEqual(code, status, data)
        return json.loads(data)

    def post_json(self, path, body, status=200, **kw):
        code, _, data = self.request(path, body=body, **kw)
        self.assertEqual(code, status, data)
        return json.loads(data)

    # ------------------------------------------------------------------ world data

    def test_worlds(self):
        res = self.get_json("/api/worlds")
        self.assertEqual([w["id"] for w in res["worlds"]], [SRV])
        dims = {d["key"]: d for d in res["worlds"][0]["dims"]}
        self.assertEqual(list(dims), ["overworld", "the_nether"])
        self.assertEqual(dims["overworld"]["bounds"], [-16, -16, 16, 16])
        self.assertEqual([l["id"] for l in dims["the_nether"]["layers"]], ["cave127", "surface320"])

    def test_column(self):
        res = self.get_json(f"/api/column?srv={SRV}&dim=overworld&x=0&z=0")
        self.assertEqual(res, {"x": 0, "z": 0, "y": support.top_y(0, 0), "known": True,
                               "block": make_world.overworld(0, support.top_y(0, 0), 0),
                               "biome": "minecraft:plains"})
        res = self.get_json(f"/api/column?srv={SRV}&dim=the_nether&x=-5&z=3&layer=cave127")
        self.assertEqual((res["block"], res["biome"]), ("minecraft:netherrack", "minecraft:nether_wastes"))
        self.assertEqual(self.get_json(f"/api/column?srv={SRV}&dim=overworld&x=900&z=0"),
                         {"x": 900, "z": 0, "known": False})

    def test_column_errors(self):
        self.assertIn("error", self.get_json(f"/api/column?srv={SRV}&dim=the_end&x=0&z=0", 404))
        self.assertIn("error", self.get_json(f"/api/column?srv={SRV}&dim=overworld&x=0", 400))
        self.assertIn("error", self.get_json(f"/api/column?srv={SRV}&dim=overworld&x=a&z=0", 400))

    def test_tile_is_png(self):
        code, headers, data = self.request(f"/api/tile/{SRV}/overworld/surface320/0/0/0.png")
        self.assertEqual(code, 200)
        self.assertEqual(headers["Content-Type"], "image/png")
        self.assertEqual(data[:8], b"\x89PNG\r\n\x1a\n")
        code, _, _ = self.request(f"/api/tile/{SRV}/overworld/surface320/5/0/0.png")
        self.assertEqual(code, 400)                         # zoom out of range

    def test_schem_export(self):
        q = urlencode({"srv": SRV, "dim": "overworld", "x0": 0, "y0": 70, "z0": 0,
                       "x1": 3, "y1": 72, "z1": 3, "name": "a/b"})
        code, headers, data = self.request("/api/schem?" + q)
        self.assertEqual(code, 200)
        self.assertIn('filename="a_b.schem"', headers["Content-Disposition"])
        self.assertEqual(json.loads(headers["X-Schem-Info"])["size"], [4, 3, 4])
        s = sch.load(data, "a_b.schem")
        self.assertEqual(BLOCKS.names[s.blocks[0, 0, 0]], make_world.overworld(0, 70, 0))

    # ------------------------------------------------------------------ schematics + editor

    def test_library_and_upload(self):
        lib = self.get_json("/api/schematics/library")
        self.assertIn("hochgeladen", lib["folders"])
        ids = np.zeros((1, 1, 1), np.int64)
        ids[0, 0, 0] = BLOCKS.id("minecraft:stone")
        code, _, data = self.request("/api/schematics/upload?name=" + quote("up load.schem"), raw=write(ids, "u"))
        self.assertEqual(code, 200, data)
        self.assertEqual(json.loads(data)["file"], "hochgeladen/up load.schem")
        files = [e["file"] for e in self.get_json("/api/schematics/library")["files"]]
        self.assertIn("hochgeladen/up load.schem", files)
        code, _, _ = self.request("/api/schematics/upload?name=x.schem", raw=b"garbage")
        self.assertEqual(code, 400)

    def test_editor_new_info_voxels(self):
        info = self.post_json("/api/editor/new", {"name": "api-new", "size": [3, 2, 1]})
        self.assertEqual(info["size"], [3, 2, 1])
        f = quote(info["file"])
        self.assertEqual(self.get_json(f"/api/editor/info?file={f}")["file"], info["file"])
        res = self.post_json("/api/editor/edit", {"file": info["file"], "ops": [
            {"op": "set", "block": "minecraft:gold_block", "pts": [[2, 1, 0]]}]})
        self.assertTrue(res["dirty"])
        vox = self.get_json(f"/api/editor/voxels?file={f}")
        idx = np.frombuffer(base64.b64decode(vox["data"]), "<u2").reshape(2, 1, 3)
        self.assertEqual(vox["palette"][idx[1, 0, 2]], "minecraft:gold_block")
        self.assertTrue(self.post_json("/api/editor/save", {"file": info["file"]})["saved"])
        code, _, data = self.request(f"/api/editor/file?file={f}")
        self.assertEqual(code, 200)
        self.assertEqual(BLOCKS.names[sch.load(data, "x.schem").blocks[1, 0, 2]], "minecraft:gold_block")

    def test_editor_cut(self):
        res = self.post_json("/api/editor/cut", {"srv": SRV, "dim": "overworld", "name": "api-cut",
                                                 "x0": 0, "y0": 60, "z0": 0, "x1": 2, "y1": 200, "z1": 2})
        self.assertEqual(res["origin"], [0, 60, 0])
        self.assertEqual(res["size"][0::2], [3, 3])

    def test_editor_errors(self):
        self.assertIn("error", self.get_json("/api/editor/info?file=" + quote("hochgeladen/missing.schem"), 404))
        self.assertIn("error", self.post_json("/api/editor/cut", {"srv": SRV, "dim": "nope", "x0": 0, "y0": 0,
                                                                  "z0": 0, "x1": 0, "y1": 0, "z1": 0}, 404))
        self.assertIn("error", self.post_json("/api/editor/edit", {"file": "hochgeladen/missing.schem"}, 404))
        self.assertIn("error", self.post_json("/api/editor/unknown", {}, 404))

    def test_placements(self):
        f = self.post_json("/api/editor/new", {"name": "api-place", "size": [2, 2, 2]})["file"]
        pl = self.post_json("/api/schematics/place", {"file": f, "server": SRV, "dim": "overworld",
                                                      "x": 1, "y": 70, "z": 2})
        self.assertEqual(pl["size"], [2, 2, 2])
        listed = self.get_json(f"/api/schematics?srv={SRV}&dim=overworld")
        self.assertIn(pl["id"], [p["id"] for p in listed])
        mat = self.get_json(f"/api/schematics/{pl['id']}/materials")
        self.assertEqual(mat["blocks"], 0)
        self.assertTrue(self.post_json("/api/schematics/delete", {"id": pl["id"]})["ok"])
        self.assertNotIn(pl["id"], [p["id"] for p in self.get_json("/api/schematics")])

    def test_printer_blocks(self):
        stairs = sch._norm_state("minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]")
        ids = np.zeros((1, 1, 3), np.int64)
        ids[0, 0, 0] = BLOCKS.id("minecraft:stone")
        ids[0, 0, 2] = BLOCKS.id(stairs)
        code, _, data = self.request("/api/schematics/upload?name=printer.schem", raw=write(ids, "p"))
        self.assertEqual(code, 200, data)
        f = json.loads(data)["file"]
        pl = self.post_json("/api/schematics/place", {"file": f, "server": "p.test", "dim": "overworld",
                                                      "x": 10, "y": 70, "z": 10})
        q = "/api/printer/blocks?srv=p.test&dim=overworld&x=11&y=70&z=10&r=4"
        self.assertEqual(self.get_json(q)["blocks"], [])          # not "In game" yet
        self.post_json("/api/schematics/update", {"id": pl["id"], "ingame": True})
        res = self.get_json(q)
        got = {tuple(res["blocks"][i:i + 3]): res["palette"][res["blocks"][i + 3]] for i in range(0, len(res["blocks"]), 4)}
        self.assertEqual(got, {(10, 70, 10): "minecraft:stone", (12, 70, 10): stairs})   # air left out
        self.assertEqual(self.get_json("/api/printer/blocks?srv=p.test&dim=overworld&x=100&y=70&z=10&r=4")["blocks"], [])
        self.assertEqual(self.get_json(q.replace("overworld", "the_nether"))["blocks"], [])

    def test_server_safe(self):
        srv = "safe.test"
        goto = {"srv": srv, "kind": "goto", "x": 1, "z": 2}
        self.assertFalse(self.get_json(f"/api/control?srv={srv}")["safe"])
        self.assertTrue(self.post_json("/api/control", {"srv": srv, "safe": True})["safe"])
        self.assertTrue(self.get_json(f"/api/control?srv={srv}")["safe"])
        self.assertIn("Server-safe", self.post_json("/api/commands", goto, 403)["error"])
        self.post_json("/api/commands", {"srv": srv, "kind": "stop"})                 # stopping always works
        self.post_json("/api/auto", {"srv": srv, "pid": "nope"}, 403)
        self.post_json("/api/auto", {"srv": srv, "store": True}, 403)
        self.post_json("/api/fix", {"srv": srv, "pid": "nope"}, 403)
        self.post_json("/api/control", {"srv": srv, "web": {"on": True, "bps": 50}})  # own printer stays allowed
        web = self.get_json(f"/api/control?srv={srv}")["web"]
        self.assertEqual((web["on"], web["bps"]), (True, 20.0))
        self.assertFalse(self.get_json(f"/api/control?srv={SRV}")["safe"])           # per server
        self.post_json("/api/control", {"srv": srv, "safe": False})
        self.post_json("/api/commands", goto)
        with open(os.path.join(self.app.rec.root, ".viewer", "safety.json"), encoding="utf-8") as fh:
            self.assertEqual(json.load(fh)["safe"][srv], False)

    # ------------------------------------------------------------------ containers, waypoints

    def test_containers(self):
        # Both halves of a double chest end up under the smaller coordinate.
        self.post_json("/api/containers", {"server": "c.test", "x": 5, "y": 64, "z": 1, "block": "minecraft:chest",
                                           "items": [{"id": "minecraft:stone", "count": 12}, {"bad": 1}]})
        e = self.post_json("/api/containers", {"server": "c.test", "x": 5, "y": 64, "z": 2, "other": [5, 64, 1],
                                               "block": "minecraft:chest", "items": []})
        self.assertEqual((e["x"], e["y"], e["z"]), (5, 64, 1))
        self.post_json("/api/containers", {"server": "other.test", "x": 0, "y": 0, "z": 0,
                                           "block": "minecraft:ender_chest"})
        listed = self.get_json("/api/containers?srv=c.test")
        self.assertEqual([c["key"] for c in listed], ["c.test|overworld|5,64,1"])
        self.assertEqual(listed[0]["items"], [])
        self.assertEqual(self.get_json("/api/containers?srv=other.test")[0]["key"], "other.test|ender")
        self.post_json("/api/containers/delete", {"key": "c.test|overworld|5,64,1"})
        self.assertEqual(self.get_json("/api/containers?srv=c.test"), [])
        self.assertIn("error", self.post_json("/api/containers", {"x": 1}, 400))

    def test_waypoints_round_trip(self):
        saved = self.post_json("/api/waypoints", [{"name": "spawn", "server": SRV, "dim": "overworld", "x": 1, "z": 2},
                                                  {"name": "incomplete"}])
        self.assertEqual(len(saved), 1)                     # entries without coordinates are dropped
        self.assertEqual(self.get_json("/api/waypoints")[0]["y"], 64)
        self.assertIn("error", self.post_json("/api/waypoints", {"not": "a list"}, 400))

    # ------------------------------------------------------------------ protocol details

    def test_foreign_origin_cannot_write(self):
        res = self.post_json("/api/editor/new", {"name": "evil"}, 403, headers={"Origin": "https://example.com"})
        self.assertIn("error", res)
        self.post_json("/api/editor/new", {"name": "local-ok"}, headers={"Origin": "http://localhost:8770"})

    def test_foreign_host_is_refused(self):
        # DNS rebinding: a foreign domain resolved to 127.0.0.1 sends its own name as Host
        code, _, _ = self.request("/api/player", headers={"Host": "evil.example:8770"})
        self.assertEqual(code, 403)
        self.post_json("/api/editor/new", {"name": "evil"}, 403, headers={"Host": "evil.example:8770"})
        for host in ("localhost:8770", "127.0.0.1:8770", "192.168.1.5:8770", "[::1]:8770"):
            code, _, _ = self.request("/api/version", headers={"Host": host})
            self.assertEqual(code, 200, host)

    def test_invalid_json_and_unknown_paths(self):
        code, _, _ = self.request("/api/editor/new", raw=b"{not json")
        self.assertEqual(code, 400)
        self.assertEqual(self.request("/api/does-not-exist")[0], 404)
        self.assertEqual(self.request("/api/does-not-exist", body={})[0], 404)

    def test_static_files(self):
        code, headers, data = self.request("/")
        self.assertEqual(code, 200)
        self.assertTrue(headers["Content-Type"].startswith("text/html"))
        self.assertEqual(self.request("/static/..%2Fserver.py")[0], 403)

    def test_lang_follows_game(self):
        from unittest import mock
        from viewer import firstrun
        with mock.patch.object(firstrun, "client_info", return_value={"language": "de_de"}):
            self.assertEqual(self.get_json("/api/lang")["game"], "de_de")
        with mock.patch.object(firstrun, "client_info", return_value={}):
            self.assertIsNone(self.get_json("/api/lang")["game"])

    def test_position_and_player(self):
        self.post_json("/api/position", {"x": 1.5, "y": 70, "z": -3, "dim": "minecraft:overworld", "server": SRV})
        pl = self.get_json("/api/player")
        self.assertTrue(pl["active"])
        self.assertEqual((pl["x"], pl["dim"]), (1.5, "overworld"))
        self.assertEqual(self.post_json("/api/position", {"y": 1}, 400)["error"], "x/y/z fehlen")


if __name__ == "__main__":
    unittest.main()
