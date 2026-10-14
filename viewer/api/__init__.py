"""HTTP routes of the viewer, one module per feature (see router.py for how a route looks)."""

from .router import routes
from . import game, maps, pages, schematics  # noqa: F401  (registers the routes)

__all__ = ["routes"]
