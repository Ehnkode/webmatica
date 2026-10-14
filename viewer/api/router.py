"""Maps a request path to the function that answers it.

Route functions live in the modules next to this one, grouped by feature:

    @routes.get("/api/worlds")
    def worlds(h, p, q):          # h: request handler (h.app, h.json, h.fail, ...), p: path, q: query
        return h.json(...)

    @routes.post("/api/waypoints")
    def save_waypoints(h, req):   # req: the parsed JSON body
        ...

Exact paths win over prefixes; prefixes are tried in the order they were added.
"""

from __future__ import annotations


class Router:
    def __init__(self):
        self._exact: dict[tuple[str, str], tuple] = {}
        self._prefix: list[tuple[str, str, tuple]] = []

    def _add(self, method: str, path: str, prefix: bool, raw: bool):
        def deco(fn):
            entry = (fn, raw)
            if prefix:
                self._prefix.append((method, path, entry))
            else:
                self._exact[(method, path)] = entry
            return fn
        return deco

    def get(self, path: str, prefix: bool = False):
        return self._add("GET", path, prefix, False)

    def post(self, path: str, prefix: bool = False, raw: bool = False):
        """raw: the function reads the body itself and gets (h, p, q) instead of the JSON."""
        return self._add("POST", path, prefix, raw)

    def find(self, method: str, path: str) -> tuple | None:
        """(function, raw) or None."""
        hit = self._exact.get((method, path))
        if hit is not None:
            return hit
        for m, pre, entry in self._prefix:
            if m == method and path.startswith(pre):
                return entry
        return None


routes = Router()
