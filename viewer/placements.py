"""Schematic library and placed schematics (for server.py).

Library: all .schem/.litematic/.nbt in the schematic folders next to the
recording (Litematica: ``schematics/``) plus
uploaded files in ``<recording>/.viewer/schematics/``.

Placements are stored in ``<recording>/.viewer/placements.json``.
"""

from __future__ import annotations

import gzip
import json
import os
import re
import threading
import time
import uuid

import numpy as np

from .core import schematic as sch
from .core.chunk import BLOCKS
from .core.mesher import MAGIC, mesh_volume
from .core.models import MODELS

EXTS = (".schem", ".litematic", ".nbt", ".schematic")


class Placements:
    def __init__(self, rec_root: str, extra_dirs: list[str] | None = None):
        self.root = rec_root
        self.data_dir = os.path.join(rec_root, ".viewer")
        self.upload_dir = os.path.join(self.data_dir, "schematics")
        os.makedirs(self.upload_dir, exist_ok=True)
        parent = os.path.dirname(os.path.abspath(rec_root))
        cands = [os.path.join(parent, "schematics"), self.upload_dir]
        self.dirs = {}
        for d in cands + list(extra_dirs or []):
            if os.path.isdir(d):
                label = "hochgeladen" if d == self.upload_dir else os.path.basename(d.rstrip("/\\"))
                self.dirs.setdefault(label, d)
        self.path = os.path.join(self.data_dir, "placements.json")
        self.lock = threading.Lock()
        self._schems: dict = {}       # (path, mtime) -> Schem
        self._trans: dict = {}        # (path, mtime, rot, mirror) -> Schem
        self._meshes: dict = {}       # same key -> bytes

    # ---------------------------------------------------------------- Library

    def library(self) -> list:
        out = []
        for label, d in self.dirs.items():
            for base, _, files in os.walk(d):
                for fn in files:
                    if not fn.lower().endswith(EXTS):
                        continue
                    p = os.path.join(base, fn)
                    rel = os.path.relpath(p, d).replace("\\", "/")
                    try:
                        st = os.stat(p)
                    except OSError:
                        continue
                    out.append({"file": f"{label}/{rel}", "name": fn.rsplit(".", 1)[0], "folder": label,
                                "bytes": st.st_size, "mtime": int(st.st_mtime)})
                    if len(out) >= 3000:
                        return out
        out.sort(key=lambda e: -e["mtime"])
        return out

    def resolve(self, file_id: str) -> str:
        label, _, rel = file_id.partition("/")
        d = self.dirs.get(label)
        if d is None or not rel:
            raise LookupError("Schematic-Ordner unbekannt")
        p = os.path.abspath(os.path.join(d, rel))
        if not p.startswith(os.path.abspath(d) + os.sep) or not os.path.isfile(p):
            raise LookupError("Schematic-Datei nicht gefunden")
        return p

    def upload(self, filename: str, data: bytes) -> dict:
        name = re.sub(r"[^A-Za-z0-9_. ()-]", "_", os.path.basename(filename)).strip(" .") or "upload.schem"
        if not name.lower().endswith(EXTS):
            raise ValueError("nur .schem, .litematic oder .nbt")
        sch.load(data, name)        # check up front so only readable files end up there
        p = os.path.join(self.upload_dir, name)
        with open(p, "wb") as f:
            f.write(data)
        return {"file": f"hochgeladen/{name}", "name": name.rsplit(".", 1)[0]}

    # ---------------------------------------------------------------- Loading with cache

    def schem(self, file_id: str, rot: int = 0, mirror: bool = False) -> sch.Schem:
        p = self.resolve(file_id)
        mt = os.path.getmtime(p)
        key = (p, mt)
        s = self._schems.get(key)
        if s is None:
            with open(p, "rb") as f:
                s = sch.load(f.read(), os.path.basename(p))
            if len(self._schems) > 16:
                self._schems.clear()
                self._trans.clear()
            self._schems[key] = s
        tkey = (p, mt, rot % 360, bool(mirror))
        t = self._trans.get(tkey)
        if t is None:
            t = sch.transform(s, rot, mirror)
            self._trans[tkey] = t
        return t

    def mesh(self, pl: dict) -> tuple[bytes, str]:
        p = self.resolve(pl["file"])
        mt = os.path.getmtime(p)
        key = (p, mt, pl.get("rot", 0) % 360, bool(pl.get("mirror")))
        etag = f'"{MODELS.version}-{MAGIC.decode()}-{abs(hash(key))}"'
        data = self._meshes.get(key)
        if data is None:
            s = self.schem(pl["file"], pl.get("rot", 0), pl.get("mirror", False))
            if max(s.size) > 2047:
                raise ValueError("Schematic zu gross fuer die 3D-Ansicht (max. 2047 Bloecke Kantenlaenge)")
            data = gzip.compress(mesh_volume(s.blocks), 5)
            if len(self._meshes) > 32:
                self._meshes.clear()
            self._meshes[key] = data
        return data, etag

    # ---------------------------------------------------------------- Placements

    def _read(self) -> list:
        try:
            with open(self.path, encoding="utf-8") as f:
                return json.load(f)
        except (OSError, ValueError):
            return []

    def _write(self, pls: list):
        tmp = self.path + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(pls, f, ensure_ascii=False, indent=1)
        os.replace(tmp, self.path)

    def _with_size(self, pl: dict) -> dict:
        try:
            s = self.schem(pl["file"], pl.get("rot", 0), pl.get("mirror", False))
            return {**pl, "size": list(s.size), "error": None,
                    "mtime": int(os.path.getmtime(self.resolve(pl["file"])) * 1000)}
        except Exception as e:  # file gone/broken: show it anyway so it can be removed
            return {**pl, "size": [1, 1, 1], "error": str(e)}

    def list(self, server: str | None = None, dim: str | None = None) -> list:
        out = []
        for pl in self._read():
            if server and pl.get("server") != server:
                continue
            if dim and pl.get("dim") != dim:
                continue
            out.append(self._with_size(pl))
        return out

    def get(self, pid: str) -> dict:
        for pl in self._read():
            if pl.get("id") == pid:
                return pl
        raise LookupError("Platzierung unbekannt")

    def place(self, req: dict) -> dict:
        s = self.schem(str(req["file"]))
        pl = {
            "id": str(time.time_ns()),
            "file": str(req["file"]), "name": str(req.get("name") or s.name)[:80],
            "server": str(req["server"]), "dim": str(req["dim"]),
            "x": int(req["x"]), "y": int(req["y"]), "z": int(req["z"]),
            "rot": 0, "mirror": False, "visible": True, "opacity": 1.0,
        }
        with self.lock:
            pls = self._read()
            pls.append(pl)
            self._write(pls)
        return self._with_size(pl)

    def update(self, req: dict) -> dict:
        with self.lock:
            pls = self._read()
            for pl in pls:
                if pl.get("id") == req.get("id"):
                    for k, conv in (("x", int), ("y", int), ("z", int), ("rot", int), ("mirror", bool),
                                    ("visible", bool), ("opacity", float), ("name", str),
                                    ("ingame", bool)):
                        if k in req:
                            pl[k] = conv(req[k])
                    pl["rot"] %= 360
                    pl["opacity"] = min(1.0, max(0.1, pl.get("opacity", 1.0)))
                    pl["name"] = pl["name"][:80]
                    self._write(pls)
                    return self._with_size(pl)
        raise LookupError("Platzierung unbekannt")

    def delete(self, pid: str):
        with self.lock:
            pls = [pl for pl in self._read() if pl.get("id") != pid]
            self._write(pls)

    # ---------------------------------------------------------------- Litematica bridge

    _LM_ROT = {0: "NONE", 90: "CLOCKWISE_90", 180: "CLOCKWISE_180", 270: "COUNTERCLOCKWISE_90"}

    def litematica(self, server: str | None) -> list:
        """Placements with "In game" for the mod, in Litematica's terms.

        The viewer stores the minimum corner of the rotated schematic (x, y, z).
        Litematica wants the point where the file origin lands, and rotates/mirrors
        around that point: first mirror (FRONT_BACK: x -> -x), then rotate
        clockwise (x, z) -> (-z, x). So: origin = corner - min(transform(file box)).
        """
        out = []
        for pl in self._read():
            if not pl.get("ingame") or (server and pl.get("server") != server):
                continue
            try:
                p = self.resolve(pl["file"])
                s = self.schem(pl["file"])
            except Exception:
                continue
            rot, mirror = pl.get("rot", 0) % 360, bool(pl.get("mirror"))
            W, H, L = s.size
            ox, oy, oz = s.offset
            xs, zs = [], []
            for cx in (ox, ox + W - 1):
                for cz in (oz, oz + L - 1):
                    x, z = (-cx if mirror else cx), cz
                    for _ in range(rot // 90):
                        x, z = -z, x
                    xs.append(x); zs.append(z)
            origin = [pl["x"] - min(xs), pl["y"] - oy, pl["z"] - min(zs)]
            out.append({
                "id": pl["id"], "uuid": str(uuid.uuid5(uuid.NAMESPACE_URL, "webmatica:" + pl["id"])),
                "name": pl.get("name") or s.name, "path": p, "dim": pl.get("dim", "overworld"),
                "origin": origin, "rotation": self._LM_ROT[rot],
                "mirror": "FRONT_BACK" if mirror else "NONE", "mtime": int(os.path.getmtime(p) * 1000),
            })
        return out

    # ---------------------------------------------------------------- Webmatica printer

    def printer_blocks(self, server: str, dim: str, x: int, y: int, z: int, r: int = 8) -> dict:
        """Target blocks of the "In game" placements in a cube around the player.

        For the mod's own printer: ``palette`` holds block states as
        ``minecraft:oak_stairs[facing=north,half=bottom]``, ``blocks`` is a flat
        list ``x, y, z, palette index, ...``. Air is left out; where placements
        overlap, the later one wins (as in the 3D view).
        """
        r = max(1, min(12, int(r)))
        palette: list[str] = []
        index: dict[int, int] = {}
        found: dict[tuple, int] = {}
        for pl in self._read():
            if not pl.get("ingame") or pl.get("server") != server or pl.get("dim", "overworld") != dim:
                continue
            try:
                s = self.schem(pl["file"], pl.get("rot", 0), pl.get("mirror", False))
            except Exception:
                continue
            W, H, L = s.size
            x0, y0, z0 = pl["x"], pl["y"], pl["z"]
            ax, bx = max(x - r, x0), min(x + r, x0 + W - 1)
            ay, by = max(y - r, y0), min(y + r, y0 + H - 1)
            az, bz = max(z - r, z0), min(z + r, z0 + L - 1)
            if ax > bx or ay > by or az > bz:
                continue
            part = s.blocks[ay - y0:by - y0 + 1, az - z0:bz - z0 + 1, ax - x0:bx - x0 + 1]
            for sid in np.unique(part):
                sid = int(sid)
                if sid not in index and not sch._is_air(BLOCKS.names[sid]):
                    index[sid] = len(palette)
                    palette.append(BLOCKS.names[sid])
            ys, zs, xs = np.nonzero(part)
            for yy, zz, xx in zip(ys.tolist(), zs.tolist(), xs.tolist()):
                i = index.get(int(part[yy, zz, xx]))
                if i is not None:
                    found[(ax + xx, ay + yy, az + zz)] = i
        flat: list[int] = []
        for (bx_, by_, bz_), i in found.items():
            flat += (bx_, by_, bz_, i)
        return {"palette": palette, "blocks": flat}

    # ---------------------------------------------------------------- Evaluation

    def materials(self, pid: str) -> dict:
        pl = self.get(pid)
        s = self.schem(pl["file"], pl.get("rot", 0), pl.get("mirror", False))
        items = sch.materials(s.blocks)
        return {"size": list(s.size), "items": sorted(items.items(), key=lambda kv: -kv[1]),
                "blocks": int(sum(items.values()))}

    def verify(self, pid: str, rec, near: tuple | None = None) -> dict:
        pl = self.get(pid)
        dim = rec.dimension(pl["server"], pl["dim"])
        if dim is None:
            raise LookupError("fuer diese Dimension gibt es keine Aufnahme")
        s = self.schem(pl["file"], pl.get("rot", 0), pl.get("mirror", False))
        return sch.verify(s, rec, dim, pl["x"], pl["y"], pl["z"], near=near)
