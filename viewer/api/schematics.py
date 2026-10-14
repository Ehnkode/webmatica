"""Schematics (placements, library, Litematica bridge), the editor, and chests."""

from __future__ import annotations

import os
import time
from urllib.parse import unquote

from ..extras import world_chests
from .router import routes


# ------------------------------------------------------------------ Placements and library

@routes.get("/api/schematics")
def schematics(h, p, q):
    return h.json(h.app.with_ingame(h.app.placements.list(q.get("srv", [None])[0], q.get("dim", [None])[0])))


@routes.get("/api/schematics/library")
def library(h, p, q):
    return h.json({"folders": list(h.app.placements.dirs), "files": h.app.placements.library()})


@routes.get("/api/schematics/", prefix=True)
def schematic(h, p, q):
    """/api/schematics/<id>/mesh|materials|verify"""
    app = h.app
    parts = p.split("/")
    if len(parts) != 5:
        return h.fail(404, "nicht gefunden")
    pid, what = unquote(parts[3]), parts[4]
    try:
        if what == "mesh":
            data, etag = app.placements.mesh(app.placements.get(pid))
            if h.not_modified(etag):
                return
            return h.send(200, data, "application/octet-stream", "no-cache",
                          {"Content-Encoding": "gzip", "ETag": etag})
        if what == "materials":
            return h.json(app.placements.materials(pid))
        if what == "verify":
            pl = app.placements.get(pid)
            me = app.player
            near = (me["x"], me["y"], me["z"]) if me and me.get("dim") == pl.get("dim") else None
            return h.json(app.placements.verify(pid, app.rec, near))
    except LookupError as e:
        return h.fail(404, str(e))
    except ValueError as e:
        return h.fail(400, str(e))
    return h.fail(404, "nicht gefunden")


@routes.post("/api/schematics/upload", raw=True)
def upload(h, p, q):
    n = int(h.headers.get("Content-Length") or 0)
    if n <= 0 or n > 64 * 1024 * 1024:
        return h.fail(400, "Datei fehlt oder ist groesser als 64 MB")
    data = h.rfile.read(n)
    try:
        return h.json(h.app.placements.upload(q.get("name", ["upload.schem"])[0], data))
    except Exception as e:
        return h.fail(400, f"Schematic nicht lesbar: {e}")


def _placement_route(path: str, fn):
    @routes.post(path)
    def route(h, req):
        try:
            return h.json(fn(h.app, req))
        except (LookupError, KeyError) as e:
            return h.fail(404, f"unbekannt: {e}")
        except Exception as e:
            return h.fail(400, str(e))
    return route


def _delete(app, req):
    app.placements.delete(str(req.get("id")))
    return {"ok": True}


_placement_route("/api/schematics/place", lambda app, req: app.placements.place(req))
_placement_route("/api/schematics/update", lambda app, req: app.placements.update(req))
_placement_route("/api/schematics/delete", _delete)


@routes.get("/api/litematica")
def litematica(h, p, q):
    """Placements the mod should show in Litematica."""
    return h.json(h.app.placements.litematica(q.get("srv", [None])[0]))


@routes.post("/api/litematica/status")
def litematica_status(h, req):
    h.app.lm_status = req if isinstance(req, dict) else {}
    h.app.lm_status_ts = time.monotonic()
    return h.json({"ok": True})


# ------------------------------------------------------------------ Editor

@routes.get("/api/editor/", prefix=True)
def editor_get(h, p, q):
    app = h.app
    try:
        if p == "/api/editor/blocks":
            return h.json(app.editor.blocks())
        if p == "/api/editor/voxels":
            return h.json(app.editor.voxels(q["file"][0]))
        if p == "/api/editor/file":
            path = app.placements.resolve(q["file"][0])
            with open(path, "rb") as f:
                data = f.read()
            name = os.path.basename(path).replace('"', "")
            return h.send(200, data, "application/octet-stream", "no-cache",
                          {"Content-Disposition": f'attachment; filename="{name}"'})
        if p == "/api/editor/info":
            return h.json(app.editor.info(q["file"][0]))
        if p == "/api/editor/mesh":
            data, etag = app.editor.mesh(q["file"][0])
            if h.not_modified(etag):
                return
            return h.send(200, data, "application/octet-stream", "no-cache",
                          {"Content-Encoding": "gzip", "ETag": etag})
    except (LookupError, KeyError) as e:
        return h.fail(404, f"unbekannt: {e}")
    except ValueError as e:
        return h.fail(400, str(e))
    return h.fail(404, "nicht gefunden")


def _editor_route(name: str, call):
    @routes.post("/api/editor/" + name)
    def route(h, req):
        try:
            return h.json(call(h.app.editor, req))
        except (LookupError, KeyError) as e:
            return h.fail(404, f"unbekannt: {e}")
        except ValueError as e:
            return h.fail(400, str(e))
    return route


for _name in ("open", "new", "cut", "rename", "edit", "undo", "save", "discard"):
    _editor_route(_name, lambda ed, req, n=_name: getattr(ed, n)(req))
_editor_route("redo", lambda ed, req: ed.undo(req, redo=True))


# ------------------------------------------------------------------ Chests

@routes.get("/api/containers")
def containers(h, p, q):
    return h.json(h.app.containers.list(q.get("srv", [None])[0]))


@routes.post("/api/containers")
def put_container(h, req):
    try:
        return h.json(h.app.containers.put(req))
    except (KeyError, TypeError, ValueError) as e:
        return h.fail(400, f"Kiste unvollstaendig: {e}")


@routes.post("/api/containers/delete")
def delete_container(h, req):
    h.app.containers.delete(str(req.get("key")))
    return h.json({"ok": True})


@routes.get("/api/containers/lid")
def lids(h, p, q):
    """Chests other players opened (lid animation seen by the mod)."""
    return h.json(h.app.lids.list(q.get("srv", [None])[0], q.get("dim", [None])[0]))


@routes.post("/api/containers/lid")
def put_lid(h, req):
    try:
        return h.json(h.app.lids.put(req))
    except (KeyError, TypeError, ValueError) as e:
        return h.fail(400, str(e))


@routes.get("/api/containers/world")
def world_containers(h, p, q):
    """Every chest in the recording around x/z, even never opened ones (cached for 30 s)."""
    app = h.app
    dim = app.rec.dimension(q["srv"][0], q["dim"][0])
    if dim is None:
        return h.json([])
    r = max(1, min(32, int(q.get("r", ["10"])[0])))
    cx, cz = int(float(q["x"][0])) >> 4, int(float(q["z"][0])) >> 4
    key = (dim.id, cx >> 2, cz >> 2, r)
    hit = app.world_chests.get(key)
    if not hit or time.monotonic() - hit[0] > 30:
        hit = (time.monotonic(), world_chests(app.rec, dim, cx, cz, r))
        if len(app.world_chests) > 16:
            app.world_chests.clear()
        app.world_chests[key] = hit
    return h.json(hit[1])
