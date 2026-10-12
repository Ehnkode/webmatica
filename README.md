# Webmatica

**English** · [Deutsch](README.de.md)

Your Minecraft world in the browser: map, 3D view, chests and schematics, plus remote control for
Litematica and Baritone. Works on any server or Singleplayer.

![3D view of a demo world](docs/screenshot-3d.jpg)

- **Mod** (Fabric 1.21.11, client only): saves every chunk you see as normal region files and
  remembers chest contents. Sends nothing to the server or the chat.
- **Viewer** (Python, runs locally at <http://127.0.0.1:8770>): map, 3D, chests, schematic editor,
  auto build with Baritone. Interface in English and German (switch at the top left).

## Requirements

- Minecraft 1.21.11 with Fabric Loader and Fabric API
- Python 3.10+ (Windows: tick "Add python.exe to PATH")
- Optional: [Baritone](https://github.com/cabaletta/baritone), [Litematica](https://modrinth.com/mod/litematica) + [MaLiLib](https://modrinth.com/mod/malilib), Litematica Printer

## Install

1. Put `webmatica-<version>.jar` from [Releases](https://github.com/Ehnkode/webmatica/releases)
   into your `mods` folder.
2. Start Minecraft once with the mod.
3. Download this repository (Code → Download ZIP), unzip it and run `start-viewer.bat`
   (Linux/macOS: `./start-viewer.sh`). The first start takes about a minute.

**F9** in game toggles recording. **?** in the viewer shows all shortcuts.

## Build the mod

```bash
./gradlew build    # jar in build/libs/
```

## Notes

Automation is not allowed on every server, use at your own risk. Recordings reveal coordinates,
don't share them.

MIT license, see [LICENSE](LICENSE).
