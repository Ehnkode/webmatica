"""Chunk decoding: vanilla chunk NBT -> numpy arrays.

Block states and biomes get process-wide unique integer IDs
(:data:`BLOCKS`, :data:`BIOMES`). This lets colors, shapes etc. be applied as
lookup tables (``lut[ids]``) over whole chunks without a Python loop.
"""

from __future__ import annotations

import re
import threading
from dataclasses import dataclass, field

import numpy as np

AIR_NAMES = frozenset({
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
    "minecraft:structure_void", "minecraft:light", "minecraft:barrier",
})
# Liquids and plants a placed block simply replaces: never "in the way"
REPLACEABLE = re.compile(r"^minecraft:(water|lava|bubble_column|short_grass|tall_grass|fern|large_fern|dead_bush|snow|"
                         r"seagrass|tall_seagrass|kelp|kelp_plant|vine|glow_lichen)$")


class Registry:
    """Thread-safe string -> int interner."""

    def __init__(self, first: str):
        self._lock = threading.Lock()
        self._ids: dict[str, int] = {}
        self.names: list[str] = []
        self.id(first)

    def id(self, key: str) -> int:
        i = self._ids.get(key)
        if i is None:
            with self._lock:
                i = self._ids.get(key)
                if i is None:
                    i = len(self.names)
                    self.names.append(key)
                    self._ids[key] = i
        return i

    def __len__(self):
        return len(self.names)


#: Block states as "minecraft:oak_stairs[facing=north,half=bottom]". ID 0 = air.
BLOCKS = Registry("minecraft:air")
#: Biomes as "minecraft:plains". ID 0 = plains (vanilla default).
BIOMES = Registry("minecraft:plains")


def state_key(entry) -> str:
    # Classic {Name, Properties}; since 26.1 vanilla also writes a bare name (default
    # state), {id, properties}, and {"": name} inside mixed lists.
    if isinstance(entry, str):
        return entry
    if "" in entry:
        return state_key(entry[""])
    name = entry.get("Name") or entry.get("id") or "minecraft:air"
    props = entry.get("Properties") or entry.get("properties")
    if not props:
        return name
    return name + "[" + ",".join(f"{k}={props[k]}" for k in sorted(props)) + "]"


def base_name(state: str) -> str:
    i = state.find("[")
    return state if i < 0 else state[:i]


def state_props(state: str) -> dict:
    i = state.find("[")
    if i < 0:
        return {}
    out = {}
    for kv in state[i + 1:-1].split(","):
        k, _, v = kv.partition("=")
        out[k] = v
    return out


def unpack(data: np.ndarray, bits: int, count: int) -> np.ndarray:
    """Vanilla bit packing (since 1.16: values don't span longs)."""
    if bits == 0 or data is None or len(data) == 0:
        return np.zeros(count, dtype=np.int32)
    per_long = 64 // bits
    shifts = np.arange(per_long, dtype=np.uint64) * np.uint64(bits)
    vals = (data[:, None] >> shifts[None, :]) & np.uint64((1 << bits) - 1)
    flat = vals.reshape(-1)[:count].astype(np.int32)
    if flat.size < count:
        flat = np.concatenate([flat, np.zeros(count - flat.size, dtype=np.int32)])
    return flat


@dataclass
class Chunk:
    cx: int
    cz: int
    min_y: int                      # lowest block Y (e.g. -64)
    blocks: np.ndarray              # uint16 (H, 16, 16) [y, z, x], global BLOCKS IDs
    biomes: np.ndarray              # uint16 (H/4, 4, 4) [y, z, x], global BIOMES IDs
    block_entities: list = field(default_factory=list)
    #: Light per section (index from the bottom): (BlockLight, SkyLight) as raw 2048 bytes or None
    light_raw: dict = field(default_factory=dict)

    @property
    def height(self) -> int:
        return self.blocks.shape[0]

    def light(self) -> tuple[np.ndarray, np.ndarray] | None:
        """(block light, sky light) uint8 (H, 16, 16) [y, z, x], or None without light data.

        Sections without sky light data count as fully lit (as vanilla does
        under open sky), those without block light data as dark.
        """
        if not self.light_raw:
            return None
        bl = np.zeros(self.blocks.shape, np.uint8)
        sl = np.full(self.blocks.shape, 15, np.uint8)
        for i, (b, k) in self.light_raw.items():
            if b is not None:
                bl[i * 16:(i + 1) * 16] = _nibbles(b)
            if k is not None:
                sl[i * 16:(i + 1) * 16] = _nibbles(k)
        return bl, sl

    def block(self, x: int, y: int, z: int) -> str:
        ly = y - self.min_y
        if not 0 <= ly < self.height:
            return "minecraft:air"
        return BLOCKS.names[self.blocks[ly, z & 15, x & 15]]


def _raw(arr) -> bytes | None:
    if arr is None or len(arr) != 2048:
        return None
    return np.asarray(arr).tobytes() if isinstance(arr, np.ndarray) else bytes(arr)


def _nibbles(raw: bytes) -> np.ndarray:
    """2048 bytes of vanilla light (4 bits per block, index y<<8 | z<<4 | x) -> (16, 16, 16)."""
    b = np.frombuffer(raw, np.uint8)
    out = np.empty(4096, np.uint8)
    out[0::2] = b & 15
    out[1::2] = b >> 4
    return out.reshape(16, 16, 16)


def from_nbt(root: dict, cx: int | None = None, cz: int | None = None) -> Chunk | None:
    sections = root.get("sections") or []
    ys = [s["Y"] for s in sections if "Y" in s]
    if not ys:
        return None
    lo, hi = min(ys), max(ys)
    n = hi - lo + 1
    blocks = np.zeros((n * 16, 16, 16), dtype=np.uint16)
    biomes = np.zeros((n * 4, 4, 4), dtype=np.uint16)
    light_raw: dict = {}

    for s in sections:
        if "Y" not in s:
            continue
        i = s["Y"] - lo
        bs = s.get("block_states")
        if bs:
            pal = bs.get("palette") or []
            if pal:
                lut = np.fromiter((BLOCKS.id(state_key(p)) for p in pal), dtype=np.int32, count=len(pal))
                bits = 0 if len(pal) == 1 else max(4, (len(pal) - 1).bit_length())
                idx = unpack(bs.get("data"), bits, 4096)
                np.clip(idx, 0, len(pal) - 1, out=idx)
                blocks[i * 16:(i + 1) * 16] = lut[idx].reshape(16, 16, 16)
        bl, sl = _raw(s.get("BlockLight")), _raw(s.get("SkyLight"))
        if bl is not None or sl is not None:
            light_raw[i] = (bl, sl)
        bm = s.get("biomes")
        if bm:
            pal = bm.get("palette") or []
            if pal:
                lut = np.fromiter((BIOMES.id(p if isinstance(p, str) else p.get("", "")) for p in pal), dtype=np.int32, count=len(pal))
                bits = 0 if len(pal) == 1 else (len(pal) - 1).bit_length()
                idx = unpack(bm.get("data"), bits, 64)
                np.clip(idx, 0, len(pal) - 1, out=idx)
                biomes[i * 4:(i + 1) * 4] = lut[idx].reshape(4, 4, 4)

    return Chunk(
        cx=root.get("xPos", cx) if cx is None else cx,
        cz=root.get("zPos", cz) if cz is None else cz,
        min_y=lo * 16,
        blocks=blocks,
        biomes=biomes,
        block_entities=root.get("block_entities") or [],
        light_raw=light_raw,
    )
