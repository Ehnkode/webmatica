"""Auto-build: fetch material and build until the schematic is done.

Flow per server, every few seconds (triggered by the mod's polling):

        plan    ->  fetch (Baritone walks to the best chest, the mod takes the items)
                        ->  build (Baritone #litematica)
                        ->  once the matching material in the inventory runs out or nothing
                                progresses any more: stop Baritone and plan again

Only loose items in the inventory and chests are counted: Baritone can't
build from shulkers. Abort with "Stop" on the web page.

Protection (each can be turned off, stored in .viewer/autobuild.json):
    radar      player nearby -> Baritone #pause, #resume after RADAR_CLEAR s without players
    eat/tools  go to the mod via /api/control (AutoGuard): eat when hungry, spare tools
    leftovers  after "done", bring leftover building materials back to matching chests
    fix        at the end, break and re-place wrongly rotated and extra blocks

Queue: after "done" the next schematic starts by itself.

Farming (mode "farm"): break specific blocks in an area of the map until the
requested amount is reached. Full inventory -> bring it to the chosen chest
or place a chest from the inventory and fill it.

Flattening (mode "flatten"): break everything above height Y in the area (like
farming, with storing) and then fill holes up to Y with a block from the inventory.
"""

from __future__ import annotations

import json
import os
import threading
import time

from .automation.build import BuildMode
from .automation.common import *  # noqa: F401,F403  (constants used to live here)
from .automation.common import DEFAULTS, FRESH_PLAYER, MAIN_SLOTS, RADAR_CLEAR, RUNNING, STEP_EVERY
from .automation.farming import FarmMode
from .automation.flatten import FlattenMode
from .automation.store import StoreMode


class AutoBuild(BuildMode, StoreMode, FarmMode, FlattenMode):
    """One automation per server at a time. ``state[srv]`` holds it as a plain dict
    (``mode``: build/store/farm/flatten, ``phase``, ``info``, ``log`` and mode-specific
    fields); ``step`` advances it, called by workers.automation_loop.

    This file: settings, queue, start/stop, the step loop and radar protection.
     The modes are in viewer/automation/.
    """

    def __init__(self, app, rec_root: str | None = None):
        self.app = app
        self.lock = threading.Lock()
        self.state: dict[str, dict] = {}
        self._busy: set[str] = set()
        self.path = os.path.join(rec_root, ".viewer", "autobuild.json") if rec_root else None
        self.settings = dict(DEFAULTS)
        self.qpath = os.path.join(rec_root, ".viewer", "autobuild-queue.json") if rec_root else None
        self.queue: dict[str, list[str]] = {}
        try:
            with open(self.qpath, encoding="utf-8") as f:
                self.queue = {str(k): [str(x) for x in v] for k, v in json.load(f).items()}
        except (OSError, ValueError, TypeError, AttributeError):
            pass
        try:
            with open(self.path, encoding="utf-8") as f:
                self.settings.update({k: v for k, v in json.load(f).items() if k in DEFAULTS})
        except (OSError, ValueError, TypeError):
            pass

    # ------------------------------------------------------------ Settings

    def get_settings(self) -> dict:
        with self.lock:
            return dict(self.settings)

    def set_settings(self, req: dict) -> dict:
        with self.lock:
            for k, v in req.items():
                if k not in DEFAULTS:
                    continue
                if k == "radarDist":
                    v = max(8, min(256, int(v)))
                elif k == "friends":
                    v = [str(n).strip()[:32] for n in (v if isinstance(v, list) else str(v).split(",")) if str(n).strip()][:50]
                else:
                    v = bool(v)
                self.settings[k] = v
            out = dict(self.settings)
        if self.path:
            try:
                os.makedirs(os.path.dirname(self.path), exist_ok=True)
                with open(self.path + ".tmp", "w", encoding="utf-8") as f:
                    json.dump(out, f, indent=1)
                os.replace(self.path + ".tmp", self.path)
            except OSError:
                pass
        return out

    def guard(self, srv: str) -> dict:
        """For the mod: eating/tool protection only while auto-build is running."""
        with self.lock:
            st = self.state.get(srv)
            on = bool(st and st["phase"] in RUNNING + ("store",))
            # "dig" always applies: also for walking started from the map
            return {"eat": on and self.settings["eat"], "tools": on and self.settings["tools"],
                    "dig": bool(self.settings["dig"])}

    # ------------------------------------------------------------ Queue

    def set_queue(self, srv: str, pids: list) -> dict:
        with self.lock:
            self.queue[srv] = [str(p) for p in pids][:50]
            data = dict(self.queue)
        if self.qpath:
            try:
                os.makedirs(os.path.dirname(self.qpath), exist_ok=True)
                with open(self.qpath + ".tmp", "w", encoding="utf-8") as f:
                    json.dump(data, f)
                os.replace(self.qpath + ".tmp", self.qpath)
            except OSError:
                pass
        return self.view(srv)

    def _next_in_queue(self, srv: str) -> bool:
        """Start the next schematic from the queue (True if one was started)."""
        while True:
            with self.lock:
                q = list(self.queue.get(srv) or [])
            if not q:
                return False
            pid = q.pop(0)
            self.set_queue(srv, q)
            try:
                self.app.placements.update({"id": pid, "ingame": True})   # Baritone builds from Litematica
                name = self.app.placements.get(pid).get("name") or "Schematic"
            except LookupError:
                continue
            self.start(srv, pid)
            self._log(srv, f"Warteschlange: als Nächstes {name}")
            return True

    def stop(self, srv: str, why: str = "gestoppt") -> dict:
        with self.lock:
            st = self.state.get(srv)
            active = st and st["phase"] not in ("off", "done", "error")
        if active:
            if st["phase"] in ("fetch", "build", "approach", "unpack", "store", "return", "fix", "mine", "deposit") or st.get("paused"):
                self.app.commands.push({"srv": srv, "kind": "stop"})
            self._end(srv, "off", why)
        return self.view(srv)

    def view(self, srv: str) -> dict:
        with self.lock:
            st = self.state.get(srv)
            queue = list(self.queue.get(srv) or [])
            if not st:
                return {"phase": "off", "queue": queue}
            out = {"pid": st["pid"], "mode": st.get("mode", "build"), "phase": st["phase"], "info": st["info"],
                   "log": st["log"][-30:], "fetched": st["fetched"], "paused": st.get("paused"), "queue": queue}
            if st.get("mode") == "flatten":
                out["farm"] = {"area": st["area"], "flatten": dict(st["flatten"]), "chest": st.get("chest"),
                               "deposits": st.get("deposits", 0)}
            if st.get("mode") == "farm":
                out["farm"] = {"area": st["area"], "targets": st["targets"], "mined": st.get("mined", {}),
                               "chest": st.get("chest"), "deposits": st.get("deposits", 0)}
            return out

    # ------------------------------------------------------------ internal

    def _log(self, srv: str, msg: str):
        with self.lock:
            st = self.state.get(srv)
            if st is None:
                return
            st["info"] = msg
            st["log"].append([int(time.time() * 1000), msg])
            del st["log"][:-60]

    def _end(self, srv: str, phase: str, msg: str):
        with self.lock:
            st = self.state.get(srv)
            if st:
                st["phase"] = phase
        self._log(srv, msg)

    def _set(self, srv: str, **kw):
        with self.lock:
            st = self.state[srv]
            if "phase" in kw and kw["phase"] != st["phase"]:
                st["ts"] = time.monotonic()
            st.update(kw)

    def _push(self, srv: str, cmd: dict) -> str:
        return self.app.commands.push({"srv": srv, **cmd})["id"]

    def _inventory(self, srv: str, inner: dict | None = None):
        """Loose items in the inventory and free slots; ``inner`` gets the shulker contents."""
        inv = self.app.inventory or {}
        if inv.get("server") not in (None, srv):
            return None, 0
        counts: dict[str, int] = {}
        used = 0
        for it in inv.get("items") or []:
            slot = it.get("slot")
            if slot is not None and slot >= MAIN_SLOTS:
                continue                       # armor/offhand don't count
            used += 1
            counts[it["id"]] = counts.get(it["id"], 0) + it["count"]
            if inner is not None:
                for c in it.get("contents") or []:
                    inner[c["id"]] = inner.get(c["id"], 0) + c["count"]
        return counts, MAIN_SLOTS - used

    def _player(self, srv: str):
        p = self.app.player
        if not p or time.monotonic() - self.app.player_ts > FRESH_PLAYER or p.get("server") != srv:
            return None
        return p

    # ------------------------------------------------------------ Step

    def step(self, srv: str) -> None:
        control = getattr(self.app, "control", None)
        if control is not None and control.safe(srv):
            self.stop(srv, "gestoppt (Server-safe ist an)")
            return
        now = time.monotonic()
        with self.lock:
            st = self.state.get(srv)
            queued = bool(st and st["phase"] == "done" and st.get("mode", "build") == "build" and self.queue.get(srv)
                          and srv not in self._busy and now >= st["next"])
            if queued:
                st["next"] = now + STEP_EVERY
        if queued:
            if self._player(srv) is not None:
                self._next_in_queue(srv)
            return
        with self.lock:
            st = self.state.get(srv)
            if not st or st["phase"] in ("off", "done", "error") or now < st["next"] or srv in self._busy:
                return
            st["next"] = now + STEP_EVERY
            self._busy.add(srv)
        try:
            self._step(srv, st, now)
        except Exception as e:  # never break the mod's polling
            self._end(srv, "error", f"Fehler: {e}")
        finally:
            with self.lock:
                self._busy.discard(srv)

    def _step(self, srv: str, st: dict, now: float) -> None:
        p = self._player(srv)
        if p is None:
            with self.lock:
                st["info"] = "wartet auf das Spiel …"
            return
        if p.get("recording") is False and st.get("mode", "build") == "build":
            # Progress is only visible through the recording: without it the build "stalls"
            with self.lock:
                st["info"] = "pausiert: Aufnahme ist aus (im Spiel F9 drücken)"
                st["rec_off"] = True
            return
        if st.pop("rec_off", None):
            self._set(srv, ts=now, progress_ts=now)
        if self._radar(srv, st, p, now):
            return
        if st.get("mode") == "store" or st["phase"] == "return":
            return self._step_store(srv, st, now)
        if st.get("mode") == "farm":
            return self._step_farm(srv, st, p, now)
        if st.get("mode") == "flatten":
            return self._step_flatten(srv, st, p, now)
        return self._step_build(srv, st, p, now)

    # ------------------------------------------------------------ Protection

    def _radar(self, srv: str, st: dict, p: dict, now: float) -> bool:
        """True while paused because of a player (then no further step)."""
        cfg = self.get_settings()
        friends = {f.lower() for f in cfg["friends"]}
        near = [o for o in p.get("others") or []
                if o.get("name", "").lower() not in friends and float(o.get("dist", 1e9)) <= cfg["radarDist"]]
        paused = st.get("paused")
        if cfg["radar"] and near:
            o = min(near, key=lambda o: o["dist"])
            with self.lock:
                st["clear_since"] = None
                st["info"] = f"pausiert: {o['name']} {int(o['dist'])} m entfernt"
            if not paused:
                self.app.commands.push({"srv": srv, "kind": "pause"})
                with self.lock:
                    st["paused"] = {"name": o["name"], "since": now}
                self._log(srv, f"Spieler {o['name']} in {int(o['dist'])} m: pausiert")
            return True
        if not paused:
            return False
        if cfg["radar"]:
            with self.lock:
                st["clear_since"] = st.get("clear_since") or now
                wait = RADAR_CLEAR - (now - st["clear_since"])
                if wait > 0:
                    st["info"] = f"pausiert: niemand mehr da, weiter in {int(wait) + 1} s"
                    return True
        # continue: don't count the wait as "stuck"
        self.app.commands.push({"srv": srv, "kind": "resume"})
        took = now - paused["since"]
        with self.lock:
            st["paused"], st["clear_since"] = None, None
            for k in ("ts", "progress_ts"):
                if k in st:
                    st[k] += took
        self._log(srv, f"weiter nach {int(took)} s Pause")
        return False
