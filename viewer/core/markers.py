"""Interessante Block-Entities je Chunk: Schildtexte und Behaelter (Stash-Finder).

Der Server schickt dem Client alle Block-Entities eines Chunks mit Typ und
Position (Inhalte von Truhen aber nicht). Die Mod speichert sie mit, daher
lassen sich Truhen, Shulker usw. zaehlen, ohne dass man sie geoeffnet hat.
"""

from __future__ import annotations

import json

from .chunk import Chunk

CONTAINERS = {
    "chest": "Truhe", "trapped_chest": "Truhe", "barrel": "Fass",
    "shulker_box": "Shulker", "ender_chest": "Endertruhe", "hopper": "Trichter",
    "dispenser": "Werfer", "dropper": "Spender", "furnace": "Ofen",
    "blast_furnace": "Ofen", "smoker": "Ofen", "brewing_stand": "Braustand",
    "crafter": "Crafter", "chiseled_bookshelf": "Buecherregal",
}
SIGNS = {"sign", "hanging_sign"}


def _text(v) -> str:
    """Textkomponente (JSON-String oder NBT-Compound) -> Klartext."""
    if isinstance(v, str):
        s = v.strip()
        if s[:1] in ('"', "{", "["):
            try:
                return _text(json.loads(s))
            except ValueError:
                return s
        return s
    if isinstance(v, list):
        return "".join(_text(x) for x in v)
    if isinstance(v, dict):
        out = _text(v.get("text", "")) if "text" in v else ""
        if "translate" in v and not out:
            out = v.get("fallback", v["translate"])
        for x in v.get("extra", []) or []:
            out += _text(x)
        return out
    return ""


def chunk_markers(ch: Chunk) -> dict:
    signs, containers = [], {}
    for be in ch.block_entities:
        if not isinstance(be, dict):
            continue
        kind = str(be.get("id", "")).split(":", 1)[-1]
        if kind in SIGNS:
            lines = []
            for side in ("front_text", "back_text"):
                msgs = (be.get(side) or {}).get("messages") or []
                lines += [_text(m) for m in msgs]
            if not lines and "Text1" in be:  # altes Format
                lines = [_text(be.get(f"Text{i}", "")) for i in range(1, 5)]
            lines = [l for l in lines if l.strip()]
            if lines:
                signs.append({"x": be.get("x"), "y": be.get("y"), "z": be.get("z"), "lines": lines})
        else:
            label = CONTAINERS.get(kind)
            if label is None and kind.endswith("shulker_box"):
                label = "Shulker"
            if label:
                containers[label] = containers.get(label, 0) + 1
    return {"signs": signs, "containers": containers}
