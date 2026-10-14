"""HTTP server of the viewer.

    python -m viewer --recording "<.minecraft>/webmatica_recording"

Where things are:
    server.py    this file: start-up, the request handler, security checks
    api/         the answers, one module per feature (pages, maps, game, schematics)
    app.py       App: state of the running viewer (recording, caches, player, stores)
    workers.py   3D meshes in worker processes, pre-rendering, automation loop
Every endpoint with its parameters: docs/protocol.md
"""

from __future__ import annotations

import argparse
import hashlib
import ipaddress
import json
import multiprocessing
import os
import shutil
import threading
import time

from concurrent.futures import ProcessPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

from .api import routes
from .app import App
from .core.tiles import TileStore
from .workers import _mesh_init, automation_loop, prerender_loop


def _local_host(value: str | None) -> bool:
    """Only "localhost" or a bare IP address may appear as Host or Origin.

    DNS rebinding points a foreign domain at 127.0.0.1; the browser then sends
    that domain as Host. An IP literal (LAN access with --host 0.0.0.0) is fine.
    """
    if not value:
        return False
    host = urlparse(value if "//" in value else "//" + value).hostname or ""
    if host == "localhost":
        return True
    try:
        ipaddress.ip_address(host)
        return True
    except ValueError:
        return False


def make_handler(app: App):
    """Request handler class bound to ``app``; the answers themselves are in viewer/api/."""

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"
        empty_png = TileStore.empty_png()

        def __init__(self, *a, **kw):
            self.app = app
            super().__init__(*a, **kw)

        def log_message(self, *a):
            pass

        # ---------------------------------------------------------- helpers for the routes

        def send(self, code: int, body: bytes, ctype: str = "application/json",
                 cache: str = "no-store", extra: dict | None = None):
            try:
                self.send_response(code)
                self.send_header("Content-Type", ctype)
                self.send_header("Content-Length", str(len(body)))
                self.send_header("Cache-Control", cache)
                for k, v in (extra or {}).items():
                    self.send_header(k, v)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(body)
            except ConnectionError:   # also Windows "ConnectionAbortedError" when the browser cancels
                pass

        def json(self, obj, code: int = 200):
            self.send(code, json.dumps(obj, ensure_ascii=False).encode("utf-8"),
                      "application/json; charset=utf-8")

        def fail(self, code: int, msg: str):
            self.json({"error": msg}, code)

        def not_modified(self, etag: str, ctype: str = "application/octet-stream") -> bool:
            """Answers 304 when the browser already has this version."""
            if self.headers.get("If-None-Match") != etag:
                return False
            self.send(304, b"", ctype, "no-cache", {"ETag": etag})
            return True

        def body_json(self):
            n = int(self.headers.get("Content-Length") or 0)
            if n > 4 * 1024 * 1024:
                raise ValueError("zu gross")
            return json.loads(self.rfile.read(n) or b"{}")

        def dim_from(self, q):
            return app.rec.dimension(q.get("srv", [""])[0], q.get("dim", [""])[0])

        # ---------------------------------------------------------- dispatch

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            if not _local_host(self.headers.get("Host")):
                return self.fail(403, "fremder Host")
            u = urlparse(self.path)
            hit = routes.find("GET", u.path)
            if hit is None:
                return self.fail(404, "nicht gefunden")
            self._run(hit[0], u.path, parse_qs(u.query))

        def do_POST(self):
            u = urlparse(self.path)
            # Only local pages may write (protection against foreign web pages in the browser).
            if not _local_host(self.headers.get("Host")):
                return self.fail(403, "fremder Host")
            origin = self.headers.get("Origin")
            if origin and urlparse(origin).hostname not in ("127.0.0.1", "localhost", self.server.server_address[0]):
                return self.fail(403, "fremde Herkunft")
            hit = routes.find("POST", u.path)
            if hit is not None and hit[1]:      # raw body, e.g. an uploaded file
                return self._run(hit[0], u.path, parse_qs(u.query))
            try:
                req = self.body_json()
            except ValueError as e:
                return self.fail(400, f"kein gueltiges JSON: {e}")
            if hit is None:
                return self.fail(404, "nicht gefunden")
            self._run(hit[0], req)

        def _run(self, fn, *args):
            try:
                fn(self, *args)
            except (KeyError, ValueError) as e:
                self.fail(400, f"Parameter fehlen oder sind ungueltig: {e}")
            except Exception as e:  # never drop the connection without a word
                import traceback
                traceback.print_exc()
                self.fail(500, f"interner Fehler: {e}")

    return Handler


def _render_version() -> str:
    """Fingerprint of what the cached tiles depend on: the render code and the block colours.

    A new mod build that only changes the web page keeps the cache.
    """
    here = os.path.dirname(os.path.abspath(__file__))
    h = hashlib.sha1()
    core = os.path.join(here, "core")
    for name in sorted(os.listdir(core)):
        if name.endswith(".py"):
            with open(os.path.join(core, name), "rb") as f:
                h.update(name.encode() + f.read())
    for name in ("atlas.json", "atlas.png", "blocks.json", "blocks.png"):
        try:
            st = os.stat(os.path.join(here, "assets", name))
            h.update(f"{name}:{st.st_size}".encode())
        except OSError:
            pass
    return h.hexdigest()[:16]


def _reset_cache_on_update(cache: str) -> None:
    """Tiles rendered by other render code may be wrong (e.g. chunks it could not read): start over."""
    os.makedirs(cache, exist_ok=True)
    stamp = os.path.join(cache, ".viewer-version")
    version = _render_version()
    try:
        with open(stamp, encoding="utf-8") as f:
            if f.read().strip() == version:
                return
    except OSError:
        pass
    # Only the default cache folder or one we stamped earlier is ours to empty.
    if os.path.basename(os.path.normpath(cache)) == ".viewer-cache" or os.path.isfile(stamp):
        shutil.rmtree(cache, ignore_errors=True)
    os.makedirs(cache, exist_ok=True)
    with open(stamp, "w", encoding="utf-8") as f:
        f.write(version)


def _exit_with_parent(srv, pid: int) -> None:
    """Quit once the game process is gone, even after a crash.

    Watches the process itself instead of a pipe: on Windows a thread blocked reading
    stdin stalls the start of the worker processes, and with them every 3D request.
    """
    if os.name == "nt":
        import ctypes
        k32 = ctypes.windll.kernel32
        h = k32.OpenProcess(0x00100000, False, pid)   # SYNCHRONIZE
        if h:
            k32.WaitForSingleObject(h, 0xFFFFFFFF)
            k32.CloseHandle(h)
    else:
        while True:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                break
            except PermissionError:
                pass
            time.sleep(2)
    srv.shutdown()


def main(argv=None):
    ap = argparse.ArgumentParser(prog="python -m viewer", description="Webmatica Viewer")
    ap.add_argument("--recording", "-r", default=os.environ.get("MAPPER_RECORDING", ""),
                    help="path to .minecraft/webmatica_recording (default: what the mod writes to ~/.webmatica/client.json)")
    ap.add_argument("--cache", default="", help="tile cache (default: <recording>/.viewer-cache)")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8770)
    ap.add_argument("--parent-pid", type=int, default=0,
                    help="quit when this process ends (the mod passes the game's)")
    ap.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1),
                    help="processes for pre-rendering the map (0 = off)")
    args = ap.parse_args(argv)
    if not args.recording or not os.path.isdir(args.recording):
        ap.error("Recording not found. Start Minecraft with the mod once (then the viewer finds it by itself) "
                 "or pass --recording <.minecraft/webmatica_recording>")
    cache = args.cache or os.path.join(args.recording, ".viewer-cache")
    _reset_cache_on_update(cache)

    app = App(args.recording, cache)
    if args.workers > 0:
        app.mesh_pool = ProcessPoolExecutor(max_workers=max(2, args.workers), initializer=_mesh_init,
                                            initargs=(args.recording,))
    if args.workers > 0:
        threading.Thread(target=prerender_loop, args=(app, args.recording, args.workers),
                         daemon=True, name="prerender").start()
    threading.Thread(target=automation_loop, args=(app,), daemon=True, name="automation").start()
    srv = ThreadingHTTPServer((args.host, args.port), make_handler(app))
    srv.daemon_threads = True
    if args.parent_pid > 0:
        threading.Thread(target=_exit_with_parent, args=(srv, args.parent_pid), daemon=True, name="parent-watch").start()
    dims = app.rec.dimensions()
    print(f"Webmatica: http://{args.host}:{args.port}")
    print(f"Recording: {app.rec.root}  ({len(dims)} dimension(s))")
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        app.save_trail()
        app.radar.save()
        app.lids.save()
        print("\nstopped")
        return
    # Ended through /api/quit or with the game: save, stop the worker processes and leave right away.
    app.save_trail()
    app.radar.save()
    app.lids.save()
    print("stopped for an update")
    if app.mesh_pool is not None:
        app.mesh_pool.shutdown(wait=False, cancel_futures=True)
    for child in multiprocessing.active_children():
        child.terminate()
    os._exit(0)


if __name__ == "__main__":
    main()
