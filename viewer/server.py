"""HTTP server of the viewer.

    python -m viewer --recording "<.minecraft>/webmatica_recording"

Endpoints (all JSON, except images):

        GET  /                                   UI
        GET  /api/worlds                         servers, dimensions, layers
        GET  /api/tile/<srv>/<dim>/<layer>/<z>/<x>/<y>.png[?at=YYYY-MM-DD|raw=1]   (at: state on that day, changed chunks only; raw: without shading)
        GET  /api/changes?srv=&dim=&since=       regions changed since <since>
        GET  /api/column?srv=&dim=&x=&z=&layer=  top block, height, biome at a spot
        GET  /api/markers?srv=&dim=&layer=       signs + containers per chunk
        GET  /api/waypoints  POST /api/waypoints waypoints (in the recording's waypoints.json)
        POST /api/position                       live position from the mod
        GET  /api/player                         last position (+ active)
        GET  /api/skin/face.png | full.png       skin from the client
        GET  /api/lod/<srv>/<dim>/<x>/<z>[?level=0..3]  height field for the 3D far view (level 0 = region, 4x larger per level)
        GET  /api/trail?srv=&dim=                player trail
        GET  /api/schematics?srv=&dim=           placed schematics
        GET  /api/schematics/library             schematic files in the folders
    GET  /api/schematics/<id>/mesh|materials|verify
    POST /api/schematics/place|update|delete
        POST /api/schematics/upload?name=        upload a file (raw data)
        GET  /api/litematica?srv=                placements with "In game" for the mod
        POST /api/litematica/status              mod reports what arrived in Litematica
        GET  /api/containers?srv=                chest inventory     POST /api/containers (mod) | /delete
        GET  /api/containers/world?srv=&dim=&x=&z=&r=  all chests in the recording (even never opened)
        GET  /api/containers/lid?srv=&dim=        chests opened by others   POST (mod)
        GET  /api/inventory                      last player inventory (from the mod)
        GET  /api/commands?srv=                  Baritone commands for the mod (pick up)
        POST /api/commands                       drop a command      POST /api/commands/result (mod)
        GET  /api/auto?srv=                      auto-build state    POST /api/auto {srv, pid} start / {srv} stop
        GET  /api/control?srv=                   Printer/layer target state for the mod   POST /api/control (browser)
        POST /api/control/status                 mod reports in-game Printer/layer   GET /api/control/state?srv=
        GET/POST /api/auto/settings              auto-build protection: radar, radarDist, friends, eat, tools, leftovers, fix
        POST /api/auto {srv, queue: [pid]}       set the queue
        POST /api/auto {srv, farm: {area, dim, targets, chest, placeChest}}   start farming
        POST /api/auto {srv, farm: {area, dim, flatten: {kind: flat|clear|fill, level, clear, fill, block}, chest, placeChest}}   flatten
        GET  /api/farm/scan?srv=&dim=&x0=..      which blocks are in the area
        GET  /api/farm/flatscan?..&kind=&level=  flatten preview: blocks to break / holes to fill, per chunk, what gets mined
        GET  /api/spawn/<srv>/<dim>/<cx>/<cz>    where mobs can spawn (needs light in the recording)
        GET  /api/spawn-tile/<srv>/<dim>/<rx>/<rz>.png[?ymax=]   the same as a 2D overlay per region
        POST /api/fix {srv, pid, extra}          have wrong/rotated (and extra) blocks broken
        GET  /api/radar?srv=                     log: who was within view distance when
        GET  /editor?file=                       schematic editor (separate page)
        GET  /api/editor/blocks|voxels|mesh|info editor data   POST /api/editor/open|new|cut|edit|undo|redo|save|discard
        GET  /api/mesh/<srv>/<dim>/<cx>/<cz>     3D chunk [?full=1 caves, ylo/yhi layer cut, xray=a,b only those blocks, at=YYYY-MM-DD state on that day]
        GET  /api/xray/names                     block names for the X-ray search
        GET  /api/xray/find?srv=&dim=&x=&z=&r=&b=a,b   searched blocks around x/z (r chunks) for the 2D map
        GET  /api/history/dates?srv=&dim=        timeline: days with a backed-up state
        GET  /api/history/diff?srv=&dim=&date=   changes since that day
"""

from __future__ import annotations

import argparse
import base64
import ipaddress
import gzip
import hashlib
import json
import mimetypes
import multiprocessing
import os
import re
import shutil
import threading
import time

import numpy as np
from concurrent.futures import ProcessPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

from .core.chunk import AIR_NAMES, BIOMES, BLOCKS, base_name
from .core.mesher import MAGIC, mesh_chunk
from .core.models import MODELS
from .core.schem import export as schem_export
from .core.tiles import Layer, PastTiles, TileStore, render_chunk
from .core.world import ChunkCache
from .placements import Placements
from .editor import Editor
from . import farm, spawn, xray
from .autobuild import AutoBuild
from .extras import Commands, Containers, Control, History, LidLog, Radar, world_chests
from .core.world import DIM_LABELS, LEGACY_SERVER, Recording

HERE = os.path.dirname(os.path.abspath(__file__))
STATIC = os.path.join(HERE, "static")

PLAYER_STALE = 5.0


class App:
    def __init__(self, recording: str, cache_dir: str):
        self.rec = Recording(recording)
        self.tiles = TileStore(self.rec, cache_dir)
        self._past: dict = {}
        self.cache_dir = cache_dir
        self.player: dict | None = None
        self.player_ts = 0.0
        self.player_seq = 0
        self.player_cond = threading.Condition()
        self.skin_face: bytes | None = None
        self.skin_full: bytes | None = None
        self.wp_lock = threading.Lock()
        self.meshes = ChunkCache(max_items=8192)
        self.streams = 0        # open /api/player/stream connections = visible viewer tabs
        self.mesh_pool = None   # ProcessPoolExecutor, see main(); without a pool it is computed in the thread
        self.skin_version = 0
        self.skin_fetch_name = None
        self.placements = Placements(recording)
        self.containers = Containers(recording)
        self.inventory = None          # last player inventory from the mod
        self.commands = Commands()
        self.history = History(recording)
        self.control = Control()
        self.radar = Radar(recording)
        self.lids = LidLog(recording)
        self.editor = Editor(self.placements, self.rec)
        self.auto = AutoBuild(self, recording)
        self.world_chests = {}         # chests from the recording, briefly cached
        self.polling: dict[str, float] = {}   # server -> last /api/control poll of its mod
        self.lm_status = {}            # last feedback from the mod on the Litematica bridge
        self.lm_status_ts = 0.0
        self.trail_path = os.path.join(recording, ".viewer", "trail.json")
        self.trail = self._load_trail()
        self.trail_saved = time.monotonic()

    # -------------------------------------------------------------- Litematica bridge

    def past(self, server: str, dim_key: str, date: str) -> PastTiles:
        """Tiles for the timelapse: map at the start of ``date``."""
        key = (server, dim_key, date)
        pt = self._past.get(key)
        if pt is None:
            if len(self._past) > 64:
                self._past.clear()
            pt = self._past[key] = PastTiles(self.rec, os.path.join(self.cache_dir, "_past", date),
                                             lambda: self.history.index(server, dim_key, date))
        return pt

    def with_ingame(self, pls: list) -> list:
        """Adds to each placement whether it is currently in game in Litematica."""
        fresh = time.monotonic() - self.lm_status_ts < 6.0
        st = self.lm_status if fresh else {}
        have = set(st.get("ids") or [])
        errs = st.get("errors") or {}
        for pl in pls:
            if not pl.get("ingame"):
                pl["ingameState"] = None
            elif not fresh:
                pl["ingameState"] = "offline"         # game/mod not connected
            elif not st.get("available", False):
                pl["ingameState"] = "kein Litematica"
            elif pl["id"] in errs:
                pl["ingameState"] = "Fehler: " + str(errs[pl["id"]])[:120]
            elif pl["id"] in have:
                pl["ingameState"] = "ok"
            else:
                pl["ingameState"] = "andere Dimension" if st.get("dim") and st.get("dim") != pl.get("dim") else "wartet"
        return pls

    # -------------------------------------------------------------- Trail

    def _load_trail(self) -> dict:
        try:
            with open(self.trail_path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return {}

    def add_trail(self, pl: dict):
        key = f"{pl['server']}/{pl['dim']}"
        pts = self.trail.setdefault(key, [])
        x, y, z = round(pl["x"], 1), round(pl["y"], 1), round(pl["z"], 1)
        now = int(time.time())
        if pts:
            lx, ly, lz, lt = pts[-1]
            if (x - lx) ** 2 + (z - lz) ** 2 < 9 and now - lt < 30:
                return
        pts.append([x, y, z, now])
        if len(pts) > 20000:
            del pts[:len(pts) - 20000]
        if time.monotonic() - self.trail_saved > 30:
            self.save_trail()

    def save_trail(self):
        self.trail_saved = time.monotonic()
        try:
            tmp = self.trail_path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.trail, f)
            os.replace(tmp, self.trail_path)
        except OSError:
            pass

    # -------------------------------------------------------------- Far view

    def lod(self, dim, rx: int, rz: int, level: int = 0) -> tuple[bytes, str] | None:
        """Height field 129x129 of a far-view tile as int16, -32768 = no data.

        Level 0 is one region (512 blocks, one point every 4 blocks), each further
        level combines 4 x 4 tiles of the level before (2048 blocks every 16,
        8192 blocks every 64). Colors come from the map tile at zoom level -2 * level.
        Row/column 128 comes from the neighbor tiles so no seam remains.
        """
        layer = Layer.parse(self.layers_for(dim.key)[0]["id"])
        if self.tiles.tile(dim, layer, -2 * level, rx, rz) is None:
            return None
        res = self._lod_grid(dim, layer, level, rx, rz)
        if res is None:
            return None
        g, stamp = res
        g = np.pad(g.astype(np.int32), ((0, 1), (0, 1)), mode="edge")
        for (dx, dz), sl, take in (((1, 0), (slice(0, 128), 128), lambda a: a[:, 0]),
                                   ((0, 1), (128, slice(0, 128)), lambda a: a[0, :]),
                                   ((1, 1), (128, 128), lambda a: a[0, 0])):
            nb = self._lod_grid(dim, layer, level, rx + dx, rz + dz, cached_only=True)
            if nb is not None:
                g[sl] = take(nb[0])
        return gzip.compress(g.astype("<i2").tobytes(), 5), stamp

    def _lod_grid(self, dim, layer, level: int, x: int, z: int, cached_only: bool = False):
        """(128x128 int16, stamp) or None. Level 0 from the region tile (.npz),
        higher levels from the 16 tiles below (max per 4 x 4), cached on disk."""
        d = self.tiles._dir(dim, layer, -2 * level)
        if level == 0:
            npz = os.path.join(d, f"{x}_{z}.npz")
            try:
                mtime = os.path.getmtime(npz)
            except OSError:
                return None
            stamp = str(int(mtime * 1000))
            key = ("lodg", dim.id, x, z, stamp)
            hit = self.meshes.get(key)
            if hit is None:
                try:
                    with np.load(npz) as f:
                        h = np.where(f["has"], f["height"], -32768).astype(np.int32)
                except (OSError, ValueError, KeyError):
                    return None
                hit = h.reshape(128, 4, 128, 4).max(axis=(1, 3)).astype(np.int16)
                self.meshes.put(key, hit)
            return hit, stamp
        path = os.path.join(d, f"{x}_{z}.lod.npy")
        if cached_only:
            try:
                return np.load(path), str(int(os.path.getmtime(path) * 1000))
            except (OSError, ValueError):
                return None
        kids = [(i, j, self._lod_grid(dim, layer, level - 1, x * 4 + i, z * 4 + j)) for j in range(4) for i in range(4)]
        if all(k is None for _, _, k in kids):
            return None
        newest = max(int(k[1]) for _, _, k in kids if k is not None)
        try:
            mtime = int(os.path.getmtime(path) * 1000)
            if mtime >= newest:
                return np.load(path), str(mtime)
        except (OSError, ValueError):
            pass
        g = np.full((128, 128), -32768, np.int16)
        for i, j, k in kids:
            if k is not None:
                g[j * 32:(j + 1) * 32, i * 32:(i + 1) * 32] = k[0].reshape(32, 4, 32, 4).max(axis=(1, 3))
        tmp = path + ".tmp.npy"
        np.save(tmp, g)
        os.replace(tmp, path)
        return g, str(int(os.path.getmtime(path) * 1000))

    # -------------------------------------------------------------- 3D

    def chunk_stamp(self, dim, cx: int, cz: int) -> str:
        """Changes as soon as the chunk or one of its 4 neighbors was rewritten."""
        parts = []
        for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
            x, z = cx + dx, cz + dz
            r = dim.region(x >> 5, z >> 5)
            h = r.header() if r else None
            i = (x & 31) | ((z & 31) << 5)
            parts.append(f"{int(h.timestamps[i])}.{int(h.offsets[i])}" if h is not None else "-")
        return "-".join(parts)

    def mesh(self, dim, cx: int, cz: int, full: bool, ylo=None, yhi=None, names=(), at=None) -> tuple[bytes, str] | None:
        stamp = self.chunk_stamp(dim, cx, cz) + ("F" if full else "") + (f"C{ylo}:{yhi}" if yhi is not None else "")
        if at:
            stamp += "A" + at
        if names:
            stamp += "X" + hashlib.sha1(",".join(names).encode()).hexdigest()[:10]
        key = (dim.id, cx, cz, stamp)
        hit = self.meshes.get(key)
        if hit is not None:
            return hit, stamp
        if self.mesh_pool is not None:
            # Separate processes: otherwise Python computes all chunks one after another because of the GIL
            data = self.mesh_pool.submit(_mesh_job, dim.server, dim.key, cx, cz, full, ylo, yhi, names, at).result()
        else:
            data = _mesh_compute(self.rec, dim, cx, cz, full, ylo, yhi, names, at, self.history)
        if data is None:
            return None
        self.meshes.put(key, data)
        return data, stamp

    def xray_find(self, dim, cx: int, cz: int, r: int, names: tuple) -> dict:
        """Searched blocks in the chunks around (cx, cz) for the 2D map, per chunk cached."""
        todo, out = [], {}
        for z in range(cz - r, cz + r + 1):
            for x in range(cx - r, cx + r + 1):
                key = ("xray", dim.id, x, z, self.chunk_stamp(dim, x, z), names)
                hit = self.meshes.get(key)
                if hit is None:
                    todo.append(((x, z), key))
                else:
                    out[(x, z)] = hit
        if todo:
            if self.mesh_pool is not None:
                # in batches, so all worker processes take part
                n = max(1, len(todo) // (4 * (self.mesh_pool._max_workers or 1)) + 1)
                batches = [todo[i:i + n] for i in range(0, len(todo), n)]
                futs = [self.mesh_pool.submit(_xray_job, dim.server, dim.key, [c for c, _ in b], names) for b in batches]
                res = [r for f in futs for r in f.result()]
            else:
                res = [_xray_compute(self.rec, dim, x, z, names) for (x, z), _ in todo]
            for ((x, z), key), hit in zip(todo, res):
                hit = hit or ([0] * len(names), [])
                self.meshes.put(key, hit)
                out[(x, z)] = hit
        counts = [0] * len(names)
        pts: list = []
        for c, p in out.values():
            counts = [a + b for a, b in zip(counts, c)]
            pts.extend(p)
        return {"names": list(names), "counts": counts, "pts": pts[:4 * 20000], "chunks": len(out)}

    # -------------------------------------------------------------- Data

    def worlds(self) -> list:
        servers: dict[str, list] = {}
        for d in self.rec.dimensions():
            regs = d.regions()
            bounds, latest = None, None
            for r in regs:
                h = r.header()
                if h is None or not h.offsets.any():
                    continue
                idx = np.nonzero(h.offsets)[0]
                cx = r.rx * 32 + (idx & 31)
                cz = r.rz * 32 + (idx >> 5)
                b = [int(cx.min()) * 16, int(cz.min()) * 16, int(cx.max()) * 16 + 16, int(cz.max()) * 16 + 16]
                bounds = b if bounds is None else [min(bounds[0], b[0]), min(bounds[1], b[1]),
                                                   max(bounds[2], b[2]), max(bounds[3], b[3])]
                i = int(idx[np.argmax(h.timestamps[idx])])
                t = int(h.timestamps[i])
                if latest is None or t > latest[0]:
                    latest = (t, (r.rx * 32 + (i & 31)) * 16 + 8, (r.rz * 32 + (i >> 5)) * 16 + 8)
            if bounds is None:
                continue
            servers.setdefault(d.server, []).append({
                "key": d.key,
                "label": DIM_LABELS.get(d.key, d.key),
                "layers": self.layers_for(d.key),
                "regions": len(regs),
                "bounds": bounds,
                "latest": [latest[1], latest[2]],
            })
        order = {"overworld": 0, "the_nether": 1, "the_end": 2}
        out = []
        for s, dims in sorted(servers.items(), key=lambda kv: (kv[0] == LEGACY_SERVER, kv[0])):
            dims.sort(key=lambda d: order.get(d["key"], 9))
            out.append({"id": s, "label": world_label(s), "dims": dims})
        return out

    @staticmethod
    def layers_for(dim_key: str) -> list:
        if dim_key == "the_nether":
            return [{"id": "cave127", "label": "Unter der Decke"},
                    {"id": "surface320", "label": "Auf der Decke"}]
        return [{"id": "surface320", "label": "Oberfläche"}]

    def column(self, dim, x: int, z: int, layer: Layer) -> dict:
        ch = self.rec.chunk(dim, x >> 4, z >> 4)
        if ch is None:
            return {"x": x, "z": z, "known": False}
        _, h, has = render_chunk(ch, layer)
        lz, lx = z & 15, x & 15
        if not has[lz, lx]:
            return {"x": x, "z": z, "known": True, "block": None}
        y = int(h[lz, lx])
        bi = ch.biomes[int(np.clip((y - ch.min_y) >> 2, 0, ch.biomes.shape[0] - 1)), lz >> 2, lx >> 2]
        return {"x": x, "z": z, "y": y, "known": True, "block": ch.block(x, y, z),
                "biome": BIOMES.names[int(bi)]}

    def changed_regions(self, dim, since: float) -> list:
        out = []
        for r in dim.regions():
            try:
                if os.path.getmtime(r.path) > since:
                    out.append([r.rx, r.rz])
            except OSError:
                pass
        return out

    def changed_chunks(self, dim, regs: list, since: float) -> list:
        """Chunks of the changed regions whose timestamp (seconds) is newer than <since>."""
        out = []
        cut = int(since) - 1
        for rx, rz in regs:
            r = dim.region(rx, rz)
            h = r.header() if r else None
            if h is None:
                continue
            for i in np.nonzero(h.timestamps >= cut)[0]:
                out.append([rx * 32 + int(i & 31), rz * 32 + int(i >> 5)])
        return out[:4000]

    # -------------------------------------------------------------- Waypoints

    def _wp_path(self) -> str:
        return os.path.join(self.rec.root, "waypoints.json")

    def waypoints(self) -> list:
        try:
            with open(self._wp_path(), encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return []

    def save_waypoints(self, wps: list):
        clean = []
        for w in wps:
            try:
                clean.append({
                    "id": str(w.get("id") or time.time_ns()),
                    "name": str(w.get("name", ""))[:80],
                    "server": str(w["server"]), "dim": str(w["dim"]),
                    "x": int(w["x"]), "y": int(w.get("y", 64)), "z": int(w["z"]),
                    "color": str(w.get("color", "#e5484d"))[:16],
                })
            except (KeyError, TypeError, ValueError):
                continue
        with self.wp_lock:
            tmp = self._wp_path() + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(clean, f, ensure_ascii=False, indent=1)
            os.replace(tmp, self._wp_path())
        return clean


def world_label(server: str) -> str:
    """Name in the world picker: the server address, or the singleplayer save."""
    if server == LEGACY_SERVER:
        return "Alte Aufnahme (gemischt)"
    if server == "singleplayer":
        # before 0.6.1 every singleplayer world landed in this one folder
        return "Einzelspieler (alle alten Welten gemischt)"
    if server.startswith("singleplayer-"):
        return "Einzelspieler: " + server[len("singleplayer-"):].replace("_", " ")
    return server


def _local_host(value: str | None) -> bool:
    """Only "localhost" or a bare IP address may appear as Host or Origin.

    DNS rebinding points a foreign domain at 127.0.0.1; the browser then sends
    that domain as Host. An IP literal (LAN access with --host 0.0.0.0) is fine.
    """
    if not value:
        return False
    host = urlparse(value if "//" in value else "//" + value).hostname or ""
    if host == "localhost":
        return True
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def make_handler(app: App):
    empty_png = TileStore.empty_png()

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *a):
            pass

        def send(self, code: int, body: bytes, ctype: str = "application/json",
                 cache: str = "no-store", extra: dict | None = None):
            try:
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", cache)
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)
            except ConnectionError:   # also Windows "ConnectionAbortedError" when the browser cancels
                pass

        def json(self, obj, code: int = 200):
            self.send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                      "application/json; charset=utf-8")

        def fail(self, code: int, msg: str):
            self.json({"error": msg}, code)

        def body_json(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 4 * 1024 * 1024:
                raise ValueError("zu gross")
            return json.loads(self.rfile.read(n) or b"{}")

        def dim_from(self, q):
            srv = q.get("srv", [""])[0]
            key = q.get("dim", [""])[0]
            return app.rec.dimension(srv, key)

        # ---------------------------------------------------------- GET

        def do_GET(self):
            if not _local_host(self.headers.get("Host")):
                return self.fail(403, "fremder Host")
            u = urlparse(self.path)
            p, q = u.path, parse_qs(u.query)
            try:
                if p == "/" or p.startswith("/static/"):
                    return self.static("index.html" if p == "/" else p[len("/static/"):])
                if p == "/editor":
                    return self.static("editor.html")
                if p == "/api/version":
                    return self.json({"version": VERSION, "tabs": app.streams})
                if p == "/api/worlds":
                    return self.json({"worlds": app.worlds(), "now": time.time()})
                if p.startswith("/api/tile/"):
                    return self.tile(p, q)
                if p == "/api/changes":
                    dim = self.dim_from(q)
                    since = float(q.get("since", ["0"])[0])
                    now = time.time()
                    regs = app.changed_regions(dim, since) if dim else []
                    chunks = app.changed_chunks(dim, regs, since) if dim and regs else []
                    return self.json({"now": now, "regions": regs, "chunks": chunks})
                if p == "/api/column":
                    dim = self.dim_from(q)
                    if dim is None:
                        return self.fail(404, "Dimension unbekannt")
                    layer = Layer.parse(q.get("layer", ["surface320"])[0])
                    return self.json(app.column(dim, int(q["x"][0]), int(q["z"][0]), layer))
                if p == "/api/markers":
                    dim = self.dim_from(q)
                    if dim is None:
                        return self.fail(404, "Dimension unbekannt")
                    layer = Layer.parse(q.get("layer", ["surface320"])[0])
                    return self.json(app.tiles.markers(dim, layer))
                if p == "/api/waypoints":
                    return self.json(app.waypoints())
                if p == "/api/schem":
                    return self.schem(q)
                if p.startswith("/api/mesh/"):
                    return self.mesh(p, q)
                if p == "/api/blocks.png":
                    path = os.path.join(HERE, "assets", "blocks.png")
                    if not os.path.exists(path):
                        return self.fail(404, "blocks.png fehlt: python -m viewer.tools.build_assets --jar <client.jar>")
                    with open(path, "rb") as f:
                        return self.send(200, f.read(), "image/png", "no-cache", {"ETag": f'"{MODELS.version}"'})
                if p in ("/api/items.png", "/api/items.json"):
                    path = os.path.join(HERE, "assets", p.rsplit("/", 1)[1])
                    if not os.path.exists(path):
                        return self.fail(404, "Item-Bilder fehlen: python -m viewer.tools.build_items --jar <client.jar>")
                    with open(path, "rb") as f:
                        return self.send(200, f.read(), "image/png" if p.endswith(".png") else "application/json",
                                         "no-cache", {"ETag": f'"{int(os.path.getmtime(path))}"'})
                if p == "/api/xray/find":
                    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
                    if dim is None:
                        return self.fail(404, "keine Aufnahme")
                    try:
                        names = xray.parse_names(q.get("b", [""])[0])
                        r = max(0, min(24, int(q.get("r", ["12"])[0])))
                        cx, cz = int(float(q["x"][0])) >> 4, int(float(q["z"][0])) >> 4
                    except (KeyError, ValueError) as e:
                        return self.fail(400, str(e))
                    if not names:
                        return self.json({"names": [], "counts": [], "pts": [], "chunks": 0})
                    return self.json(app.xray_find(dim, cx, cz, r, names))
                if p == "/api/xray/names":
                    # Every block the assets know (for the search suggestions), otherwise what the recording has seen so far
                    names = MODELS.blockstates if MODELS.available else {base_name(n) for n in BLOCKS.names}
                    return self.json(sorted(n for n in names if n not in AIR_NAMES))
                if p == "/api/blocks-info":
                    return self.json({"available": MODELS.available, "version": MODELS.version,
                                      "width": getattr(MODELS, "width", 0), "height": getattr(MODELS, "height", 0)})
                if p == "/api/player/stream":
                    return self.player_stream()
                if p == "/api/player":
                    pl = app.player
                    active = pl is not None and time.monotonic() - app.player_ts < PLAYER_STALE
                    return self.json({**(pl or {}), "active": active, "skinVersion": app.skin_version,
                                      "hasSkin": app.skin_full is not None or app.skin_face is not None})
                if p in ("/api/skin/face.png", "/api/skin/full.png"):
                    data = app.skin_face if p.endswith("face.png") else app.skin_full
                    if not data and p.endswith("full.png"):
                        data = self.skin_by_name()
                    if not data:
                        return self.fail(404, "kein Skin")
                    return self.send(200, data, "image/png", "no-cache")
                if p.startswith("/api/lod/"):
                    parts = [unquote(x) for x in p.split("/")[3:]]
                    dim = app.rec.dimension(parts[0], parts[1])
                    if dim is None:
                        return self.fail(404, "Dimension unbekannt")
                    res = app.lod(dim, int(parts[2]), int(parts[3]), max(0, min(3, int(q.get("level", ["0"])[0]))))
                    if res is None:
                        return self.send(204, b"")
                    data, stamp = res
                    etag = f'"{stamp}"'
                    if self.headers.get("If-None-Match") == etag:
                        return self.send(304, b"", "application/octet-stream", "no-cache", {"ETag": etag})
                    return self.send(200, data, "application/octet-stream", "no-cache",
                                     {"Content-Encoding": "gzip", "ETag": etag})
                if p == "/api/trail":
                    key = f"{q.get('srv', [''])[0]}/{q.get('dim', [''])[0]}"
                    return self.json(app.trail.get(key, [])[-int(q.get("n", ["5000"])[0]):])
                if p == "/api/schematics":
                    return self.json(app.with_ingame(app.placements.list(q.get("srv", [None])[0], q.get("dim", [None])[0])))
                if p == "/api/litematica":
                    return self.json(app.placements.litematica(q.get("srv", [None])[0]))
                if p == "/api/containers":
                    return self.json(app.containers.list(q.get("srv", [None])[0]))
                if p.startswith("/api/editor/"):
                    try:
                        if p == "/api/editor/blocks":
                            return self.json(app.editor.blocks())
                        if p == "/api/editor/voxels":
                            return self.json(app.editor.voxels(q["file"][0]))
                        if p == "/api/editor/file":
                            path = app.placements.resolve(q["file"][0])
                            with open(path, "rb") as f:
                                data = f.read()
                            name = os.path.basename(path).replace('"', "")
                            return self.send(200, data, "application/octet-stream", "no-cache",
                                             {"Content-Disposition": f'attachment; filename="{name}"'})
                        if p == "/api/editor/info":
                            return self.json(app.editor.info(q["file"][0]))
                        if p == "/api/editor/mesh":
                            data, etag = app.editor.mesh(q["file"][0])
                            if self.headers.get("If-None-Match") == etag:
                                return self.send(304, b"", "application/octet-stream", "no-cache", {"ETag": etag})
                            return self.send(200, data, "application/octet-stream", "no-cache",
                                             {"Content-Encoding": "gzip", "ETag": etag})
                    except (LookupError, KeyError) as e:
                        return self.fail(404, f"unbekannt: {e}")
                    except ValueError as e:
                        return self.fail(400, str(e))
                if p == "/api/containers/lid":
                    return self.json(app.lids.list(q.get("srv", [None])[0], q.get("dim", [None])[0]))
                if p == "/api/containers/world":
                    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
                    if dim is None:
                        return self.json([])
                    r = max(1, min(32, int(q.get("r", ["10"])[0])))
                    cx, cz = int(float(q["x"][0])) >> 4, int(float(q["z"][0])) >> 4
                    key = (dim.id, cx >> 2, cz >> 2, r)
                    hit = app.world_chests.get(key)
                    if not hit or time.monotonic() - hit[0] > 30:
                        hit = (time.monotonic(), world_chests(app.rec, dim, cx, cz, r))
                        if len(app.world_chests) > 16:
                            app.world_chests.clear()
                        app.world_chests[key] = hit
                    return self.json(hit[1])
                if p == "/api/inventory":
                    return self.json(app.inventory)
                if p == "/api/commands":
                    return self.json(app.commands.pop(q["srv"][0]))
                if p == "/api/control":
                    # Only read here: the mod waits at most 2 s for this answer.
                    # Auto-build and layer automation run in automation_loop.
                    srv = q["srv"][0]
                    app.polling[srv] = time.monotonic()
                    return self.json({**app.control.for_mod(srv), "guard": app.auto.guard(srv)})
                if p == "/api/auto/settings":
                    return self.json(app.auto.get_settings())
                if p == "/api/auto":
                    return self.json(app.auto.view(q["srv"][0]))
                if p.startswith("/api/spawn-tile/"):
                    # 2D overlay: one PNG per region, cached until the region file changes
                    parts = [unquote(x) for x in p.split("/")[3:]]
                    dim = app.rec.dimension(parts[0], parts[1]) if len(parts) == 4 else None
                    if dim is None:
                        return self.fail(404, "keine Aufnahme")
                    rx, rz = int(parts[2]), int(parts[3][:-4] if parts[3].endswith(".png") else parts[3])
                    ymax = int(q["ymax"][0]) if "ymax" in q else None
                    reg = dim.region(rx, rz)
                    h = reg.header() if reg else None
                    if h is None:
                        return self.send(200, empty_png, "image/png", "no-cache")
                    etag = f'"spawn-{h.mtime_ns}-{ymax}"'
                    if self.headers.get("If-None-Match") == etag:
                        return self.send(304, b"", "image/png", "no-cache", {"ETag": etag})
                    key = ("spawn", dim.id, rx, rz, h.mtime_ns, ymax)
                    png = app.meshes.get(key)
                    if png is None:
                        png = (app.mesh_pool.submit(_spawn_job, dim.server, dim.key, rx, rz, ymax).result()
                               if app.mesh_pool is not None else spawn.region_png(app.rec, dim, rx, rz, ymax)) or empty_png
                        app.meshes.put(key, png)
                    return self.send(200, png, "image/png", "no-cache", {"ETag": etag})
                if p.startswith("/api/spawn/"):
                    parts = [unquote(x) for x in p.split("/")[3:]]
                    dim = app.rec.dimension(parts[0], parts[1]) if len(parts) == 4 else None
                    if dim is None:
                        return self.fail(404, "keine Aufnahme")
                    ch = app.rec.chunk(dim, int(parts[2]), int(parts[3]))
                    return self.json(spawn.points(ch, dim.key) if ch else {"light": False, "pts": []})
                if p == "/api/farm/flatscan":
                    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
                    if dim is None:
                        return self.fail(404, "keine Aufnahme")
                    try:
                        a = farm.norm_area({k: q[k][0] for k in ("x0", "z0", "x1", "z1", "y0", "y1")})
                        kind = q.get("kind", ["flat"])[0]
                        if kind == "light":
                            plan = spawn.torch_plan(app.rec, dim, a)
                            return self.json({"clear": 0, "fill": len(plan["torches"]), "spots": plan["spots"], "mined": [],
                                              "chunks": [[cx, cz, 0, n] for (cx, cz), n in sorted(plan["chunks"].items())]})
                        level = int(q["level"][0]) if kind == "flat" else 0
                        return self.json(farm.flat_preview(app.rec, dim, a, kind, level))
                    except (KeyError, ValueError) as e:
                        return self.fail(400, str(e))
                if p == "/api/farm/scan":
                    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
                    if dim is None:
                        return self.fail(404, "keine Aufnahme")
                    try:
                        return self.json(farm.scan(app.rec, dim, {k: q[k][0] for k in ("x0", "z0", "x1", "z1", "y0", "y1")}))
                    except (KeyError, ValueError) as e:
                        return self.fail(400, str(e))
                if p == "/api/control/state":
                    return self.json(app.control.view(q["srv"][0]))
                if p == "/api/radar":
                    return self.json(app.radar.list(q.get("srv", [None])[0]))
                if p == "/api/commands/last":
                    if q.get("id"):
                        return self.json(app.commands.results.get(q["id"][0]))
                    return self.json(app.commands.last)
                if p == "/api/history/dates":
                    return self.json(app.history.dates(q["srv"][0], q["dim"][0]))
                if p == "/api/history/diff":
                    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
                    if dim is None:
                        return self.fail(404, "keine Aufnahme fuer diese Dimension")
                    return self.json(app.history.diff(app.rec, dim, q["srv"][0], q["dim"][0], q["date"][0]))
                if p == "/api/schematics/library":
                    return self.json({"folders": list(app.placements.dirs), "files": app.placements.library()})
                if p.startswith("/api/schematics/"):
                    return self.schematic_get(p, q)
                return self.fail(404, "nicht gefunden")
            except (KeyError, ValueError) as e:
                return self.fail(400, f"Parameter fehlen oder sind ungueltig: {e}")
            except Exception as e:  # never drop the connection without a word
                import traceback
                traceback.print_exc()
                return self.fail(500, f"interner Fehler: {e}")

        def player_payload(self) -> bytes:
            pl = app.player
            active = pl is not None and time.monotonic() - app.player_ts < PLAYER_STALE
            return json.dumps({**(pl or {}), "active": active, "skinVersion": app.skin_version,
                               "hasSkin": app.skin_full is not None or app.skin_face is not None}).encode()

        def player_stream(self):
            """Server-sent events: every new position to the browser immediately (instead of polling)."""
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("X-Accel-Buffering", "no")
            self.send_header("Connection", "close")
            self.end_headers()
            self.close_connection = True
            seq = -1
            with app.player_cond:
                app.streams += 1
            try:
                while True:
                    with app.player_cond:
                        app.player_cond.wait_for(lambda: app.player_seq != seq, timeout=2.0)
                        seq = app.player_seq
                    self.wfile.write(b"data: " + self.player_payload() + b"\n\n")
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
                return
            finally:
                with app.player_cond:
                    app.streams -= 1

        def skin_by_name(self):
            """No skin from the mod: fetch once by player name (mc-heads.net)."""
            pl = app.player or {}
            name = re.sub(r"[^A-Za-z0-9_]", "", pl.get("name", ""))
            if not name or app.skin_fetch_name == name:
                return None
            app.skin_fetch_name = name
            try:
                import urllib.request
                req = urllib.request.Request(f"https://mc-heads.net/skin/{name}", headers={"User-Agent": "webmatica"})
                data = urllib.request.urlopen(req, timeout=8).read()
                if data[:8] == b"\x89PNG\r\n\x1a\n":
                    app.skin_full = data
                    app.skin_version += 1
                    return data
            except Exception as e:
                print(f"Skin fuer {name} nicht ladbar: {e}")
            return None

        def schematic_get(self, p, q):
            parts = p.split("/")
            if len(parts) != 5:
                return self.fail(404, "nicht gefunden")
            pid, what = unquote(parts[3]), parts[4]
            try:
                if what == "mesh":
                    data, etag = app.placements.mesh(app.placements.get(pid))
                    if self.headers.get("If-None-Match") == etag:
                        return self.send(304, b"", "application/octet-stream", "no-cache", {"ETag": etag})
                    return self.send(200, data, "application/octet-stream", "no-cache",
                                     {"Content-Encoding": "gzip", "ETag": etag})
                if what == "materials":
                    return self.json(app.placements.materials(pid))
                if what == "verify":
                    pl_ = app.placements.get(pid)
                    me = app.player
                    near = (me["x"], me["y"], me["z"]) if me and me.get("dim") == pl_.get("dim") else None
                    return self.json(app.placements.verify(pid, app.rec, near))
            except LookupError as e:
                return self.fail(404, str(e))
            except ValueError as e:
                return self.fail(400, str(e))
            return self.fail(404, "nicht gefunden")

        def schem(self, q):
            dim = self.dim_from(q)
            if dim is None:
                return self.fail(404, "Dimension unbekannt")
            c = [int(q[k][0]) for k in ("x0", "y0", "z0", "x1", "y1", "z1")]
            name = re.sub(r"[^A-Za-z0-9_. -]", "_", q.get("name", ["auswahl"])[0]).strip(" .")[:60] or "auswahl"
            try:
                data, info = schem_export(app.rec, dim, *c, name=name)
            except LookupError as e:
                return self.fail(404, str(e))
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Content-Disposition", f'attachment; filename="{name}.schem"')
            self.send_header("X-Schem-Info", json.dumps(info))
            self.end_headers()
            self.wfile.write(data)

        def mesh(self, p: str, q):
            parts = [unquote(x) for x in p.split("/")[3:]]
            if len(parts) != 4:
                return self.fail(400, "Pfad: /api/mesh/<srv>/<dim>/<cx>/<cz>")
            dim = app.rec.dimension(parts[0], parts[1])
            if dim is None:
                return self.fail(404, "Dimension unbekannt")
            if not MODELS.available:
                return self.fail(503, "Blockmodelle fehlen: python -m viewer.tools.build_assets --jar <client.jar>")
            cx, cz = int(parts[2]), int(parts[3])
            opt = lambda k: int(q[k][0]) if k in q else None
            try:
                names = xray.parse_names(q.get("xray", [""])[0])
            except ValueError as e:
                return self.fail(400, str(e))
            at = q.get("at", [""])[0] or None
            if at and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", at):
                return self.fail(400, "Datum: JJJJ-MM-TT")
            res = app.mesh(dim, cx, cz, q.get("full", ["0"])[0] == "1", opt("ylo"), opt("yhi"), names, at)
            if res is None:
                return self.send(204, b"", "application/octet-stream")
            data, stamp = res
            # Atlas version is part of it: new assets = new UVs, old meshes in the browser cache no longer fit
            etag = f'"{MODELS.version}-{MAGIC.decode()}-{stamp}"'
            if self.headers.get("If-None-Match") == etag:
                return self.send(304, b"", "application/octet-stream", "no-cache", {"ETag": etag})
            self.send(200, data, "application/octet-stream", "no-cache",
                      {"ETag": etag, "Content-Encoding": "gzip"})

        def do_HEAD(self):
            self.do_GET()

        def static(self, rel: str):
            path = os.path.normpath(os.path.join(STATIC, unquote(rel)))
            if not path.startswith(STATIC + os.sep) and path != os.path.join(STATIC, "index.html"):
                return self.fail(403, "verboten")
            if not os.path.isfile(path):
                return self.fail(404, "nicht gefunden")
            with open(path, "rb") as f:
                data = f.read()
            ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
            if ctype.startswith("text/") or ctype.endswith("javascript"):
                ctype += "; charset=utf-8"
            self.send(200, data, ctype, "no-cache")

        def tile(self, p: str, q=None):
            parts = [unquote(x) for x in p.split("/")[3:]]
            if len(parts) != 6 or not parts[5].endswith(".png"):
                return self.fail(400, "Kachelpfad: /api/tile/<srv>/<dim>/<layer>/<z>/<x>/<y>.png")
            srv, dim_key, layer_s, z, x, y = parts
            dim = app.rec.dimension(srv, dim_key)
            if dim is None:
                return self.fail(404, "Dimension unbekannt")
            zoom, tx, tz = int(z), int(x), int(y[:-4])
            if zoom < -8 or zoom > 0:
                return self.fail(400, "Zoom ausserhalb")
            at = (q or {}).get("at", [""])[0]
            if at and not re.fullmatch(r"\d{4}-\d{2}-\d{2}", at):
                return self.fail(400, "Datum: JJJJ-MM-TT")
            store = app.past(srv, dim_key, at) if at else app.tiles
            if (q or {}).get("raw", [""])[0] == "1" and not at:
                path = store.tile(dim, Layer.parse(layer_s), zoom, tx, tz, raw=True)
            else:
                path = store.tile(dim, Layer.parse(layer_s), zoom, tx, tz)
            if path is None:
                return self.send(200, empty_png, "image/png", "no-cache")
            etag = f'"{int(os.path.getmtime(path) * 1000)}"'
            if self.headers.get("If-None-Match") == etag:
                return self.send(304, b"", "image/png", "no-cache", {"ETag": etag})
            with open(path, "rb") as f:
                self.send(200, f.read(), "image/png", "no-cache", {"ETag": etag})

        # ---------------------------------------------------------- POST

        def do_POST(self):
            u = urlparse(self.path)
            # Only local pages may write (protection against foreign web pages in the browser).
            if not _local_host(self.headers.get("Host")):
                return self.fail(403, "fremder Host")
            origin = self.headers.get("Origin")
            if origin and urlparse(origin).hostname not in ("127.0.0.1", "localhost", self.server.server_address[0]):
                return self.fail(403, "fremde Herkunft")
            if u.path == "/api/schematics/upload":
                n = int(self.headers.get("Content-Length") or 0)
                if n <= 0 or n > 64 * 1024 * 1024:
                    return self.fail(400, "Datei fehlt oder ist groesser als 64 MB")
                data = self.rfile.read(n)
                try:
                    return self.json(app.placements.upload(parse_qs(u.query).get("name", ["upload.schem"])[0], data))
                except Exception as e:
                    return self.fail(400, f"Schematic nicht lesbar: {e}")
            try:
                req = self.body_json()
            except ValueError as e:
                return self.fail(400, f"kein gueltiges JSON: {e}")
            if u.path.startswith("/api/schematics/"):
                try:
                    if u.path == "/api/schematics/place":
                        return self.json(app.placements.place(req))
                    if u.path == "/api/schematics/update":
                        return self.json(app.placements.update(req))
                    if u.path == "/api/schematics/delete":
                        app.placements.delete(str(req.get("id")))
                        return self.json({"ok": True})
                except (LookupError, KeyError) as e:
                    return self.fail(404, f"unbekannt: {e}")
                except Exception as e:
                    return self.fail(400, str(e))
            if u.path.startswith("/api/editor/"):
                try:
                    fn = {"/api/editor/open": app.editor.open, "/api/editor/new": app.editor.new,
                          "/api/editor/cut": app.editor.cut, "/api/editor/rename": app.editor.rename, "/api/editor/edit": app.editor.edit,
                          "/api/editor/undo": app.editor.undo, "/api/editor/save": app.editor.save, "/api/editor/discard": app.editor.discard,
                          "/api/editor/redo": lambda r: app.editor.undo(r, redo=True)}.get(u.path)
                    if fn is None:
                        return self.fail(404, "nicht gefunden")
                    return self.json(fn(req))
                except (LookupError, KeyError) as e:
                    return self.fail(404, f"unbekannt: {e}")
                except ValueError as e:
                    return self.fail(400, str(e))
            if u.path == "/api/quit":
                # The mod asks an outdated viewer to make room for the new one.
                self.json({"ok": True})
                threading.Thread(target=self.server.shutdown, daemon=True).start()
                return
            if u.path == "/api/position":
                return self.position(req)
            if u.path == "/api/containers":
                try:
                    return self.json(app.containers.put(req))
                except (KeyError, TypeError, ValueError) as e:
                    return self.fail(400, f"Kiste unvollstaendig: {e}")
            if u.path == "/api/containers/lid":
                try:
                    return self.json(app.lids.put(req))
                except (KeyError, TypeError, ValueError) as e:
                    return self.fail(400, str(e))
            if u.path == "/api/containers/delete":
                app.containers.delete(str(req.get("key")))
                return self.json({"ok": True})
            if u.path == "/api/auto/settings":
                return self.json(app.auto.set_settings(req))
            if u.path == "/api/auto":
                try:
                    if req.get("store"):
                        return self.json(app.auto.start_store(str(req["srv"]), bool(req.get("hotbar"))))
                    if req.get("startQueue"):
                        app.auto._next_in_queue(str(req["srv"]))
                        return self.json(app.auto.view(str(req["srv"])))
                    if "queue" in req:
                        return self.json(app.auto.set_queue(str(req["srv"]), list(req["queue"] or [])))
                    if req.get("farm") and (req["farm"] or {}).get("flatten"):
                        return self.json(app.auto.start_flatten(str(req["srv"]), dict(req["farm"])))
                    if req.get("farm"):
                        return self.json(app.auto.start_farm(str(req["srv"]), dict(req["farm"])))
                    if req.get("pid"):
                        return self.json(app.auto.start(str(req["srv"]), str(req["pid"])))
                    return self.json(app.auto.stop(str(req["srv"])))
                except (LookupError, KeyError) as e:
                    return self.fail(404, f"unbekannt: {e}")
                except ValueError as e:
                    return self.fail(400, str(e))
            if u.path == "/api/fix":
                try:
                    res = app.placements.verify(str(req["pid"]), app.rec)
                    pts = AutoBuild.fix_points(res, bool(req.get("extra")))
                    if not pts:
                        return self.json({"n": 0})
                    cmd = app.commands.push({"srv": str(req["srv"]), "kind": "breakboxes", "boxes": farm.boxes(pts),
                                             "label": "Falsche Blöcke", "pickup": True})
                    return self.json({"n": len(pts), "id": cmd["id"]})
                except LookupError as e:
                    return self.fail(404, str(e))
                except (KeyError, ValueError) as e:
                    return self.fail(400, str(e))
            if u.path == "/api/commands":
                try:
                    if req.get("kind") == "stop":
                        app.auto.stop(str(req.get("srv")), "gestoppt (Stopp-Knopf)")
                    return self.json(app.commands.push(req))
                except (KeyError, ValueError) as e:
                    return self.fail(400, str(e))
            if u.path == "/api/control":
                try:
                    return self.json(app.control.push(req))
                except (KeyError, TypeError, ValueError) as e:
                    return self.fail(400, str(e))
            if u.path == "/api/control/status":
                app.control.set_status(req if isinstance(req, dict) else {})
                return self.json({"ok": True})
            if u.path == "/api/commands/result":
                app.commands.result(req)
                return self.json({"ok": True})
            if u.path == "/api/litematica/status":
                app.lm_status = req if isinstance(req, dict) else {}
                app.lm_status_ts = time.monotonic()
                return self.json({"ok": True})
            if u.path == "/api/waypoints":
                if not isinstance(req, list):
                    return self.fail(400, "Liste erwartet")
                return self.json(app.save_waypoints(req))
            return self.fail(404, "nicht gefunden")

        def position(self, req: dict):
            try:
                pl = {
                    "x": float(req["x"]), "y": float(req["y"]), "z": float(req["z"]),
                    "yaw": float(req.get("yaw", 0)), "pitch": float(req.get("pitch", 0)),
                    "dim": str(req.get("dim", "")).split(":", 1)[-1],
                    "server": str(req.get("server", "")),
                    "name": str(req.get("name", "")),
                    "skinModel": str(req.get("skinModel", "")),
                    "recording": bool(req.get("recording", True)),
                    "written": int(req.get("written", 0) or 0),
                    "t": float(req.get("t", 0) or 0),
                }
            except (KeyError, TypeError, ValueError):
                return self.fail(400, "x/y/z fehlen")
            for field, attr in (("skinFace", "skin_face"), ("skinFull", "skin_full")):
                if req.get(field):
                    try:
                        data = base64.b64decode(req[field])
                        if data[:8] == b"\x89PNG\r\n\x1a\n" and getattr(app, attr) != data:
                            setattr(app, attr, data)
                            app.skin_version += 1
                    except ValueError:
                        pass
            if isinstance(req.get("others"), list):
                pl["others"] = app.radar.update(pl, req["others"])
            if isinstance(req.get("inv"), list):
                app.inventory = {"items": Containers._clean_items(req["inv"]), "t": time.time() * 1000,
                                 "server": pl["server"], "name": pl["name"]}
            with app.player_cond:
                app.player, app.player_ts = pl, time.monotonic()
                app.player_seq += 1
                app.player_cond.notify_all()
            if pl["server"]:
                app.add_trail(pl)
            self.json({"ok": True})

    return Handler


# ------------------------------------------------------------------ 3D meshes in worker processes

_LOOKUPS: dict = {}


def _mesh_compute(rec, dim, cx, cz, full, ylo=None, yhi=None, names=(), at=None, hist=None):
    # at: state at the start of that day (timelapse), from the mod's daily backups
    get = (lambda x, z: hist.chunk_at(rec, dim, at, x, z)) if at else (lambda x, z: rec.chunk(dim, x, z))
    ch = get(cx, cz)
    if ch is None:
        return None
    nb = {(dx, dz): get(cx + dx, cz + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))}
    if names:
        # X-ray: only the searched blocks, the whole height (or the layer cut), with their positions behind it
        look = _LOOKUPS.get(names)
        if look is None:
            look = _LOOKUPS[names] = xray.Lookup(names)
        nb = {k: xray.only(c, look) for k, c in nb.items()}
        data = mesh_chunk(xray.only(ch, look), nb, ylo, yhi, open_bottom=ylo is not None)
        return gzip.compress(data + xray.trailer(ch, look, len(names)), 3)
    if yhi is not None:
        return gzip.compress(mesh_chunk(ch, nb, ylo, yhi, open_bottom=ylo is not None), 3)
    y_min = None
    if not full and dim.key != "the_nether":
        _, h, has = render_chunk(ch, Layer())
        if has.any():
            y_min = int(h[has].min()) - 12
    return gzip.compress(mesh_chunk(ch, nb, y_min, caves=full and dim.key == "overworld"), 3)


_WORKER: dict = {}


def _mesh_init(recording):
    _WORKER["rec"] = Recording(recording)
    _WORKER["hist"] = History(recording)


def _spawn_job(server, dim_key, rx, rz, ymax):
    rec = _WORKER["rec"]
    dim = rec.dimension(server, dim_key)
    return None if dim is None else spawn.region_png(rec, dim, rx, rz, ymax)


def _xray_compute(rec, dim, cx, cz, names):
    look = _LOOKUPS.get(names)
    if look is None:
        look = _LOOKUPS[names] = xray.Lookup(names)
    return xray.finds(rec.chunk(dim, cx, cz), look, len(names))


def _xray_job(server, dim_key, chunks, names):
    rec = _WORKER["rec"]
    dim = rec.dimension(server, dim_key)
    return [None if dim is None else _xray_compute(rec, dim, cx, cz, names) for cx, cz in chunks]


def _mesh_job(server, dim_key, cx, cz, full, ylo=None, yhi=None, names=(), at=None):
    rec = _WORKER["rec"]
    dim = rec.dimension(server, dim_key)
    return None if dim is None else _mesh_compute(rec, dim, cx, cz, full, ylo, yhi, names, at, _WORKER["hist"])


# ------------------------------------------------------------------ Pre-rendering

def _prerender_one(args):
    recording, cache_dir, server, dim_key, layer_key, rx, rz = args
    rec = Recording(recording)
    dim = rec.dimension(server, dim_key)
    if dim is not None:
        TileStore(rec, cache_dir).region_tile(dim, Layer.parse(layer_key), rx, rz)


def prerender_loop(app: App, recording: str, workers: int, interval: float = 10.0):
    """Keeps all region tiles up to date in the background (newest regions first)."""
    done: dict = {}
    with ProcessPoolExecutor(max_workers=workers) as pool:
        while True:
            jobs = []
            for dim in app.rec.dimensions():
                for layer in App.layers_for(dim.key):
                    for r in dim.regions():
                        try:
                            m = os.path.getmtime(r.path)
                        except OSError:
                            continue
                        k = (dim.id, layer["id"], r.rx, r.rz)
                        if done.get(k) != m:
                            jobs.append((m, k, (recording, app.cache_dir, dim.server, dim.key,
                                                layer["id"], r.rx, r.rz)))
            jobs.sort(key=lambda j: -j[0])
            futures = [(m, k, pool.submit(_prerender_one, a)) for m, k, a in jobs]
            for m, k, fut in futures:
                try:
                    fut.result()
                    done[k] = m
                except Exception as e:
                    print(f"Vorrendern fehlgeschlagen {k}: {e}")
                    done[k] = m
            time.sleep(interval)


def _bundle_version() -> str:
    """Stamp the mod wrote when it unpacked this copy; empty when started by hand."""
    try:
        with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".webmatica-version"),
                  encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


VERSION = _bundle_version()


def automation_loop(app: App, every: float = 0.5) -> None:
    """Auto-build and layer automation for every server whose mod polls right now.

    Verifying a big schematic can take seconds; doing it in the mod's poll made
    that poll time out (and the guard switches flicker).
    """
    while True:
        now = time.monotonic()
        for srv, seen in list(app.polling.items()):
            if now - seen > 10.0:
                continue
            try:
                app.control.auto_step(srv, lambda pid: app.placements.verify(pid, app.rec))
                app.auto.step(srv)
            except Exception as e:  # keep the loop alive
                print(f"automation {srv}: {e}")
        time.sleep(every)


def _render_version() -> str:
    """Fingerprint of what the cached tiles depend on: the render code and the block colours.

    A new mod build that only changes the web page keeps the cache.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    h = hashlib.sha1()
    core = os.path.join(here, "core")
    for name in sorted(os.listdir(core)):
        if name.endswith(".py"):
            with open(os.path.join(core, name), "rb") as f:
                h.update(name.encode() + f.read())
    for name in ("atlas.json", "atlas.png", "blocks.json", "blocks.png"):
        try:
            st = os.stat(os.path.join(here, "assets", name))
            h.update(f"{name}:{st.st_size}".encode())
        except OSError:
            pass
    return h.hexdigest()[:16]


def _reset_cache_on_update(cache: str) -> None:
    """Tiles rendered by other render code may be wrong (e.g. chunks it could not read): start over."""
    os.makedirs(cache, exist_ok=True)
    stamp = os.path.join(cache, ".viewer-version")
    version = _render_version()
    try:
        with open(stamp, encoding="utf-8") as f:
            if f.read().strip() == version:
                return
    except OSError:
        pass
    # Only the default cache folder or one we stamped earlier is ours to empty.
    if os.path.basename(os.path.normpath(cache)) == ".viewer-cache" or os.path.isfile(stamp):
        shutil.rmtree(cache, ignore_errors=True)
    os.makedirs(cache, exist_ok=True)
    with open(stamp, "w", encoding="utf-8") as f:
        f.write(version)


def _exit_with_parent(srv, pid: int) -> None:
    """Quit once the game process is gone, even after a crash.

    Watches the process itself instead of a pipe: on Windows a thread blocked reading
    stdin stalls the start of the worker processes, and with them every 3D request.
    """
    if os.name == "nt":
        import ctypes
        k32 = ctypes.windll.kernel32
        h = k32.OpenProcess(0x00100000, False, pid)   # SYNCHRONIZE
        if h:
            k32.WaitForSingleObject(h, 0xFFFFFFFF)
            k32.CloseHandle(h)
    else:
        while True:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            except PermissionError:
                pass
            time.sleep(2)
    srv.shutdown()


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m viewer", description="Webmatica Viewer")
    ap.add_argument("--recording", "-r", default=os.environ.get("MAPPER_RECORDING", ""),
                    help="path to .minecraft/webmatica_recording (default: what the mod writes to ~/.webmatica/client.json)")
    ap.add_argument("--cache", default="", help="tile cache (default: <recording>/.viewer-cache)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8770)
    ap.add_argument("--parent-pid", type=int, default=0,
                    help="quit when this process ends (the mod passes the game's)")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1),
                    help="processes for pre-rendering the map (0 = off)")
    args = ap.parse_args(argv)
    if not args.recording or not os.path.isdir(args.recording):
        ap.error("Recording not found. Start Minecraft with the mod once (then the viewer finds it by itself) "
                 "or pass --recording <.minecraft/webmatica_recording>")
    cache = args.cache or os.path.join(args.recording, ".viewer-cache")
    _reset_cache_on_update(cache)

    app = App(args.recording, cache)
    if args.workers > 0:
        app.mesh_pool = ProcessPoolExecutor(max_workers=max(2, args.workers), initializer=_mesh_init,
                                            initargs=(args.recording,))
    if args.workers > 0:
        threading.Thread(target=prerender_loop, args=(app, args.recording, args.workers),
                         daemon=True, name="prerender").start()
    threading.Thread(target=automation_loop, args=(app,), daemon=True, name="automation").start()
    srv = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    srv.daemon_threads = True
    if args.parent_pid > 0:
        threading.Thread(target=_exit_with_parent, args=(srv, args.parent_pid), daemon=True, name="parent-watch").start()
    dims = app.rec.dimensions()
    print(f"Webmatica: http://{args.host}:{args.port}")
    print(f"Recording: {app.rec.root}  ({len(dims)} dimension(s))")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        print("\nstopped")
        return
    # Ended through /api/quit or with the game: stop the worker processes and leave right away.
    print("stopped for an update")
    if app.mesh_pool is not None:
        app.mesh_pool.shutdown(wait=False, cancel_futures=True)
    for child in multiprocessing.active_children():
        child.terminate()
    os._exit(0)


if __name__ == "__main__":
    main()
