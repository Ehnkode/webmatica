"""Chest inventory, Baritone commands and timeline (for server.py).

- Chests: the mod reports the contents of every opened chest, stored in
    ``<recording>/.viewer/containers.json``.
- Commands: the browser drops Baritone goals, the mod picks them up.
- Timeline: before overwriting, the mod backs up a chunk's old state
    once per day to ``<recording>/<server>/.history/<date>/...``. Here
    "state on day X" is compared against the current state.
"""

from __future__ import annotations

import json
import os
import re
import threading
import time

import numpy as np

from .core.chunk import BLOCKS, base_name, from_nbt
from .core.world import DIMENSIONS, Region, _REGION_RE

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


# ------------------------------------------------------------------ Chests

class Containers:
    def __init__(self, rec_root: str):
        self.path = os.path.join(rec_root, ".viewer", "containers.json")
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        self.lock = threading.Lock()
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data: dict = json.load(f)
        except (OSError, ValueError):
            self.data = {}

    def _save(self):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(self.data, f, ensure_ascii=False)
        os.replace(tmp, self.path)

    @staticmethod
    def _clean_items(items) -> list:
        out = []
        for it in items or []:
            if not isinstance(it, dict) or "id" not in it:
                continue
            e = {"id": str(it["id"]), "count": int(it.get("count", 1))}
            if it.get("name"):
                e["name"] = str(it["name"])[:60]
            if isinstance(it.get("slot"), int):
                e["slot"] = it["slot"]
            if isinstance(it.get("contents"), list):
                e["contents"] = Containers._clean_items(it["contents"])
            out.append(e)
        return out[:64]

    def put(self, req: dict) -> dict:
        srv, dim = str(req["server"]), str(req.get("dim", "overworld"))
        x, y, z = int(req["x"]), int(req["y"]), int(req["z"])
        block = str(req.get("block", ""))
        ender = block.endswith("ender_chest")
        other = req.get("other")
        old_keys = []
        if not ender and isinstance(other, list) and len(other) == 3:
            # Double chest: always store under the smaller half, regardless of which was clicked
            ox, oy, oz = (int(v) for v in other)
            old_keys = [f"{srv}|{dim}|{x},{y},{z}", f"{srv}|{dim}|{ox},{oy},{oz}"]
            x, y, z = min((x, y, z), (ox, oy, oz))
        key = f"{srv}|ender" if ender else f"{srv}|{dim}|{x},{y},{z}"
        entry = {"key": key, "server": srv, "dim": "ender" if ender else dim, "x": x, "y": y, "z": z,
                 "block": block, "title": str(req.get("title", ""))[:60], "slots": int(req.get("slots", 0)),
                 "items": self._clean_items(req.get("items")), "t": int(req.get("t") or time.time() * 1000)}
        with self.lock:
            for k in old_keys:
                self.data.pop(k, None)
            self.data[key] = entry
            self._save()
        return entry

    def delete(self, key: str):
        with self.lock:
            if self.data.pop(key, None) is not None:
                self._save()

    def list(self, server: str | None) -> list:
        with self.lock:
            return [e for e in self.data.values() if not server or e["server"] == server]


# ------------------------------------------------------------------ Chest lids (other players)

class LidLog:
    """Chests that someone else opened within view distance (block event from the mod)."""

    def __init__(self, rec_root: str):
        self.path = os.path.join(rec_root, ".viewer", "chest_lids.json")
        os.makedirs(os.path.dirname(self.path), exist_ok=True)
        self.lock = threading.Lock()
        try:
            with open(self.path, encoding="utf-8") as f:
                self.data: dict = json.load(f)
        except (OSError, ValueError):
            self.data = {}

    def put(self, req: dict) -> dict:
        srv, dim = str(req["server"]), str(req.get("dim", "overworld"))
        x, y, z = int(req["x"]), int(req["y"]), int(req["z"])
        key = f"{srv}|{dim}|{x},{y},{z}"
        t = int(req.get("t") or time.time() * 1000)
        opened = bool(req.get("open"))
        with self.lock:
            e = self.data.get(key) or {"key": key, "server": srv, "dim": dim, "x": x, "y": y, "z": z,
                                       "block": str(req.get("block", "")), "opens": 0, "first": t}
            if opened:
                e["opens"] += 1
                e["last"] = t
            e["open"] = opened
            e["seen"] = t
            self.data[key] = e
            if len(self.data) > 20000:
                for k in sorted(self.data, key=lambda k: self.data[k].get("seen", 0))[:2000]:
                    self.data.pop(k)
            tmp = self.path + ".tmp"
            with open(tmp, "w", encoding="utf-8") as f:
                json.dump(self.data, f, ensure_ascii=False)
            os.replace(tmp, self.path)
        return e

    def list(self, server: str | None, dim: str | None) -> list:
        with self.lock:
            return [e for e in self.data.values()
                    if (not server or e["server"] == server) and (not dim or e["dim"] == dim)]


# ------------------------------------------------------------------ Chests from the recording

STORAGE = ("chest", "trapped_chest", "barrel", "shulker_box")   # hoppers/droppers are mostly farms
_CW = {"north": "east", "east": "south", "south": "west", "west": "north"}
_CCW = {v: k for k, v in _CW.items()}
_STEP = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


def world_chests(rec, dim, cx0: int, cz0: int, r: int) -> list:
    """All storage blocks (chest, barrel, shulker ...) in the recorded chunks around (cx0, cz0).

    The recording knows the position even without opening (block entities come with the chunk),
    but not the contents. Double chests are merged under the smaller half, as in
    ``Containers``.
    """
    from .core.chunk import state_props
    out = {}
    for cz in range(cz0 - r, cz0 + r + 1):
        for cx in range(cx0 - r, cx0 + r + 1):
            try:
                ch = rec.chunk(dim, cx, cz)
            except Exception:
                continue
            if ch is None:
                continue
            for be in ch.block_entities:
                if not isinstance(be, dict):
                    continue
                kind = str(be.get("id", "")).split(":", 1)[-1]
                if kind not in STORAGE and not kind.endswith("shulker_box"):
                    continue
                try:
                    x, y, z = int(be["x"]), int(be["y"]), int(be["z"])
                except (KeyError, TypeError, ValueError):
                    continue
                state = ch.block(x, y, z)
                block = base_name(state)
                if block in AIR:
                    continue
                pos = other = (x, y, z)
                if kind in ("chest", "trapped_chest"):
                    pr = state_props(state)
                    t, f = pr.get("type"), pr.get("facing")
                    if t in ("left", "right") and f in _CW:
                        d = _CW[f] if t == "left" else _CCW[f]
                        other = (x + _STEP[d][0], y, z + _STEP[d][1])
                        pos = min(pos, other)
                if pos not in out:
                    out[pos] = {"x": pos[0], "y": pos[1], "z": pos[2], "block": block}
                if other != pos or (x, y, z) != pos:
                    out[pos]["other"] = list(max((x, y, z), other))
    return list(out.values())


# ------------------------------------------------------------------ Baritone commands

class Commands:
    TTL = 15.0

    def __init__(self):
        self.lock = threading.Lock()
        self.queue: dict[str, list] = {}
        self.last: dict | None = None
        self.results: dict[str, dict] = {}   # id -> result, for auto-build

    def push(self, req: dict) -> dict:
        kind = str(req.get("kind"))
        if kind not in ("goto", "stop", "pause", "resume", "clear", "litematica", "highlight", "fetch", "unpack", "store",
                        "breakboxes", "chestdump"):
            raise ValueError("unbekannter Befehl")
        cmd = {"id": str(time.time_ns()), "kind": kind, "ts": time.monotonic()}
        if kind == "goto":
            cmd["x"], cmd["z"] = int(req["x"]), int(req["z"])
            if req.get("y") is not None:
                cmd["y"] = int(req["y"])
        elif kind == "clear":
            for k in ("x0", "y0", "z0", "x1", "y1", "z1"):
                cmd[k] = int(req[k])
        elif kind == "litematica":
            cmd["pid"] = str(req["pid"])
        elif kind == "fetch":
            cmd["x"], cmd["y"], cmd["z"] = int(req["x"]), int(req["y"]), int(req["z"])
            cmd["slots"] = [int(v) for v in (req.get("slots") or [])][:64]
            cmd["items"] = [{"id": str(i["id"]), "count": int(i.get("count", 64))} for i in (req.get("items") or [])][:64]
            if req.get("noShulker"):
                cmd["noShulker"] = True
        elif kind == "unpack":
            cmd["items"] = [{"id": str(i["id"]), "count": int(i.get("count", 64))} for i in req["items"]][:64]
            if req.get("box"):
                cmd["box"] = [int(v) for v in req["box"][:6]]
        elif kind == "store":
            cmd["x"], cmd["y"], cmd["z"] = int(req["x"]), int(req["y"]), int(req["z"])
            cmd["put"] = [{"slot": int(i["slot"]), "id": str(i["id"])} for i in req["put"]][:41]
        elif kind == "highlight":
            cmd["pts"] = [[int(v) for v in p[:3]] for p in req["pts"][:64]]
        elif kind == "breakboxes":
            cmd["boxes"] = [[int(v) for v in b[:6]] for b in req["boxes"][:1500]]
            cmd["label"] = str(req.get("label") or "Abbauen")[:40]
            cmd["pickup"] = bool(req.get("pickup", True))
            if req.get("timeout"):
                cmd["timeout"] = max(30, min(1800, int(req["timeout"])))
            cmd["ids"] = [str(i) for i in (req.get("ids") or [])][:64]
            if req.get("fill"):
                fill = str(req["fill"])
                if not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", fill):
                    raise ValueError("ungültiger Block")
                cmd["fill"] = fill
        elif kind == "chestdump":
            cmd["slots"] = [int(v) for v in (req.get("slots") or [])][:36]
            cmd["ids"] = [str(i) for i in (req.get("ids") or [])][:64]
        with self.lock:
            q = self.queue.setdefault(str(req["srv"]), [])
            if kind == "stop":
                q.clear()
            q.append(cmd)
            del q[:-5]
        return {k: v for k, v in cmd.items() if k != "ts"}

    def pop(self, srv: str) -> list:
        now = time.monotonic()
        with self.lock:
            q = self.queue.pop(srv, [])
        return [{k: v for k, v in c.items() if k != "ts"} for c in q if now - c["ts"] < self.TTL]

    def result(self, req: dict):
        self.last = {"id": str(req.get("id", "")), "ok": bool(req.get("ok")), "msg": str(req.get("msg", ""))[:200],
                     "t": time.time()}
        with self.lock:
            self.results[self.last["id"]] = self.last
            if len(self.results) > 64:
                for k in list(self.results)[:16]:
                    self.results.pop(k)


# ------------------------------------------------------------------ Printer + Litematica layers

class Control:
    """Desired state for Printer and Litematica layers (per server).

    The browser changes parts of it, each change increments ``seq``; the mod
    applies the state as soon as it sees a new number, and reports the real
    in-game state back. "Layer by layer" is computed by the server itself:
    the lowest layer of the chosen schematic in which something is still missing.
    """

    PRINTER_KEYS = {"on": bool, "range": float, "interval": int, "air": bool, "interact": bool,
                    "strip": bool, "fluids": bool}
    AUTO_EVERY = 3.0

    def __init__(self):
        self.lock = threading.Lock()
        self.state: dict[str, dict] = {}
        self.status: dict[str, dict] = {}
        self.status_ts: dict[str, float] = {}
        self._auto_ts: dict[str, float] = {}

    def _st(self, srv: str) -> dict:
        return self.state.setdefault(srv, {"seq": int(time.time()), "printer": {}, "layer": None, "auto": None})

    def push(self, req: dict) -> dict:
        srv = str(req["srv"])
        with self.lock:
            st = self._st(srv)
            changed = False
            if isinstance(req.get("printer"), dict):
                pr = {}
                for k, conv in self.PRINTER_KEYS.items():
                    if k in req["printer"]:
                        pr[k] = conv(req["printer"][k])
                st["printer"] = pr          # only what changed, otherwise hotkey changes get overwritten
                changed = True
                st["printer_seq"] = st["seq"] + 1
            if "layer" in req:
                st["layer"] = self._layer(req["layer"])
                changed = True
            if "auto" in req:
                st["auto"] = str(req["auto"]) if req["auto"] else None
                self._auto_ts.pop(srv, None)
                if not st["auto"] and "layer" not in req:
                    st["layer"] = {"mode": "all"}
                    changed = True
            if changed:
                st["seq"] += 1
            return self.view(srv)

    @staticmethod
    def _layer(lay) -> dict | None:
        if not isinstance(lay, dict):
            return None
        if lay.get("mode") == "all":
            return {"mode": "all"}
        lo = int(lay["min"])
        hi = int(lay.get("max", lo))
        return {"mode": "range", "min": min(lo, hi), "max": max(lo, hi)}

    def for_mod(self, srv: str) -> dict:
        with self.lock:
            st = self._st(srv)
            # Keep sending the Printer change only until the mod has confirmed it
            done = int((self.status.get(srv) or {}).get("seq", -1)) >= st.get("printer_seq", 0)
            return {"seq": st["seq"], "printer": None if done else (st["printer"] or None), "layer": st["layer"]}

    def set_status(self, req: dict):
        srv = str(req.get("server") or "")
        with self.lock:
            self.status[srv] = req
            self.status_ts[srv] = time.monotonic()

    def view(self, srv: str) -> dict:
        fresh = time.monotonic() - self.status_ts.get(srv, -1e9) < 6.0
        st = self._st(srv)
        return {"seq": st["seq"], "auto": st["auto"], "layer": st["layer"], "autoInfo": st.get("autoInfo"),
                "online": fresh, "game": self.status.get(srv) if fresh else None}

    def auto_step(self, srv: str, verify) -> None:
        """Layer by layer: set the lowest open layer every few seconds."""
        with self.lock:
            st = self._st(srv)
            pid = st["auto"]
            if not pid or time.monotonic() - self._auto_ts.get(srv, 0) < self.AUTO_EVERY:
                return
            self._auto_ts[srv] = time.monotonic()
        try:
            res = verify(pid)
        except Exception as e:  # placement gone or similar: automation off
            with self.lock:
                st["auto"], st["autoInfo"] = None, f"aus: {e}"
            return
        lowest = res.get("lowestMissing")
        with self.lock:
            if not lowest:
                want = {"mode": "all"}
                st["autoInfo"] = "fertig" if not res.get("todoTotal") else "nur noch falsche Bloecke"
            else:
                y = lowest["y"]
                want = {"mode": "range", "min": y, "max": y}
                st["autoInfo"] = f"Y {y}: noch {lowest['count']} Bloecke"
            if want != st["layer"]:
                st["layer"] = want
                st["seq"] += 1


# ------------------------------------------------------------------ Radar

class Radar:
    """Other players within view distance: current state plus log (radar.json)."""

    def __init__(self, rec_root: str):
        self.path = os.path.join(rec_root, ".viewer", "radar.json")
        self.lock = threading.Lock()
        self.saved = time.monotonic()
        try:
            with open(self.path, encoding="utf-8") as f:
                self.log: list = json.load(f)
        except (OSError, ValueError):
            self.log = []
        # (server, name) -> the log entry still being extended (seen within the last 120 s)
        self._open = {(s.get("server"), s.get("name")): s for s in self.log if isinstance(s, dict)}

    def update(self, me: dict, others: list) -> list:
        now = time.time()
        clean = []
        for o in others[:64]:
            try:
                e = {"name": str(o["name"])[:32], "uuid": str(o.get("uuid", ""))[:40], "x": float(o["x"]),
                     "y": float(o["y"]), "z": float(o["z"]), "yaw": float(o.get("yaw", 0))}
            except (KeyError, TypeError, ValueError):
                continue
            e["dist"] = round(((e["x"] - me["x"]) ** 2 + (e["z"] - me["z"]) ** 2) ** 0.5, 1)
            clean.append(e)
        with self.lock:
            for e in clean:
                key = (me["server"], e["name"])
                last = self._open.get(key)
                if last is None or now - last.get("last", 0) >= 120:
                    last = {"name": e["name"], "server": me["server"], "dim": me["dim"], "first": now,
                            "x": round(e["x"]), "z": round(e["z"]), "closest": e["dist"]}
                    self.log.append(last)
                    self._open[key] = last
                last["last"] = now
                if e["dist"] <= last["closest"]:
                    last.update(closest=e["dist"], x=round(e["x"]), z=round(e["z"]))
            if len(self.log) > 500:
                del self.log[:-500]
                kept = {id(x) for x in self.log}
                self._open = {k: v for k, v in self._open.items() if id(v) in kept}
            if clean and time.monotonic() - self.saved > 10:
                self.saved = time.monotonic()
                tmp = self.path + ".tmp"
                try:
                    os.makedirs(os.path.dirname(self.path), exist_ok=True)
                    with open(tmp, "w", encoding="utf-8") as f:
                        json.dump(self.log, f)
                    os.replace(tmp, self.path)
                except OSError:
                    pass
        return clean

    def list(self, server: str | None) -> list:
        with self.lock:
            return [s for s in self.log if not server or s["server"] == server][-200:][::-1]


# ------------------------------------------------------------------ Timeline

class History:
    def __init__(self, rec_root: str):
        self.root = rec_root
        self._cache: dict = {}
        self._chunks: dict = {}
        self._names_len = 0
        self._base = np.zeros(0, np.int32)
        self._air = np.zeros(0, bool)

    def _dir(self, server: str, date: str, dim: str) -> str:
        sub = DIMENSIONS.get(dim, "")
        base = os.path.join(self.root, server, ".history", date)
        return os.path.join(base, sub, "region") if sub else os.path.join(base, "region")

    def dates(self, server: str, dim: str) -> list:
        hroot = os.path.join(self.root, server, ".history")
        try:
            names = sorted(os.listdir(hroot))
        except OSError:
            return []
        return [d for d in names if os.path.isdir(self._dir(server, d, dim))]

    def index(self, server: str, dim: str, date: str) -> dict:
        """{(cx, cz): (region file, index)} of the earliest backed-up state from ``date`` on.

        That is the chunk's state at the start of that day (for the timelapse).
        Briefly cached; the mod only ever adds new backups.
        """
        key = ("idx", server, dim, date)
        hit = self._cache.get(key)
        if hit and time.monotonic() - hit[0] < 10.0:
            return hit[1]
        first: dict = {}
        for d in self.dates(server, dim):
            if d < date:
                continue
            rdir = self._dir(server, d, dim)
            for n in sorted(os.listdir(rdir)):
                m = _REGION_RE.match(n)
                if not m:
                    continue
                p = os.path.join(rdir, n)
                rx, rz = int(m.group(1)), int(m.group(2))
                for idx in Region(p, rx, rz).chunk_indices():
                    first.setdefault((rx * 32 + (int(idx) & 31), rz * 32 + (int(idx) >> 5)), (p, int(idx)))
        if len(self._cache) > 40:
            self._cache.clear()
        self._cache[key] = (time.monotonic(), first)
        return first

    def chunk_at(self, rec, dim, date: str, cx: int, cz: int):
        """The chunk as it was at the start of ``date`` (today's state if it has not changed since)."""
        src = self.index(dim.server, dim.key, date).get((cx, cz))
        if src is None:
            return rec.chunk(dim, cx, cz)
        path, idx = src
        key = ("chunk", path, idx, os.path.getmtime(path))
        hit = self._chunks.get(key)
        if hit is None:
            m = _REGION_RE.match(os.path.basename(path))
            root = Region(path, int(m.group(1)), int(m.group(2))).read_nbt(idx)
            hit = from_nbt(root, cx, cz) if root else None
            if len(self._chunks) > 512:
                self._chunks.clear()
            self._chunks[key] = hit
        return hit

    def _tables(self):
        names = BLOCKS.names
        if len(names) != self._names_len:
            seen: dict[str, int] = {}
            self._base = np.array([seen.setdefault(base_name(n), len(seen)) for n in names], np.int32)
            self._air = np.array([base_name(n) in AIR for n in names], bool)
            self._names_len = len(names)
        return self._base, self._air

    def diff(self, rec, dim, server: str, dim_key: str, date: str, max_points: int = 80000) -> dict:
        dates = [d for d in self.dates(server, dim_key) if d >= date]
        # Chunk -> (region file, index) of the earliest state from the chosen day on
        first: dict[tuple, tuple] = {}
        stamp = []
        for d in dates:
            rdir = self._dir(server, d, dim_key)
            for n in sorted(os.listdir(rdir)):
                m = _REGION_RE.match(n)
                if not m:
                    continue
                p = os.path.join(rdir, n)
                stamp.append((p, os.path.getmtime(p)))
                rx, rz = int(m.group(1)), int(m.group(2))
                reg = Region(p, rx, rz)
                for idx in reg.chunk_indices():
                    cx, cz = rx * 32 + (int(idx) & 31), rz * 32 + (int(idx) >> 5)
                    first.setdefault((cx, cz), (reg, int(idx)))
        key = (server, dim_key, date, tuple(stamp), int(time.time() // 5))
        if key in self._cache:
            return self._cache[key]

        added, removed, changed = [], [], []
        per_chunk = []
        totals = {"added": 0, "removed": 0, "changed": 0}
        for (cx, cz), (reg, idx) in first.items():
            root = reg.read_nbt(idx)
            old = from_nbt(root, cx, cz) if root else None
            new = rec.chunk(dim, cx, cz)
            if old is None or new is None:
                continue
            base, air = self._tables()
            y0 = max(old.min_y, new.min_y)
            y1 = min(old.min_y + old.height, new.min_y + new.height)
            if y1 <= y0:
                continue
            a = old.blocks[y0 - old.min_y:y1 - old.min_y].astype(np.intp)
            b = new.blocks[y0 - new.min_y:y1 - new.min_y].astype(np.intp)
            if len(base) <= max(int(a.max()), int(b.max())):
                base, air = self._tables()
            oa, na = air[a], air[b]
            m_add = oa & ~na
            m_rem = ~oa & na
            m_chg = ~oa & ~na & (base[a] != base[b])
            c = (int(m_add.sum()), int(m_rem.sum()), int(m_chg.sum()))
            if not any(c):
                continue
            per_chunk.append({"cx": cx, "cz": cz, "added": c[0], "removed": c[1], "changed": c[2]})
            for k, n in zip(("added", "removed", "changed"), c):
                totals[k] += n
            for mask, out in ((m_add, added), (m_rem, removed), (m_chg, changed)):
                ys, zs, xs = np.nonzero(mask)
                if len(xs):
                    out.append(np.stack([xs + cx * 16, ys + y0, zs + cz * 16], -1).astype(np.int32))

        def flat(parts, n):
            if not parts:
                return []
            arr = np.concatenate(parts)
            if len(arr) > n:
                arr = arr[np.linspace(0, len(arr) - 1, n).astype(np.intp)]
            return arr.reshape(-1).tolist()

        per_chunk.sort(key=lambda e: -(e["added"] + e["removed"] + e["changed"]))
        res = {"date": date, "dates": dates, "totals": totals, "chunks": per_chunk[:500],
               "points": {"added": flat(added, max_points // 3), "removed": flat(removed, max_points // 3),
                          "changed": flat(changed, max_points // 3)}}
        if len(self._cache) > 8:
            self._cache.clear()
        self._cache[key] = res
        return res
