"""Read the recording folder: server -> dimensions -> regions -> chunks.

Layout (as written by the mod):

    webmatica_recording/
      2b2t.org/
        region/           Overworld
        DIM-1/region/     Nether
        DIM1/region/      End
            region/             old recording from the first mod version (mixed)

Region files are not kept in memory. The header (positions and
timestamps per chunk) is reread when the mtime changes, so the
viewer sees new and changed chunks without restarting.
"""

from __future__ import annotations

import gzip
import os
import re
import threading
import zlib
from collections import OrderedDict
from dataclasses import dataclass

import numpy as np

from . import nbt
from .chunk import Chunk, from_nbt

DIMENSIONS = {
    "overworld": "",
    "the_nether": "DIM-1",
    "the_end": "DIM1",
}
DIM_LABELS = {"overworld": "Overworld", "the_nether": "Nether", "the_end": "End"}
LEGACY_SERVER = "_alt"

_REGION_RE = re.compile(r"^r\.(-?\d+)\.(-?\d+)\.mca$")


@dataclass
class RegionHeader:
    mtime_ns: int
    size: int
    offsets: np.ndarray      # (1024,) sector offset, 0 = chunk missing
    sectors: np.ndarray      # (1024,) sector count
    timestamps: np.ndarray   # (1024,) Unix seconds of the last change


class Region:
    def __init__(self, path: str, rx: int, rz: int):
        self.path = path
        self.rx = rx
        self.rz = rz
        self._header: RegionHeader | None = None
        self._lock = threading.Lock()

    def header(self) -> RegionHeader | None:
        try:
            st = os.stat(self.path)
        except FileNotFoundError:
            return None
        h = self._header
        if h is not None and h.mtime_ns == st.st_mtime_ns and h.size == st.st_size:
            return h
        with self._lock:
            with open(self.path, "rb") as f:
                raw = f.read(8192)
            if len(raw) < 8192:
                return None
            loc = np.frombuffer(raw, dtype=">u4", count=1024)
            ts = np.frombuffer(raw, dtype=">u4", count=1024, offset=4096).astype(np.int64)
            h = RegionHeader(st.st_mtime_ns, st.st_size,
                             (loc >> 8).astype(np.int64), (loc & 0xFF).astype(np.int64), ts)
            self._header = h
            return h

    def chunk_indices(self) -> np.ndarray:
        h = self.header()
        if h is None:
            return np.zeros(0, dtype=np.int64)
        return np.nonzero(h.offsets)[0]

    def read_nbt(self, idx: int, typed: bool = False) -> dict | None:
        h = self.header()
        if h is None or h.offsets[idx] == 0:
            return None
        with open(self.path, "rb") as f:
            f.seek(int(h.offsets[idx]) * 4096)
            head = f.read(5)
            if len(head) < 5:
                return None
            length = int.from_bytes(head[:4], "big")
            comp = head[4]
            payload = f.read(length - 1) if length > 1 else b""
        if comp & 0x80:
            # An oversized chunk is stored externally in c.<x>.<z>.mcc next to the region.
            cx = self.rx * 32 + (idx & 31)
            cz = self.rz * 32 + (idx >> 5)
            ext = os.path.join(os.path.dirname(self.path), f"c.{cx}.{cz}.mcc")
            try:
                with open(ext, "rb") as f:
                    payload = f.read()
            except FileNotFoundError:
                return None
            comp &= 0x7F
        if comp == 1:
            raw = gzip.decompress(payload)
        elif comp == 2:
            raw = zlib.decompress(payload)
        elif comp == 3:
            raw = payload
        else:
            return None  # the mod doesn't write LZ4 etc.
        return nbt.read(raw, typed)


class Dimension:
    """A region/ folder (one dimension of a server)."""

    def __init__(self, server: str, key: str, region_dir: str):
        self.server = server
        self.key = key
        self.region_dir = region_dir
        self._regions: dict[tuple[int, int], Region] = {}
        self._scan_lock = threading.Lock()

    @property
    def id(self) -> str:
        return f"{self.server}/{self.key}"

    def regions(self) -> list[Region]:
        """Current regions (new files are detected on every call)."""
        with self._scan_lock:
            try:
                names = os.listdir(self.region_dir)
            except FileNotFoundError:
                names = []
            seen = set()
            for n in names:
                m = _REGION_RE.match(n)
                if not m:
                    continue
                k = (int(m.group(1)), int(m.group(2)))
                seen.add(k)
                if k not in self._regions:
                    self._regions[k] = Region(os.path.join(self.region_dir, n), *k)
            for k in list(self._regions):
                if k not in seen:
                    del self._regions[k]
            return list(self._regions.values())

    def region(self, rx: int, rz: int) -> Region | None:
        r = self._regions.get((rx, rz))
        if r is None:
            p = os.path.join(self.region_dir, f"r.{rx}.{rz}.mca")
            if os.path.exists(p):
                self.regions()
                r = self._regions.get((rx, rz))
        return r


class ChunkCache:
    """LRU for decoded chunks; the key contains the chunk timestamp."""

    def __init__(self, max_items: int = 768):
        self.max_items = max_items
        self._d: OrderedDict = OrderedDict()
        self._lock = threading.Lock()

    def get(self, key):
        with self._lock:
            v = self._d.get(key)
            if v is not None:
                self._d.move_to_end(key)
            return v

    def put(self, key, value):
        with self._lock:
            self._d[key] = value
            self._d.move_to_end(key)
            while len(self._d) > self.max_items:
                self._d.popitem(last=False)


class Recording:
    def __init__(self, root: str):
        self.root = os.path.abspath(root)
        self.cache = ChunkCache()
        self._dims: dict[str, Dimension] = {}
        self._lock = threading.Lock()

    def dimensions(self) -> list[Dimension]:
        """All servers/dimensions with a region/ folder. Newly created ones are detected."""
        found: dict[str, tuple[str, str, str]] = {}
        legacy = os.path.join(self.root, "region")
        if os.path.isdir(legacy):
            found[f"{LEGACY_SERVER}/overworld"] = (LEGACY_SERVER, "overworld", legacy)
        try:
            servers = sorted(os.listdir(self.root))
        except FileNotFoundError:
            servers = []
        for s in servers:
            sdir = os.path.join(self.root, s)
            if s == "region" or s.startswith(".") or not os.path.isdir(sdir):
                continue
            for key, sub in DIMENSIONS.items():
                rdir = os.path.join(sdir, sub, "region") if sub else os.path.join(sdir, "region")
                if os.path.isdir(rdir):
                    found[f"{s}/{key}"] = (s, key, rdir)
            custom = os.path.join(sdir, "dimensions")
            if os.path.isdir(custom):
                for ns in os.listdir(custom):
                    for path in os.listdir(os.path.join(custom, ns)):
                        rdir = os.path.join(custom, ns, path, "region")
                        if os.path.isdir(rdir):
                            key = f"{ns}:{path}"
                            found[f"{s}/{key}"] = (s, key, rdir)
        with self._lock:
            for did, (s, key, rdir) in found.items():
                if did not in self._dims:
                    self._dims[did] = Dimension(s, key, rdir)
            for did in list(self._dims):
                if did not in found:
                    del self._dims[did]
            return list(self._dims.values())

    def dimension(self, server: str, key: str) -> Dimension | None:
        did = f"{server}/{key}"
        d = self._dims.get(did)
        if d is None:
            self.dimensions()
            d = self._dims.get(did)
        return d

    def raw_chunk(self, dim: Dimension, cx: int, cz: int, typed: bool = True) -> dict | None:
        """Uncached chunk NBT (for exporting the block entities)."""
        region = dim.region(cx >> 5, cz >> 5)
        return region.read_nbt((cx & 31) | ((cz & 31) << 5), typed) if region else None

    def chunk(self, dim: Dimension, cx: int, cz: int) -> Chunk | None:
        region = dim.region(cx >> 5, cz >> 5)
        if region is None:
            return None
        h = region.header()
        if h is None:
            return None
        idx = (cx & 31) | ((cz & 31) << 5)
        if h.offsets[idx] == 0:
            return None
        key = (region.path, idx, int(h.timestamps[idx]), int(h.offsets[idx]))
        ch = self.cache.get(key)
        if ch is None:
            root = region.read_nbt(idx)
            if root is None:
                return None
            ch = from_nbt(root, cx, cz)
            if ch is None:
                return None
            self.cache.put(key, ch)
        return ch
