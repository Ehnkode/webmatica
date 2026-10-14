"""Work outside the request threads: 3D meshes and X-ray in worker processes,
pre-rendering the map, and the automation loop.

The *_job functions run in a ProcessPoolExecutor, so they only get plain values
and open the recording themselves (see _mesh_init).
"""

from __future__ import annotations

import gzip
import os
import time
from concurrent.futures import ProcessPoolExecutor

from .core.mesher import mesh_chunk
from .core.tiles import Layer, TileStore, render_chunk
from .core.world import Recording
from .extras import History
from . import spawn, xray


_LOOKUPS: dict = {}


def _mesh_compute(rec, dim, cx, cz, full, ylo=None, yhi=None, names=(), at=None, hist=None):
    # at: state at the start of that day (timelapse), from the mod's daily backups
    get = (lambda x, z: hist.chunk_at(rec, dim, at, x, z)) if at else (lambda x, z: rec.chunk(dim, x, z))
    ch = get(cx, cz)
    if ch is None:
        return None
    nb = {(dx, dz): get(cx + dx, cz + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))}
    if names:
        # X-ray: only the searched blocks, the whole height (or the layer cut), with their positions behind it
        look = _LOOKUPS.get(names)
        if look is None:
            look = _LOOKUPS[names] = xray.Lookup(names)
        nb = {k: xray.only(c, look) for k, c in nb.items()}
        data = mesh_chunk(xray.only(ch, look), nb, ylo, yhi, open_bottom=ylo is not None)
        return gzip.compress(data + xray.trailer(ch, look, len(names)), 3)
    if yhi is not None:
        return gzip.compress(mesh_chunk(ch, nb, ylo, yhi, open_bottom=ylo is not None), 3)
    y_min = None
    if not full and dim.key != "the_nether":
        _, h, has = render_chunk(ch, Layer())
        if has.any():
            y_min = int(h[has].min()) - 12
    return gzip.compress(mesh_chunk(ch, nb, y_min, caves=full and dim.key == "overworld"), 3)


_WORKER: dict = {}


def _mesh_init(recording):
    _WORKER["rec"] = Recording(recording)
    _WORKER["hist"] = History(recording)


def _spawn_job(server, dim_key, rx, rz, ymax):
    rec = _WORKER["rec"]
    dim = rec.dimension(server, dim_key)
    return None if dim is None else spawn.region_png(rec, dim, rx, rz, ymax)


def _xray_compute(rec, dim, cx, cz, names):
    look = _LOOKUPS.get(names)
    if look is None:
        look = _LOOKUPS[names] = xray.Lookup(names)
    return xray.finds(rec.chunk(dim, cx, cz), look, len(names))


def _xray_job(server, dim_key, chunks, names):
    rec = _WORKER["rec"]
    dim = rec.dimension(server, dim_key)
    return [None if dim is None else _xray_compute(rec, dim, cx, cz, names) for cx, cz in chunks]


def _mesh_job(server, dim_key, cx, cz, full, ylo=None, yhi=None, names=(), at=None):
    rec = _WORKER["rec"]
    dim = rec.dimension(server, dim_key)
    return None if dim is None else _mesh_compute(rec, dim, cx, cz, full, ylo, yhi, names, at, _WORKER["hist"])


# ------------------------------------------------------------------ Pre-rendering

def _prerender_one(args):
    recording, cache_dir, server, dim_key, layer_key, rx, rz = args
    rec = Recording(recording)
    dim = rec.dimension(server, dim_key)
    if dim is not None:
        TileStore(rec, cache_dir).region_tile(dim, Layer.parse(layer_key), rx, rz)


def prerender_loop(app, recording: str, workers: int, interval: float = 10.0):
    """Keeps all region tiles up to date in the background (newest regions first)."""
    done: dict = {}
    with ProcessPoolExecutor(max_workers=workers) as pool:
        while True:
            jobs = []
            for dim in app.rec.dimensions():
                for layer in app.layers_for(dim.key):
                    for r in dim.regions():
                        try:
                            m = os.path.getmtime(r.path)
                        except OSError:
                            continue
                        k = (dim.id, layer["id"], r.rx, r.rz)
                        if done.get(k) != m:
                            jobs.append((m, k, (recording, app.cache_dir, dim.server, dim.key,
                                                layer["id"], r.rx, r.rz)))
            jobs.sort(key=lambda j: -j[0])
            futures = [(m, k, pool.submit(_prerender_one, a)) for m, k, a in jobs]
            for m, k, fut in futures:
                try:
                    fut.result()
                    done[k] = m
                except Exception as e:
                    print(f"Vorrendern fehlgeschlagen {k}: {e}")
                    done[k] = m
            time.sleep(interval)


def automation_loop(app, every: float = 0.5) -> None:
    """Auto-build and layer automation for every server whose mod polls right now.

    Verifying a big schematic can take seconds; doing it in the mod's poll made
    that poll time out (and the guard switches flicker).
    """
    while True:
        now = time.monotonic()
        for srv, seen in list(app.polling.items()):
            if now - seen > 10.0:
                continue
            try:
                app.control.auto_step(srv, lambda pid: app.placements.verify(pid, app.rec))
                app.auto.step(srv)
            except Exception as e:  # keep the loop alive
                print(f"automation {srv}: {e}")
        time.sleep(every)
