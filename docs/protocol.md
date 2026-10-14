# Protocol

How the browser, the viewer and the mod talk to each other. Everything runs on
the player's own computer: the viewer listens on `127.0.0.1:8770`, the mod and
the browser are its clients.

```
 Minecraft + mod  ──POST /api/position (5x/s)──▶  viewer (Python)  ◀──GET/POST──  browser
                  ◀─GET /api/control, /api/commands (polls)─┘
                  ──POST /api/commands/result, /api/control/status──▶
```

The viewer never connects to the game; the mod asks. So a command from the
browser waits in the viewer until the mod's next poll picks it up.

## Security

* Only `Host: localhost` or an IP literal is answered (protects against DNS
  rebinding: a foreign site pointing its name at 127.0.0.1).
* POST requests with an `Origin` other than 127.0.0.1 / localhost / the bind
  address are refused, so a web page in the same browser cannot drive the bot.

## What the mod sends

**POST /api/position** (several times a second)

| field | meaning |
|---|---|
| x, y, z, yaw, pitch | player position |
| dim | `minecraft:overworld` etc. |
| server | world folder name (server address or `singleplayer-<save>`) |
| name, skinModel, skinFace, skinFull | player and skin (PNG as base64, only when changed) |
| recording | F9 state; the automations pause while it is off |
| written | chunks written so far |
| mods | `{"litematica": bool, "baritone": bool, ...}`, missing on old mod versions |
| others | players in view distance (for the radar) |
| inv | inventory slots `[{id, count, slot}]` |

**POST /api/containers**, **/api/containers/lid**: chest contents when the
player opens one; lids other players open.

**POST /api/litematica/status**: which placements arrived in Litematica.

**POST /api/control/status**: in-game Printer / layer state, with the `seq` it has applied.

**POST /api/commands/result** `{id, ok, msg}`: outcome of a command.

## What the mod polls

**GET /api/control?srv=** (about every second, with a 2 s timeout)

```json
{"seq": 3, "printer": {"on": true}, "layer": {"mode": "range", "min": 64, "max": 64},
 "guard": {"eat": true, "tools": true, "dig": false}}
```

* `printer` holds only the changed Printer settings and is sent until the mod confirms that `seq` in /api/control/status.
* `guard` comes from the protection switches. `eat` and `tools` only apply
  while an automation runs; `dig` always applies (also when walking from the
  map). Without an answer for 5 s the mod falls back to "no guard" and walking
  never breaks blocks.

**GET /api/commands?srv=** returns the waiting commands and removes them. Kinds:

| kind | what the mod does |
|---|---|
| goto {x, y?, z} | Baritone walks there (no digging unless `guard.dig`) |
| fetch / store | FetchTask: take items from chests / put inventory into chests |
| unpack | UnpackTask: empty shulker boxes |
| breakboxes {boxes, pickup} | FarmTask: break the blocks in the boxes, pick the drops up |
| chestdump | FarmTask: put the farmed items into a chest |
| clear {x0..z1} | Baritone `sel cleararea` |
| litematica {pid} | Baritone builds that Litematica placement |
| highlight {pts, label} | particles on chests |
| pause / resume / stop | stop also cancels every task and restores Baritone's settings |

## Browser endpoints

All answers are JSON unless an image or binary mesh. Errors are
`{"error": "..."}` with status 400 (bad parameters), 403, 404 or 500.
The code for each one is in `viewer/api/` (search for the path).

```
GET  /                                   UI
GET  /api/worlds                         servers, dimensions, layers
GET  /api/tile/<srv>/<dim>/<layer>/<z>/<x>/<y>.png[?at=YYYY-MM-DD|raw=1]   (at: state on that day, changed chunks only; raw: without shading)
GET  /api/changes?srv=&dim=&since=       regions changed since <since>
GET  /api/column?srv=&dim=&x=&z=&layer=  top block, height, biome at a spot
GET  /api/markers?srv=&dim=&layer=       signs + containers per chunk
GET  /api/waypoints  POST /api/waypoints waypoints (in the recording's waypoints.json)
POST /api/position                       live position from the mod
GET  /api/player                         last position (+ active)
GET  /api/skin/face.png | full.png       skin from the client
GET  /api/lod/<srv>/<dim>/<x>/<z>[?level=0..3]  height field for the 3D far view (level 0 = region, 4x larger per level)
GET  /api/trail?srv=&dim=                player trail
GET  /api/schematics?srv=&dim=           placed schematics
GET  /api/schematics/library             schematic files in the folders
GET  /api/schematics/<id>/mesh|materials|verify
POST /api/schematics/place|update|delete
POST /api/schematics/upload?name=        upload a file (raw data)
GET  /api/litematica?srv=                placements with "In game" for the mod
POST /api/litematica/status              mod reports what arrived in Litematica
GET  /api/containers?srv=                chest inventory     POST /api/containers (mod) | /delete
GET  /api/containers/world?srv=&dim=&x=&z=&r=  all chests in the recording (even never opened)
GET  /api/containers/lid?srv=&dim=        chests opened by others   POST (mod)
GET  /api/inventory                      last player inventory (from the mod)
GET  /api/commands?srv=                  Baritone commands for the mod (pick up)
POST /api/commands                       drop a command      POST /api/commands/result (mod)
GET  /api/auto?srv=                      auto-build state    POST /api/auto {srv, pid} start / {srv} stop
GET  /api/control?srv=                   Printer/layer target state for the mod   POST /api/control (browser)
POST /api/control/status                 mod reports in-game Printer/layer   GET /api/control/state?srv=
GET/POST /api/auto/settings              auto-build protection: radar, radarDist, friends, eat, tools, leftovers, fix
POST /api/auto {srv, queue: [pid]}       set the queue
POST /api/auto {srv, farm: {area, dim, targets, chest, placeChest}}   start farming
POST /api/auto {srv, farm: {area, dim, flatten: {kind: flat|clear|fill, level, clear, fill, block}, chest, placeChest}}   flatten
GET  /api/farm/scan?srv=&dim=&x0=..      which blocks are in the area
GET  /api/farm/flatscan?..&kind=&level=  flatten preview: blocks to break / holes to fill, per chunk, what gets mined
GET  /api/spawn/<srv>/<dim>/<cx>/<cz>    where mobs can spawn (needs light in the recording)
GET  /api/spawn-tile/<srv>/<dim>/<rx>/<rz>.png[?ymax=]   the same as a 2D overlay per region
POST /api/fix {srv, pid, extra}          have wrong/rotated (and extra) blocks broken
GET  /api/radar?srv=                     log: who was within view distance when
GET  /editor?file=                       schematic editor (separate page)
GET  /api/editor/blocks|voxels|mesh|info editor data   POST /api/editor/open|new|cut|edit|undo|redo|save|discard
GET  /api/mesh/<srv>/<dim>/<cx>/<cz>     3D chunk [?full=1 caves, ylo/yhi layer cut, xray=a,b only those blocks, at=YYYY-MM-DD state on that day]
GET  /api/xray/names                     block names for the X-ray search
GET  /api/xray/find?srv=&dim=&x=&z=&r=&b=a,b   searched blocks around x/z (r chunks) for the 2D map
GET  /api/history/dates?srv=&dim=        timeline: days with a backed-up state
GET  /api/history/diff?srv=&dim=&date=   changes since that day
```
