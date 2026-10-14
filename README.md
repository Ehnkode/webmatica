# Webmatica
[![Latest Release](https://img.shields.io/github/v/release/Ehnkode/webmatica?style=flat-square&color=blue)](https://github.com/Ehnkode/webmatica/releases/latest)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg?style=flat-square)](LICENSE)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.11%20--%2026.3-informational?style=flat-square&logo=minecraft)](https://github.com/Ehnkode/webmatica/releases)
[![Python Version](https://img.shields.io/badge/Python-3.10%2B-blue?style=flat-square&logo=python)](https://www.python.org/)

**English** · [Deutsch](README.de.md)

Your Minecraft world in the browser: map, 3D view, chests and schematics, plus remote control for
Litematica and Baritone. Works on any server or Singleplayer.

![Webmatica showcase](docs/showcase.gif)

- **Mod** (Fabric, Minecraft 1.21.11 to 26.3, client only): saves every chunk you see as normal region files and
  remembers chest contents. Sends nothing to the server or the chat.
- **Viewer** (Python, runs locally at <http://127.0.0.1:8770>): everything below, in your browser.
  English and German (switch under Options).

## Features

| | |
|---|---|
| ![Desert village in 3D](docs/village.jpg) | ![Right-click the map and Baritone walks there](docs/goto.gif) |
| ![Click a chest to see its contents, search all chests](docs/storage.gif) | ![Place a schematic in the world and move it](docs/place.gif) |
| ![Cutting through a schematic layer by layer](docs/editor-cut.gif) | ![Building a house in the schematic editor](docs/build.gif) |

- **Map and 3D** of everything you have seen, with real block models, smooth light, shadows and a
  far view of up to 65,000 blocks. Live position, trail, follow camera and mob spawn spots.
- **Schematic editor:** place, break and fill blocks, replace or delete every block of a type, copy,
  move and rotate, with undo. Any area of your world becomes a schematic with two clicks.
- **Schematics:** load `.litematic`, `.schem` or `.nbt`, place them on the map, send them to
  Litematica with one click, and see which items are still missing. Your inventory and chests are
  already subtracted.
- **Automation with Baritone:** farm chosen blocks, clear, fill or flatten an area, or build a whole
  schematic. Materials come from your chests, leftovers go back, and it pauses when a player comes
  close. A queue builds several schematics in a row.
- **Storage:** every chest you open is remembered, shulker contents included. Search an item across
  all chests, let Baritone fetch exactly the stacks you picked, or store your inventory where each
  item already is. Stash finder included.
- **Printer:** places a schematic's blocks as soon as you are near them, no Litematica needed.
  It turns your head only towards the server, so stairs and logs come out the right way.
- **Server-safe** (options, per server): for public servers. Turns off Baritone, every automation,
  the Litematica Printer and X-ray; the printer then only places what you could by hand (no faked look
  direction, only visible faces, at most 5 blocks per second).
- **X-Ray:** show only the blocks you search for, in 3D and on the map, and send Baritone straight to a find.
- **More:** waypoints, signs, a timelapse of what changed (export as GIF or video), radar for nearby
  players, search for coordinates or waypoints.

## Requirements

- Minecraft 1.21.11, 26.1, 26.2 or 26.3 with Fabric Loader and Fabric API
- Python 3.10+
- Optional: [Baritone](https://github.com/cabaletta/baritone), [Litematica](https://modrinth.com/mod/litematica) + [MaLiLib](https://modrinth.com/mod/malilib), Litematica Printer (Webmatica has its own printer that works without them)

## Install

1. Put the jar for your Minecraft version (`webmatica-<version>+mc<minecraft>.jar`) from Modrinth or
   [Releases](https://github.com/Ehnkode/webmatica/releases) into your `mods` folder.
2. Start Minecraft and press **F10**. The viewer starts and opens in your browser. The first
   start takes about a minute (it installs numpy and Pillow and builds the block images).

The viewer comes with the mod and lives in `<game folder>/webmatica-viewer/`. It stops when you
close the game. To use it without the game, run `start-viewer.bat` in that folder
(Linux/macOS: `./start-viewer.sh`).

**F9** in game toggles recording, **F10** opens the viewer. **?** in the viewer shows all shortcuts.

## Build the mod

```bash
./gradlew buildAll    # one jar per Minecraft version in build/libs/
```

## How it's made

Webmatica is built with the help of AI (Claude). The idea, the direction, testing in the game and all decisions are mine, the AI helps write code and text.

## Notes

Automation is not allowed on every server, use at your own risk. Recordings reveal coordinates,
don't share them.

MIT license, see [LICENSE](LICENSE).
