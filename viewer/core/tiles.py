"""Kartenkacheln.

Zoomstufe 0: eine Kachel = eine Region (512 x 512 Bloecke, 1 Pixel pro Block).
Zoomstufe -k: eine Kachel fasst 2^k x 2^k Regionen zusammen (aus den vier
Kindkacheln verkleinert). Hineinzoomen ueber 0 hinaus vergroessert der Browser.

Alles wird unter ``cache_dir`` auf Platte gehalten. Fuer Zoomstufe 0 liegt
neben dem PNG ein .npz mit den Chunk-Zeitstempeln der Region; aendern sich
einzelne Chunks, werden nur diese neu berechnet.
"""

from __future__ import annotations

import io
import json
import os
import threading
from dataclasses import dataclass

import numpy as np
from PIL import Image

from .chunk import Chunk
from .colors import PALETTE, TINT_FOLIAGE, TINT_GRASS, TINT_WATER
from .markers import chunk_markers
from .world import Dimension, Recording

TILE = 512


@dataclass(frozen=True)
class Layer:
    """Welche Schicht gezeigt wird.

    mode="surface": oberster Block unterhalb von ``y_max``.
    mode="cave": ab ``y_max`` abwaerts erst die Decke ueberspringen, dann den
    ersten Block darunter zeigen (Nether unter dem Bedrock-Dach).
    """
    mode: str = "surface"
    y_max: int = 320

    @property
    def key(self) -> str:
        return f"{self.mode}{self.y_max}"

    @staticmethod
    def parse(s: str) -> "Layer":
        for mode in ("surface", "cave"):
            if s.startswith(mode):
                try:
                    return Layer(mode, int(s[len(mode):]))
                except ValueError:
                    break
        return Layer()


def render_chunk(ch: Chunk, layer: Layer):
    """-> (rgb float32 (16,16,3), height int32 (16,16), has bool (16,16))."""
    PALETTE.sync()
    top_rel = min(ch.height - 1, layer.y_max - ch.min_y)
    if top_rel < 0:
        z = np.zeros((16, 16), np.int32)
        return np.zeros((16, 16, 3), np.float32), z, z.astype(bool)
    ids = ch.blocks[:top_rel + 1][::-1].astype(np.intp)   # Index 0 = oberste Schicht
    solid = ~PALETTE.block_empty[ids]
    rows = np.arange(ids.shape[0])[:, None, None]

    if layer.mode == "cave":
        # Erste Luft unter der Decke suchen; Spalten ohne Luft bleiben leer.
        air = ~solid
        has_air = air.any(0)
        first_air = np.argmax(air, 0)
        solid = solid & (rows >= first_air[None])
        solid &= has_air[None]

    has = solid.any(0)
    top = np.argmax(solid, 0)                                # (16,16) Zeile im ids-Stack
    zz, xx = np.meshgrid(np.arange(16), np.arange(16), indexing="ij")
    top_id = ids[top, zz, xx]

    # Durchscheinendes (Wasser, Glas, Pflanzen): mit dem ersten deckenden Block darunter mischen.
    alpha_top = PALETTE.block_alpha[top_id]
    opaque = solid & (PALETTE.block_alpha[ids] >= 0.99) & ~PALETTE.block_water[ids] & (rows > top[None])
    has_under = opaque.any(0)
    under = np.where(has_under, np.argmax(opaque, 0), top)
    under_id = ids[under, zz, xx]

    y_top = ch.min_y + top_rel - top
    y_under = ch.min_y + top_rel - under
    biome = ch.biomes[np.clip((y_top - ch.min_y) >> 2, 0, ch.biomes.shape[0] - 1), zz >> 2, xx >> 2]
    biome_rgb = PALETTE.biome_rgb[biome.astype(np.intp)]     # (16,16,3,3)

    def tinted(bid):
        rgb = PALETTE.block_rgb[bid].copy()
        t = PALETTE.block_tint[bid]
        for kind, slot in ((TINT_GRASS, 0), (TINT_FOLIAGE, 1), (TINT_WATER, 2)):
            m = t == kind
            if m.any():
                rgb[m] = rgb[m] * biome_rgb[m][:, slot] / 255.0
        return rgb

    c_top = tinted(top_id)
    c_under = tinted(under_id)
    is_water = PALETTE.block_water[top_id]
    depth = np.maximum(0, y_top - y_under)
    a = np.where(is_water, np.clip(0.55 + depth * 0.04, 0.55, 0.92), alpha_top)
    a = np.where(has_under | is_water, a, 1.0)[..., None]
    rgb = c_top * a + c_under * (1 - a)
    return rgb.astype(np.float32), y_top.astype(np.int32), has


def shade(rgb: np.ndarray, height: np.ndarray, has: np.ndarray, water: np.ndarray | None = None) -> np.ndarray:
    """Hoehenschattierung wie auf Vanilla-Karten: heller, wenn hoeher als der Nachbar im Norden."""
    north = np.vstack([height[:1], height[:-1]])
    north_has = np.vstack([has[:1], has[:-1]])
    north = np.where(north_has, north, height)
    dh = np.clip(height - north, -8, 8).astype(np.float32)
    f = 1.0 + dh * 0.045
    if water is not None:
        f = np.where(water, 1.0, f)
    out = np.clip(rgb * f[..., None], 0, 255).astype(np.uint8)
    alpha = np.where(has, 255, 0).astype(np.uint8)
    return np.dstack([out, alpha])


def _paint(ch: Chunk, layer: Layer, sl, rgb, height, has, water):
    """Einen Chunk in die Arrays einer Region-Kachel malen."""
    c_rgb, c_h, c_has = render_chunk(ch, layer)
    rgb[sl], height[sl], has[sl] = c_rgb, c_h, c_has
    water[sl] = False  # Wasser flach halten: unten ueber Farbe erkannt
    top_rel = min(ch.height - 1, layer.y_max - ch.min_y)
    if top_rel >= 0:
        yi = np.clip(c_h - ch.min_y, 0, ch.height - 1)
        zz, xx = np.meshgrid(np.arange(16), np.arange(16), indexing="ij")
        water[sl] = PALETTE.block_water[ch.blocks[yi, zz, xx].astype(np.intp)] & c_has


class TileStore:
    def __init__(self, rec: Recording, cache_dir: str):
        self.rec = rec
        self.cache_dir = cache_dir
        self._locks: dict[str, threading.Lock] = {}
        self._locks_lock = threading.Lock()

    def _lock(self, key: str) -> threading.Lock:
        with self._locks_lock:
            lk = self._locks.get(key)
            if lk is None:
                lk = self._locks[key] = threading.Lock()
            return lk

    def _dir(self, dim: Dimension, layer: Layer, zoom: int) -> str:
        server = dim.server.replace("/", "_")
        d = os.path.join(self.cache_dir, server, dim.key.replace(":", "_"), layer.key, str(zoom))
        os.makedirs(d, exist_ok=True)
        return d

    # ------------------------------------------------------------ Zoomstufe 0

    def region_tile(self, dim: Dimension, layer: Layer, rx: int, rz: int) -> str | None:
        """Pfad zum PNG einer Region (aktualisiert bei Bedarf), None wenn leer."""
        region = dim.region(rx, rz)
        if region is None:
            return None
        h = region.header()
        if h is None or not h.offsets.any():
            return None
        d = self._dir(dim, layer, 0)
        png = os.path.join(d, f"{rx}_{rz}.png")
        npz = os.path.join(d, f"{rx}_{rz}.npz")
        stamp = h.timestamps * (h.offsets > 0) + h.offsets   # aendert sich bei jedem Neuschreiben

        with self._lock(png):
            old = None
            if os.path.exists(npz) and os.path.exists(png):
                try:
                    with np.load(npz) as z:
                        old = {k: z[k] for k in z.files}
                except (OSError, ValueError):
                    old = None
                if old is not None and np.array_equal(old["stamp"], stamp):
                    return png

            if old is None:
                rgb = np.zeros((TILE, TILE, 3), np.float32)
                height = np.full((TILE, TILE), -64, np.int32)
                has = np.zeros((TILE, TILE), bool)
                water = np.zeros((TILE, TILE), bool)
                todo = np.nonzero(h.offsets)[0]
                old_stamp = np.full(1024, -1, np.int64)
            else:
                rgb, height, has, water = old["rgb"].astype(np.float32), old["height"], old["has"], old["water"]
                old_stamp = old["stamp"]
                todo = np.nonzero(stamp != old_stamp)[0]

            mpath = os.path.join(d, f"{rx}_{rz}.markers.json")
            markers = {}
            if old is not None and os.path.exists(mpath):
                try:
                    with open(mpath) as f:
                        markers = json.load(f)
                except (OSError, ValueError):
                    markers = {}
            for idx in todo:
                markers.pop(str(int(idx)), None)
                lx, lz = int(idx) & 31, int(idx) >> 5
                sl = (slice(lz * 16, lz * 16 + 16), slice(lx * 16, lx * 16 + 16))
                ch = None
                if h.offsets[idx]:
                    try:
                        ch = self.rec.chunk(dim, rx * 32 + lx, rz * 32 + lz)
                    except Exception as e:  # kaputter Chunk darf die Kachel nicht verhindern
                        print(f"Chunk {rx * 32 + lx},{rz * 32 + lz} defekt: {e}")
                if ch is None:
                    has[sl] = False
                    continue
                m = chunk_markers(ch)
                if m["signs"] or m["containers"]:
                    markers[str(int(idx))] = m
                _paint(ch, layer, sl, rgb, height, has, water)

            with open(mpath + ".tmp", "w") as f:
                json.dump(markers, f)
            os.replace(mpath + ".tmp", mpath)
            img = shade(rgb, height, has, water)
            tmp = png + ".tmp"
            Image.fromarray(img, "RGBA").save(tmp, "PNG", compress_level=3)
            os.replace(tmp, png)
            np.savez(npz + ".tmp.npz", rgb=rgb.astype(np.float16), height=height, has=has,
                     water=water, stamp=stamp)
            os.replace(npz + ".tmp.npz", npz)
            return png

    def markers(self, dim: Dimension, layer: Layer) -> dict:
        """Schilder und Behaelter aller bereits gerenderten Regionen."""
        d = self._dir(dim, layer, 0)
        signs, stashes = [], []
        for r in dim.regions():
            p = os.path.join(d, f"{r.rx}_{r.rz}.markers.json")
            try:
                with open(p) as f:
                    data = json.load(f)
            except (OSError, ValueError):
                continue
            for idx, m in data.items():
                i = int(idx)
                cx, cz = r.rx * 32 + (i & 31), r.rz * 32 + (i >> 5)
                signs += m.get("signs", [])
                if m.get("containers"):
                    stashes.append({"cx": cx, "cz": cz, "count": sum(m["containers"].values()),
                                    "types": m["containers"]})
        return {"signs": signs, "stashes": stashes}

    # ------------------------------------------------------------ Zoomstufen < 0

    def raw_region_tile(self, dim: Dimension, layer: Layer, rx: int, rz: int) -> str | None:
        """Wie ``region_tile``, aber Farben ohne Hoehenschattierung (fuer die 3D-Fernsicht,
        die ihre Schattierung selbst aus den Hoehen rechnet)."""
        if self.region_tile(dim, layer, rx, rz) is None:
            return None
        d = self._dir(dim, layer, 0)
        npz = os.path.join(d, f"{rx}_{rz}.npz")
        png = os.path.join(d, f"{rx}_{rz}.raw.png")
        with self._lock(png):
            try:
                if os.path.getmtime(png) >= os.path.getmtime(npz):
                    return png
            except OSError:
                pass
            with np.load(npz) as z:
                rgb, has = z["rgb"].astype(np.float32), z["has"]
            img = np.dstack([np.clip(rgb, 0, 255).astype(np.uint8), np.where(has, 255, 0).astype(np.uint8)])
            tmp = png + ".tmp"
            Image.fromarray(img, "RGBA").save(tmp, "PNG", compress_level=3)
            os.replace(tmp, png)
            return png

    def tile(self, dim: Dimension, layer: Layer, zoom: int, tx: int, tz: int,
             regions: set | None = None, raw: bool = False) -> str | None:
        if zoom >= 0:
            return self.raw_region_tile(dim, layer, tx, tz) if raw else self.region_tile(dim, layer, tx, tz)
        if regions is None:
            regions = {(r.rx, r.rz) for r in dim.regions()}
        span = 1 << (-zoom)
        x0, z0 = tx * span, tz * span
        if not any(x0 <= rx < x0 + span and z0 <= rz < z0 + span for rx, rz in regions):
            return None
        children = []
        for dz in (0, 1):
            for dx in (0, 1):
                p = self.tile(dim, layer, zoom + 1, tx * 2 + dx, tz * 2 + dz, regions, raw)
                children.append((dx, dz, p))
        d = self._dir(dim, layer, zoom)
        png = os.path.join(d, f"{tx}_{tz}{'.raw' if raw else ''}.png")
        with self._lock(png):
            newest = max((os.path.getmtime(p) for _, _, p in children if p), default=0)
            if os.path.exists(png) and os.path.getmtime(png) >= newest:
                return png
            out = Image.new("RGBA", (TILE, TILE), (0, 0, 0, 0))
            half = TILE // 2
            for dx, dz, p in children:
                if not p:
                    continue
                with Image.open(p) as im:
                    small = im.convert("RGBA").resize((half, half), Image.Resampling.BOX)
                out.paste(small, (dx * half, dz * half))
            tmp = png + ".tmp"
            out.save(tmp, "PNG", compress_level=3)
            os.replace(tmp, png)
            return png

    @staticmethod
    def empty_png() -> bytes:
        buf = io.BytesIO()
        Image.new("RGBA", (1, 1), (0, 0, 0, 0)).save(buf, "PNG")
        return buf.getvalue()


class PastTiles(TileStore):
    """Kacheln "so sah es an Tag X aus" fuer den Zeitraffer.

    Enthalten nur Chunks, von denen es einen gesicherten Stand ab Tag X gibt (die
    sich also seitdem geaendert haben), in diesem alten Stand. Alles andere ist
    durchsichtig, die Karte darunter zeigt dort den heutigen Stand, der dann ja
    auch damals galt.

    ``index()`` liefert {(cx, cz): (Pfad der Region-Datei, Chunk-Index)}.
    """

    def __init__(self, rec: Recording, cache_dir: str, index):
        super().__init__(rec, cache_dir)
        self.index = index

    def _by_region(self) -> dict:
        out: dict = {}
        for (cx, cz), src in self.index().items():
            out.setdefault((cx >> 5, cz >> 5), {})[(cx, cz)] = src
        return out

    def region_tile(self, dim: Dimension, layer: Layer, rx: int, rz: int) -> str | None:
        from .chunk import from_nbt
        from .world import Region
        chunks = self._by_region().get((rx, rz))
        if not chunks:
            return None
        d = self._dir(dim, layer, 0)
        png = os.path.join(d, f"{rx}_{rz}.png")
        newest = max(os.path.getmtime(p) for p, _i in chunks.values())
        with self._lock(png):
            if os.path.exists(png) and os.path.getmtime(png) >= newest:
                return png
            rgb = np.zeros((TILE, TILE, 3), np.float32)
            height = np.full((TILE, TILE), -64, np.int32)
            has = np.zeros((TILE, TILE), bool)
            water = np.zeros((TILE, TILE), bool)
            regions: dict = {}
            for (cx, cz), (path, idx) in chunks.items():
                reg = regions.get(path) or regions.setdefault(path, Region(path, rx, rz))
                try:
                    root = reg.read_nbt(idx)
                    ch = from_nbt(root, cx, cz) if root else None
                except Exception:
                    ch = None
                if ch is None:
                    continue
                lx, lz = cx & 31, cz & 31
                _paint(ch, layer, (slice(lz * 16, lz * 16 + 16), slice(lx * 16, lx * 16 + 16)), rgb, height, has, water)
            img = shade(rgb, height, has, water)
            tmp = png + ".tmp"
            Image.fromarray(img, "RGBA").save(tmp, "PNG", compress_level=3)
            os.replace(tmp, png)
            return png

    def tile(self, dim: Dimension, layer: Layer, zoom: int, tx: int, tz: int,
             regions: set | None = None, raw: bool = False) -> str | None:
        if regions is None:
            regions = set(self._by_region())
        return super().tile(dim, layer, zoom, tx, tz, regions)
