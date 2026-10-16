# Testing

## Translations

The English table must cover every German text of the web page:

```
node tools/check-i18n.mjs
```

## Game tests (AutoTest)

The dev client creates a fresh superflat survival world, builds a small course
for each scenario with commands, lets Webmatica do the job on its own and checks
the result. No viewer is needed.

```
./gradlew :1.21.11:runClient -Pautotest=all -Pautoquit
./gradlew :26.3:runClient "-Pautotest=walk*,printer-orient"
```

`-Pautotest` takes `all`, names, or a prefix with `*`. `-Pautoquit` closes the
game at the end. Results land in `run/<version>/autotest/results.txt`, one line
per scenario (PASS/FAIL, time, what was found). Without a screen (Linux CI):
prefix the command with `xvfb-run -a`.

| Scenario | What it checks |
|---|---|
| walk-flat | own walking engine reaches a point 30 blocks away |
| walk-stairs | steps up a staircase of full blocks |
| walk-slabs | climbs slabs and half steps (real floor heights) |
| walk-wall | walks around a wall it can't jump over |
| walk-drop | drops 3 blocks without damage |
| walk-lava | goes around a lava strip, never touches it |
| walk-water | crosses shallow water |
| walk-far | 150 blocks, planning again as chunks load |
| walk-unreachable | goal sealed in bedrock: gives up instead of running forever |
| fetch-chest | walks behind a wall to a chest and takes 32 cobblestone |
| printer-wall | prints a 5×4 wall |
| printer-safe | server-safe: only visible faces, at most 5 blocks per second |
| printer-orient | logs, stairs (all directions, upside down), slabs, furnaces |

A scenario fails on damage, fire or lava contact, wrong or missing blocks, or a
timeout. The scenarios live in `AutoTest.java`;
