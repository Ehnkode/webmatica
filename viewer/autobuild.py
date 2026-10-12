"""Autobau: Material holen und bauen, bis die Schematic steht.

Ablauf je Server, alle paar Sekunden (angestossen vom Abfragen der Mod):

    planen  ->  holen (Baritone laeuft zur besten Kiste, Mod nimmt die Items)
            ->  bauen (Baritone #litematica)
            ->  sobald das passende Material im Inventar alle ist oder nichts
                mehr vorangeht: Baritone anhalten und wieder planen

Gerechnet wird nur mit losen Items im Inventar und in den Kisten: Baritone kann
aus Shulkern nicht bauen. Abbrechen mit "Stopp" auf der Webseite.

Schutz (einzeln abschaltbar, gespeichert in .viewer/autobuild.json):
  radar      Spieler in der Naehe -> Baritone #pause, nach RADAR_CLEAR s ohne Spieler #resume
  eat/tools  gehen per /api/control an die Mod (AutoGuard): essen bei Hunger, Werkzeug schonen
  leftovers  nach "fertig" uebrige Baumaterialien in passende Kisten zurueckbringen
"""

from __future__ import annotations

import json
import math
import os
import threading
import time

FRESH_PLAYER = 6.0       # s: so alt darf die letzte Position sein
STEP_EVERY = 2.0         # s
FETCH_TIMEOUT = 240.0    # s
SETTLE = 2.5             # s nach dem Holen, bis das Inventar gemeldet ist
STALL = 45.0             # s ohne Fortschritt beim Bauen
BAD_CHEST = 600.0        # s: Kiste, die nicht ging, so lange auslassen
MAIN_SLOTS = 36
NEAR = 20.0              # Bloecke: ab hier darf Baritone fuer den Bau selbst laufen
RADAR_CLEAR = 15.0       # s ohne Spieler in der Naehe, dann geht es weiter
RUNNING = ("plan", "fetch", "build", "approach", "unpack", "return")
DEFAULTS = {"radar": True, "radarDist": 64, "friends": [], "eat": False, "tools": True, "leftovers": True}


def _full(it: str) -> str:
    return it if ":" in it else "minecraft:" + it


class AutoBuild:
    def __init__(self, app, rec_root: str | None = None):
        self.app = app
        self.lock = threading.Lock()
        self.state: dict[str, dict] = {}
        self._busy: set[str] = set()
        self.path = os.path.join(rec_root, ".viewer", "autobuild.json") if rec_root else None
        self.settings = dict(DEFAULTS)
        try:
            with open(self.path, encoding="utf-8") as f:
                self.settings.update({k: v for k, v in json.load(f).items() if k in DEFAULTS})
        except (OSError, ValueError, TypeError):
            pass

    # ------------------------------------------------------------ Einstellungen

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
        """Fuer die Mod: Essen/Werkzeugschutz nur, solange der Autobau laeuft."""
        with self.lock:
            st = self.state.get(srv)
            on = bool(st and st["phase"] in RUNNING + ("store",))
            return {"eat": on and self.settings["eat"], "tools": on and self.settings["tools"]}

    # ------------------------------------------------------------ Bedienung

    def start(self, srv: str, pid: str) -> dict:
        self.app.placements.get(pid)          # wirft, wenn es die Platzierung nicht gibt
        with self.lock:
            self.state[srv] = {"pid": pid, "phase": "plan", "info": "startet …", "log": [],
                               "cmd": None, "ts": time.monotonic(), "not_before": 0.0, "fails": 0,
                               "stalls": 0, "bad": {}, "score": None, "progress_ts": time.monotonic(),
                               "next": 0.0, "fetched": 0}
        self._log(srv, "Autobau gestartet")
        return self.view(srv)

    def start_store(self, srv: str, hotbar: bool = False) -> dict:
        """Einlagern: jeden Stack aus dem Inventar in eine Kiste, in der dieses Item schon liegt."""
        p = self._player(srv)
        inv = self.app.inventory or {}
        if p is None or inv.get("server") not in (None, srv):
            raise ValueError("Spiel nicht verbunden")
        stops, left = self._plan_store(srv, p, inv.get("items") or [], hotbar)
        with self.lock:
            self.state[srv] = {"pid": None, "mode": "store", "phase": "store", "info": "", "log": [], "cmd": None,
                               "ts": time.monotonic(), "next": 0.0, "fetched": 0, "stops": stops, "fails": 0,
                               "bad": {}, "not_before": 0.0}
        n = sum(len(s["put"]) for s in stops)
        self._log(srv, f"Einlagern: {n} Stacks in {len(stops)} Kisten" +
                  (f", ohne passende Kiste: {', '.join(left[:6])}" if left else ""))
        if not stops:
            self._end(srv, "done", "nichts einzulagern" + (f" (ohne passende Kiste: {', '.join(left[:6])})" if left else ""))
        return self.view(srv)

    def _plan_store(self, srv: str, p: dict, items: list, hotbar: bool):
        chests = []
        for c in self.app.containers.list(srv):
            if c.get("dim") != p.get("dim") or c.get("block", "").endswith("shulker_box"):
                continue
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
        # naechste Kiste zuerst, dann jeweils die naechste
        todo = [ch for ch in chests if ch["put"]]
        stops, cur = [], (p["x"], p["z"])
        while todo:
            ch = min(todo, key=lambda ch: math.hypot(ch["c"]["x"] - cur[0], ch["c"]["z"] - cur[1]))
            todo.remove(ch)
            stops.append({"x": ch["c"]["x"], "y": ch["c"]["y"], "z": ch["c"]["z"], "put": ch["put"],
                          "name": ch["c"].get("title") or f"{ch['c']['x']} {ch['c']['y']} {ch['c']['z']}"})
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
        self._log(srv, f"bringt {len(stop['put'])} Stacks zu {stop['name']}")

    def stop(self, srv: str, why: str = "gestoppt") -> dict:
        with self.lock:
            st = self.state.get(srv)
            active = st and st["phase"] not in ("off", "done", "error")
        if active:
            if st["phase"] in ("fetch", "build", "approach", "unpack", "store", "return") or st.get("paused"):
                self.app.commands.push({"srv": srv, "kind": "stop"})
            self._end(srv, "off", why)
        return self.view(srv)

    def view(self, srv: str) -> dict:
        with self.lock:
            st = self.state.get(srv)
            if not st:
                return {"phase": "off"}
            return {"pid": st["pid"], "mode": st.get("mode", "build"), "phase": st["phase"], "info": st["info"],
                    "log": st["log"][-30:], "fetched": st["fetched"], "paused": st.get("paused")}

    # ------------------------------------------------------------ intern

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
        """Lose Items im Inventar und freie Plaetze; ``inner`` bekommt den Inhalt der Shulker."""
        inv = self.app.inventory or {}
        if inv.get("server") not in (None, srv):
            return None, 0
        counts: dict[str, int] = {}
        used = 0
        for it in inv.get("items") or []:
            slot = it.get("slot")
            if slot is not None and slot >= MAIN_SLOTS:
                continue                       # Ruestung/Zweithand zaehlen nicht
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

    # ------------------------------------------------------------ Schritt

    def step(self, srv: str) -> None:
        now = time.monotonic()
        with self.lock:
            st = self.state.get(srv)
            if not st or st["phase"] in ("off", "done", "error") or now < st["next"] or srv in self._busy:
                return
            st["next"] = now + STEP_EVERY
            self._busy.add(srv)
        try:
            self._step(srv, st, now)
        except Exception as e:  # nie die Mod-Abfrage kaputt machen
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
        if self._radar(srv, st, p, now):
            return
        if st.get("mode") == "store" or st["phase"] == "return":
            return self._step_store(srv, st, now)
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

    # ------------------------------------------------------------ Schutz

    def _radar(self, srv: str, st: dict, p: dict, now: float) -> bool:
        """True, solange wegen eines Spielers pausiert ist (dann kein weiterer Schritt)."""
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
        # weiter: Wartezeit nicht als "haengt" zaehlen
        self.app.commands.push({"srv": srv, "kind": "resume"})
        took = now - paused["since"]
        with self.lock:
            st["paused"], st["clear_since"] = None, None
            for k in ("ts", "progress_ts"):
                if k in st:
                    st[k] += took
        self._log(srv, f"weiter nach {int(took)} s Pause")
        return False

    def _finish(self, srv: str, st: dict, p, msg: str) -> None:
        """Fertig gebaut: uebrige Baumaterialien zurueck in die Kisten (wenn eingeschaltet)."""
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

    def _missing(self, pid: str):
        res = self.app.placements.verify(pid, self.app.rec)
        miss: dict[str, int] = {}
        for it, n in res.get("missingItems") or []:
            miss[_full(it)] = miss.get(_full(it), 0) + int(n)
        return miss, res

    def _plan(self, srv: str, st: dict, pl: dict, p: dict) -> None:
        miss, res = self._missing(st["pid"])
        if not miss:
            wrong = int(res.get("todoTotal") or 0)
            return self._finish(srv, st, p, "fertig!" + (f" ({wrong} Blöcke sind falsch oder verdreht, bitte selbst prüfen)" if wrong else ""))
        inner: dict[str, int] = {}
        inv, free = self._inventory(srv, inner)
        if inv is None:
            with self.lock:
                st["info"] = "wartet auf dein Inventar …"
            return
        need = {k: n - inv.get(k, 0) for k, n in miss.items() if n - inv.get(k, 0) > 0}
        # Letztes Holen hat nichts gebracht (Kiste anders als gemerkt)? Dann die Kiste auslassen
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
        # Shulker im Inventar mit passendem Inhalt: zuerst auspacken (kein Weg noetig)
        unpack = [(k, min(n, inner[k])) for k, n in need.items() if inner.get(k)]
        if unpack and free > 0 and not st.get("unpack_off"):
            w, h, l = pl.get("size") or (1, 1, 1)
            box = [pl["x"], pl["y"], pl["z"], pl["x"] + w - 1, pl["y"] + h - 1, pl["z"] + l - 1]
            cid = self._push(srv, {"kind": "unpack", "items": [{"id": k, "count": n} for k, n in unpack], "box": box})
            self._set(srv, phase="unpack", cmd=cid)
            desc = ", ".join(f"{n}× {k.split(':')[-1]}" for k, n in unpack[:4]) + (" …" if len(unpack) > 4 else "")
            return self._log(srv, f"packt Shulker aus ({desc})")
        # Erst holen (solange Platz ist), dann bauen
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
            # Weit weg: erst nur laufen (ohne Abbauen), sonst graebt sich Baritone zur Baustelle durch
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

    def _best_chest(self, srv, pl, p, need, free, now, bad):
        best, best_score = None, 0.0
        for c in self.app.containers.list(srv):
            if c.get("dim") != pl.get("dim") or bad.get(c["key"], 0) > now:
                continue
            have: dict[str, int] = {}
            for it in c.get("items") or []:
                have[it["id"]] = have.get(it["id"], 0) + it["count"]
                for inner in it.get("contents") or []:      # Shulker: werden mitgenommen und ausgepackt
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
        """Naechster Punkt knapp ausserhalb des Schematic-Rahmens (x/z) und Abstand dorthin."""
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
        score = sum(miss.values()) + carried     # sinkt bei jedem gesetzten Block
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
