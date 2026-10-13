"""Builds the viewer's block assets from the Minecraft client.

    python -m viewer.tools.build_assets --jar "<.minecraft>/versions/1.21.11/1.21.11.jar"

Reads blockstates, block models and block textures directly from the jar and
writes to ``viewer/assets/``:

        blocks.png   atlas of all block textures (16x16, first animation frame)
        blocks.json  blockstates (raw) + resolved models (parent chains and
                                  texture variables already replaced, textures as atlas index)

The files contain Mojang assets and therefore don't belong in the git repo.
"""

from __future__ import annotations

import argparse
import io
import json
import math
import os
import zipfile

import numpy as np
from PIL import Image

HERE = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(HERE, "assets")
TILE = 16


class Source:
    """Reads either from the client jar or from an extracted folder."""

    def __init__(self, jar: str | None, directory: str | None):
        self.zip = zipfile.ZipFile(jar) if jar else None
        self.dir = directory
        if self.zip:
            self.prefix = "assets/minecraft/"
            names = self.zip.namelist()
            self.blockstate_names = sorted(n[len(self.prefix) + 12:-5] for n in names
                                           if n.startswith(self.prefix + "blockstates/") and n.endswith(".json"))
        else:
            self.blockstate_names = sorted(n[:-5] for n in os.listdir(os.path.join(directory, "blockstates"))
                                           if n.endswith(".json"))

    def _read(self, rel: str) -> bytes | None:
        if self.zip:
            try:
                return self.zip.read(self.prefix + rel)
            except KeyError:
                return None
        # Old project folder layout: textures/{block,blockstates,models/block}
        alt = rel.replace("textures/block/", "block/")
        for cand in (rel, alt):
            p = os.path.join(self.dir, cand)
            if os.path.exists(p):
                with open(p, "rb") as f:
                    return f.read()
        return None

    def json(self, rel: str):
        raw = self._read(rel)
        return json.loads(raw) if raw else None

    def texture(self, name: str) -> Image.Image | None:
        raw = self._read(f"textures/{name}.png")
        if raw is None:
            return None
        return Image.open(io.BytesIO(raw)).convert("RGBA")


def norm_id(ref: str) -> str:
    """'minecraft:block/stone' / 'block/stone' -> 'block/stone'."""
    return ref.split(":", 1)[-1]


class ModelResolver:
    def __init__(self, src: Source):
        self.src = src
        self.synthetic: dict[str, Image.Image] = {}   # self-built textures (chests, beds, ...)
        self.raw: dict[str, dict | None] = {}
        self.textures: dict[str, int] = {}   # 'block/stone' -> atlas index
        self.tex_list: list[str] = []

    def load(self, mid: str):
        mid = norm_id(mid)
        if mid not in self.raw:
            self.raw[mid] = self.src.json(f"models/{mid}.json")
        return self.raw[mid]

    def tex_index(self, name: str) -> int:
        name = norm_id(name)
        i = self.textures.get(name)
        if i is None:
            i = self.textures[name] = len(self.tex_list)
            self.tex_list.append(name)
        return i

    def resolve(self, mid: str) -> dict:
        chain, cur = [], norm_id(mid)
        while cur and len(chain) < 16:
            m = self.load(cur)
            if m is None:
                break
            chain.append(m)
            parent = m.get("parent")
            cur = norm_id(parent) if parent and not parent.startswith("builtin/") else None
        textures, elements, ao = {}, None, True
        for m in reversed(chain):
            textures.update(m.get("textures", {}))
            if "ambientocclusion" in m:
                ao = m["ambientocclusion"]
        for m in chain:
            if "elements" in m:
                elements = m["elements"]
                break

        def tex(ref: str):
            seen = 0
            while ref and ref.startswith("#") and seen < 10:
                ref = textures.get(ref[1:])
                seen += 1
            if not ref or ref.startswith("#"):
                return None
            return self.tex_index(ref)

        out_el = []
        for e in elements or []:
            faces = {}
            for d, f in (e.get("faces") or {}).items():
                t = tex(f.get("texture", ""))
                if t is None:
                    continue
                face = {"t": t}
                for k in ("uv", "cullface", "rotation", "tintindex"):
                    if k in f:
                        face[k] = f[k]
                faces[d] = face
            if not faces:
                continue
            el = {"from": e["from"], "to": e["to"], "faces": faces}
            if "rotation" in e:
                el["rotation"] = e["rotation"]
            if e.get("shade") is False:
                el["shade"] = False
            out_el.append(el)
        particle = tex(textures.get("particle", "")) if textures.get("particle") else None
        return {"elements": out_el, "particle": particle, "ao": ao}


def build(src: Source, out_dir: str = OUT):
    res = ModelResolver(src)
    blockstates, models = {}, {}
    for name in src.blockstate_names:
        bs = src.json(f"blockstates/{name}.json")
        if not bs:
            continue
        blockstates["minecraft:" + name] = bs
        variants = bs.get("variants", {}).values()
        parts = [p.get("apply") for p in bs.get("multipart", [])]
        for v in list(variants) + parts:
            for entry in (v if isinstance(v, list) else [v]):
                if entry and "model" in entry:
                    mid = norm_id(entry["model"])
                    if mid not in models:
                        models[mid] = res.resolve(mid)
    add_entity_blocks(src, res, blockstates, models)
    # Fluids have no elements; note the textures for the special case.
    for t in ("block/water_still", "block/lava_still"):
        res.tex_index(t)

    n = len(res.tex_list)
    cols = int(math.ceil(math.sqrt(n)))
    rows = int(math.ceil(n / cols))
    atlas = Image.new("RGBA", (cols * TILE, rows * TILE), (0, 0, 0, 0))
    kinds = []   # 0 = opaque, 1 = cutout (alpha 0/255), 2 = translucent
    colors = []
    for i, name in enumerate(res.tex_list):
        img = res.synthetic.get(name) or src.texture(name)
        if img is None:
            img = Image.new("RGBA", (TILE, TILE), (255, 0, 255, 255))
        w = img.width
        img = img.crop((0, 0, w, w))                 # first animation frame
        if w != TILE:
            img = img.resize((TILE, TILE), Image.Resampling.NEAREST)
        a = np.asarray(img)[..., 3]
        if a.min() == 255:
            kinds.append(0)
        elif ((a == 0) | (a == 255)).all():
            kinds.append(1)
        else:
            kinds.append(2)
        px = np.asarray(img, np.float32).reshape(-1, 4)
        w8 = px[:, 3] / 255.0
        rgb = (px[:, :3] * w8[:, None]).sum(0) / max(w8.sum(), 1e-6)
        colors.append([int(round(c)) for c in rgb] + [round(float(w8.mean()), 3)])
        r, c = divmod(i, cols)
        atlas.paste(img, (c * TILE, r * TILE))

    os.makedirs(out_dir, exist_ok=True)
    atlas.save(os.path.join(out_dir, "blocks.png"))
    meta = {
        "version": 1,
        "tile": TILE, "cols": cols, "rows": rows,
        "textures": res.tex_list, "kinds": kinds, "colors": colors,
        "blockstates": blockstates, "models": models,
    }
    with open(os.path.join(out_dir, "blocks.json"), "w") as f:
        json.dump(meta, f, separators=(",", ":"))
    print(f"{len(blockstates)} Blockstates, {len(models)} Modelle, {n} Texturen -> {out_dir}")


# ---------------------------------------------------------------- Block entities
#
# Chests, beds and shulker boxes have no block model in the game; they are
# drawn as entities. For the viewer we build simple block models from the
# entity textures: faces are cut out, rotated into world orientation
# and put into the atlas as separate 16x16 tiles.

_FACING_Y = {"north": 0, "east": 90, "south": 180, "west": 270}
_COLORS = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
           "cyan", "purple", "blue", "brown", "green", "red", "black"]


def _crop(img, x0, y0, x1, y1):
    return np.asarray(img.crop((x0, y0, x1, y1)))


def _tile(parts):
    """parts: [(array HxWx4, col, row)] -> 16x16 image."""
    t = np.zeros((16, 16, 4), np.uint8)
    for a, c, r in parts:
        h, w = a.shape[:2]
        dst = t[r:r + h, c:c + w]
        m = a[..., 3:4] > 0
        dst[:] = np.where(m, a[:dst.shape[0], :dst.shape[1]], dst)
    return Image.fromarray(t, "RGBA")


def _box(fr, to, faces, cull=None):
    return {"from": list(fr), "to": list(to),
            "faces": {d: ({"t": t, "cullface": cull[d]} if cull and d in cull else {"t": t}) for d, t in faces.items()}}


def _chest(res, img, name, kind):
    """kind: single | left | right. Returns the model for facing=north."""
    tex = lambda key, im: (res.synthetic.__setitem__(f"synthetic/{name}_{kind}_{key}", im),
                           res.tex_index(f"synthetic/{name}_{kind}_{key}"))[1]
    lock = _crop(img, 1, 1, 3, 5)[::-1, ::-1]                       # 2x4, rotated like the front
    if kind == "single":
        w, x0, x1 = 14, 1, 15
        top, bottom = _crop(img, 28, 0, 42, 14), _crop(img, 14, 19, 28, 33)
        strips = {"front": 42, "back": 14, "side_a": 0, "side_b": 28}
        widths = {"front": 14, "back": 14, "side_a": 14, "side_b": 14}
    else:
        w = 15
        x0, x1 = (1, 16) if kind == "left" else (0, 15)
        top, bottom = _crop(img, 29, 0, 44, 14), _crop(img, 14, 19, 29, 33)
        if kind == "left":
            strips = {"front": 43, "back": 14, "side": 29}
        else:
            strips = {"front": 44, "back": 14, "side": 0}
        widths = {"front": 15, "back": 15, "side": 14}

    def side(u, wd):
        lid = _crop(img, u, 14, u + wd, 19)
        base = _crop(img, u, 33, u + wd, 42)
        # The strips are upside down in the texture (the entity model is rotated around X):
        # flip each strip on its own, lid on top, bottom part below.
        return np.concatenate([lid[::-1], base[::-1]], 0)[:, ::-1]

    t = {}
    # Top: column = x, row = z (north up); the open edge to the partner half fits after mirroring.
    t["up"] = tex("top", _tile([(top[:, ::-1], x0, 1)]))
    t["down"] = tex("bottom", _tile([(bottom, x0, 1)]))
    front = side(strips["front"], widths["front"])
    fc = 16 - x1                                                    # north side: u = 16 - x
    parts = [(front, fc, 2)]
    if kind == "single":
        parts.append((lock, 7, 5))
    elif kind == "left":
        parts.append((lock[:, 1:], 0, 5))
    else:
        parts.append((lock[:, :1], 15, 5))
    t["north"] = tex("front", _tile(parts))
    t["south"] = tex("back", _tile([(side(strips["back"], widths["back"]), x0, 2)]))
    if kind == "single":
        s_img = tex("side", _tile([(side(strips["side_a"], 14), 1, 2)]))
        t["west"] = t["east"] = s_img
    else:
        s_img = tex("side", _tile([(side(strips["side"], 14), 1, 2)]))
        # Side piece at the closed end (the open side meets the partner half)
        t["west" if kind == "left" else "east"] = s_img
    return {"elements": [_box((x0, 0, 1), (x1, 14, 15), t)], "particle": t["up"], "ao": True}


def _bed(res, img, color, part):
    key = f"synthetic/bed_{color}_{part}_"
    tex = lambda k, im: (res.synthetic.__setitem__(key + k, im), res.tex_index(key + k))[1]
    a = np.asarray(img)
    oy = 0 if part == "head" else 22
    top = a[oy + 6:oy + 22, 6:22]
    west_strip, east_strip = a[oy + 6:oy + 22, 0:6], a[oy + 6:oy + 22, 22:28]
    west = np.zeros((6, 16, 4), np.uint8)
    east = np.zeros((6, 16, 4), np.uint8)
    for r in range(16):
        for c in range(6):
            west[5 - c, r] = west_strip[r, c]
            east[c, 15 - r] = east_strip[r, c]
    end = a[0:6, 6:22][::-1] if part == "head" else a[22:28, 22:38][::-1]
    bottom = a[oy + 6:oy + 22, 28:44]
    leg = np.tile(a[0:3, 50:53], (6, 6, 1))[:16, :16]
    t = {"up": tex("top", _tile([(top, 0, 0)])), "west": tex("west", _tile([(west, 0, 7)])),
         "east": tex("east", _tile([(east, 0, 7)])), "down": tex("bottom", _tile([(bottom, 0, 0)]))}
    t["north" if part == "head" else "south"] = tex("end", _tile([(end, 0, 7)]))
    legt = tex("leg", _tile([(leg, 0, 0)]))
    lf = {d: legt for d in ("north", "south", "west", "east", "down")}
    lz = (0, 3) if part == "head" else (13, 16)
    els = [_box((0, 3, 0), (16, 9, 16), t),
           _box((0, 0, lz[0]), (3, 3, lz[1]), lf), _box((13, 0, lz[0]), (16, 3, lz[1]), lf)]
    return {"elements": els, "particle": t["up"], "ao": True}


def _shulker(res, img, name):
    key = f"synthetic/{name}_"
    tex = lambda k, im: (res.synthetic.__setitem__(key + k, im), res.tex_index(key + k))[1]
    a = np.asarray(img)
    t = {"up": tex("top", _tile([(a[0:16, 16:32], 0, 0)])), "down": tex("bottom", _tile([(a[28:44, 32:48], 0, 0)]))}
    for d, u in (("north", 0), ("east", 16), ("south", 32), ("west", 48)):
        t[d] = tex(d, _tile([(a[44:52, u:u + 16], 0, 8), (a[16:28, u:u + 16], 0, 0)]))
    cull = {d: d for d in t}
    return {"elements": [_box((0, 0, 0), (16, 16, 16), t, cull)], "particle": t["north"], "ao": True}


_SHULKER_ROT = {"up": {}, "down": {"x": 180}, "north": {"x": 90}, "south": {"x": 90, "y": 180},
                "east": {"x": 90, "y": 90}, "west": {"x": 90, "y": 270}}


def add_entity_blocks(src, res, blockstates, models):
    def tex(path):
        return src.texture(path)

    chests = {"chest": "normal", "trapped_chest": "trapped", "ender_chest": "ender"}
    for ox in ("", "exposed_", "weathered_", "oxidized_"):
        for wx in ("", "waxed_"):
            chests[f"{wx}{ox}copper_chest"] = "copper" + (f"_{ox[:-1]}" if ox else "")
    made = 0
    for block, texname in chests.items():
        if "minecraft:" + block not in blockstates:
            continue
        variants = {}
        for kind in ("single", "left", "right"):
            img = tex(f"entity/chest/{texname}" + ("" if kind == "single" else f"_{kind}"))
            if img is None or img.size != (64, 64):
                continue
            mid = f"synthetic/{block}_{kind}"
            models[mid] = _chest(res, img, texname, kind)
            kinds = ("single",) if block == "ender_chest" else (kind,)
            for k in kinds:
                for f, y in _FACING_Y.items():
                    variants[f"facing={f},type={k}" if block != "ender_chest" else f"facing={f}"] = {"model": mid, "y": y}
            if block == "ender_chest":
                break
        if variants:
            blockstates["minecraft:" + block] = {"variants": variants}
            made += 1
    for color in _COLORS:
        img = tex(f"entity/bed/{color}")
        if img is None or img.size != (64, 64) or f"minecraft:{color}_bed" not in blockstates:
            continue
        variants = {}
        for part in ("head", "foot"):
            mid = f"synthetic/{color}_bed_{part}"
            models[mid] = _bed(res, img, color, part)
            for f, y in _FACING_Y.items():
                variants[f"facing={f},part={part}"] = {"model": mid, "y": y}
        blockstates[f"minecraft:{color}_bed"] = {"variants": variants}
        made += 1
    for color in [None] + _COLORS:
        block = f"{color}_shulker_box" if color else "shulker_box"
        img = tex(f"entity/shulker/shulker_{color}" if color else "entity/shulker/shulker")
        if img is None or img.size != (64, 64) or "minecraft:" + block not in blockstates:
            continue
        mid = "synthetic/" + block
        models[mid] = _shulker(res, img, block)
        blockstates["minecraft:" + block] = {"variants": {f"facing={f}": {"model": mid, **r} for f, r in _SHULKER_ROT.items()}}
        made += 1
    print(f"{made} Block-Entities mit eigenem Modell (Truhen, Betten, Shulkerkisten)")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    g = ap.add_mutually_exclusive_group(required=True)
    g.add_argument("--jar", help="Minecraft-Client-Jar (z. B. versions/1.21.11/1.21.11.jar)")
    g.add_argument("--dir", help="entpackter Asset-Ordner (blockstates/, models/, textures/)")
    ap.add_argument("--out", default=OUT)
    a = ap.parse_args(argv)
    build(Source(a.jar, a.dir), a.out)


if __name__ == "__main__":
    main()
