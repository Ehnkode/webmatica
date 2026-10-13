"""Shared fixtures for the viewer tests.

The synthetic recording takes about a second to generate, so it is built once
per test run and copied for tests that write into it (placements, editor files).
"""

from __future__ import annotations

import atexit
import contextlib
import io
import os
import shutil
import tempfile

from viewer.tests import make_world

SERVER = "demo.server"
# size=1 -> overworld chunks -1..0 on both axes (blocks -16..15), one chunk per region
SIZE = 1

_shared: str | None = None


def shared_world() -> str:
    """Read-only recording shared by all tests. Do not write into it."""
    global _shared
    if _shared is None:
        tmp = tempfile.TemporaryDirectory(prefix="webmatica-test-")
        atexit.register(tmp.cleanup)
        root = os.path.join(tmp.name, "recording")
        with contextlib.redirect_stdout(io.StringIO()):
            make_world.main(root, SIZE)
        _shared = root
    return _shared


def world_copy(parent: str) -> str:
    """Writable copy of the shared recording inside ``parent``; returns its root."""
    dst = os.path.join(parent, "recording")
    shutil.copytree(shared_world(), dst)
    return dst


def top_y(x: int, z: int, top: int = 319, bottom: int = -64) -> int | None:
    """Highest non-air block of the generator at (x, z): ground truth for height tests."""
    for y in range(top, bottom - 1, -1):
        if make_world.overworld(x, y, z) != "minecraft:air":
            return y
    return None
