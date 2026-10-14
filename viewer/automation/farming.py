"""Farming: break chosen blocks in an area until the requested amount is reached,
storing the drops in a chest when the inventory is full."""

from __future__ import annotations

import re
import time

from .. import farm
from .common import FREE_MIN, KEEP, MAIN_SLOTS, MINE_BATCH, MINE_TIMEOUT, SETTLE, _full


class FarmMode:
    """Phases: fplan -> mine | deposit -> fplan ... -> done."""

    def start_farm(self, srv: str, req: dict) -> dict:
        p = self._player(srv)
        if p is None:
            raise ValueError("Spiel nicht verbunden")
        area = farm.norm_area(req["area"])
        dim_key = str(req.get("dim") or p.get("dim"))
        dim = self.app.rec.dimension(srv, dim_key)
        if dim is None:
            raise ValueError("für diese Dimension gibt es keine Aufnahme")
        targets = {_full(str(k)): max(1, int(v)) for k, v in (req.get("targets") or {}).items() if int(v) > 0}
        if not targets:
            raise ValueError("keine Blöcke gewählt")
        have = farm.count(self.app.rec, dim, area, set(targets))
        chest = None
        if req.get("chest"):
            c = next((c for c in self.app.containers.list(srv) if c.get("key") == req["chest"]), None)
            if c is None:
                raise ValueError("Kiste unbekannt")
            chest = {"x": c["x"], "y": c["y"], "z": c["z"], "name": c.get("title") or f"{c['x']} {c['y']} {c['z']}", "own": False}
        with self.lock:
            self.state[srv] = {"pid": None, "mode": "farm", "phase": "fplan", "info": "", "log": [], "cmd": None,
                               "ts": time.monotonic(), "next": 0.0, "fetched": 0, "not_before": 0.0,
                               "area": area, "dim": dim_key, "targets": targets, "start": have, "mined": {k: 0 for k in targets},
                               "chest": chest, "place": bool(req.get("placeChest", True)), "skip": set(), "fails": 0,
                               "deposits": 0, "batch": None}
        short = ", ".join(f"{n}× {k.split(':')[-1]}" for k, n in targets.items())
        self._log(srv, f"Farmen gestartet: {short}")
        lack = [k.split(":")[-1] for k, n in targets.items() if have.get(k, 0) == 0]
        if lack:
            self._log(srv, "Im Bereich nicht aufgezeichnet: " + ", ".join(lack))
        return self.view(srv)

    def _step_farm(self, srv: str, st: dict, p: dict, now: float) -> None:
        if p.get("dim") != st["dim"]:
            with self.lock:
                st["info"] = "du bist in einer anderen Dimension als der Farm-Bereich"
            return
        dim = self.app.rec.dimension(srv, st["dim"])
        phase = st["phase"]
        if phase == "mine":
            r = self.app.commands.results.get(st["cmd"])
            if r is None:
                if now - st["ts"] > MINE_TIMEOUT + 30:
                    self.app.commands.push({"srv": srv, "kind": "stop"})
                    self._log(srv, "Abbauen hängt, nimmt die nächste Stelle")
                    st["skip"].update(st["batch"] or [])
                    self._set(srv, phase="fplan", not_before=now + SETTLE)
                return
            self._set(srv, phase="fplan", not_before=now + SETTLE + 1.0, cmd=None, judge=True)
            return
        if phase == "deposit":
            r = self.app.commands.results.get(st["cmd"])
            if r is None:
                if now - st["ts"] > 240.0:
                    self.app.commands.push({"srv": srv, "kind": "stop"})
                    return self._end(srv, "error", "Einlagern hängt")
                return
            self._log(srv, r["msg"])
            m = re.search(r"bei (-?\d+) (-?\d+) (-?\d+)", r["msg"])
            if r["msg"].startswith("Kiste:") and m and r["ok"]:
                x, y, z = map(int, m.groups())
                st["chest"] = {"x": x, "y": y, "z": z, "name": f"neue Kiste {x} {y} {z}", "own": True}
            if not r["ok"] or "Kiste voll" in r["msg"]:
                if st.get("chest") and "Kiste voll" in r["msg"]:
                    st["chest"] = None                     # place a new one next time
                if not r["ok"] and not st.get("place"):
                    return self._end(srv, "error", "Einlagern ging nicht: " + r["msg"])
                st["deposit_fails"] = st.get("deposit_fails", 0) + 1
                if st["deposit_fails"] >= 3:
                    return self._end(srv, "error", "Einlagern klappt nicht: " + r["msg"])
            else:
                st["deposit_fails"] = 0
                st["deposits"] = st.get("deposits", 0) + 1
            self._set(srv, phase="fplan", not_before=now + SETTLE)
            return
        if now < st["not_before"]:
            return
        # fplan: count progress
        left = farm.count(self.app.rec, dim, st["area"], set(st["targets"]))
        mined = {k: max(0, st["start"].get(k, 0) - left.get(k, 0)) for k in st["targets"]}
        open_ = {k: n - mined[k] for k, n in st["targets"].items() if n - mined[k] > 0 and left.get(k, 0) > 0}
        if st.pop("judge", False):
            gained = sum(mined.values()) - sum(st["mined"].values())
            if gained <= 0:
                st["skip"].update(st["batch"] or [])
                st["fails"] += 1
                if st["fails"] >= 4:
                    return self._end(srv, "error", "kommt nicht weiter (4× nichts abgebaut)")
                self._log(srv, "an dieser Stelle ging nichts, nimmt eine andere")
            else:
                st["fails"] = 0
        with self.lock:
            st["mined"] = mined
        if not open_:
            done = ", ".join(f"{mined[k]}× {k.split(':')[-1]}" for k in st["targets"])
            short = [k.split(":")[-1] for k, n in st["targets"].items() if mined[k] < n]
            return self._end(srv, "done", f"fertig: {done}" + (f" (im Bereich gibt es nicht mehr: {', '.join(short)})" if short else ""))
        _inv, free = self._inventory(srv)
        if _inv is None:
            with self.lock:
                st["info"] = "wartet auf dein Inventar …"
            return
        if free < FREE_MIN:
            return self._farm_deposit(srv, st, now)
        pts = farm.positions(self.app.rec, dim, st["area"], set(open_), (p["x"], p["y"], p["z"]),
                             limit=MINE_BATCH, skip=st["skip"])
        if not pts:
            return self._end(srv, "error", "keine erreichbaren Blöcke mehr im Bereich")
        cid = self._push(srv, {"kind": "breakboxes", "boxes": farm.boxes(pts), "label": "Farmen", "pickup": True,
                               "timeout": int(MINE_TIMEOUT)})
        self._set(srv, phase="mine", cmd=cid, batch=pts)
        todo = ", ".join(f"{n}× {k.split(':')[-1]}" for k, n in open_.items())
        self._log(srv, f"baut {len(pts)} Blöcke ab bei {pts[0][0]} {pts[0][1]} {pts[0][2]} (noch {todo})")

    def _farm_deposit(self, srv: str, st: dict, now: float) -> None:
        inv = self.app.inventory or {}
        put = [{"slot": it["slot"], "id": it["id"]} for it in inv.get("items") or []
               if it.get("slot") is not None and 9 <= it["slot"] < MAIN_SLOTS and not KEEP.search(it["id"])]
        if not put:
            return self._end(srv, "error", "Inventar voll, aber nichts zum Einlagern (nur Werkzeug/Essen)")
        ch = st.get("chest")
        if ch:
            cid = self._push(srv, {"kind": "store", "x": ch["x"], "y": ch["y"], "z": ch["z"], "put": put})
            self._set(srv, phase="deposit", cmd=cid)
            return self._log(srv, f"Inventar voll: bringt {len(put)} Stacks zu {ch['name']}")
        if not st.get("place"):
            return self._end(srv, "error", "Inventar voll und keine Kiste gewählt")
        has_chest = any(it.get("id") == "minecraft:chest" for it in inv.get("items") or [])
        if not has_chest:
            return self._end(srv, "error", "Inventar voll und keine Kiste dabei (Kiste mitnehmen oder eine wählen)")
        cid = self._push(srv, {"kind": "chestdump", "slots": [x["slot"] for x in put]})
        self._set(srv, phase="deposit", cmd=cid)
        self._log(srv, f"Inventar voll: stellt eine Kiste auf und packt {len(put)} Stacks hinein")
