# Webmatica
[![Latest Release](https://img.shields.io/github/v/release/Ehnkode/webmatica?style=flat-square&color=blue)](https://github.com/Ehnkode/webmatica/releases/latest)
[![License: LGPL-3.0](https://img.shields.io/badge/License-LGPL--3.0-blue.svg?style=flat-square)](COPYING.LESSER)
[![Minecraft Version](https://img.shields.io/badge/Minecraft-1.21.11%20--%2026.3-informational?style=flat-square&logo=minecraft)](https://github.com/Ehnkode/webmatica/releases)

[English](README.md) · **Deutsch**

Deine Minecraft-Welt im Browser: Karte, 3D-Ansicht, Kisten und Schematics, dazu Litematica und
Baritone fernsteuern. Läuft auf jedem Server und im Singleplayer.

![Webmatica im Überblick](docs/showcase.gif)

- **Mod** (Fabric, Minecraft 1.21.11 bis 26.3, nur Client): speichert jeden Chunk, den du siehst, als normale
  Regiondateien und merkt sich Kisteninhalte. Schickt nichts an den Server oder in den Chat.
- **Viewer** (in der Mod eingebaut, läuft lokal unter <http://127.0.0.1:8770>): alles Folgende im Browser.
  Auf Deutsch und Englisch (Umschalter unter Optionen).
- **Im Spiel** (**F8**): das Wichtigste, ohne Minecraft zu verlassen, siehe [Im Spiel](#im-spiel).

## Funktionen

| | |
|---|---|
| ![Wüstendorf in 3D](docs/village.de.jpg) | ![Rechtsklick auf die Karte, du läufst hin](docs/goto.gif) |
| ![Kiste anklicken, Inhalt sehen, alle Kisten durchsuchen](docs/storage.gif) | ![Schematic in der Welt platzieren und verschieben](docs/place.gif) |
| ![Schematic Schicht für Schicht aufschneiden](docs/editor-cut.gif) | ![Ein Haus im Schematic-Editor bauen](docs/build.gif) |

- **Karte und 3D** von allem, was du gesehen hast, mit echten Blockmodellen, weichem Licht,
  Schatten, bewegtem Wasser und Fernsicht bis 65.000 Blöcke. Die Karte hat eine weiche
  Relief-Schattierung (oder den klassischen Vanilla-Look). Live-Position, Spur, Verfolger-Kamera und
  Mob-Spawns.
- **Bereiche auswählen:** auf der Karte aufziehen und die Höhe wählen, oder in 3D wie bei Axiom:
  Linksklick setzt die erste Ecke, Rechtsklick die zweite, das Mausrad erweitert den Bereich in
  Blickrichtung. Auch Luftblöcke.
- **Schematic-Editor:** Blöcke setzen, ersetzen (mit Luft = entfernen) und füllen, alle Blöcke
  einer Sorte ersetzen oder löschen, kopieren, verschieben, drehen, mit Rückgängig. Schicht für
  Schicht aufschneiden. Jeder Bereich deiner Welt wird mit zwei Klicks zur Schematic.
- **Schematics:** `.litematic`, `.schem` oder `.nbt` laden, auf der Karte platzieren, mit einem
  Klick an Litematica schicken und sehen, welche Items noch fehlen. Inventar und Kisten sind schon
  abgezogen.
- **Automation mit Baritone:** bestimmte Blöcke farmen, einen Bereich abbauen, füllen oder ebnen
  oder eine ganze Schematic bauen. Material kommt aus deinen Kisten, Reste gehen zurück, und er hält
  an, wenn ein Spieler in die Nähe kommt. Eine Warteschlange baut mehrere Schematics nacheinander.
- **Lager:** Jede Kiste, die du öffnest, wird gemerkt, auch der Inhalt von Shulkern. Such ein Item
  über alle Kisten, lass genau die gewählten Stacks holen oder dein Inventar dorthin
  einlagern, wo das Item schon liegt. Abgebaute Kisten verschwinden von selbst. Mit Stash-Finder:
  hinlaufen oder in 3D hinfliegen.
- **Eigene Lauf-Engine:** Rechtsklick auf die Karte und du läufst hin; Holen, Einlagern und
  Einsammeln laufen genauso. Klappt ohne Baritone: Sie findet den Weg über Treppen, Stufen und
  Abgründe, um Mauern, Lava und tiefes Wasser herum.
  Ist Baritone installiert, läuft Baritone, und die eigene Engine übernimmt, wenn es feststeckt
  (Option „Laufen mit“).
- **Printer:** setzt die Blöcke einer Schematic, sobald du in der Nähe bist, auch ohne Litematica.
  Dreht dabei nur gegenüber dem Server den Kopf, damit Treppen und Stämme richtig herum liegen.
- **Server-safe** (Optionen, pro Server): für öffentliche Server. Schaltet Baritone, alle
  Automationen und X-Ray ab; der Printer setzt dann nur, was du auch von Hand
  könntest (keine vorgetäuschte Blickrichtung, nur sichtbare Flächen, höchstens 5 Blöcke pro Sekunde).
- **X-Ray:** nur die gesuchten Blöcke zeigen, in 3D und auf der Karte, und Baritone direkt zu einem Fund schicken.
- **Außerdem:** Wegpunkte, Schilder, Zeitraffer der Änderungen (Export als GIF oder Video), Radar für
  Spieler in der Nähe, Suche nach Koordinaten oder Wegpunkten.

## Im Spiel

- **Menü (F8 oder `/wm`):** Kisten durchsuchen, Job-Anzeige mit Pause und Stopp, Einstellungen und
  der Weg zum Viewer. Das Spiel läuft dabei weiter, Jobs auch.
- **Kistensuche:** Item eintippen, jede Kiste damit sehen, mit Entfernung und ob es in einer
  Shulker liegt. Kiste markieren (Kasten und Linie, durch Wände sichtbar, weg sobald du sie
  öffnest), hinlaufen oder holen lassen.
- **Auswahl-Werkzeug (B):** Linksklick setzt die erste Ecke, Rechtsklick die zweite (bis 160 Blöcke
  weit), das Mausrad schiebt die Seite in Blickrichtung (Sprinten = 8 Blöcke). Kein Klick geht an
  den Server. Der Rahmen ist durch Wände sichtbar und erscheint auch im Viewer. Danach als
  Schematic speichern oder ebnen, füllen oder abbauen.
- **Job-Anzeige:** kleiner Kasten am Bildschirmrand, solange ein Job läuft, Ecke wählbar,
  abschaltbar.
- **Kisten-Info:** „In Kisten: 340 (12 Kisten)“ im Tooltip jedes Items und kurz über der Hotbar,
  wenn du das Item wechselst.

## Voraussetzungen

- Minecraft 1.21.11, 26.1, 26.2 oder 26.3 mit Fabric Loader und Fabric API
- Optional: [Baritone](https://github.com/cabaletta/baritone) (nur für Bauen, Farmen und Ebnen; Laufen geht ohne), [Litematica](https://modrinth.com/mod/litematica) + [MaLiLib](https://modrinth.com/mod/malilib)

## Installation

1. Die Jar für deine Minecraft-Version (`webmatica-<version>+mc<minecraft>.jar`) von Modrinth, CurseForge oder den
   [Releases](https://github.com/Ehnkode/webmatica/releases) in den `mods`-Ordner legen.
2. Minecraft starten und **F10** drücken. Der Viewer startet und öffnet sich im Browser. Sonst ist
   nichts zu installieren: Beim ersten Start erzeugt er in wenigen Sekunden die Blockbilder aus deinem Spiel.

Der Viewer läuft im Spiel und endet, wenn du es schließt. Seine Blockbilder liegen in
`<Spielordner>/webmatica-viewer/assets/`.

**F8** öffnet das Menü im Spiel, **B** das Auswahl-Werkzeug, **F9** schaltet die Aufnahme an und aus,
**F10** öffnet den Viewer (alle Tasten in der Steuerung änderbar). **?** im Viewer zeigt alle Tastenkürzel.

## Mod bauen

```bash
./gradlew buildAll    # eine Jar pro Minecraft-Version in build/libs/
```

Spieltests im Dev-Client (Printer, Laufen, Holen): `./gradlew :1.21.11:runClient -Pautotest=all -Pautoquit`, siehe [docs/testing.md](docs/testing.md).

## Wie es entsteht

Webmatica entsteht mit KI-Unterstützung (Claude). Ideen, Richtung, Tests im Spiel und alle Entscheidungen kommen von mir, beim Schreiben von Code und Texten hilft die KI.

## Hinweise

Automatisierung ist nicht auf jedem Server erlaubt, Nutzung auf eigenes Risiko. Aufnahmen verraten
Koordinaten, also nicht teilen.

Copyright (C) 2026 Ehnkode. Lizenziert unter der GNU Lesser General Public License v3.0, siehe
[COPYING.LESSER](COPYING.LESSER) und [COPYING](COPYING) (die GPL, auf der sie aufbaut). Releases vor dem
Java-Viewer sind unter der MIT-Lizenz erschienen.
