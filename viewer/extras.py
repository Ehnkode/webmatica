"""Kisten-Inventar, Baritone-Befehle und Zeitleiste (fuer server.py).

- Kisten: die Mod meldet den Inhalt jeder geoeffneten Kiste, gespeichert in
  ``<aufnahme>/.viewer/containers.json``.
- Befehle: der Browser legt Baritone-Ziele ab, die Mod holt sie ab.
- Zeitleiste: die Mod sichert vor dem Ueberschreiben den alten Stand eines Chunks
  einmal pro Tag nach ``<aufnahme>/<server>/.history/<datum>/...``. Hier wird
  "Stand an Tag X" gegen den aktuellen Stand verglichen.
"""

from __future__ import annotations

import json
import os
import threading
import time

import numpy as np

from .core.chunk import BLOCKS, base_name, from_nbt
from .core.world import DIMENSIONS, Region, _REGION_RE

AIR = {"minecraft:air", "minecraft:cave_air", "minecraft:void_air"}


# ------------------------------------------------------------------ Kisten

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
            # Doppelkiste: immer unter der kleineren Haelfte speichern, egal welche angeklickt wurde
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


# ------------------------------------------------------------------ Truhen-Deckel (andere Spieler)

class LidLog:
    """Kisten, die jemand anderes in Sichtweite geoeffnet hat (Block-Event der Mod)."""

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


# ------------------------------------------------------------------ Kisten aus der Aufnahme

STORAGE = ("chest", "trapped_chest", "barrel", "shulker_box")   # Trichter/Werfer sind meist Farmen
_CW = {"north": "east", "east": "south", "south": "west", "west": "north"}
_CCW = {v: k for k, v in _CW.items()}
_STEP = {"north": (0, -1), "south": (0, 1), "east": (1, 0), "west": (-1, 0)}


def world_chests(rec, dim, cx0: int, cz0: int, r: int) -> list:
    """Alle Lager-Bloecke (Truhe, Fass, Shulker ...) in den aufgezeichneten Chunks um (cx0, cz0).

    Die Position kennt die Aufnahme auch ohne Oeffnen (Block-Entities kommen mit dem Chunk),
    den Inhalt nicht. Doppelkisten werden wie bei ``Containers`` unter der kleineren Haelfte
    zusammengefasst.
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


# ------------------------------------------------------------------ Baritone-Befehle

class Commands:
    TTL = 15.0

    def __init__(self):
        self.lock = threading.Lock()
        self.queue: dict[str, list] = {}
        self.last: dict | None = None
        self.results: dict[str, dict] = {}   # id -> Ergebnis, fuer den Autobau

    def push(self, req: dict) -> dict:
        kind = str(req.get("kind"))
        if kind not in ("goto", "stop", "pause", "resume", "clear", "litematica", "highlight", "fetch", "unpack", "store"):
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


# ------------------------------------------------------------------ Printer + Litematica-Schichten

class Control:
    """Gewuenschter Zustand fuer Printer und Litematica-Schichten (je Server).

    Der Browser aendert Teile davon, jede Aenderung erhoeht ``seq``; die Mod
    setzt den Zustand, sobald sie eine neue Nummer sieht, und meldet den echten
    Zustand im Spiel zurueck. "Schicht fuer Schicht" rechnet der Server selbst:
    die unterste Schicht der gewaehlten Schematic, in der noch etwas fehlt.
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
                st["printer"] = pr          # nur das Geaenderte, sonst ueberschreibt man Hotkey-Aenderungen
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
            # Printer-Aenderung nur so lange mitschicken, bis die Mod sie bestaetigt hat
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
        """Schicht fuer Schicht: alle paar Sekunden die unterste offene Schicht einstellen."""
        with self.lock:
            st = self._st(srv)
            pid = st["auto"]
            if not pid or time.monotonic() - self._auto_ts.get(srv, 0) < self.AUTO_EVERY:
                return
            self._auto_ts[srv] = time.monotonic()
        try:
            res = verify(pid)
        except Exception as e:  # Platzierung weg o.ae.: Automatik aus
            with self.lock:
                st["auto"], st["autoInfo"] = None, f"aus: {e}"
            return
        missing = [t for t in res.get("todo") or [] if t[4] == "missing"]
        with self.lock:
            if not missing:
                want = {"mode": "all"}
                st["autoInfo"] = "fertig" if not res.get("todoTotal") else "nur noch falsche Bloecke"
            else:
                y = min(t[1] for t in missing)
                n = sum(1 for t in missing if t[1] == y)
                want = {"mode": "range", "min": y, "max": y}
                st["autoInfo"] = f"Y {y}: noch {n}{'+' if len(missing) >= 400 else ''} Bloecke"
            if want != st["layer"]:
                st["layer"] = want
                st["seq"] += 1


# ------------------------------------------------------------------ Radar

class Radar:
    """Andere Spieler in Sichtweite: aktueller Stand plus Logbuch (radar.json)."""

    def __init__(self, rec_root: str):
        self.path = os.path.join(rec_root, ".viewer", "radar.json")
        self.lock = threading.Lock()
        self.saved = time.monotonic()
        try:
            with open(self.path, encoding="utf-8") as f:
                self.log: list = json.load(f)
        except (OSError, ValueError):
            self.log = []

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
                last = next((s for s in reversed(self.log[-50:]) if s["name"] == e["name"]
                             and s["server"] == me["server"] and now - s["last"] < 120), None)
                if last is None:
                    last = {"name": e["name"], "server": me["server"], "dim": me["dim"], "first": now,
                            "x": round(e["x"]), "z": round(e["z"]), "closest": e["dist"]}
                    self.log.append(last)
                last["last"] = now
                if e["dist"] <= last["closest"]:
                    last.update(closest=e["dist"], x=round(e["x"]), z=round(e["z"]))
            del self.log[:-500]
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


# ------------------------------------------------------------------ Zeitleiste

class History:
    def __init__(self, rec_root: str):
        self.root = rec_root
        self._cache: dict = {}
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
        # Chunk -> (Region-Datei, Index) des fruehesten Stands ab dem gewaehlten Tag
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
