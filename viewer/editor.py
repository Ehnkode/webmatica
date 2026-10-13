"""Schematic editor (server side).

The browser sends edits (set block, fill area, replace); they are
applied to the block array here and immediately saved as a Sponge schematic
(.schem). Each file has undo/redo.

Only files in ``hochgeladen/`` (``.viewer/schematics``) are edited.
Everything else (Litematica folder, .litematic, rotated placements) is first
written there as a copy, so a foreign file is never overwritten.
"""

from __future__ import annotations

import base64
import gzip
import json
import os
import re
import threading

import numpy as np

from .core import nbt
from .core import schematic as sch
from .core.chunk import AIR_NAMES, BLOCKS, base_name, state_props
from .core.mesher import MAGIC, mesh_volume
from .core.models import MODELS
from .core.schem import export as schem_export
from .core.schem import write as schem_write

MAX_EDGE = 512
MAX_VOLUME = 16 * 1024 * 1024
UNDO = 40

# Preferred values when the browser sets a block without properties
PREF = {"facing": "north", "half": "bottom", "type": "bottom", "shape": "straight", "axis": "y",
        "face": "floor", "hinge": "left", "open": "false", "powered": "false", "lit": "false",
        "attachment": "floor", "part": "foot", "occupied": "false", "rotation": "0", "east": "false",
        "west": "false", "north": "false", "south": "false", "up": "false", "down": "false",
        "in_wall": "false", "snowy": "false", "level": "0", "age": "0", "waterlogged": "false",
        "hanging": "false", "triggered": "false", "inverted": "false", "extended": "false",
        "delay": "1", "mode": "compare", "locked": "false", "bottom": "false", "distance": "7",
        "persistent": "true", "candles": "1", "pickles": "1", "layers": "1", "eggs": "1"}


def _block_props(name: str) -> dict:
    """Properties of a block with all possible values (from the blockstate files)."""
    bs = MODELS.blockstates.get(name) if MODELS.available else None
    props: dict[str, list] = {}

    def add(k, v):
        lst = props.setdefault(k, [])
        for x in str(v).split("|"):
            if x not in lst:
                lst.append(x)

    if not bs:
        return props
    for key in (bs.get("variants") or {}):
        for kv in key.split(","):
            k, _, v = kv.partition("=")
            if k:
                add(k, v)

    def walk(cond):
        if isinstance(cond, dict):
            for k, v in cond.items():
                if k in ("OR", "AND"):
                    for c in v:
                        walk(c)
                else:
                    add(k, v)

    for part in bs.get("multipart") or []:
        walk(part.get("when"))
    # Multipart often names only the "on" values (fence: north=true, wall: low|tall)
    for k, vals in props.items():
        if set(vals) <= {"true"}:
            vals.append("false")
        elif ({"low", "tall"} & set(vals) or {"side", "up"} & set(vals)) and "none" not in vals:
            vals.append("none")
    return props


class Editor:
    def __init__(self, placements, rec):
        self.pl = placements
        self.rec = rec
        self.lock = threading.Lock()
        self.sessions: dict[str, dict] = {}
        self._blocks = None
        self._props: dict[str, dict] = {}
        self._defaults: dict[str, str] = {}

    # ---------------------------------------------------------------- Blocks

    def props(self, name: str) -> dict:
        p = self._props.get(name)
        if p is None:
            p = self._props[name] = _block_props(name)
        return p

    def default_state(self, name: str) -> str:
        st = self._defaults.get(name)
        if st is not None:
            return st
        bs = MODELS.blockstates.get(name) if MODELS.available else None
        chosen: dict = {}
        if bs and bs.get("variants"):
            best, score = None, -1
            for key in bs["variants"]:
                kv = dict(x.partition("=")[::2] for x in key.split(",") if x)
                sc = sum(1 for k, v in kv.items() if PREF.get(k) == v)
                if sc > score:
                    best, score = kv, sc
            chosen = dict(best or {})
        for k, vals in self.props(name).items():
            if k not in chosen:
                chosen[k] = PREF[k] if PREF.get(k) in vals else ("false" if "false" in vals else "none" if "none" in vals else vals[0])
        st = sch._norm_state(name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(chosen.items())) + "]" if chosen else ""))
        self._defaults[name] = st
        return st

    def complete(self, state: str) -> str:
        """Fill missing properties with default values."""
        state = sch._norm_state(state or "minecraft:air")
        name = base_name(state)
        if name in AIR_NAMES:
            return "minecraft:air"
        given = state_props(state)
        full = state_props(self.default_state(name))
        full.update({k: v for k, v in given.items() if k in self.props(name) or not self.props(name)})
        return sch._norm_state(name + ("[" + ",".join(f"{k}={v}" for k, v in sorted(full.items())) + "]" if full else ""))

    def blocks(self) -> list:
        """All placeable blocks (with item image), including properties."""
        if self._blocks is None:
            items = {}
            try:
                with open(os.path.join(os.path.dirname(__file__), "assets", "items.json")) as f:
                    items = json.load(f).get("index", {})
            except (OSError, ValueError):
                pass
            out = []
            for name in sorted(MODELS.blockstates if MODELS.available else []):
                if items and name not in items:
                    continue
                out.append({"id": name, "props": self.props(name), "default": self.default_state(name)})
            self._blocks = out
        return self._blocks

    # ---------------------------------------------------------------- Files

    def _editable(self, file_id: str) -> bool:
        return file_id.startswith("hochgeladen/") and file_id.lower().endswith(".schem")

    def _new_name(self, base: str) -> str:
        base = re.sub(r"[^A-Za-z0-9_. ()-]", "_", base).strip(" .")[:60] or "neu"
        n, i = base, 2
        while os.path.exists(os.path.join(self.pl.upload_dir, n + ".schem")):
            n, i = f"{base} ({i})", i + 1
        return n

    def _save_new(self, name: str, ids: np.ndarray, bes=None, origin=None) -> str:
        n = self._new_name(name)
        data = schem_write(ids, n, bes or [], origin=origin)
        p = os.path.join(self.pl.upload_dir, n + ".schem")
        with open(p, "wb") as f:
            f.write(data)
        return f"hochgeladen/{n}.schem"

    @staticmethod
    def _bes_out(s: sch.Schem) -> list:
        out = []
        for be in s.block_entities:
            out.append({"Pos": nbt.IntArray([int(v) for v in be["pos"]]), "Id": be.get("id", ""),
                        "Data": be.get("data") or {}})
        return out

    def open(self, req: dict) -> dict:
        """Open a file or placement for editing; returns the (possibly new) file."""
        pid = req.get("pid")
        if pid:
            pl = self.pl.get(str(pid))
            file_id, rot, mirror = pl["file"], pl.get("rot", 0) % 360, bool(pl.get("mirror"))
            if not self._editable(file_id) or rot or mirror:
                s = self.pl.schem(file_id, rot, mirror)
                new = self._save_new(s.name + " bearbeitet", s.blocks, self._bes_out(s))
                self.pl.update({"id": pl["id"], "rot": 0, "mirror": False})
                self._set_file(pl["id"], new)
                file_id = new
            return self.info(file_id, pid=pl["id"])
        file_id = str(req["file"])
        if not self._editable(file_id):
            s = self.pl.schem(file_id)
            file_id = self._save_new(s.name + " bearbeitet", s.blocks, self._bes_out(s))
        return self.info(file_id)

    def _set_file(self, pid: str, file_id: str):
        with self.pl.lock:
            pls = self.pl._read()
            for p in pls:
                if p.get("id") == pid:
                    p["file"] = file_id
            self.pl._write(pls)

    def rename(self, req: dict) -> dict:
        """Rename a file; placements then point to the new name."""
        file_id = str(req["file"])
        if not self._editable(file_id):
            raise ValueError("nur eigene Schematics lassen sich umbenennen")
        old = file_id.rsplit("/", 1)[-1].rsplit(".", 1)[0]
        want = re.sub(r"[^A-Za-z0-9_. ()-]", "_", str(req.get("name") or "")).strip(" .")[:60]
        if not want:
            raise ValueError("Name fehlt")
        if want == old:
            return self.info(file_id)
        with self.lock:
            s = self._session(file_id)
            n = self._new_name(want) if want.lower() != old.lower() else want
            new_id = f"hochgeladen/{n}.schem"
            new_path = os.path.join(self.pl.upload_dir, n + ".schem")
            s["name"], s["path"] = n, new_path
            os.replace(self.pl.resolve(file_id), new_path)
            # Name in the file too, but only write the saved state (unsaved stays unsaved)
            disk = sch.load(open(new_path, "rb").read(), n + ".schem")
            with open(new_path + ".tmp", "wb") as f:
                f.write(schem_write(disk.blocks.astype(np.uint16), n, self._bes_out(disk)))
            os.replace(new_path + ".tmp", new_path)
            s["mtime"] = os.path.getmtime(new_path)
            self.sessions.pop(file_id, None)
            self.sessions[new_id] = s
        with self.pl.lock:
            pls = self.pl._read()
            for p in pls:
                if p.get("file") == file_id:
                    p["file"] = new_id
                    if p.get("name") in (None, "", old, s["name"]) or str(p.get("name", "")).startswith(old):
                        p["name"] = n
            self.pl._write(pls)
        return self.info(new_id)

    def new(self, req: dict) -> dict:
        w, h, l = (max(1, min(MAX_EDGE, int(v))) for v in (req.get("size") or [16, 16, 16]))
        ids = np.zeros((h, l, w), np.uint16)
        return self.info(self._save_new(str(req.get("name") or "neu"), ids))

    def cut(self, req: dict) -> dict:
        dim = self.rec.dimension(str(req["srv"]), str(req["dim"]))
        if dim is None:
            raise LookupError("Dimension unbekannt")
        c = [int(req[k]) for k in ("x0", "y0", "z0", "x1", "y1", "z1")]
        name = self._new_name(str(req.get("name") or "ausschnitt"))
        data, _ = schem_export(self.rec, dim, *c, name=name)
        s = sch.load(data, name + ".schem")
        # Trim air at the top, otherwise the cutout reaches up to the build height
        filled = np.nonzero(s.blocks.reshape(s.blocks.shape[0], -1).any(1))[0]
        top = int(filled[-1]) + 1 if len(filled) else 1
        blocks = s.blocks[:top]
        if blocks.size > MAX_VOLUME or max(blocks.shape) > MAX_EDGE:
            raise ValueError(f"Ausschnitt zu groß für den Editor ({blocks.shape[2]}×{blocks.shape[0]}×{blocks.shape[1]}), "
                             f"höchstens {MAX_EDGE} pro Kante: Y-Bereich kleiner wählen")
        bes = [be for be in self._bes_out(s) if int(be["Pos"][1]) < top]
        origin = [min(c[0], c[3]), min(c[1], c[4]), min(c[2], c[5])]
        file_id = self._save_new(name, blocks, bes, origin=tuple(origin))
        return self.info(file_id, origin=origin)

    def info(self, file_id: str, **extra) -> dict:
        s = self._session(file_id)
        H, L, W = s["blocks"].shape
        return {"file": file_id, "name": file_id.rsplit("/", 1)[-1].rsplit(".", 1)[0], "size": [W, H, L],
                "undo": len(s["undo"]), "redo": len(s["redo"]), "dirty": bool(s.get("dirty")), **extra}

    def _session(self, file_id: str) -> dict:
        p = self.pl.resolve(file_id)
        mt = os.path.getmtime(p)
        s = self.sessions.get(file_id)
        if s is None or (s["mtime"] != mt and not s.get("dirty")):
            sc = self.pl.schem(file_id)
            s = {"blocks": sc.blocks.astype(np.uint16).copy(), "bes": self._bes_out(sc), "undo": [], "redo": [],
                 "path": p, "mtime": mt, "name": sc.name, "dirty": False, "pshift": [0, 0, 0], "rev": 0}
            self.sessions[file_id] = s
        return s

    def voxels(self, file_id: str) -> dict:
        with self.lock:
            s = self._session(file_id)
            b = s["blocks"]
        used, inv = np.unique(b, return_inverse=True)
        H, L, W = b.shape
        return {"size": [W, H, L], "palette": [BLOCKS.names[int(i)] for i in used],
                "data": base64.b64encode(inv.astype("<u2").tobytes()).decode()}

    def mesh(self, file_id: str):
        """Mesh from the edit state (including unsaved), not from the file."""
        with self.lock:
            s = self._session(file_id)
            key = (s["path"], s["mtime"], s["rev"])
            if s.get("mesh_key") == key:
                return s["mesh"], s["mesh_etag"]
            blocks = s["blocks"].copy()
        data = gzip.compress(mesh_volume(blocks), 5)
        etag = f'"{MODELS.version}-{MAGIC.decode()}-ed-{abs(hash(key))}"'
        with self.lock:
            s["mesh_key"], s["mesh"], s["mesh_etag"] = key, data, etag
        return data, etag

    # ---------------------------------------------------------------- Editing

    def _grow(self, s: dict, lo: tuple, hi: tuple) -> tuple:
        """Grow the array so that [lo, hi] fits. Returns the shift (dx, dy, dz)."""
        b = s["blocks"]
        H, L, W = b.shape
        nlo = (min(0, lo[0]), min(0, lo[1]), min(0, lo[2]))
        nhi = (max(W - 1, hi[0]), max(H - 1, hi[1]), max(L - 1, hi[2]))
        if nlo == (0, 0, 0) and nhi == (W - 1, H - 1, L - 1):
            return (0, 0, 0)
        nw, nh, nl = (nhi[i] - nlo[i] + 1 for i in range(3))
        if max(nw, nh, nl) > MAX_EDGE or nw * nh * nl > MAX_VOLUME:
            raise ValueError(f"Schematic würde zu groß ({nw}×{nh}×{nl}), höchstens {MAX_EDGE} pro Kante")
        dx, dy, dz = -int(nlo[0]), -int(nlo[1]), -int(nlo[2])
        nb = np.zeros((nh, nl, nw), np.uint16)
        nb[dy:dy + H, dz:dz + L, dx:dx + W] = b
        s["blocks"] = nb
        for be in s["bes"]:
            p = be["Pos"]
            be["Pos"] = nbt.IntArray([int(p[0]) + dx, int(p[1]) + dy, int(p[2]) + dz])
        return (dx, dy, dz)

    def edit(self, req: dict) -> dict:
        file_id = str(req["file"])
        ops = req.get("ops") or []
        with self.lock:
            s = self._session(file_id)
            before = (s["blocks"].copy(), [dict(be) for be in s["bes"]])
            shift = [0, 0, 0]
            changed = 0
            try:
                for op in ops:
                    d, n = self._op(s, op, shift)
                    changed += n
                    for i in range(3):
                        shift[i] += int(d[i])
            except Exception:
                s["blocks"], s["bes"] = before
                raise
            if changed or any(shift):
                s["undo"].append((*before, tuple(shift)))
                del s["undo"][:-UNDO]
                s["redo"].clear()
                self._touch(s, shift)
        return {**self.info(file_id), "shift": shift, "changed": changed}

    def _clone(self, s: dict, op: dict, shift: list) -> tuple:
        """Copy/move an area, rotating/mirroring it. ``to`` is the new minimum corner."""
        bx = [int(v) for v in op["box"]]
        sh = np.array(shift)
        lo = np.minimum(bx[:3], bx[3:]) + sh
        hi = np.maximum(bx[:3], bx[3:]) + sh
        b = s["blocks"]
        H, L, W = b.shape
        lo = np.maximum(lo, 0)
        hi = np.minimum(hi, [W - 1, H - 1, L - 1])
        if (hi < lo).any():
            return (0, 0, 0), 0
        sub = b[lo[1]:hi[1] + 1, lo[2]:hi[2] + 1, lo[0]:hi[0] + 1].copy()
        sub = sch.transform(sch.Schem("ausschnitt", sub), int(op.get("rot") or 0), bool(op.get("mirror"))).blocks
        if op.get("move"):
            b[lo[1]:hi[1] + 1, lo[2]:hi[2] + 1, lo[0]:hi[0] + 1] = 0
        sh_, sl_, sw_ = sub.shape
        to = np.array([int(v) for v in op["to"]]) + sh
        d = self._grow(s, tuple(to), tuple(to + np.array([sw_ - 1, sh_ - 1, sl_ - 1])))
        to = to + np.array(d)
        b = s["blocks"]
        view = b[to[1]:to[1] + sh_, to[2]:to[2] + sl_, to[0]:to[0] + sw_]
        mask = np.ones(sub.shape, bool) if op.get("air") else sub != 0
        view[mask] = sub[mask]
        ys, zs, xs = np.nonzero(mask)
        self._drop_bes(s, set(zip((xs + to[0]).tolist(), (ys + to[1]).tolist(), (zs + to[2]).tolist())))
        return d, int(mask.sum())

    def _op(self, s: dict, op: dict, shift: list) -> tuple:
        kind = op.get("op")
        if kind == "clone":
            return self._clone(s, op, shift)
        state = self.complete(str(op.get("block") or "minecraft:air"))
        sid = BLOCKS.id(state)
        if kind == "set":
            pts = np.array([[int(v) for v in p[:3]] for p in op.get("pts") or []], np.int64).reshape(-1, 3)
            if not len(pts):
                return (0, 0, 0), 0
            pts += np.array(shift)
            d = self._grow(s, tuple(pts.min(0)), tuple(pts.max(0))) if sid else (0, 0, 0)
            pts += np.array(d)
            b = s["blocks"]
            H, L, W = b.shape
            ok = (pts[:, 0] >= 0) & (pts[:, 0] < W) & (pts[:, 1] >= 0) & (pts[:, 1] < H) & (pts[:, 2] >= 0) & (pts[:, 2] < L)
            pts = pts[ok]
            b[pts[:, 1], pts[:, 2], pts[:, 0]] = sid
            self._drop_bes(s, {tuple(p) for p in pts.tolist()})
            return d, len(pts)
        if kind in ("fill", "replace", "walls", "hollow"):
            bx = [int(v) for v in op["box"]]
            lo = np.minimum(bx[:3], bx[3:]) + np.array(shift)
            hi = np.maximum(bx[:3], bx[3:]) + np.array(shift)
            d = self._grow(s, tuple(lo), tuple(hi)) if sid and kind != "replace" else (0, 0, 0)
            lo, hi = lo + np.array(d), hi + np.array(d)
            b = s["blocks"]
            H, L, W = b.shape
            lo = np.maximum(lo, 0)
            hi = np.minimum(hi, [W - 1, H - 1, L - 1])
            if (hi < lo).any():
                return d, 0
            view = b[lo[1]:hi[1] + 1, lo[2]:hi[2] + 1, lo[0]:hi[0] + 1]
            if kind == "fill":
                mask = np.ones(view.shape, bool)
            elif kind == "replace":
                frm = str(op.get("from") or "")
                names = BLOCKS.names
                if not frm or frm == "*":
                    mask = view != 0                       # everything except air
                elif "[" in frm:
                    mask = view == BLOCKS.id(self.complete(frm))
                else:
                    frm = sch._norm_state(frm)
                    ids = np.unique(view)
                    hit = [i for i in ids.tolist() if base_name(names[i]) == frm or (frm in AIR_NAMES and i == 0)]
                    mask = np.isin(view, hit)
            else:
                mask = np.zeros(view.shape, bool)
                mask[:, :, 0] = mask[:, :, -1] = True
                mask[:, 0, :] = mask[:, -1, :] = True
                if kind == "hollow":
                    mask[0], mask[-1] = True, True
                    view[1:-1, 1:-1, 1:-1] = 0
            n = int(mask.sum())
            if kind == "replace" and op.get("keep") and sid:
                # Keep orientation: stairs stay stairs facing the same direction
                tgt = base_name(BLOCKS.names[sid])
                orig = view.copy()
                for i in np.unique(orig[mask]).tolist():
                    pr = state_props(BLOCKS.names[i])
                    st = tgt + ("[" + ",".join(f"{k}={v}" for k, v in sorted(pr.items())) + "]" if pr else "")
                    view[mask & (orig == i)] = BLOCKS.id(self.complete(st))
            else:
                view[mask] = sid
            ys, zs, xs = np.nonzero(mask)
            self._drop_bes(s, set(zip((xs + lo[0]).tolist(), (ys + lo[1]).tolist(), (zs + lo[2]).tolist())))
            return d, n
        raise ValueError(f"unbekannte Bearbeitung {kind!r}")

    @staticmethod
    def _drop_bes(s: dict, pts: set):
        if pts and s["bes"]:
            s["bes"] = [be for be in s["bes"] if tuple(int(v) for v in be["Pos"]) not in pts]

    def _save(self, file_id: str, s: dict, shift):
        data = schem_write(s["blocks"], s["name"], s["bes"])
        tmp = s["path"] + ".tmp"
        with open(tmp, "wb") as f:
            f.write(data)
        os.replace(tmp, s["path"])
        s["mtime"] = os.path.getmtime(s["path"])
        if any(shift):
            # Shift this file's placements so the blocks stay put in the world
            with self.pl.lock:
                pls = self.pl._read()
                for p in pls:
                    if p.get("file") == file_id and not p.get("rot") and not p.get("mirror"):
                        p["x"] -= shift[0]
                        p["y"] -= shift[1]
                        p["z"] -= shift[2]
                self.pl._write(pls)

    @staticmethod
    def _touch(s: dict, shift):
        """Keep the edit in memory only; ``save`` writes the file."""
        s["dirty"] = True
        s["rev"] += 1
        s["pshift"] = [s["pshift"][i] + int(shift[i]) for i in range(3)]

    def save(self, req: dict) -> dict:
        """Write unsaved edits to the file."""
        file_id = str(req["file"])
        with self.lock:
            s = self._session(file_id)
            if s.get("dirty"):
                self._save(file_id, s, s["pshift"])
                s["dirty"], s["pshift"] = False, [0, 0, 0]
        return {**self.info(file_id), "saved": True}

    def discard(self, req: dict) -> dict:
        """Discard unsaved edits: the file is reread on next access."""
        file_id = str(req["file"])
        with self.lock:
            self.sessions.pop(file_id, None)
        return {**self.info(file_id), "shift": [0, 0, 0]}

    def undo(self, req: dict, redo: bool = False) -> dict:
        file_id = str(req["file"])
        with self.lock:
            s = self._session(file_id)
            src, dst = (s["redo"], s["undo"]) if redo else (s["undo"], s["redo"])
            if not src:
                return {**self.info(file_id), "shift": [0, 0, 0], "changed": 0}
            blocks, bes, sh = src.pop()
            dst.append((s["blocks"].copy(), [dict(be) for be in s["bes"]], sh))
            s["blocks"], s["bes"] = blocks, bes
            # Undo or redo the shift from growing as well
            move = tuple(int(v) for v in sh) if redo else tuple(-int(v) for v in sh)
            self._touch(s, move)
        return {**self.info(file_id), "shift": list(move), "changed": 1}
