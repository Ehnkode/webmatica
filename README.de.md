# Webmatica

[English](README.md) · **Deutsch**

Deine Minecraft-Welt im Browser: Karte, 3D-Ansicht, Kisten und Schematics, dazu Litematica und
Baritone fernsteuern. Für 2b2t gebaut, läuft auf jedem Server.

![3D-Ansicht einer Demo-Welt](docs/screenshot-3d.jpg)

- **Mod** (Fabric 1.21.11, nur Client): speichert jeden Chunk, den du siehst, als normale
  Regiondateien und merkt sich Kisteninhalte. Schickt nichts an den Server oder in den Chat.
- **Viewer** (Python, läuft lokal unter <http://127.0.0.1:8770>): Karte, 3D, Kisten,
  Schematic-Editor, Autobau mit Baritone. Oberfläche auf Deutsch und Englisch (Umschalter oben links).

## Voraussetzungen

- Minecraft 1.21.11 mit Fabric Loader und Fabric API
- Python 3.10+ (Windows: „Add python.exe to PATH“ anhaken)
- Optional: [Baritone](https://github.com/cabaletta/baritone), [Litematica](https://modrinth.com/mod/litematica) + [MaLiLib](https://modrinth.com/mod/malilib), Litematica Printer

## Installation

1. `webmatica-<version>.jar` von den [Releases](https://github.com/Ehnkode/webmatica/releases)
   in den `mods`-Ordner legen.
2. Minecraft einmal mit der Mod starten.
3. Dieses Repository herunterladen (Code → Download ZIP), entpacken und `start-viewer.bat`
   starten (Linux/macOS: `./start-viewer.sh`). Der erste Start dauert etwa eine Minute.

**F9** im Spiel schaltet die Aufnahme an und aus. **?** im Viewer zeigt alle Tastenkürzel.

## Mod bauen

```bash
./gradlew build    # Jar in build/libs/
```

## Hinweise

Automatisierung ist nicht auf jedem Server erlaubt, Nutzung auf eigenes Risiko. Aufnahmen verraten
Koordinaten, also nicht teilen.

MIT-Lizenz, siehe [LICENSE](LICENSE).
