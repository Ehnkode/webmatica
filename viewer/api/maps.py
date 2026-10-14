"""The map: 2D tiles, 3D meshes, far view, X-ray, spawn overlay, history and waypoints."""

from __future__ import annotations

import json
import os
import re
import time
from urllib.parse import unquote

from ..core.chunk import AIR_NAMES, BLOCKS, base_name
from ..core.mesher import MAGIC
from ..core.models import MODELS
from ..core.schem import export as schem_export
from ..core.tiles import Layer
from .. import spawn, xray
from ..workers import _spawn_job
from .router import routes

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATE = re.compile(r"\d{4}-\d{2}-\d{2}")


def _path_parts(p: str) -> list:
    """/api/<what>/<a>/<b>/... -> [a, b, ...] (unquoted)"""
    return [unquote(x) for x in p.split("/")[3:]]


@routes.get("/api/worlds")
def worlds(h, p, q):
    return h.json({"worlds": h.app.worlds(), "now": time.time()})


@routes.get("/api/tile/", prefix=True)
def tile(h, p, q):
    parts = _path_parts(p)
    if len(parts) != 6 or not parts[5].endswith(".png"):
        return h.fail(400, "Kachelpfad: /api/tile/<srv>/<dim>/<layer>/<z>/<x>/<y>.png")
    srv, dim_key, layer_s, z, x, y = parts
    dim = h.app.rec.dimension(srv, dim_key)
    if dim is None:
        return h.fail(404, "Dimension unbekannt")
    zoom, tx, tz = int(z), int(x), int(y[:-4])
    if zoom < -8 or zoom > 0:
        return h.fail(400, "Zoom ausserhalb")
    at = q.get("at", [""])[0]
    if at and not DATE.fullmatch(at):
        return h.fail(400, "Datum: JJJJ-MM-TT")
    store = h.app.past(srv, dim_key, at) if at else h.app.tiles
    if q.get("raw", [""])[0] == "1" and not at:
        path = store.tile(dim, Layer.parse(layer_s), zoom, tx, tz, raw=True)
    else:
        path = store.tile(dim, Layer.parse(layer_s), zoom, tx, tz)
    if path is None:
        return h.send(200, h.empty_png, "image/png", "no-cache")
    etag = f'"{int(os.path.getmtime(path) * 1000)}"'
    if h.not_modified(etag, "image/png"):
        return
    with open(path, "rb") as f:
        h.send(200, f.read(), "image/png", "no-cache", {"ETag": etag})


@routes.get("/api/changes")
def changes(h, p, q):
    dim = h.dim_from(q)
    since = float(q.get("since", ["0"])[0])
    now = time.time()
    regs = h.app.changed_regions(dim, since) if dim else []
    chunks = h.app.changed_chunks(dim, regs, since) if dim and regs else []
    return h.json({"now": now, "regions": regs, "chunks": chunks})


@routes.get("/api/column")
def column(h, p, q):
    dim = h.dim_from(q)
    if dim is None:
        return h.fail(404, "Dimension unbekannt")
    layer = Layer.parse(q.get("layer", ["surface320"])[0])
    return h.json(h.app.column(dim, int(q["x"][0]), int(q["z"][0]), layer))


@routes.get("/api/markers")
def markers(h, p, q):
    dim = h.dim_from(q)
    if dim is None:
        return h.fail(404, "Dimension unbekannt")
    layer = Layer.parse(q.get("layer", ["surface320"])[0])
    return h.json(h.app.tiles.markers(dim, layer))


@routes.get("/api/waypoints")
def waypoints(h, p, q):
    return h.json(h.app.waypoints())


@routes.post("/api/waypoints")
def save_waypoints(h, req):
    if not isinstance(req, list):
        return h.fail(400, "Liste erwartet")
    return h.json(h.app.save_waypoints(req))


@routes.get("/api/schem")
def schem(h, p, q):
    """Download a box of the recording as .schem."""
    dim = h.dim_from(q)
    if dim is None:
        return h.fail(404, "Dimension unbekannt")
    c = [int(q[k][0]) for k in ("x0", "y0", "z0", "x1", "y1", "z1")]
    name = re.sub(r"[^A-Za-z0-9_. -]", "_", q.get("name", ["auswahl"])[0]).strip(" .")[:60] or "auswahl"
    try:
        data, info = schem_export(h.app.rec, dim, *c, name=name)
    except LookupError as e:
        return h.fail(404, str(e))
    h.send_response(200)
    h.send_header("Content-Type", "application/octet-stream")
    h.send_header("Content-Length", str(len(data)))
    h.send_header("Content-Disposition", f'attachment; filename="{name}.schem"')
    h.send_header("X-Schem-Info", json.dumps(info))
    h.end_headers()
    h.wfile.write(data)


@routes.get("/api/mesh/", prefix=True)
def mesh(h, p, q):
    parts = _path_parts(p)
    if len(parts) != 4:
        return h.fail(400, "Pfad: /api/mesh/<srv>/<dim>/<cx>/<cz>")
    dim = h.app.rec.dimension(parts[0], parts[1])
    if dim is None:
        return h.fail(404, "Dimension unbekannt")
    if not MODELS.available:
        return h.fail(503, "Blockmodelle fehlen: python -m viewer.tools.build_assets --jar <client.jar>")
    cx, cz = int(parts[2]), int(parts[3])
    opt = lambda k: int(q[k][0]) if k in q else None
    try:
        names = xray.parse_names(q.get("xray", [""])[0])
    except ValueError as e:
        return h.fail(400, str(e))
    at = q.get("at", [""])[0] or None
    if at and not DATE.fullmatch(at):
        return h.fail(400, "Datum: JJJJ-MM-TT")
    res = h.app.mesh(dim, cx, cz, q.get("full", ["0"])[0] == "1", opt("ylo"), opt("yhi"), names, at)
    if res is None:
        return h.send(204, b"", "application/octet-stream")
    data, stamp = res
    # Atlas version is part of it: new assets = new UVs, old meshes in the browser cache no longer fit
    etag = f'"{MODELS.version}-{MAGIC.decode()}-{stamp}"'
    if h.not_modified(etag):
        return
    h.send(200, data, "application/octet-stream", "no-cache", {"ETag": etag, "Content-Encoding": "gzip"})


@routes.get("/api/blocks.png")
def blocks_png(h, p, q):
    path = os.path.join(HERE, "assets", "blocks.png")
    if not os.path.exists(path):
        return h.fail(404, "blocks.png fehlt: python -m viewer.tools.build_assets --jar <client.jar>")
    with open(path, "rb") as f:
        return h.send(200, f.read(), "image/png", "no-cache", {"ETag": f'"{MODELS.version}"'})


@routes.get("/api/items.png")
@routes.get("/api/items.json")
def items(h, p, q):
    path = os.path.join(HERE, "assets", p.rsplit("/", 1)[1])
    if not os.path.exists(path):
        return h.fail(404, "Item-Bilder fehlen: python -m viewer.tools.build_items --jar <client.jar>")
    with open(path, "rb") as f:
        return h.send(200, f.read(), "image/png" if p.endswith(".png") else "application/json",
                      "no-cache", {"ETag": f'"{int(os.path.getmtime(path))}"'})


@routes.get("/api/blocks-info")
def blocks_info(h, p, q):
    return h.json({"available": MODELS.available, "version": MODELS.version,
                   "width": getattr(MODELS, "width", 0), "height": getattr(MODELS, "height", 0)})


@routes.get("/api/xray/find")
def xray_find(h, p, q):
    dim = h.app.rec.dimension(q["srv"][0], q["dim"][0])
    if dim is None:
        return h.fail(404, "keine Aufnahme")
    try:
        names = xray.parse_names(q.get("b", [""])[0])
        r = max(0, min(24, int(q.get("r", ["12"])[0])))
        cx, cz = int(float(q["x"][0])) >> 4, int(float(q["z"][0])) >> 4
    except (KeyError, ValueError) as e:
        return h.fail(400, str(e))
    if not names:
        return h.json({"names": [], "counts": [], "pts": [], "chunks": 0})
    return h.json(h.app.xray_find(dim, cx, cz, r, names))


@routes.get("/api/xray/names")
def xray_names(h, p, q):
    # Every block the assets know (for the search suggestions), otherwise what the recording has seen so far
    names = MODELS.blockstates if MODELS.available else {base_name(n) for n in BLOCKS.names}
    return h.json(sorted(n for n in names if n not in AIR_NAMES))


@routes.get("/api/lod/", prefix=True)
def lod(h, p, q):
    parts = _path_parts(p)
    dim = h.app.rec.dimension(parts[0], parts[1])
    if dim is None:
        return h.fail(404, "Dimension unbekannt")
    res = h.app.lod(dim, int(parts[2]), int(parts[3]), max(0, min(3, int(q.get("level", ["0"])[0]))))
    if res is None:
        return h.send(204, b"")
    data, stamp = res
    etag = f'"{stamp}"'
    if h.not_modified(etag):
        return
    return h.send(200, data, "application/octet-stream", "no-cache", {"Content-Encoding": "gzip", "ETag": etag})


@routes.get("/api/trail")
def trail(h, p, q):
    key = f"{q.get('srv', [''])[0]}/{q.get('dim', [''])[0]}"
    return h.json(h.app.trail.get(key, [])[-int(q.get("n", ["5000"])[0]):])


@routes.get("/api/spawn-tile/", prefix=True)
def spawn_tile(h, p, q):
    """2D overlay: one PNG per region, cached until the region file changes."""
    app = h.app
    parts = _path_parts(p)
    dim = app.rec.dimension(parts[0], parts[1]) if len(parts) == 4 else None
    if dim is None:
        return h.fail(404, "keine Aufnahme")
    rx, rz = int(parts[2]), int(parts[3][:-4] if parts[3].endswith(".png") else parts[3])
    ymax = int(q["ymax"][0]) if "ymax" in q else None
    reg = dim.region(rx, rz)
    head = reg.header() if reg else None
    if head is None:
        return h.send(200, h.empty_png, "image/png", "no-cache")
    etag = f'"spawn-{head.mtime_ns}-{ymax}"'
    if h.not_modified(etag, "image/png"):
        return
    key = ("spawn", dim.id, rx, rz, head.mtime_ns, ymax)
    png = app.meshes.get(key)
    if png is None:
        png = (app.mesh_pool.submit(_spawn_job, dim.server, dim.key, rx, rz, ymax).result()
               if app.mesh_pool is not None else spawn.region_png(app.rec, dim, rx, rz, ymax)) or h.empty_png
        app.meshes.put(key, png)
    return h.send(200, png, "image/png", "no-cache", {"ETag": etag})


@routes.get("/api/spawn/", prefix=True)
def spawn_points(h, p, q):
    parts = _path_parts(p)
    dim = h.app.rec.dimension(parts[0], parts[1]) if len(parts) == 4 else None
    if dim is None:
        return h.fail(404, "keine Aufnahme")
    ch = h.app.rec.chunk(dim, int(parts[2]), int(parts[3]))
    return h.json(spawn.points(ch, dim.key) if ch else {"light": False, "pts": []})


@routes.get("/api/history/dates")
def history_dates(h, p, q):
    return h.json(h.app.history.dates(q["srv"][0], q["dim"][0]))


@routes.get("/api/history/diff")
def history_diff(h, p, q):
    dim = h.app.rec.dimension(q["srv"][0], q["dim"][0])
    if dim is None:
        return h.fail(404, "keine Aufnahme fuer diese Dimension")
    return h.json(h.app.history.diff(h.app.rec, dim, q["srv"][0], q["dim"][0], q["date"][0]))
