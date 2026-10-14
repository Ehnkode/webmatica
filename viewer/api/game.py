"""Talking to the game: the player, the mod's polls, commands for Baritone,
auto-build, farming and the radar."""

from __future__ import annotations

import base64
import json
import re
import threading
import time

from ..app import PLAYER_STALE, VERSION
from ..autobuild import AutoBuild
from ..extras import Containers
from .. import farm, spawn
from .router import routes

AREA = ("x0", "z0", "x1", "z1", "y0", "y1")


@routes.get("/api/version")
def version(h, p, q):
    return h.json({"version": VERSION, "tabs": h.app.streams})


@routes.post("/api/quit")
def quit_(h, req):
    # The mod asks an outdated viewer to make room for the new one.
    h.json({"ok": True})
    threading.Thread(target=h.server.shutdown, daemon=True).start()


# ------------------------------------------------------------------ Player

def player_payload(app) -> dict:
    pl = app.player
    active = pl is not None and time.monotonic() - app.player_ts < PLAYER_STALE
    return {**(pl or {}), "active": active, "skinVersion": app.skin_version,
            "hasSkin": app.skin_full is not None or app.skin_face is not None}


@routes.get("/api/player")
def player(h, p, q):
    return h.json(player_payload(h.app))


@routes.get("/api/player/stream")
def player_stream(h, p, q):
    """Server-sent events: every new position to the browser immediately (instead of polling)."""
    app = h.app
    h.send_response(200)
    h.send_header("Content-Type", "text/event-stream")
    h.send_header("Cache-Control", "no-cache")
    h.send_header("X-Accel-Buffering", "no")
    h.send_header("Connection", "close")
    h.end_headers()
    h.close_connection = True
    seq = -1
    with app.player_cond:
        app.streams += 1
    try:
        while True:
            with app.player_cond:
                app.player_cond.wait_for(lambda: app.player_seq != seq, timeout=2.0)
                seq = app.player_seq
            h.wfile.write(b"data: " + json.dumps(player_payload(app)).encode() + b"\n\n")
            h.wfile.flush()
    except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError, OSError):
        return
    finally:
        with app.player_cond:
            app.streams -= 1


@routes.get("/api/skin/face.png")
@routes.get("/api/skin/full.png")
def skin(h, p, q):
    data = h.app.skin_face if p.endswith("face.png") else h.app.skin_full
    if not data and p.endswith("full.png"):
        data = _skin_by_name(h.app)
    if not data:
        return h.fail(404, "kein Skin")
    return h.send(200, data, "image/png", "no-cache")


def _skin_by_name(app):
    """No skin from the mod: fetch once by player name (mc-heads.net)."""
    pl = app.player or {}
    name = re.sub(r"[^A-Za-z0-9_]", "", pl.get("name", ""))
    if not name or app.skin_fetch_name == name:
        return None
    app.skin_fetch_name = name
    try:
        import urllib.request
        req = urllib.request.Request(f"https://mc-heads.net/skin/{name}", headers={"User-Agent": "webmatica"})
        data = urllib.request.urlopen(req, timeout=8).read()
        if data[:8] == b"\x89PNG\r\n\x1a\n":
            app.skin_full = data
            app.skin_version += 1
            return data
    except Exception as e:
        print(f"Skin fuer {name} nicht ladbar: {e}")
    return None


@routes.post("/api/position")
def position(h, req):
    """Live position from the mod (several times a second), with inventory, skin and other players."""
    app = h.app
    try:
        pl = {
            "x": float(req["x"]), "y": float(req["y"]), "z": float(req["z"]),
            "yaw": float(req.get("yaw", 0)), "pitch": float(req.get("pitch", 0)),
            "dim": str(req.get("dim", "")).split(":", 1)[-1],
            "server": str(req.get("server", "")),
            "name": str(req.get("name", "")),
            "skinModel": str(req.get("skinModel", "")),
            "recording": bool(req.get("recording", True)),
            "written": int(req.get("written", 0) or 0),
            "t": float(req.get("t", 0) or 0),
            # helper mods found in the game (None: mod too old to tell)
            "mods": {k: bool(v) for k, v in req["mods"].items()} if isinstance(req.get("mods"), dict) else None,
        }
    except (KeyError, TypeError, ValueError):
        return h.fail(400, "x/y/z fehlen")
    for field, attr in (("skinFace", "skin_face"), ("skinFull", "skin_full")):
        if req.get(field):
            try:
                data = base64.b64decode(req[field])
                if data[:8] == b"\x89PNG\r\n\x1a\n" and getattr(app, attr) != data:
                    setattr(app, attr, data)
                    app.skin_version += 1
            except ValueError:
                pass
    if isinstance(req.get("others"), list):
        pl["others"] = app.radar.update(pl, req["others"])
    if isinstance(req.get("inv"), list):
        app.inventory = {"items": Containers._clean_items(req["inv"]), "t": time.time() * 1000,
                         "server": pl["server"], "name": pl["name"]}
    with app.player_cond:
        app.player, app.player_ts = pl, time.monotonic()
        app.player_seq += 1
        app.player_cond.notify_all()
    if pl["server"]:
        app.add_trail(pl)
    h.json({"ok": True})


@routes.get("/api/inventory")
def inventory(h, p, q):
    return h.json(h.app.inventory)


@routes.get("/api/radar")
def radar(h, p, q):
    return h.json(h.app.radar.list(q.get("srv", [None])[0]))


# ------------------------------------------------------------------ Commands for the mod

@routes.get("/api/commands")
def commands(h, p, q):
    return h.json(h.app.commands.pop(q["srv"][0]))


# Commands that are fine on any server: they only stop the bot or mark chests
SAFE_COMMANDS = {"highlight", "stop", "pause", "resume"}
SAFE_MSG = "Server-safe ist an: Bot-Funktionen sind aus (Optionen)"


def server_safe(h, srv) -> bool:
    return h.app.control.safe(str(srv))


@routes.post("/api/commands")
def push_command(h, req):
    if req.get("kind") not in SAFE_COMMANDS and server_safe(h, req.get("srv")):
        return h.fail(403, SAFE_MSG)
    try:
        if req.get("kind") == "stop":
            h.app.auto.stop(str(req.get("srv")), "gestoppt (Stopp-Knopf)")
        return h.json(h.app.commands.push(req))
    except (KeyError, ValueError) as e:
        return h.fail(400, str(e))


@routes.get("/api/commands/last")
def last_command(h, p, q):
    if q.get("id"):
        return h.json(h.app.commands.results.get(q["id"][0]))
    return h.json(h.app.commands.last)


@routes.post("/api/commands/result")
def command_result(h, req):
    h.app.commands.result(req)
    return h.json({"ok": True})


# ------------------------------------------------------------------ Printer / layer control

@routes.get("/api/control")
def control(h, p, q):
    # Only read here: the mod waits at most 2 s for this answer.
    # Auto-build and layer automation run in workers.automation_loop.
    srv = q["srv"][0]
    h.app.polling[srv] = time.monotonic()
    return h.json({**h.app.control.for_mod(srv), "guard": h.app.auto.guard(srv)})


@routes.post("/api/control")
def push_control(h, req):
    try:
        srv = str(req["srv"])
        safe = bool(req["safe"]) if "safe" in req else server_safe(h, srv)
        if safe and (req.get("printer") or {}).get("on"):
            return h.fail(403, "Server-safe ist an: der Litematica Printer bleibt aus, nimm den Webmatica-Printer")
        res = h.app.control.push(req)
        if req.get("safe"):
            h.app.auto.stop(srv, "gestoppt (Server-safe eingeschaltet)")
        return h.json(res)
    except (KeyError, TypeError, ValueError) as e:
        return h.fail(400, str(e))


@routes.get("/api/printer/blocks")
def printer_blocks(h, p, q):
    """Target blocks around the player for the mod's own printer."""
    try:
        return h.json(h.app.placements.printer_blocks(
            q["srv"][0], q["dim"][0], int(q["x"][0]), int(q["y"][0]), int(q["z"][0]), int(q.get("r", ["8"])[0])))
    except (KeyError, ValueError) as e:
        return h.fail(400, str(e))


@routes.post("/api/control/status")
def control_status(h, req):
    h.app.control.set_status(req if isinstance(req, dict) else {})
    return h.json({"ok": True})


@routes.get("/api/control/state")
def control_state(h, p, q):
    return h.json(h.app.control.view(q["srv"][0]))


# ------------------------------------------------------------------ Auto-build, farming, flattening

@routes.get("/api/auto/settings")
def auto_settings(h, p, q):
    return h.json(h.app.auto.get_settings())


@routes.post("/api/auto/settings")
def set_auto_settings(h, req):
    return h.json(h.app.auto.set_settings(req))


@routes.get("/api/auto")
def auto(h, p, q):
    return h.json(h.app.auto.view(q["srv"][0]))


@routes.post("/api/auto")
def start_auto(h, req):
    """One endpoint for every automation; the fields of the request pick which (see docs/protocol.md)."""
    auto = h.app.auto
    try:
        srv = str(req["srv"])
        starts = req.get("store") or req.get("startQueue") or req.get("farm") or req.get("pid")
        if starts and server_safe(h, srv):
            return h.fail(403, SAFE_MSG)
        if req.get("store"):
            return h.json(auto.start_store(srv, bool(req.get("hotbar"))))
        if req.get("startQueue"):
            auto._next_in_queue(srv)
            return h.json(auto.view(srv))
        if "queue" in req:
            return h.json(auto.set_queue(srv, list(req["queue"] or [])))
        if req.get("farm") and (req["farm"] or {}).get("flatten"):
            return h.json(auto.start_flatten(srv, dict(req["farm"])))
        if req.get("farm"):
            return h.json(auto.start_farm(srv, dict(req["farm"])))
        if req.get("pid"):
            return h.json(auto.start(srv, str(req["pid"])))
        return h.json(auto.stop(srv))
    except (LookupError, KeyError) as e:
        return h.fail(404, f"unbekannt: {e}")
    except ValueError as e:
        return h.fail(400, str(e))


@routes.post("/api/fix")
def fix(h, req):
    """Have wrong/rotated (and with extra: superfluous) blocks of a placement broken."""
    app = h.app
    if server_safe(h, req.get("srv")):
        return h.fail(403, SAFE_MSG)
    try:
        res = app.placements.verify(str(req["pid"]), app.rec)
        pts = AutoBuild.fix_points(res, bool(req.get("extra")))
        if not pts:
            return h.json({"n": 0})
        cmd = app.commands.push({"srv": str(req["srv"]), "kind": "breakboxes", "boxes": farm.boxes(pts),
                                 "label": "Falsche Blöcke", "pickup": True})
        return h.json({"n": len(pts), "id": cmd["id"]})
    except LookupError as e:
        return h.fail(404, str(e))
    except (KeyError, ValueError) as e:
        return h.fail(400, str(e))


@routes.get("/api/farm/scan")
def farm_scan(h, p, q):
    dim = h.app.rec.dimension(q["srv"][0], q["dim"][0])
    if dim is None:
        return h.fail(404, "keine Aufnahme")
    try:
        return h.json(farm.scan(h.app.rec, dim, {k: q[k][0] for k in AREA}))
    except (KeyError, ValueError) as e:
        return h.fail(400, str(e))


@routes.get("/api/farm/flatscan")
def farm_flatscan(h, p, q):
    """Flatten preview: blocks to break / holes to fill per chunk (kind=light: where torches go)."""
    app = h.app
    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
    if dim is None:
        return h.fail(404, "keine Aufnahme")
    try:
        a = farm.norm_area({k: q[k][0] for k in AREA})
        kind = q.get("kind", ["flat"])[0]
        if kind == "light":
            plan = spawn.torch_plan(app.rec, dim, a)
            return h.json({"clear": 0, "fill": len(plan["torches"]), "spots": plan["spots"], "mined": [],
                           "chunks": [[cx, cz, 0, n] for (cx, cz), n in sorted(plan["chunks"].items())]})
        level = int(q["level"][0]) if kind == "flat" else 0
        return h.json(farm.flat_preview(app.rec, dim, a, kind, level))
    except (KeyError, ValueError) as e:
        return h.fail(400, str(e))
