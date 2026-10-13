"""Creates a small synthetic recording for tests and demos.

    python -m viewer.tests.make_world /tmp/demo_recording

Creates ``demo.server/`` with an overworld (hills, water, trees, a house with a
sign) and a Nether (ceiling of bedrock/netherrack, lava lake).
"""

from __future__ import annotations

import math
import os
import struct
import sys
import time
import zlib

import numpy as np

from viewer.core import nbt


def pack(indices: np.ndarray, bits: int) -> list[int]:
    per_long = 64 // bits
    n = (len(indices) + per_long - 1) // per_long
    out = np.zeros(n, dtype=np.uint64)
    for i, v in enumerate(indices.astype(np.uint64)):
        out[i // per_long] |= v << np.uint64((i % per_long) * bits)
    return [int(x) for x in out]


def section_nbt(y: int, states: list[str], idx: np.ndarray, biome: str) -> dict:
    pal = []
    for s in states:
        name, _, rest = s.partition("[")
        e = {"Name": name}
        if rest:
            e["Properties"] = dict(kv.split("=") for kv in rest[:-1].split(","))
        pal.append(e)
    bs = {"palette": pal}
    if len(pal) > 1:
        bs["data"] = nbt.LongArray(pack(idx.reshape(-1), max(4, (len(pal) - 1).bit_length())))
    return {"Y": nbt.Byte(y), "block_states": bs, "biomes": {"palette": [biome]}}


def chunk_nbt(cx: int, cz: int, column, biome: str, min_sy=-4, max_sy=19, extra=None) -> dict:
    """column(x, y, z) -> state; called per block (keep it small)."""
    sections = []
    solid = np.zeros(((max_sy - min_sy + 1) * 16, 16, 16), bool)
    for sy in range(min_sy, max_sy + 1):
        states = ["minecraft:air"]
        lookup = {"minecraft:air": 0}
        idx = np.zeros((16, 16, 16), dtype=np.int64)
        for ly in range(16):
            y = sy * 16 + ly
            for z in range(16):
                for x in range(16):
                    s = column(cx * 16 + x, y, cz * 16 + z)
                    if s != "minecraft:air":
                        solid[(sy - min_sy) * 16 + ly, z, x] = True
                        i = lookup.get(s)
                        if i is None:
                            i = lookup[s] = len(states)
                            states.append(s)
                        idx[ly, z, x] = i
        sections.append(section_nbt(sy, states, idx, biome))
    # Light as from the mod: sky light 15 above the top block, 0 below; no block light
    sky = np.where(np.cumsum(solid[::-1], 0)[::-1] > 0, 0, 15).astype(np.uint8)
    for i, sec in enumerate(sections):
        v = sky[i * 16:(i + 1) * 16].reshape(-1)
        sec["SkyLight"] = nbt.ByteArray((v[0::2] | (v[1::2] << 4)).astype(np.uint8).tobytes())
        sec["BlockLight"] = nbt.ByteArray(bytes(2048))
    root = {
        "DataVersion": nbt.Int(4671), "xPos": nbt.Int(cx), "yPos": nbt.Int(min_sy), "zPos": nbt.Int(cz),
        "Status": "minecraft:full", "sections": sections,
        "block_entities": extra or [],
    }
    return root


def write_region(path: str, chunks: dict[tuple[int, int], dict]):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    header = bytearray(8192)
    body = bytearray()
    sector = 2
    now = int(time.time())
    for (cx, cz), root in chunks.items():
        data = zlib.compress(nbt.write(root))
        blob = struct.pack(">IB", len(data) + 1, 2) + data
        n = (len(blob) + 4095) // 4096
        blob += b"\0" * (n * 4096 - len(blob))
        idx = (cx & 31) | ((cz & 31) << 5)
        struct.pack_into(">I", header, idx * 4, (sector << 8) | n)
        struct.pack_into(">I", header, 4096 + idx * 4, now)
        body += blob
        sector += n
    with open(path, "wb") as f:
        f.write(header + body)


# Showcase with special blocks on a floating platform (x 32..63, z 32..47, y 100).
SHOWCASE = [
    "minecraft:oak_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]",
    "minecraft:oak_stairs[facing=east,half=bottom,shape=straight,waterlogged=false]",
    "minecraft:oak_stairs[facing=south,half=top,shape=straight,waterlogged=false]",
    "minecraft:stone_slab[type=bottom,waterlogged=false]",
    "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]",
    "minecraft:oak_fence[east=true,north=false,south=false,waterlogged=false,west=true]",
    "minecraft:glass_pane[east=true,north=false,south=false,waterlogged=false,west=true]",
    "minecraft:torch",
    "minecraft:wall_torch[facing=north]",
    "minecraft:lantern[hanging=false,waterlogged=false]",
    "minecraft:chest[facing=south,type=single,waterlogged=false]",
    "minecraft:oak_door[facing=south,half=lower,hinge=left,open=false,powered=false]",
    "minecraft:poppy",
    "minecraft:short_grass",
    "minecraft:hopper[enabled=true,facing=down]",
    "minecraft:anvil[facing=north]",
    "minecraft:cobblestone_wall[east=tall,north=none,south=none,up=true,waterlogged=false,west=tall]",
    "minecraft:white_bed[facing=south,occupied=false,part=foot]",
    "minecraft:glass",
    "minecraft:red_stained_glass",
    "minecraft:oak_sign[rotation=0,waterlogged=false]",
    "minecraft:shulker_box[facing=up]",
    "minecraft:campfire[facing=north,lit=true,signal_fire=false,waterlogged=false]",
    "minecraft:oak_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]",
    "minecraft:crafting_table",
    "minecraft:furnace[facing=south,lit=false]",
    "minecraft:chest[facing=south,type=right,waterlogged=false]",
    "minecraft:chest[facing=south,type=left,waterlogged=false]",
    "minecraft:ender_chest[facing=south,waterlogged=false]",
    "minecraft:anvil[facing=east]",
    "minecraft:white_bed[facing=south,occupied=false,part=head]",
    "minecraft:observer[facing=up,powered=false]",
]


# Composite blocks (double chest, bed) on the platform, (x, z) -> block
PAIRS = {
    (40, 44): "minecraft:red_bed[facing=south,occupied=false,part=foot]",
    (40, 45): "minecraft:red_bed[facing=south,occupied=false,part=head]",
    (44, 44): "minecraft:chest[facing=north,type=left,waterlogged=false]",
    (45, 44): "minecraft:chest[facing=north,type=right,waterlogged=false]",
    (47, 44): "minecraft:trapped_chest[facing=west,type=single,waterlogged=false]",
    (49, 44): "minecraft:red_shulker_box[facing=up]",
    (51, 44): "minecraft:blue_shulker_box[facing=north]",
    (53, 44): "minecraft:light_blue_bed[facing=east,occupied=false,part=foot]",
    (54, 44): "minecraft:light_blue_bed[facing=east,occupied=false,part=head]",
}


def overworld(x: int, y: int, z: int) -> str:
    if 32 <= x < 64 and 32 <= z < 48 and y in (100, 101):
        if y == 100:
            return "minecraft:smooth_stone"
        if (x, z) in PAIRS:
            return PAIRS[(x, z)]
        if z == 40 and (x - 32) % 1 == 0 and x - 32 < len(SHOWCASE):
            return SHOWCASE[x - 32]
        if z == 42 and x - 32 < len(SHOWCASE) and x % 2 == 0:
            return SHOWCASE[(x - 32) // 2 + 12] if (x - 32) // 2 + 12 < len(SHOWCASE) else "minecraft:air"
        return "minecraft:air"
    h = int(70 + 8 * math.sin(x / 23.0) + 6 * math.cos(z / 17.0))
    if y < -60:
        return "minecraft:bedrock" if y == -64 else "minecraft:deepslate"
    if y < h - 4:
        return "minecraft:stone"
    # House at (8..14, 8..14)
    if 8 <= x <= 14 and 8 <= z <= 14 and h <= y <= h + 4:
        edge = x in (8, 14) or z in (8, 14)
        if y == h + 4:
            return "minecraft:oak_planks"
        return "minecraft:cobblestone" if edge else "minecraft:air"
    if y < h:
        return "minecraft:dirt"
    if y == h:
        return "minecraft:sand" if h < 66 else "minecraft:grass_block[snowy=false]"
    if y <= 64:
        return "minecraft:water[level=0]"
    # Trees in a grid
    if x % 11 == 3 and z % 13 == 5 and h > 66 and y <= h + 4:
        return "minecraft:oak_log[axis=y]"
    if (x - 3) % 11 in (0, 1, 10) and (z - 5) % 13 in (0, 1, 12) and h > 66 and h + 3 <= y <= h + 5:
        return "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]"
    return "minecraft:air"


def nether(x: int, y: int, z: int) -> str:
    if y in (0, 127):
        return "minecraft:bedrock"
    if y < 0 or y > 127:
        return "minecraft:air"
    floor = int(40 + 6 * math.sin(x / 9.0) * math.cos(z / 11.0))
    roof = 110 + int(4 * math.sin((x + z) / 7.0))
    if y <= floor or y >= roof:
        return "minecraft:netherrack"
    if y <= 31:
        return "minecraft:lava[level=0]"
    return "minecraft:air"


def main(root: str, size: int = 4):
    srv = os.path.join(root, "demo.server")
    sign = [{"id": "minecraft:sign", "x": nbt.Int(10), "y": nbt.Int(75), "z": nbt.Int(7),
             "front_text": {"messages": ['"Willkommen"', '"auf der"', '"Demo-Base"', '""']}}]
    ow = {}
    for cx in range(-size, size):
        for cz in range(-size, size):
            ow[(cx, cz)] = chunk_nbt(cx, cz, overworld, "minecraft:plains",
                                     extra=sign if (cx, cz) == (0, 0) else None)
    by_region: dict = {}
    for (cx, cz), r in ow.items():
        by_region.setdefault((cx >> 5, cz >> 5), {})[(cx, cz)] = r
    for (rx, rz), chs in by_region.items():
        write_region(os.path.join(srv, "region", f"r.{rx}.{rz}.mca"), chs)

    ne = {}
    for cx in range(-2, 2):
        for cz in range(-2, 2):
            ne[(cx, cz)] = chunk_nbt(cx, cz, nether, "minecraft:nether_wastes", min_sy=0, max_sy=7)
    by_region = {}
    for (cx, cz), r in ne.items():
        by_region.setdefault((cx >> 5, cz >> 5), {})[(cx, cz)] = r
    for (rx, rz), chs in by_region.items():
        write_region(os.path.join(srv, "DIM-1", "region", f"r.{rx}.{rz}.mca"), chs)
    print(f"Demo-Aufnahme geschrieben: {srv}")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "demo_recording")
