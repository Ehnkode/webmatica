"""Flattening, clearing, filling and lighting an area (one mode with four kinds)."""

from __future__ import annotations

import re
import time

from .. import farm, spawn
from .common import FILL_BATCH, FREE_MIN, LIGHT_BATCH, MINE_BATCH, MINE_TIMEOUT, _full


class FlattenMode:
    """Stage clear (break everything above the level), then stage fill; mine/deposit like farming."""

    def start_flatten(self, srv: str, req: dict) -> dict:
        p = self._player(srv)
        if p is None:
            raise ValueError("Spiel nicht verbunden")
        area = farm.norm_area(req["area"])
        dim_key = str(req.get("dim") or p.get("dim"))
        dim = self.app.rec.dimension(srv, dim_key)
        if dim is None:
            raise ValueError("für diese Dimension gibt es keine Aufnahme")
        fl = req.get("flatten") or {}
        kind = str(fl.get("kind") or "flat")
        if kind not in ("flat", "clear", "fill", "light"):
            raise ValueError("unbekannte Automation")
        # Breaking = clear everything in the area (layer below the area), filling = every empty spot up to the top
        if kind == "clear":
            level, clear, fill = area["y0"] - 1, True, False
        elif kind == "fill":
            level, clear, fill = area["y1"], False, True
        elif kind == "light":
            # torches onto the spawn spots, until nothing spawns any more
            level, clear, fill = area["y1"], False, True
            fl = dict(fl, block=fl.get("block") or "minecraft:torch")
        else:
            level = int(fl["level"])
            clear, fill = bool(fl.get("clear", True)), bool(fl.get("fill", True))
        block = _full(str(fl.get("block") or "").strip()) if fill else None
        if fill and not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", block or ""):
            raise ValueError("Block zum Auffüllen fehlt")
        if not clear and not fill:
            raise ValueError("nichts zu tun (weder abtragen noch auffüllen)")
        chest = None
        if req.get("chest"):
            c = next((c for c in self.app.containers.list(srv) if c.get("key") == req["chest"]), None)
            if c is None:
                raise ValueError("Kiste unbekannt")
            chest = {"x": c["x"], "y": c["y"], "z": c["z"], "name": c.get("title") or f"{c['x']} {c['y']} {c['z']}", "own": False}
        with self.lock:
            self.state[srv] = {"pid": None, "mode": "flatten", "phase": "fplan", "info": "", "log": [], "cmd": None,
                               "ts": time.monotonic(), "next": 0.0, "fetched": 0, "not_before": 0.0,
                               "area": area, "dim": dim_key, "chest": chest, "place": bool(req.get("placeChest", True)),
                               "skip": set(), "fails": 0, "deposits": 0, "batch": None, "last": None,
                               "flatten": {"kind": kind, "level": level, "clear": clear, "fill": fill, "block": block,
                                           "stage": "clear" if clear else "fill", "clearLeft": None, "fillLeft": None}}
        if kind == "clear":
            self._log(srv, f"Abbauen gestartet: Y {area['y0']} bis {area['y1']}")
        elif kind == "fill":
            self._log(srv, f"Füllen gestartet: mit {block.split(':')[-1]}, Y {area['y0']} bis {area['y1']}")
        elif kind == "light":
            self._log(srv, f"Ausleuchten gestartet: {block.split(':')[-1]} auf die Spawnstellen, Y {area['y0']} bis {area['y1']}")
        else:
            what = " und ".join(x for x in ("abtragen" if clear else "", f"mit {block.split(':')[-1]} auffüllen" if fill else "") if x)
            self._log(srv, f"Ebnen auf Y {level} gestartet: {what}")
        return self.view(srv)

    def _judge(self, srv: str, st: dict, left: int) -> bool:
        """After a job: did anything progress? False after 4 failures in a row."""
        if st.pop("judge", False) and st.get("last") is not None:
            if left >= st["last"]:
                st["skip"].update(st["batch"] or [])
                st["fails"] += 1
                if st["fails"] >= 4:
                    return False
                self._log(srv, "an dieser Stelle ging nichts, nimmt eine andere")
            else:
                st["fails"] = 0
        st["last"] = left
        return True

    def _step_flatten(self, srv: str, st: dict, p: dict, now: float) -> None:
        if st["phase"] in ("mine", "deposit"):
            return self._step_farm(srv, st, p, now)       # same job tracking as for farming
        if p.get("dim") != st["dim"]:
            with self.lock:
                st["info"] = "du bist in einer anderen Dimension als der Bereich"
            return
        if now < st["not_before"]:
            return
        dim = self.app.rec.dimension(srv, st["dim"])
        fl = st["flatten"]
        near = (p["x"], p["y"], p["z"])
        if fl["stage"] == "clear":
            above = dict(st["area"], y0=fl["level"] + 1)
            left = farm.count(self.app.rec, dim, above, {farm.ANY})[farm.ANY] if above["y0"] <= above["y1"] else 0
            ok = self._judge(srv, st, left)
            with self.lock:
                fl["clearLeft"] = left
                fl["clearTotal"] = max(fl.get("clearTotal") or 0, left)   # for the progress bar
            pts = farm.positions(self.app.rec, dim, above, {farm.ANY}, near, limit=MINE_BATCH, skip=st["skip"]) if left and ok else []
            if not pts:
                self._log(srv, "abgetragen" if left == 0 else f"abgetragen, {left} Blöcke kommen nicht weg")
                if not fl["fill"]:
                    if fl.get("kind") == "clear":
                        return self._end(srv, "done", "fertig: Bereich abgebaut")
                    return self._end(srv, "done", f"fertig: Bereich über Y {fl['level']} abgetragen")
                with self.lock:
                    fl["stage"] = "fill"
                    st["last"], st["fails"] = None, 0
                return
            _inv, free = self._inventory(srv)
            if _inv is None:
                with self.lock:
                    st["info"] = "wartet auf dein Inventar …"
                return
            if free < FREE_MIN:
                return self._farm_deposit(srv, st, now)
            cid = self._push(srv, {"kind": "breakboxes", "boxes": farm.boxes(pts), "label": "Abbauen" if fl.get("kind") == "clear" else "Ebnen", "pickup": True,
                                   "timeout": int(MINE_TIMEOUT)})
            self._set(srv, phase="mine", cmd=cid, batch=pts)
            return self._log(srv, f"trägt {len(pts)} Blöcke ab bei {pts[0][0]} {pts[0][1]} {pts[0][2]} (noch {left})")
        # Filling
        light = fl.get("kind") == "light"
        if light:
            every = spawn.torch_plan(self.app.rec, dim, st["area"], near)["torches"]
        else:
            every = farm.holes(self.app.rec, dim, st["area"], fl["level"], near, every=fl.get("kind") == "fill")
        with self.lock:
            fl["fillLeft"] = len(every)
            fl["fillTotal"] = max(fl.get("fillTotal") or 0, len(every))
        if not every:
            if light:
                return self._end(srv, "done", "fertig: im Bereich spawnt nichts mehr")
            if fl.get("kind") == "fill":
                return self._end(srv, "done", "fertig: Bereich gefüllt")
            return self._end(srv, "done", f"fertig: Bereich ist auf Y {fl['level']} eben")
        if not self._judge(srv, st, len(every)):
            return self._end(srv, "error", f"kommt beim {'Ausleuchten' if light else 'Auffüllen'} nicht weiter, "
                                           f"{len(every)} {'Fackeln' if light else 'Löcher'} offen")
        holes = [h for h in every if h not in st["skip"]]
        if not holes:
            return self._end(srv, "error", f"{len(every)} {'Stellen lassen sich nicht ausleuchten' if light else 'Löcher lassen sich nicht füllen'} (nicht erreichbar?)")
        inv, _free = self._inventory(srv)
        if inv is None:
            with self.lock:
                st["info"] = "wartet auf dein Inventar …"
            return
        have = inv.get(fl["block"], 0)
        name = fl["block"].split(":")[-1]
        if have <= 0:
            return self._end(srv, "error", f"kein {name} mehr im Inventar, es fehlen noch {len(holes)} Blöcke")
        batch = holes[:min(have, FILL_BATCH)]
        if light:
            batch = holes[:min(have, LIGHT_BATCH)]
        label = "Ausleuchten" if light else "Füllen" if fl.get("kind") == "fill" else "Auffüllen"
        cid = self._push(srv, {"kind": "breakboxes", "boxes": farm.boxes(batch), "label": label, "pickup": False,
                               "fill": fl["block"], "timeout": int(MINE_TIMEOUT)})
        self._set(srv, phase="mine", cmd=cid, batch=batch)
        if light:
            return self._log(srv, f"setzt {len(batch)} {name} (noch etwa {len(holes)} nötig, {have} dabei)")
        self._log(srv, f"füllt {len(batch)} Löcher mit {name} (noch {len(holes)}, {have} dabei)")
