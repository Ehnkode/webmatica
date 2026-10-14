"""The web pages themselves and the files under /static/."""

from __future__ import annotations

import mimetypes
import os
from urllib.parse import unquote

from .router import routes

STATIC = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "static")


def static(h, rel: str):
    path = os.path.normpath(os.path.join(STATIC, unquote(rel)))
    if not path.startswith(STATIC + os.sep) and path != os.path.join(STATIC, "index.html"):
        return h.fail(403, "verboten")
    if not os.path.isfile(path):
        return h.fail(404, "nicht gefunden")
    with open(path, "rb") as f:
        data = f.read()
    ctype = mimetypes.guess_type(path)[0] or "application/octet-stream"
    if ctype.startswith("text/") or ctype.endswith("javascript"):
        ctype += "; charset=utf-8"
    h.send(200, data, ctype, "no-cache")


@routes.get("/")
def index(h, p, q):
    return static(h, "index.html")


@routes.get("/static/", prefix=True)
def static_file(h, p, q):
    return static(h, p[len("/static/"):])


@routes.get("/editor")
def editor_page(h, p, q):
    return static(h, "editor.html")
