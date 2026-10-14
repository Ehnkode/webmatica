"""Storing: each stack from the inventory into a chest that already holds this item,
and bringing leftovers back after a build."""

from __future__ import annotations

import math
import time

from .common import FETCH_TIMEOUT, MAIN_SLOTS, STORE_RADIUS, _full


class StoreMode:
    """Phase store (mode store) or return (after a build), one chest per command."""

    def store_plan(self, srv: str, hotbar: bool = False) -> dict:
        """Preview for the confirm dialog: which chests storing would walk to, and what stays."""
        p = self._player(srv)
        inv = self.app.inventory or {}
        if p is None or inv.get("server") not in (None, srv):
            raise ValueError("Spiel nicht verbunden")
        stops, left = self._plan_store(srv, p, inv.get("items") or [], hotbar, STORE_RADIUS)
        return {"stops": [{k: s[k] for k in ("x", "y", "z", "name", "dist")} | {"n": len(s["put"])} for s in stops],
                "left": left, "radius": STORE_RADIUS}

    def start_store(self, srv: str, hotbar: bool = False) -> dict:
        """Storing: each stack from the inventory into a chest nearby that already holds this item."""
        p = self._player(srv)
        inv = self.app.inventory or {}
        if p is None or inv.get("server") not in (None, srv):
            raise ValueError("Spiel nicht verbunden")
        stops, left = self._plan_store(srv, p, inv.get("items") or [], hotbar, STORE_RADIUS)
        with self.lock:
            self.state[srv] = {"pid": None, "mode": "store", "phase": "store", "info": "", "log": [], "cmd": None,
                               "ts": time.monotonic(), "next": 0.0, "fetched": 0, "stops": stops, "fails": 0,
                               "bad": {}, "not_before": 0.0}
        n = sum(len(s["put"]) for s in stops)
        self._log(srv, f"Einlagern: {n} Stacks in {len(stops)} Kisten im Umkreis von {STORE_RADIUS:.0f} m" +
                  (f", ohne passende Kiste: {', '.join(left[:6])}" if left else ""))
        if not stops:
            self._end(srv, "done", f"nichts einzulagern: keine Kiste im Umkreis von {STORE_RADIUS:.0f} m hat diese Items" + (f" (ohne passende Kiste: {', '.join(left[:6])})" if left else ""))
        return self.view(srv)

    def _plan_store(self, srv: str, p: dict, items: list, hotbar: bool, radius: float | None = None):
        chests = []
        for c in self.app.containers.list(srv):
            if c.get("dim") != p.get("dim") or c.get("block", "").endswith("shulker_box"):
                continue
            if radius is not None and math.dist((c["x"], c["y"], c["z"]), (p["x"], p["y"], p["z"])) > radius:
                continue                       # far away (other base, old chest): Baritone would wander off
            ids: dict[str, int] = {}
            for it in c.get("items") or []:
                ids[it["id"]] = ids.get(it["id"], 0) + it["count"]
            free = int(c.get("slots") or 27) - len(c.get("items") or [])
            if ids and free > 0:
                chests.append({"c": c, "ids": ids, "free": free, "put": []})
        left = []
        lo = 0 if hotbar else 9
        for it in sorted(items, key=lambda it: it.get("slot", 0)):
            slot = it.get("slot")
            if slot is None or not lo <= slot < MAIN_SLOTS:
                continue
            best, best_score = None, 0.0
            for ch in chests:
                have = ch["ids"].get(it["id"], 0)
                if not have or ch["free"] <= 0:
                    continue
                dist = math.hypot(ch["c"]["x"] - p["x"], ch["c"]["z"] - p["z"])
                score = (1.0 + math.log1p(have)) / (dist + 32.0)
                if score > best_score:
                    best, best_score = ch, score
            if best is None:
                left.append(it["id"].split(":")[-1])
                continue
            best["put"].append({"slot": slot, "id": it["id"]})
            best["free"] -= 1
        # nearest chest first, then the next nearest each time
        todo = [ch for ch in chests if ch["put"]]
        stops, cur = [], (p["x"], p["z"])
        while todo:
            ch = min(todo, key=lambda ch: math.hypot(ch["c"]["x"] - cur[0], ch["c"]["z"] - cur[1]))
            todo.remove(ch)
            c = ch["c"]
            stops.append({"x": c["x"], "y": c["y"], "z": c["z"], "put": ch["put"],
                          "dist": round(math.dist((c["x"], c["y"], c["z"]), (p["x"], p["y"], p["z"]))),
                          "name": f"{c.get('title') or 'Kiste'} bei {c['x']} {c['y']} {c['z']}"})
            cur = (ch["c"]["x"], ch["c"]["z"])
        return stops, sorted(set(left))

    def _step_store(self, srv: str, st: dict, now: float) -> None:
        if st["cmd"] is not None:
            r = self.app.commands.results.get(st["cmd"])
            if r is None:
                if now - st["ts"] < FETCH_TIMEOUT:
                    return
                self.app.commands.push({"srv": srv, "kind": "stop"})
                r = {"ok": False, "msg": "Einlagern dauert zu lange"}
            self._log(srv, r["msg"])
            with self.lock:
                if r["ok"]:
                    if st["phase"] == "return":
                        st["returned"] = st.get("returned", 0) + 1
                    else:
                        st["fetched"] += 1
                st["cmd"] = None
        if not st["stops"]:
            if st["phase"] == "return":
                n = st.get("returned", 0)
                return self._end(srv, "done", "fertig!" + (f" Reste in {n} Kisten zurückgebracht" if n else ""))
            return self._end(srv, "done", f"eingelagert in {st['fetched']} Kisten")
        stop = st["stops"].pop(0)
        cid = self._push(srv, {"kind": "store", "x": stop["x"], "y": stop["y"], "z": stop["z"], "put": stop["put"]})
        with self.lock:
            st["cmd"], st["ts"] = cid, time.monotonic()
        self._log(srv, f"bringt {len(stop['put'])} Stacks zu {stop['name']} ({stop['dist']} m vom Start)")

    def _finish(self, srv: str, st: dict, p, msg: str) -> None:
        """Finished building: leftover materials back into the chests (if enabled)."""
        if not self.get_settings()["leftovers"] or p is None:
            return self._end(srv, "done", msg)
        try:
            mats = {_full(it) for it, _n in self.app.placements.materials(st["pid"]).get("items") or []}
        except Exception:
            mats = set()
        inv = self.app.inventory or {}
        items = [it for it in inv.get("items") or [] if it.get("id") in mats]
        if not items:
            return self._end(srv, "done", msg)
        stops, left = self._plan_store(srv, p, items, hotbar=True)
        if not stops:
            return self._end(srv, "done", msg + (f" Reste ohne passende Kiste: {', '.join(left[:6])}" if left else ""))
        with self.lock:
            st.update(phase="return", stops=stops, cmd=None, returned=0, ts=time.monotonic())
        self._log(srv, f"{msg} Bringt {sum(len(s['put']) for s in stops)} Reste-Stacks in {len(stops)} Kisten zurück" +
                  (f" (ohne passende Kiste: {', '.join(left[:4])})" if left else ""))
