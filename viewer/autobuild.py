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
  fix        am Ende falsch gedrehte und ueberfluessige Bloecke abbauen und neu setzen

Warteschlange: nach "fertig" startet die naechste Schematic von selbst.

Farmen (mode "farm"): in einem Bereich der Karte bestimmte Bloecke abbauen, bis
die gewuenschte Anzahl erreicht ist. Volles Inventar -> in die gewaehlte Kiste
bringen oder eine Kiste aus dem Inventar aufstellen und hineinpacken.

Ebnen (mode "flatten"): im Bereich alles ueber Hoehe Y abbauen (wie Farmen, mit
Einlagern) und danach Loecher bis Y mit einem Block aus dem Inventar auffuellen.
"""

from __future__ import annotations

import json
import math
import os
import re
import threading
import time

from . import farm

FRESH_PLAYER = 6.0       # s: so alt darf die letzte Position sein
STEP_EVERY = 2.0         # s
FETCH_TIMEOUT = 240.0    # s
SETTLE = 2.5             # s nach dem Holen, bis das Inventar gemeldet ist
STALL = 45.0             # s ohne Fortschritt beim Bauen
BAD_CHEST = 600.0        # s: Kiste, die nicht ging, so lange auslassen
MAIN_SLOTS = 36
NEAR = 20.0              # Bloecke: ab hier darf Baritone fuer den Bau selbst laufen
RADAR_CLEAR = 15.0       # s ohne Spieler in der Naehe, dann geht es weiter
RUNNING = ("plan", "fetch", "build", "approach", "unpack", "return", "fix", "fplan", "mine", "deposit")
DEFAULTS = {"radar": True, "radarDist": 64, "friends": [], "eat": False, "tools": True, "leftovers": True, "fix": True}
FIX_ROUNDS = 2
MINE_BATCH = 48
MINE_TIMEOUT = 360.0     # s fuer einen Abbau-Auftrag
FREE_MIN = 3             # so viele freie Plaetze, sonst erst einlagern
FILL_BATCH = 128         # Loecher je Auffuell-Auftrag
# bleibt beim Einlagern im Inventar
KEEP = re.compile(r"pickaxe|_axe|shovel|_hoe|sword|shears|chest$|shulker_box|totem|elytra|helmet|chestplate|leggings|boots|"
                  r"flint_and_steel|bucket|ender_pearl|golden_apple|golden_carrot|bread|cooked_|steak|baked_potato|firework")


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

    # ------------------------------------------------------------ Warteschlange

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
        """Naechste Schematic aus der Warteschlange starten (True, wenn eine lief an)."""
        while True:
            with self.lock:
                q = list(self.queue.get(srv) or [])
            if not q:
                return False
            pid = q.pop(0)
            self.set_queue(srv, q)
            try:
                self.app.placements.update({"id": pid, "ingame": True})   # Baritone baut aus Litematica
                name = self.app.placements.get(pid).get("name") or "Schematic"
            except LookupError:
                continue
            self.start(srv, pid)
            self._log(srv, f"Warteschlange: als Nächstes {name}")
            return True

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
        if st.get("mode") == "farm":
            return self._step_farm(srv, st, p, now)
        if st.get("mode") == "flatten":
            return self._step_flatten(srv, st, p, now)
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

    # ------------------------------------------------------------ falsche Bloecke

    @staticmethod
    def fix_points(res: dict, extra: bool = True) -> list:
        pts = res.get("points") or {}
        out = farm.flat_points(pts.get("wrong") or []) + farm.flat_points(pts.get("rotated") or [])
        if extra:
            out += farm.flat_points(pts.get("extra") or [])
        return out

    def _start_fix(self, srv: str, st: dict, res: dict) -> bool:
        """Am Ende: verdrehte/ueberfluessige Bloecke abbauen lassen, danach normal weiter."""
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

    # ------------------------------------------------------------ Farmen

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
                    st["chest"] = None                     # naechstes Mal eine neue aufstellen
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
        # fplan: Stand zaehlen
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

    # ------------------------------------------------------------ Ebnen

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
                               "flatten": {"level": level, "clear": clear, "fill": fill, "block": block,
                                           "stage": "clear" if clear else "fill", "clearLeft": None, "fillLeft": None}}
        what = " und ".join(x for x in ("abtragen" if clear else "", f"mit {block.split(':')[-1]} auffüllen" if fill else "") if x)
        self._log(srv, f"Ebnen auf Y {level} gestartet: {what}")
        return self.view(srv)

    def _judge(self, srv: str, st: dict, left: int) -> bool:
        """Nach einem Auftrag: ging etwas voran? False nach 4 Fehlschlaegen hintereinander."""
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
            return self._step_farm(srv, st, p, now)       # gleiche Auftragsverfolgung wie beim Farmen
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
            pts = farm.positions(self.app.rec, dim, above, {farm.ANY}, near, limit=MINE_BATCH, skip=st["skip"]) if left and ok else []
            if not pts:
                self._log(srv, "abgetragen" if left == 0 else f"abgetragen, {left} Blöcke kommen nicht weg")
                if not fl["fill"]:
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
            cid = self._push(srv, {"kind": "breakboxes", "boxes": farm.boxes(pts), "label": "Ebnen", "pickup": True,
                                   "timeout": int(MINE_TIMEOUT)})
            self._set(srv, phase="mine", cmd=cid, batch=pts)
            return self._log(srv, f"trägt {len(pts)} Blöcke ab bei {pts[0][0]} {pts[0][1]} {pts[0][2]} (noch {left})")
        # Auffuellen
        every = farm.holes(self.app.rec, dim, st["area"], fl["level"], near)
        with self.lock:
            fl["fillLeft"] = len(every)
        if not every:
            return self._end(srv, "done", f"fertig: Bereich ist auf Y {fl['level']} eben")
        if not self._judge(srv, st, len(every)):
            return self._end(srv, "error", f"kommt beim Auffüllen nicht weiter, {len(every)} Löcher offen")
        holes = [h for h in every if h not in st["skip"]]
        if not holes:
            return self._end(srv, "error", f"{len(every)} Löcher lassen sich nicht füllen (nicht erreichbar?)")
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
        cid = self._push(srv, {"kind": "breakboxes", "boxes": farm.boxes(batch), "label": "Auffüllen", "pickup": False,
                               "fill": fl["block"], "timeout": int(MINE_TIMEOUT)})
        self._set(srv, phase="mine", cmd=cid, batch=batch)
        self._log(srv, f"füllt {len(batch)} Löcher mit {name} (noch {len(holes)}, {have} dabei)")
