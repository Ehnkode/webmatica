"""X-ray: a chunk with only the searched blocks, everything else becomes air.

The 3D view requests the normal chunk mesh with ``xray=<names>`` and gets the
textured searched blocks, plus a trailer with their positions (for the glow
boxes, the counts and "jump to the next find"):

    "XRY1"  uint32 m   uint32 count[m]           finds per requested name (m names)
            uint32 n   int16 [x, y, z, k] * n    at most MAX_POINTS of them, lowest first;
                                                  x/z relative to the chunk corner,
                                                  k = index into the requested names
"""

from __future__ import annotations

import struct
from dataclasses import replace

import numpy as np

from .core.chunk import AIR_NAMES, BLOCKS, Chunk, base_name

TRAILER = b"XRY1"
MAX_NAMES = 64
MAX_POINTS = 2048   # per chunk; searching for stone should not send millions of boxes


def parse_names(raw: str) -> tuple[str, ...]:
    """'diamond_ore,minecraft:chest' -> ('minecraft:chest', 'minecraft:diamond_ore'), sorted, without air."""
    out = set()
    for n in raw.split(","):
        n = n.strip().lower()
        if not n:
            continue
        if ":" not in n:
            n = "minecraft:" + n
        if n not in AIR_NAMES:
            out.add(n)
    if len(out) > MAX_NAMES:
        raise ValueError(f"höchstens {MAX_NAMES} Blöcke gleichzeitig")
    return tuple(sorted(out))


class Lookup:
    """Block state ID -> index into ``names`` + 1 (0 = not searched). Grows with the registry."""

    def __init__(self, names: tuple[str, ...]):
        self.index = {n: i + 1 for i, n in enumerate(names)}
        self.lut = np.zeros(0, np.int16)

    def __call__(self, ids: np.ndarray) -> np.ndarray:
        top = int(ids.max()) + 1 if ids.size else 0
        if top > len(self.lut):
            self.lut = np.array([self.index.get(base_name(s), 0) for s in BLOCKS.names], np.int16)
            if top > len(self.lut):
                self.lut = np.pad(self.lut, (0, top - len(self.lut)))
        return self.lut[ids]


def only(ch: Chunk | None, look: Lookup) -> Chunk | None:
    """Copy of the chunk with all other blocks as air and without light data (searched blocks fully lit)."""
    if ch is None:
        return None
    keep = look(ch.blocks) > 0
    return replace(ch, blocks=np.where(keep, ch.blocks, 0).astype(ch.blocks.dtype), light_raw={})


def trailer(ch: Chunk, look: Lookup, m: int) -> bytes:
    k = look(ch.blocks)
    ys, zs, xs = np.nonzero(k)
    kk = k[ys, zs, xs] - 1
    counts = np.bincount(kk, minlength=m)[:m].astype("<u4")
    pts = np.stack([xs, ys + ch.min_y, zs, kk], -1)[:MAX_POINTS].astype("<i2")
    return TRAILER + struct.pack("<I", m) + counts.tobytes() + struct.pack("<I", len(pts)) + pts.tobytes()


def finds(ch: Chunk | None, look: Lookup, m: int) -> tuple[list, list]:
    """(counts per name, [x, y, z, k, ...] in world coordinates, at most MAX_POINTS) for the 2D map."""
    if ch is None:
        return [0] * m, []
    k = look(ch.blocks)
    ys, zs, xs = np.nonzero(k)
    kk = k[ys, zs, xs] - 1
    counts = np.bincount(kk, minlength=m)[:m].tolist()
    pts = np.stack([xs + ch.cx * 16, ys + ch.min_y, zs + ch.cz * 16, kk], -1)[:MAX_POINTS]
    return counts, pts.reshape(-1).tolist()
