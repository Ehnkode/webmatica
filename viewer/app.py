"""State of a running viewer: the recording, caches, the player and every feature's store."""

from __future__ import annotations

import gzip
import hashlib
import json
import os
import re
import threading
import time

import numpy as np

from .core.chunk import BIOMES
from .core.tiles import Layer, PastTiles, TileStore, render_chunk
from .core.world import DIM_LABELS, LEGACY_SERVER, ChunkCache, Recording
from .placements import Placements
from .editor import Editor
from .autobuild import AutoBuild
from .extras import Commands, Containers, Control, History, LidLog, Radar
from .workers import _mesh_compute, _mesh_job, _xray_compute, _xray_job

PLAYER_STALE = 5.0   # seconds without a position from the mod: player shown as offline


def _bundle_version() -> str:
    """Stamp the mod wrote when it unpacked this copy; empty when started by hand."""
    try:
        with open(os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".webmatica-version"),
                  encoding="utf-8") as f:
            return f.read().strip()
    except OSError:
        return ""


VERSION = _bundle_version()


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
        # before 0.7.0 every singleplayer world landed in this one folder
        return "Einzelspieler (alle alten Welten gemischt)"
    if server.startswith("singleplayer-"):
        return "Einzelspieler: " + server[len("singleplayer-"):].replace("_", " ")
    m = re.fullmatch(r"(.+)_(\d{1,5})", server)
    if m:
        return f"{m.group(1)}:{m.group(2)}"     # server on another port than 25565
    return server
