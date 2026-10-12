"""Bereich der Aufnahme -> Sponge Schematic v3 (.schem).

Laesst sich mit WorldEdit, FAWE, Litematica (Import) und Axiom laden. Kisten,
Schilder usw. werden mit ihrem Inhalt uebernommen, soweit der Client ihn
gesehen hat (2b2t schickt Kisteninhalte nur nach dem Oeffnen).
"""

from __future__ import annotations

import gzip
import time

import numpy as np

from . import nbt
from .chunk import BLOCKS
from .world import Dimension, Recording

MAX_VOLUME = 64 * 1024 * 1024        # Bloecke; groesser wird die Datei unhandlich
_BE_SKIP = {"x", "y", "z", "id", "keepPacked"}


def _varints(values: np.ndarray) -> bytes:
    """Palette-Indizes als VarInt-Folge (Sponge-Format), vektorisiert."""
    v = values.astype(np.uint32)
    if not len(v):
        return b""
    if v.max() < 128:
        return v.astype(np.uint8).tobytes()
    nbytes = np.ones(len(v), np.int64)
    for k in (7, 14, 21, 28):
        nbytes += v >= (1 << k)
    out = np.zeros(int(nbytes.sum()), np.uint8)
    pos = np.concatenate([[0], np.cumsum(nbytes)[:-1]])
    rest = v.copy()
    for k in range(5):
        live = nbytes > k
        more = nbytes > k + 1
        out[pos[live] + k] = (rest[live] & 0x7F) | np.where(more[live], 0x80, 0)
        rest >>= 7
    return out.tobytes()


def export(rec: Recording, dim: Dimension, x0: int, y0: int, z0: int, x1: int, y1: int, z1: int,
           name: str = "Auswahl") -> tuple[bytes, dict]:
    """Ecken inklusive, beliebige Reihenfolge. Liefert (gzip-Bytes, Infos)."""
    x0, x1 = sorted((x0, x1))
    y0, y1 = sorted((y0, y1))
    z0, z1 = sorted((z0, z1))
    W, H, Ln = x1 - x0 + 1, y1 - y0 + 1, z1 - z0 + 1
    if W * H * Ln > MAX_VOLUME:
        raise ValueError(f"Bereich zu gross ({W}x{H}x{Ln}), hoechstens {MAX_VOLUME:,} Bloecke")
    if max(W, H, Ln) > 32767:
        raise ValueError("eine Kante ist laenger als 32767 Bloecke")

    ids = np.zeros((H, Ln, W), np.int64)            # [y, z, x], globale Block-IDs, 0 = Luft
    known = np.zeros((Ln, W), bool)                 # Spalten aus aufgezeichneten Chunks
    block_entities = []
    data_version = None

    for cz in range(z0 >> 4, (z1 >> 4) + 1):
        for cx in range(x0 >> 4, (x1 >> 4) + 1):
            ch = rec.chunk(dim, cx, cz)
            if ch is None:
                continue
            # Ueberlappung Chunk <-> Auswahl in Weltkoordinaten
            ax, bx = max(x0, cx * 16), min(x1, cx * 16 + 15)
            az, bz = max(z0, cz * 16), min(z1, cz * 16 + 15)
            ay, by = max(y0, ch.min_y), min(y1, ch.min_y + ch.height - 1)
            known[az - z0:bz - z0 + 1, ax - x0:bx - x0 + 1] = True
            if ay <= by:
                ids[ay - y0:by - y0 + 1, az - z0:bz - z0 + 1, ax - x0:bx - x0 + 1] = \
                    ch.blocks[ay - ch.min_y:by - ch.min_y + 1, az & 15:(bz & 15) + 1, ax & 15:(bx & 15) + 1]
            if ch.block_entities or data_version is None:
                raw = rec.raw_chunk(dim, cx, cz) or {}
                data_version = data_version or raw.get("DataVersion")
                for be in raw.get("block_entities") or []:
                    try:
                        bx_, by_, bz_ = int(be["x"]), int(be["y"]), int(be["z"])
                    except (KeyError, TypeError, ValueError):
                        continue
                    if x0 <= bx_ <= x1 and y0 <= by_ <= y1 and z0 <= bz_ <= z1:
                        block_entities.append({
                            "Pos": nbt.IntArray([bx_ - x0, by_ - y0, bz_ - z0]),
                            "Id": str(be.get("id", "")),
                            "Data": {k: v for k, v in be.items() if k not in _BE_SKIP},
                        })

    if not known.any():
        raise LookupError("in diesem Bereich wurde nichts aufgezeichnet")

    data = write(ids, name, block_entities, data_version, origin=(x0, y0, z0))
    info = {
        "size": [W, H, Ln],
        "blocks": int((ids != 0).sum()),
        "palette": int(len(np.unique(ids))),
        "blockEntities": len(block_entities),
        "coverage": round(float(known.mean()), 3),
    }
    return data, info


def write(ids: np.ndarray, name: str, block_entities: list | None = None, data_version: int | None = None,
          origin: tuple | None = None) -> bytes:
    """Block-IDs (H, L, W) [y, z, x] als Sponge Schematic v3 (gzip-Bytes).

    ``block_entities`` im Sponge-Format ({"Pos": IntArray, "Id": str, "Data": {...}}).
    """
    H, Ln, W = ids.shape
    used, inv = np.unique(ids, return_inverse=True)
    if used[0] != 0:                      # Luft immer Index 0
        used = np.concatenate([[0], used])
        inv = inv + 1
    palette = {BLOCKS.names[int(sid)]: nbt.Int(i) for i, sid in enumerate(used)}
    meta = {"Name": name, "Author": "Webmatica", "Date": nbt.Long(int(time.time() * 1000))}
    if origin is not None:
        meta["WorldOrigin"] = nbt.IntArray(list(origin))
    schem = {
        "Version": nbt.Int(3),
        "DataVersion": nbt.Int(int(data_version or 4671)),
        "Metadata": meta,
        "Width": nbt.Short(W), "Height": nbt.Short(H), "Length": nbt.Short(Ln),
        "Offset": nbt.IntArray([0, 0, 0]),
        "Blocks": {
            "Palette": palette,
            "Data": nbt.ByteArray(_varints(inv.reshape(-1))),
            "BlockEntities": list(block_entities or []),
        },
    }
    return gzip.compress(nbt.write({"Schematic": schem}), 6)

