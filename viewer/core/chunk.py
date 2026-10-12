"""Chunk-Dekodierung: Vanilla-Chunk-NBT -> numpy-Arrays.

Block-States und Biome bekommen prozessweit eindeutige Ganzzahl-IDs
(:data:`BLOCKS`, :data:`BIOMES`). Damit lassen sich Farben, Formen usw. als
Lookup-Tabellen (``lut[ids]``) ueber ganze Chunks anwenden, ohne Python-Schleife.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field

import numpy as np

AIR_NAMES = frozenset({
    "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
    "minecraft:structure_void", "minecraft:light", "minecraft:barrier",
})


class Registry:
    """Thread-sicherer String -> int Interner."""

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


#: Block-States als "minecraft:oak_stairs[facing=north,half=bottom]". ID 0 = Luft.
BLOCKS = Registry("minecraft:air")
#: Biome als "minecraft:plains". ID 0 = plains (Vanilla-Default).
BIOMES = Registry("minecraft:plains")


def state_key(entry: dict) -> str:
    name = entry.get("Name", "minecraft:air")
    props = entry.get("Properties")
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
    """Vanilla-Bitpacking (seit 1.16: Werte ueberspannen keine Longs)."""
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
    min_y: int                      # unterste Block-Y (z. B. -64)
    blocks: np.ndarray              # uint16 (H, 16, 16) [y, z, x], globale BLOCKS-IDs
    biomes: np.ndarray              # uint16 (H/4, 4, 4) [y, z, x], globale BIOMES-IDs
    block_entities: list = field(default_factory=list)

    @property
    def height(self) -> int:
        return self.blocks.shape[0]

    def block(self, x: int, y: int, z: int) -> str:
        ly = y - self.min_y
        if not 0 <= ly < self.height:
            return "minecraft:air"
        return BLOCKS.names[self.blocks[ly, z & 15, x & 15]]


def from_nbt(root: dict, cx: int | None = None, cz: int | None = None) -> Chunk | None:
    sections = root.get("sections") or []
    ys = [s["Y"] for s in sections if "Y" in s]
    if not ys:
        return None
    lo, hi = min(ys), max(ys)
    n = hi - lo + 1
    blocks = np.zeros((n * 16, 16, 16), dtype=np.uint16)
    biomes = np.zeros((n * 4, 4, 4), dtype=np.uint16)

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
        bm = s.get("biomes")
        if bm:
            pal = bm.get("palette") or []
            if pal:
                lut = np.fromiter((BIOMES.id(p) for p in pal), dtype=np.int32, count=len(pal))
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
    )
