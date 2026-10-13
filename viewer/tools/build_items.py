"""Build item images for the chest inventory (assets/items.png + assets/items.json).

    python -m viewer.tools.build_items --jar "<.minecraft>/versions/1.21.11/1.21.11.jar"

Needs ``build_assets`` first (block atlas and models). Blocks are drawn as in the
inventory, at an angle from above (own small software rasterizer over the
viewer's block models), all other items flat from their item textures.
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

from ..core.chunk import BLOCKS
from ..core.models import ASSETS, TINT_FIXED, TINT_FOLIAGE, TINT_GRASS, TINT_NONE, BlockModels, _FIXED

SIZE = 32
GRASS = (0x7c, 0xbd, 0x6b)
FOLIAGE = (0x59, 0xae, 0x30)

# View direction as in the inventory (display.gui: rotation [30, 225, 0])
_a, _b = math.radians(225), math.radians(30)
RY = np.array([[math.cos(_a), 0, math.sin(_a)], [0, 1, 0], [-math.sin(_a), 0, math.cos(_a)]], np.float32)
RX = np.array([[1, 0, 0], [0, math.cos(_b), -math.sin(_b)], [0, math.sin(_b), math.cos(_b)]], np.float32)
VIEW = RX @ RY


class Jar:
    def __init__(self, path: str):
        self.z = zipfile.ZipFile(path)
        self.names = set(self.z.namelist())

    def json(self, p: str):
        p = "assets/minecraft/" + p
        return json.loads(self.z.read(p)) if p in self.names else None

    def tex(self, ref: str) -> Image.Image | None:
        ref = ref.split(":", 1)[-1]
        p = f"assets/minecraft/textures/{ref}.png"
        if p not in self.names:
            return None
        im = Image.open(io.BytesIO(self.z.read(p))).convert("RGBA")
        w = im.width
        return im.crop((0, 0, w, w)) if im.height > w else im   # animated: first frame


# ------------------------------------------------------------------ Resolve item definition

def pick_model(node):
    """items/<id>.json -> (model name, tints, special?) for the inventory view."""
    if not isinstance(node, dict):
        return None
    t = node.get("type", "").split(":", 1)[-1]
    if t == "model":
        return node.get("model"), node.get("tints") or [], False
    if t == "special":
        return node.get("base"), [], True
    if t == "select":
        for c in node.get("cases", []):
            w = c.get("when")
            if w == "gui" or (isinstance(w, list) and "gui" in w):
                return pick_model(c.get("model"))
        return pick_model(node.get("fallback")) or pick_model((node.get("cases") or [{}])[0].get("model"))
    if t == "condition":
        return pick_model(node.get("on_false")) or pick_model(node.get("on_true"))
    if t == "range_dispatch":
        return pick_model(node.get("fallback")) or pick_model(((node.get("entries") or [{}])[0]).get("model"))
    if t == "composite":
        return pick_model((node.get("models") or [None])[0])
    return pick_model(node.get("model")) if "model" in node else None


def model_chain(jar: Jar, name: str) -> tuple[dict, list]:
    """Textures and parent chain of a model (models/<name>.json)."""
    textures, chain = {}, []
    n = name.split(":", 1)[-1]
    for _ in range(12):
        chain.append(n)
        m = jar.json(f"models/{n}.json")
        if m is None:
            break
        for k, v in (m.get("textures") or {}).items():
            textures.setdefault(k, v.get("sprite") if isinstance(v, dict) else v)
        par = m.get("parent")
        if not par:
            break
        n = par.split(":", 1)[-1]
    return textures, chain


def tint_rgb(t: dict):
    v = t.get("value", t.get("default"))
    if isinstance(v, int):
        v &= 0xFFFFFF
        return (v >> 16) & 255, (v >> 8) & 255, v & 255
    if t.get("type", "").endswith("grass"):
        return GRASS
    return None


def flat_icon(jar: Jar, textures: dict, tints: list) -> Image.Image | None:
    out = Image.new("RGBA", (16, 16))
    found = False
    for i in range(8):
        ref = textures.get(f"layer{i}")
        if not ref:
            continue
        im = jar.tex(ref)
        if im is None:
            continue
        im = im.resize((16, 16), Image.NEAREST)
        if i < len(tints) and tints[i]:
            rgb = tint_rgb(tints[i])
            if rgb:
                a = np.asarray(im, np.float32)
                a[..., :3] *= np.array(rgb, np.float32) / 255.0
                im = Image.fromarray(a.clip(0, 255).astype(np.uint8))
        out.alpha_composite(im)
        found = True
    return out.resize((SIZE, SIZE), Image.NEAREST) if found else None


# ------------------------------------------------------------------ Draw blocks at an angle

def render_block(models: BlockModels, atlas: np.ndarray, comp) -> Image.Image | None:
    if comp is None or not len(comp.verts):
        return None
    img = np.zeros((SIZE, SIZE, 4), np.float32)
    depth = np.full((SIZE, SIZE), -1e9, np.float32)
    v = (comp.verts.reshape(-1, 3) - 0.5) @ VIEW.T
    v = v.reshape(-1, 4, 3)
    scale = SIZE * 0.62
    sx = SIZE / 2 + v[..., 0] * scale
    sy = SIZE / 2 - v[..., 1] * scale
    sz = v[..., 2]
    ys, xs = np.mgrid[0:SIZE, 0:SIZE]
    px, py = xs + 0.5, ys + 0.5
    H, W = atlas.shape[:2]
    for q in range(len(comp.verts)):
        tint = int(comp.tint[q])
        col = (np.array(GRASS if tint == TINT_GRASS else FOLIAGE if tint == TINT_FOLIAGE
                        else comp.fixed_tint if tint == TINT_FIXED else (255, 255, 255), np.float32) / 255.0)
        shade = {1: 1.0, 2: 0.8, 3: 0.8, 4: 0.6, 5: 0.6}.get(int(comp.normal[q]), 0.5)
        for tri in ((0, 1, 2), (0, 2, 3)):
            ax, ay = sx[q, list(tri)], sy[q, list(tri)]
            den = (ay[1] - ay[2]) * (ax[0] - ax[2]) + (ax[2] - ax[1]) * (ay[0] - ay[2])
            if abs(den) < 1e-6:
                continue
            x0, x1 = int(max(0, ax.min())), int(min(SIZE - 1, math.ceil(ax.max())))
            y0, y1 = int(max(0, ay.min())), int(min(SIZE - 1, math.ceil(ay.max())))
            if x1 < x0 or y1 < y0:
                continue
            bx, by = px[y0:y1 + 1, x0:x1 + 1], py[y0:y1 + 1, x0:x1 + 1]
            w0 = ((ay[1] - ay[2]) * (bx - ax[2]) + (ax[2] - ax[1]) * (by - ay[2])) / den
            w1 = ((ay[2] - ay[0]) * (bx - ax[2]) + (ax[0] - ax[2]) * (by - ay[2])) / den
            w2 = 1 - w0 - w1
            inside = (w0 >= -1e-4) & (w1 >= -1e-4) & (w2 >= -1e-4)
            if not inside.any():
                continue
            uv = comp.uv[q][list(tri)]
            u = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
            vv = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
            z = w0 * sz[q, tri[0]] + w1 * sz[q, tri[1]] + w2 * sz[q, tri[2]]
            ui = np.clip(u.astype(np.int32), 0, W - 1)
            vi = np.clip(vv.astype(np.int32), 0, H - 1)
            texel = atlas[vi, ui]
            sub_d = depth[y0:y1 + 1, x0:x1 + 1]
            ok = inside & (texel[..., 3] > 0.1) & (z > sub_d)
            if not ok.any():
                continue
            rgb = texel[..., :3] * col * shade
            sub = img[y0:y1 + 1, x0:x1 + 1]
            a = texel[..., 3:4]
            sub[ok, :3] = (rgb * a + sub[..., :3] * (1 - a))[ok]
            sub[ok, 3] = np.maximum(sub[ok, 3], texel[ok, 3])
            sub_d[ok] = z[ok]
    if img[..., 3].max() == 0:
        return None
    return Image.fromarray((img.clip(0, 1) * 255).astype(np.uint8))


def compile_model(models: BlockModels, model: str, item: str):
    """Compile a block model unrotated (as the inventory shows it)."""
    sel = models._select
    models._select = lambda name, props: [{"model": model}]
    try:
        return models._compile(item)
    finally:
        models._select = sel


def inject_jar_model(jar: Jar, models: BlockModels, name: str) -> bool:
    """Block model the viewer doesn't know (e.g. *_inventory), taken over from the
    jar, provided all textures are in the block atlas."""
    textures, chain = model_chain(jar, name)
    elements = None
    for n in chain:
        m = jar.json(f"models/{n}.json")
        if m and m.get("elements"):
            elements = m["elements"]
            break
    if not elements:
        return False

    def resolve(ref, depth=0):
        while ref.startswith("#") and depth < 8:
            ref = textures.get(ref[1:], "")
            depth += 1
        return ref.split(":", 1)[-1]
    els = []
    for el in elements:
        faces = {}
        for d, f in (el.get("faces") or {}).items():
            t = models.textures.get(resolve(f.get("texture", "")))
            if t is None:
                return False
            face = {"t": t}
            for k in ("uv", "rotation", "tintindex", "cullface"):
                if k in f:
                    face[k] = f[k]
            faces[d] = face
        e = {"from": el["from"], "to": el["to"], "faces": faces}
        if el.get("rotation"):
            e["rotation"] = el["rotation"]
        els.append(e)
    models.models[name] = {"elements": els}
    return True


def default_state(models: BlockModels, block: str) -> str:
    bs = models.blockstates.get(block) or {}
    keys = list((bs.get("variants") or {}).keys())
    if not keys or "" in keys:
        return block
    pref = {"facing": "north", "half": "bottom", "type": "single", "part": "foot", "shape": "straight",
            "axis": "y", "face": "floor", "attachment": "floor"}

    def score(k):
        props = dict(kv.partition("=")[::2] for kv in k.split(","))
        return sum(props.get(p) == v for p, v in pref.items())
    best = max(keys, key=score)
    return f"{block}[{best}]"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", required=True, help="Minecraft-Client-Jar")
    ap.add_argument("--out", default=ASSETS)
    args = ap.parse_args(argv)

    jar = Jar(args.jar)
    models = BlockModels(args.out)
    atlas = np.asarray(Image.open(os.path.join(args.out, "blocks.png")).convert("RGBA"), np.float32) / 255.0
    ids = sorted(n[len("assets/minecraft/items/"):-5] for n in jar.names
                 if n.startswith("assets/minecraft/items/") and n.endswith(".json"))
    icons, stats = {}, {"block": 0, "flat": 0, "fallback": 0, "missing": 0}
    for item in ids:
        full = "minecraft:" + item
        res = pick_model((jar.json(f"items/{item}.json") or {}).get("model"))
        icon = None
        if res and res[0]:
            model, tints, special = res
            mname = model.split(":", 1)[-1]
            textures, chain = model_chain(jar, mname)
            if any(c in ("item/generated", "builtin/generated", "item/handheld", "item/handheld_rod",
                         "item/handheld_mace") for c in chain) or ("layer0" in textures and not mname.startswith("block/")):
                icon = flat_icon(jar, textures, tints)
                stats["flat"] += icon is not None
            if icon is None and (special or mname.startswith("block/")):
                comp = None
                if mname.startswith("block/") and mname not in models.models:
                    inject_jar_model(jar, models, mname)
                if mname.startswith("block/") and mname in models.models and models.models[mname].get("elements"):
                    comp = compile_model(models, mname, full)
                if (comp is None or not len(comp.verts)) and full in models.blockstates:
                    comp = models._compile(default_state(models, full))
                icon = render_block(models, atlas, comp)
                stats["block"] += icon is not None
            if icon is None:
                for k in ("particle", "layer0", "all", "side", "texture"):
                    if k in textures and not textures[k].startswith("#"):
                        im = jar.tex(textures[k])
                        if im is not None:
                            icon = im.resize((SIZE, SIZE), Image.NEAREST)
                            stats["fallback"] += 1
                            break
        if icon is None:
            stats["missing"] += 1
            continue
        icons[full] = icon

    cols = 48
    names = sorted(icons)
    rows = (len(names) + cols - 1) // cols
    sheet = Image.new("RGBA", (cols * SIZE, rows * SIZE))
    for i, n in enumerate(names):
        sheet.paste(icons[n], ((i % cols) * SIZE, (i // cols) * SIZE))
    sheet.save(os.path.join(args.out, "items.png"), optimize=True)
    with open(os.path.join(args.out, "items.json"), "w") as f:
        json.dump({"size": SIZE, "cols": cols, "rows": rows, "index": {n: i for i, n in enumerate(names)}}, f)
    print(f"{len(names)} Item-Bilder ({stats}) -> {args.out}/items.png")


if __name__ == "__main__":
    main()
