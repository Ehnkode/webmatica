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
| `BuildControl` | polls /api/control: printer and layer settings, the guard switches, server-safe |
| `WebPrinter` | Webmatica's own printer: target blocks from /api/printer/blocks, places them within reach (server-safe: only what a player could do by hand) |
| `ServerSafe` | the server-safe switch: stops the bot tasks when it turns on |
| `BaritoneBridge` | polls /api/commands and hands each command to a task or to Baritone |
| `FetchTask`, `UnpackTask`, `FarmTask` | multi-step bot jobs (fetch/store, empty shulkers, break and pick up) |
| `BotTask` | their shared base: one command at a time, phases on the client tick, frozen while paused or eating, the result report |
| `BaritoneApi` | every call into Baritone (reflection through its API interfaces): commands, settings, walking, state |
| `AutoTest` | game tests for the dev client (`-Pautotest`, see testing.md); inactive otherwise |
| `Walker` | every walk of the tasks and of goto: Baritone if installed, else `NativeWalker`; switches over when Baritone is stuck (guard `engine`: auto, native, baritone) |
| `NativeWalker` | own walking: A* over standable spots (step up 1, drop 3, never digs or places), follows the path with the movement keys |
| `BaritoneSafety` | walk without breaking blocks unless the guard allows digging; restore Baritone's settings |
| `AutoGuard` | eat and save tools while an automation runs; pause and resume |
| `LitematicaBridge`, `Highlighter` | placements into Litematica (reflection), particles on chests |
| `ViewerLauncher` | F10: builds the block images on first use, starts the viewer server, opens the browser |

Baritone and Litematica are optional: they are only touched through reflection,
so the mod runs without them.

**Viewer server** (`src/main/java/io/github/ehnkode/webmatica/viewer/`, plain Java,
no Minecraft imports). Runs inside the game: reads the recording, serves the web page
on 127.0.0.1:8770 and runs the automations' logic. The mod only executes steps.

| file | job |
|---|---|
| `Server` | start-up, request dispatch, Host/Origin checks, pre-render and automation loops; `main` runs it without the game |
| `Routes`, `Router`, `Http` | the answer to each URL |
| `App` | the state of a running viewer (recording, tile cache, player, every store) |
| `Stores` | smaller stores: commands queue, chests, printer/server-safe/layer control, history, radar, lids |
| `core/` | reading the recording: region files and NBT (`Recording`, `Region`, `Nbt`, `Chunk`), map tiles (`Tiles`, `Palette`, `Png`), block models (`Models`), schematics (`Schematic`) |
| `Mesher`, `Xray` | 3D chunk meshes, X-ray search |
| `AutoBuild` | auto-build, storing, farming and flattening: one step per server every 2 seconds |
| `Placements`, `Editor` | placed schematics and the schematic editor |
| `Farm`, `Spawn`, `WorldChests` | area scans, mob spawn spots, chests in the recording |
| `AssetBuilder` | block and item images from the game jar, built once per Minecraft version |

**Web page and reference** (`viewer/`). `viewer/static/` is the web page, packed
into the jar. The Python files next to it are the earlier viewer: the Java server
gives the same answers, and its tests (`python3 -m unittest discover -s viewer/tests -t .`)
still describe the expected behavior.

## The web page

ES modules without a build step. Each file imports what it uses from the
others and exports what the others use (`export { ... }` at the end).

Load order: every file first imports the file before it (`import './prev.js';`),
and `static/index.html` loads only the last one, `js/start.js`. So the files run
in a fixed order, the one listed in index.html: core, world, sidebar, places,
player, selection, schematics, materials, chests, gif, history, keyboard,
automation, radar, farm, xray, safe, start. Code that runs while a file loads may only
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
| `js/safe.js` | the server-safe switch in the options |
| `view3d.js`, `view3d/` | the three.js 3D view (ES module); the player figure and the markers are in `view3d/` |
| `editor.js` + `editor.html` | the schematic editor page |

New UI text is written in German; add the English line to `i18n/en.tsv`
(`{}` stands for numbers or names).

## Following one feature through

"Break wrong blocks" as an example:

1. `js/materials.js` shows the button after a progress check
   (`GET /api/schematics/<id>/verify`, answered in `Routes`,
   computed in `core/Schematic.verify`).
2. `js/farm.js` marks the blocks in 3D (`view3d.setVerify`) and, after the
   confirmation, sends `POST /api/fix` (`Routes`).
3. The viewer turns the points into boxes and queues a `breakboxes` command.
4. The mod's `BaritoneBridge` picks it up on its next poll and starts `FarmTask`,
   which reports back with `POST /api/commands/result`.
