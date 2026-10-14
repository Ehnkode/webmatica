# Architecture

A map of the code for people who want to change it. How the parts talk to
each other (every endpoint and field) is in [protocol.md](protocol.md).

## The two halves

**Mod** (`src/main/java/io/github/ehnkode/webmatica/`, Fabric, client only).
Records what the player sees and is the hands of the automations.
Built for several Minecraft versions with Stonecutter (`stonecutter.gradle.kts`);
version differences live in `Mc.java` and `//?` comments.

| file | job |
|---|---|
| `MapperClient` | entry point: keys (F9), ticks, world change |
| `RegionStore`, `ChunkSnapshot`, `WorldChunkMixin`, `RecordingTarget` | write seen chunks as region files, one folder per world |
| `PositionReporter` | POST /api/position several times a second (position, inventory, mods found, other players) |
| `ContainerTracker`, `ChestLidTracker` | chest contents when opened, lids opened by others |
| `BuildControl` | polls /api/control: Printer and layer settings, the guard switches |
| `BaritoneBridge` | polls /api/commands and hands each command to a task or to Baritone |
| `FetchTask`, `UnpackTask`, `FarmTask` | multi-step bot jobs (fetch/store, empty shulkers, break and pick up) |
| `BotTask` | their shared base: one command at a time, phases on the client tick, frozen while paused or eating, the result report |
| `BaritoneApi` | every call into Baritone (reflection through its API interfaces): commands, settings, walking, state |
| `BaritoneSafety` | walk without breaking blocks unless the guard allows digging; restore Baritone's settings |
| `AutoGuard` | eat and save tools while an automation runs; pause and resume |
| `LitematicaBridge`, `Highlighter` | placements into Litematica (reflection), particles on chests |
| `ViewerLauncher` | unpack and start the viewer (finds Python, installs requirements, venv fallback) |

Baritone and Litematica are optional: they are only touched through reflection,
so the mod runs without them.

**Viewer** (`viewer/`, Python 3, no framework). Reads the recording, serves
the web page and runs the automations' logic. The mod only executes steps.

| path | job |
|---|---|
| `server.py` | start-up, request handler, Host/Origin checks |
| `api/` | the answer to each URL, one module per feature: `pages`, `maps`, `game`, `schematics` |
| `app.py` | `App`: the state of a running viewer (recording, tile cache, player, every store) |
| `workers.py` | 3D meshes and X-ray in worker processes, pre-rendering, the automation loop |
| `core/` | reading the recording: region files and NBT (`world`, `nbt`, `chunk`), map tiles (`tiles`, `colors`), 3D meshes (`mesher`, `models`), schematics (`schem`, `schematic`) |
| `autobuild.py`, `automation/` | auto-build, storing, farming and flattening: the step loop in `autobuild.py`, one module per mode |
| `extras.py` | smaller stores: commands queue, chests, Printer/layer control, history, radar, lids |
| `placements.py`, `editor.py` | placed schematics and the schematic editor |
| `farm.py`, `spawn.py`, `xray.py` | area scans, mob spawn spots, X-ray search |
| `static/` | the web page (see below) |
| `tests/` | `python3 -m unittest discover -s viewer/tests -t .` |

## The web page

ES modules without a build step. Each file imports what it uses from the
others and exports what the others use (`export { ... }` at the end).

Load order: every file first imports the file before it (`import './prev.js';`),
and `static/index.html` loads only the last one, `js/start.js`. So the files run
in a fixed order, the one listed in index.html: core, world, sidebar, places,
player, selection, schematics, materials, chests, gif, history, keyboard,
automation, radar, farm, xray, start. Code that runs while a file loads may only
use files earlier in that list; inside functions and event handlers every
import is available. `core.js` imports nothing.
A new file goes into this chain (its first import is the file before it, the
file after it imports the new one) and into the comment in index.html.

Only `i18n.js`, `interp.js` and Leaflet stay plain scripts (globals `I18N`,
`PlayerInterp`, `L`).

| file | job |
|---|---|
| `i18n.js` | translates the German UI on the fly with `i18n/en.tsv` (German text is the key) |
| `js/core.js` | state, helpers (`$`, `api`, `fmt`, `esc`), the Leaflet map |
| `js/world.js` | the shown world, dimension and layer: selector buttons, map tiles |
| `js/player.js` | live player, game status chip, 2D/3D switch |
| `js/sidebar.js`, `js/keyboard.js`, `js/start.js` | layout, shortcuts, first view |
| `js/schematics.js`, `js/materials.js` | placing schematics, material list, progress check |
| `js/farm.js`, `js/automation.js` | farming, flattening, wrong blocks, auto-build, storing |
| `js/chests.js`, `js/places.js`, `js/history.js`, `js/radar.js`, `js/xray.js`, `js/selection.js` | the other tabs |
| `view3d.js`, `view3d/` | the three.js 3D view (ES module); the player figure and the markers are in `view3d/` |
| `editor.js` + `editor.html` | the schematic editor page |

New UI text is written in German; add the English line to `i18n/en.tsv`
(`{}` stands for numbers or names).

## Following one feature through

"Break wrong blocks" as an example:

1. `js/materials.js` shows the button after a progress check
   (`GET /api/schematics/<id>/verify`, answered in `api/schematics.py`,
   computed in `core/schematic.py`).
2. `js/farm.js` marks the blocks in 3D (`view3d.setVerify`) and, after the
   confirmation, sends `POST /api/fix` (`api/game.py`).
3. The viewer turns the points into boxes and queues a `breakboxes` command.
4. The mod's `BaritoneBridge` picks it up on its next poll and starts `FarmTask`,
   which reports back with `POST /api/commands/result`.
