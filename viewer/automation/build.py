"""Building a schematic: fetch material from chests, unpack shulkers, walk to the site,
let Baritone build from Litematica, and at the end break wrong blocks."""

from __future__ import annotations

import math
import time

from .. import farm
from .common import BAD_CHEST, FETCH_TIMEOUT, FIX_ROUNDS, MINE_TIMEOUT, NEAR, SETTLE, STALL, UNSEEN_WAIT, _full


class BuildMode:
    """Phases: plan -> fetch | unpack | approach -> build -> (fix) -> plan ... -> done."""

    def start(self, srv: str, pid: str) -> dict:
        self.app.placements.get(pid)          # raises if the placement doesn't exist
        with self.lock:
            self.state[srv] = {"pid": pid, "phase": "plan", "info": "startet …", "log": [],
                               "cmd": None, "ts": time.monotonic(), "not_before": 0.0, "fails": 0,
                               "stalls": 0, "bad": {}, "score": None, "progress_ts": time.monotonic(),
                               "next": 0.0, "fetched": 0}
        self._log(srv, "Autobau gestartet")
        return self.view(srv)

    def _step_build(self, srv: str, st: dict, p: dict, now: float) -> None:
        try:
            pl = self.app.placements._with_size(self.app.placements.get(st["pid"]))
        except LookupError:
            return self._end(srv, "error", "Schematic gibt es nicht mehr")
        if pl.get("dim") != p.get("dim"):
            with self.lock:
                st["info"] = "du bist in einer anderen Dimension als die Schematic"
            return
        phase = st["phase"]
        if phase == "plan":
            if now >= st["not_before"]:
                self._plan(srv, st, pl, p)
        elif phase == "fetch":
            self._watch_fetch(srv, st, now)
        elif phase == "build":
            self._watch_build(srv, st, pl, now)
        elif phase == "approach":
            self._watch_approach(srv, st, pl, p, now)
        elif phase == "unpack":
            self._watch_unpack(srv, st, now)
        elif phase == "fix":
            self._watch_fix(srv, st, now)

    def _missing(self, pid: str):
        res = self.app.placements.verify(pid, self.app.rec)
        miss: dict[str, int] = {}
        for it, n in res.get("missingItems") or []:
            miss[_full(it)] = miss.get(_full(it), 0) + int(n)
        return miss, res

    def _plan(self, srv: str, st: dict, pl: dict, p: dict) -> None:
        miss, res = self._missing(st["pid"])
        unknown = int((res.get("stats") or {}).get("unknown", 0))
        if not miss and unknown:
            # Parts of the frame were never recorded: "nothing missing" means nothing there
            return self._unseen(srv, st, pl, p, unknown)
        st.pop("unseen_until", None)
        if not miss:
            if self._start_fix(srv, st, res):
                return
            s_ = res.get("stats") or {}
            wrong = int(s_.get("rotated", 0)) + int(s_.get("extra", 0))
            return self._finish(srv, st, p, "fertig!" + (f" ({wrong} Blöcke sind verdreht oder zu viel, bitte selbst prüfen)" if wrong else ""))
        inner: dict[str, int] = {}
        inv, free = self._inventory(srv, inner)
        if inv is None:
            with self.lock:
                st["info"] = "wartet auf dein Inventar …"
            return
        need = {k: n - inv.get(k, 0) for k, n in miss.items() if n - inv.get(k, 0) > 0}
        # Last fetch brought nothing (chest differs from what was remembered)? Then skip the chest
        open_need = sum(need.values()) - sum(min(n, inner.get(k, 0)) for k, n in need.items())
        chk = st.pop("check", None)
        if chk and open_need >= chk[1]:
            st["bad"][chk[0]] = time.monotonic() + BAD_CHEST
            with self.lock:
                st["fails"] += 1
            if st["fails"] >= 4:
                return self._end(srv, "error", "Holen bringt nichts (Kisten anders als gemerkt? einmal öffnen)")
            self._log(srv, "Holen hat nichts gebracht, nimmt eine andere Kiste")
        elif chk:
            st["fails"] = 0
        have_some = any(inv.get(k, 0) > 0 for k in miss)
        # Shulker in the inventory with matching contents: unpack it first (no walking needed)
        unpack = [(k, min(n, inner[k])) for k, n in need.items() if inner.get(k)]
        if unpack and free > 0 and not st.get("unpack_off"):
            w, h, l = pl.get("size") or (1, 1, 1)
            box = [pl["x"], pl["y"], pl["z"], pl["x"] + w - 1, pl["y"] + h - 1, pl["z"] + l - 1]
            cid = self._push(srv, {"kind": "unpack", "items": [{"id": k, "count": n} for k, n in unpack], "box": box})
            self._set(srv, phase="unpack", cmd=cid)
            desc = ", ".join(f"{n}× {k.split(':')[-1]}" for k, n in unpack[:4]) + (" …" if len(unpack) > 4 else "")
            return self._log(srv, f"packt Shulker aus ({desc})")
        # Fetch first (while there is room), then build
        if need and free > 0:
            stop = self._best_chest(srv, pl, p, need, free, now=time.monotonic(), bad=st["bad"])
            if stop:
                chest, take = stop
                cid = self._push(srv, {"kind": "fetch", "x": chest["x"], "y": chest["y"], "z": chest["z"],
                                       "items": [{"id": k, "count": n} for k, n in take]})
                self._set(srv, phase="fetch", cmd=cid, chest=chest["key"], check=(chest["key"], open_need))
                desc = ", ".join(f"{n}× {k.split(':')[-1]}" for k, n in take[:4]) + (" …" if len(take) > 4 else "")
                return self._log(srv, f"holt {desc} aus {chest['x']} {chest['y']} {chest['z']}")
        if have_some:
            # Far away: only walk first (without breaking), otherwise Baritone digs its way to the site
            tx, tz, d = self._near_box(pl, p)
            if d > NEAR:
                self._push(srv, {"kind": "goto", "x": tx, "z": tz})
                self._set(srv, phase="approach", target=(tx, tz), last_d=d, progress_ts=time.monotonic())
                return self._log(srv, f"läuft zur Baustelle ({int(d)} m)")
            cid = self._push(srv, {"kind": "litematica", "pid": st["pid"]})
            self._set(srv, phase="build", cmd=cid, score=None, progress_ts=time.monotonic())
            return self._log(srv, f"baut ({sum(miss.values())} Blöcke fehlen noch)")
        if need and free <= 0:
            return self._end(srv, "error", "Inventar voll mit anderen Sachen: bitte Platz machen und neu starten")
        names = ", ".join(f"{n}× {k.split(':')[-1]}" for k, n in sorted(need.items(), key=lambda kv: -kv[1])[:6])
        self._end(srv, "error", f"Material fehlt (in keiner gemerkten Kiste): {names}")

    def _unseen(self, srv: str, st: dict, pl: dict, p: dict, unknown: int) -> None:
        """Walk to the site so the recording sees it; give up if it stays unseen."""
        now = time.monotonic()
        tx, tz, d = self._near_box(pl, p)
        if d > NEAR:
            self._push(srv, {"kind": "goto", "x": tx, "z": tz})
            self._set(srv, phase="approach", target=(tx, tz), last_d=d, progress_ts=now)
            return self._log(srv, f"läuft zur Baustelle, {unknown} Blöcke dort wurden noch nie aufgezeichnet")
        until = st.get("unseen_until")
        if until is None:
            with self.lock:
                st["unseen_until"] = now + UNSEEN_WAIT
                st["info"] = "wartet, bis die Aufnahme die Baustelle gespeichert hat …"
            return
        if now < until:
            return
        self._end(srv, "error", f"{unknown} Blöcke der Schematic wurden noch nie aufgezeichnet "
                                "(Aufnahme an? Einmal über die Baustelle laufen, dann neu starten)")

    def _best_chest(self, srv, pl, p, need, free, now, bad):
        best, best_score = None, 0.0
        for c in self.app.containers.list(srv):
            if c.get("dim") != pl.get("dim") or bad.get(c["key"], 0) > now:
                continue
            have: dict[str, int] = {}
            for it in c.get("items") or []:
                have[it["id"]] = have.get(it["id"], 0) + it["count"]
                for inner in it.get("contents") or []:      # shulkers: taken along and unpacked
                    have[inner["id"]] = have.get(inner["id"], 0) + inner["count"]
            take, slots = [], free
            for k, n in sorted(need.items(), key=lambda kv: -kv[1]):
                k_have = min(n, have.get(k, 0))
                if k_have <= 0 or slots <= 0:
                    continue
                k_have = min(k_have, slots * 64)
                slots -= math.ceil(k_have / 64)
                take.append((k, k_have))
            if not take:
                continue
            gain = sum(n for _, n in take)
            dist = math.hypot(c["x"] - p["x"], c["z"] - p["z"])
            score = gain / (dist + 32.0)
            if score > best_score:
                best, best_score = (c, take), score
        return best

    @staticmethod
    def _near_box(pl: dict, p: dict):
        """Nearest point just outside the schematic frame (x/z) and the distance to it."""
        w, _h, l = pl.get("size") or (1, 1, 1)
        x0, z0, x1, z1 = pl["x"] - 2, pl["z"] - 2, pl["x"] + w + 1, pl["z"] + l + 1
        tx = min(max(int(p["x"]), x0), x1)
        tz = min(max(int(p["z"]), z0), z1)
        if x0 < tx < x1 and z0 < tz < z1:
            return tx, tz, 0.0
        return tx, tz, math.hypot(tx - p["x"], tz - p["z"])

    def _watch_approach(self, srv: str, st: dict, pl: dict, p: dict, now: float) -> None:
        _tx, _tz, d = self._near_box(pl, p)
        if d <= NEAR:
            self.app.commands.push({"srv": srv, "kind": "stop"})
            return self._set(srv, phase="plan", not_before=now + 1.0)
        if d < st["last_d"] - 1.0:
            self._set(srv, last_d=d, progress_ts=now)
        elif now - st["progress_ts"] > STALL:
            self.app.commands.push({"srv": srv, "kind": "stop"})
            return self._end(srv, "error", "kommt ohne Abbauen nicht zur Baustelle (Weg freimachen oder näher hinstellen)")

    def _watch_unpack(self, srv: str, st: dict, now: float) -> None:
        r = self.app.commands.results.get(st["cmd"])
        if r is None:
            if now - st["ts"] > 60.0:
                self._log(srv, "Auspacken hängt, holt stattdessen aus Kisten")
                self._set(srv, phase="plan", not_before=now + SETTLE, unpack_off=True)
            return
        if r["ok"]:
            self._log(srv, r["msg"])
        else:
            with self.lock:
                st["unpack_fails"] = st.get("unpack_fails", 0) + 1
            off = st["unpack_fails"] >= 2
            self._log(srv, r["msg"] + (" – Auspacken aus" if off else ""))
            if off:
                st["unpack_off"] = True
        self._set(srv, phase="plan", not_before=now + SETTLE)

    def _watch_fetch(self, srv: str, st: dict, now: float) -> None:
        r = self.app.commands.results.get(st["cmd"])
        if r is None:
            if now - st["ts"] > FETCH_TIMEOUT:
                self.app.commands.push({"srv": srv, "kind": "stop"})
                st["bad"][st.get("chest")] = now + BAD_CHEST
                self._log(srv, "Holen dauert zu lange, nächste Kiste")
                self._set(srv, phase="plan", not_before=now + SETTLE)
            return
        took = r["ok"] or "Items genommen" in r["msg"] and not r["msg"].startswith("Holen: nichts")
        if took:
            with self.lock:
                st["fetched"] += 1
            self._log(srv, r["msg"])
        else:
            st["bad"][st.get("chest")] = now + BAD_CHEST
            with self.lock:
                st["fails"] += 1
            if st["fails"] >= 4:
                return self._end(srv, "error", f"Holen klappt nicht: {r['msg']}")
            self._log(srv, f"{r['msg']}, versucht eine andere Kiste")
        self._set(srv, phase="plan", not_before=now + SETTLE)

    def _watch_build(self, srv: str, st: dict, pl: dict, now: float) -> None:
        r = self.app.commands.results.get(st["cmd"])
        if r is not None and not r["ok"]:
            return self._end(srv, "error", f"Bauen ging nicht: {r['msg']}")
        if now - st["ts"] < 5.0:
            return
        miss, _ = self._missing(st["pid"])
        if not miss:
            self.app.commands.push({"srv": srv, "kind": "stop"})
            return self._finish(srv, st, self._player(srv), "fertig!")
        inv, _free = self._inventory(srv)
        inv = inv or {}
        carried = sum(inv.get(k, 0) for k in miss)
        score = sum(miss.values()) + carried     # drops with every placed block
        if st["score"] is None or score < st["score"]:
            self._set(srv, score=score, progress_ts=now, stalls=0)
            with self.lock:
                st["info"] = f"baut … noch {sum(miss.values())} Blöcke, {carried} passende Items dabei"
        if carried == 0:
            self.app.commands.push({"srv": srv, "kind": "stop"})
            self._log(srv, "Material alle, holt Nachschub")
            return self._set(srv, phase="plan", not_before=now + SETTLE)
        if now - st["progress_ts"] > STALL:
            self.app.commands.push({"srv": srv, "kind": "stop"})
            with self.lock:
                st["stalls"] += 1
            if st["stalls"] >= 3:
                return self._end(srv, "error", "Baritone kommt nicht weiter (3× ohne Fortschritt)")
            self._log(srv, "geht nicht voran, plant neu (fehlt evtl. ein anderes Material)")
            self._set(srv, phase="plan", not_before=now + SETTLE, progress_ts=now)

    # ------------------------------------------------------------ wrong blocks

    @staticmethod
    def fix_points(res: dict, extra: bool = True) -> list:
        pts = res.get("points") or {}
        out = farm.flat_points(pts.get("wrong") or []) + farm.flat_points(pts.get("rotated") or [])
        if extra:
            out += farm.flat_points(pts.get("extra") or [])
        return out

    def _start_fix(self, srv: str, st: dict, res: dict) -> bool:
        """At the end: have rotated/extra blocks broken, then continue normally."""
        if not self.get_settings()["fix"] or st.get("fix_rounds", 0) >= FIX_ROUNDS:
            return False
        pts = self.fix_points(res)
        if not pts:
            return False
        boxes = farm.boxes(pts)
        cid = self._push(srv, {"kind": "breakboxes", "boxes": boxes, "label": "Falsche Blöcke", "pickup": True,
                               "timeout": int(MINE_TIMEOUT)})
        self._set(srv, phase="fix", cmd=cid, fix_rounds=st.get("fix_rounds", 0) + 1)
        self._log(srv, f"baut {len(pts)} falsche oder überflüssige Blöcke ab")
        return True

    def _watch_fix(self, srv: str, st: dict, now: float) -> None:
        r = self.app.commands.results.get(st["cmd"])
        if r is None:
            if now - st["ts"] > MINE_TIMEOUT + 30:
                self.app.commands.push({"srv": srv, "kind": "stop"})
                self._log(srv, "Abbauen dauert zu lange, plant neu")
                self._set(srv, phase="plan", not_before=now + SETTLE)
            return
        self._log(srv, r["msg"])
        self._set(srv, phase="plan", not_before=now + SETTLE + 1.0)
