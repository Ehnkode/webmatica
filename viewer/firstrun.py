"""Erster Start ohne Handarbeit: Aufnahme-Ordner und Blocktexturen finden.

Die Mod schreibt beim Spielstart ``~/.webmatica/client.json`` mit dem Pfad zur
Aufnahme und zur Spiel-Jar. Daraus baut der Viewer einmalig seine Block- und
Item-Bilder (die gehoeren Mojang und liegen deshalb nicht im Repo) und weiss, welche
Aufnahme er zeigen soll. Ohne diese Datei helfen ``--recording`` und ``--jar``.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import zipfile

ASSETS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "assets")
NEEDED = ("blocks.json", "blocks.png", "items.json", "items.png")
INFO = os.path.join(os.path.expanduser("~"), ".webmatica", "client.json")
MC_VERSION = "1.21.11"


def client_info() -> dict:
    try:
        with open(INFO, encoding="utf-8") as f:
            return json.load(f)
    except (OSError, ValueError):
        return {}


def _has_assets(jar: str) -> bool:
    try:
        with zipfile.ZipFile(jar) as z:
            return any(n.startswith("assets/minecraft/blockstates/") for n in z.namelist())
    except (OSError, zipfile.BadZipFile):
        return False


def find_jar() -> str | None:
    """Spiel-Jar mit den Minecraft-Assets: erst aus der Mod, dann die ueblichen Orte."""
    cands = []
    info = client_info()
    if info.get("gameJar"):
        cands.append(info["gameJar"])
    home = os.path.expanduser("~")
    rel = os.path.join("versions", MC_VERSION, MC_VERSION + ".jar")
    if os.environ.get("APPDATA"):
        cands.append(os.path.join(os.environ["APPDATA"], ".minecraft", rel))
    cands += [os.path.join(home, ".minecraft", rel),
              os.path.join(home, "Library", "Application Support", "minecraft", rel)]
    for c in cands:
        if c and os.path.isfile(c) and _has_assets(c):
            return c
    return None


def find_recording() -> str | None:
    rec = client_info().get("recording")
    if rec:
        os.makedirs(rec, exist_ok=True)       # Mod laeuft, hat aber noch nichts aufgenommen
        return rec
    return None


def assets_ready() -> bool:
    return all(os.path.exists(os.path.join(ASSETS, n)) for n in NEEDED)


def build_assets(jar: str) -> None:
    print(f"First start: building block textures and item images from {jar} (takes about a minute) …", flush=True)
    for tool in ("build_assets", "build_items"):
        subprocess.run([sys.executable, "-m", f"viewer.tools.{tool}", "--jar", jar], check=True,
                       cwd=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    print("Done.", flush=True)


def prepare(argv: list[str]) -> list[str]:
    """Nimmt ``--jar`` aus den Argumenten, baut fehlende Assets, setzt die Aufnahme als Standard."""
    args = list(argv)
    jar = None
    if "--jar" in args:
        i = args.index("--jar")
        jar = args[i + 1] if i + 1 < len(args) else None
        del args[i:i + 2]
    if not assets_ready():
        jar = jar or find_jar()
        if not jar:
            sys.exit("Block textures are missing and the Minecraft jar was not found.\n"
                     "Start Minecraft with the mod once (then the viewer finds everything by itself)\n"
                     f"or pass it: python -m viewer --jar \"<.minecraft>/versions/{MC_VERSION}/{MC_VERSION}.jar\"")
        build_assets(jar)
    if "--recording" not in args and "-r" not in args and not os.environ.get("MAPPER_RECORDING"):
        rec = find_recording()
        if rec:
            os.environ["MAPPER_RECORDING"] = rec
    return args
