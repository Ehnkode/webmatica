# Webmatica

[English](README.md) · **Deutsch**

Deine Minecraft-Welt im Browser: Karte, 3D-Ansicht, Kisten und Schematics, dazu Litematica und
Baritone fernsteuern. Läuft auf jedem Server und im Singleplayer.

![Schematic in 3D mit der Liste der fehlenden Items](docs/3d.jpg)

- **Mod** (Fabric 1.21.11, nur Client): speichert jeden Chunk, den du siehst, als normale
  Regiondateien und merkt sich Kisteninhalte. Schickt nichts an den Server oder in den Chat.
- **Viewer** (Python, läuft lokal unter <http://127.0.0.1:8770>): alles Folgende im Browser.
  Auf Deutsch und Englisch (Umschalter oben links).

## Funktionen

| | |
|---|---|
| ![Karte](docs/map.jpg) | ![Schematic-Editor](docs/editor.jpg) |
| ![Verfolger-Kamera](docs/follow.jpg) | |

- **Karte und 3D** von allem, was du gesehen hast, mit echten Blockmodellen, Live-Position und Spur.
- **Verfolger-Kamera:** in 3D dich oder einen anderen Spieler anklicken und die Kamera auf ihn
  festsetzen, von hinten, aus der Ich-Sicht oder von oben.
- **Schematics:** `.litematic`, `.schem` oder `.nbt` laden, auf der Karte platzieren, mit einem
  Klick an Litematica schicken und sehen, welche Items noch fehlen. Inventar und Kisten sind schon
  abgezogen.
- **Schematic-Editor:** Blöcke setzen, abbauen und füllen, alle Blöcke einer Sorte ersetzen oder
  löschen, kopieren, verschieben, drehen, mit Rückgängig. Jeder Bereich deiner Welt kann zur
  Schematic werden.
- **Kisten:** Jede Kiste, die du öffnest, wird gemerkt, auch der Inhalt von Shulkern. Such ein
  Item über alle Kisten hinweg, dann läuft Baritone auf Knopfdruck hin oder holt genau die Stacks,
  die du ausgewählt hast.
- **Goto:** Rechtsklick auf die Karte, „Hierhin laufen“, und Baritone läuft hin.
- **Autobau:** holt fehlendes Material aus deinen Kisten, baut mit Baritone Schicht für Schicht,
  holt Nachschub und bringt Reste zurück. Hält an, wenn ein Spieler in die Nähe kommt.
  Am Ende korrigiert er falsche Blöcke, eine Warteschlange baut mehrere Schematics nacheinander.
- **Farmen:** Bereich auf der Karte wählen, Blöcke und Menge angeben, Baritone baut ab und sammelt
  ein. Volles Inventar kommt in eine gewählte Kiste oder eine, die er selbst aufstellt.
- **Außerdem:** Wegpunkte, Schilder, Stash-Finder, Zeitleiste der Änderungen, Radar für Spieler
  in der Nähe.

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
