# Truhen-Deckel abfangen (Mod 0.3.6)

Mappings: **Yarn** (1.21.11). In Mojang-Mappings heißen die Klassen
`ClientboundBlockEventPacket` und `ClientPacketListener.handleBlockEvent`; in Yarn
`BlockEventS2CPacket` und `ClientPlayNetworkHandler.onBlockEvent`. Der Code ist
Yarn, die fertige Jar ist auf Intermediary umgeschrieben (so wie Fabric sie lädt).

## Wie das Paket für Truhen aufgebaut ist

`BlockEventS2CPacket` hat vier Felder:

| Feld | Typ | Bei Truhen |
|---|---|---|
| `pos` | BlockPos | Position der Truhe (bei Doppeltruhen kommt für jede Hälfte ein eigenes Paket) |
| `block` | Block | `chest`, `trapped_chest`, `ender_chest` oder eine Shulkerkiste |
| `type` | int | `1` = „Anzahl Spieler, die die Kiste offen haben, hat sich geändert“ |
| `data` | int | diese Anzahl: `> 0` heißt offen, `0` heißt zu |

Auf dem Server zählt `ViewerCountManager` mit, wie viele Spieler eine Kiste offen
haben. Ändert sich die Zahl, ruft er `world.addSyncedBlockEvent(pos, block, 1, anzahl)`.
Der Server schickt das Paket an alle Spieler im Umkreis von 64 Blöcken. Beim
Client landet es in `onBlockEvent`, dann in `ChestBlockEntity.onSyncedBlockEvent`,
und der Deckel animiert.

**Wichtig:** Das Paket kommt nur, wenn sich die Zahl gerade ändert. Ob eine Kiste
früher schon einmal offen war, steht nirgends: weder in den Chunk-Daten noch in
einem anderen Paket. Man sieht also nur, wer **jetzt** in deiner Nähe Kisten benutzt.

## Mixin

`src/main/java/io/github/ehnkode/webmatica/mixin/ClientPlayNetworkHandlerMixin.java`:

```java
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    // ... (onBlockEntityUpdate wie bisher)

    /**
     * Am Ende einhaengen: der erste Aufruf kommt vom Netzwerk-Thread
     * und bricht in forceMainThread ab, erst der zweite (Client-Thread) laeuft durch.
     */
    @Inject(method = "onBlockEvent", at = @At("TAIL"))
    private void mapper$onBlockEvent(BlockEventS2CPacket packet, CallbackInfo ci) {
        ChestLidTracker.getInstance().onBlockEvent(packet.getPos(), packet.getBlock(), packet.getType(), packet.getData());
    }
}
```

Warum `TAIL` und nicht `HEAD`: `onBlockEvent` beginnt mit
`NetworkThreadUtils.forceMainThread(...)`. Beim ersten Aufruf (Netty-Thread) wirft das
eine Ausnahme und plant das Paket für den Client-Thread ein. Mit `HEAD` würde jedes
Ereignis doppelt gezählt, mit `TAIL` nur einmal und gleich auf dem richtigen Thread.

Die Fabric API hat kein Event für eingehende Play-Pakete aus Vanilla, deshalb ist ein Mixin hier der saubere Weg.

## mixins.json

Es ist keine Änderung nötig, weil das Mixin in der vorhandenen Klasse steckt. `webmatica.mixins.json`:

```json
{
  "required": true,
  "package": "io.github.ehnkode.webmatica.mixin",
  "compatibilityLevel": "JAVA_21",
  "client": [
    "ClientPlayNetworkHandlerMixin",
    "WorldChunkMixin"
  ],
  "injectors": { "defaultRequire": 1 }
}
```

## Logik

In `ChestLidTracker.java` passiert Folgendes:

1. Er nimmt nur `type == 1` und Blöcke vom Typ `ChestBlock` (deckt auch Trapped Chest ab), `EnderChestBlock` oder `ShulkerBoxBlock`.
2. Kisten, die du selbst gerade geöffnet hast, überspringt er (`ContainerTracker.isOwn`, auch beim Holen per Baritone).
3. Er schreibt ins Log, z. B. `Truhe bei [12, 80, 18] wurde GEÖFFNET (1 Spieler)`.
4. Die letzten 500 Ereignisse liegen in einer Liste (`recent()`, Record `LidEvent`), damit sie später ins HUD oder Overlay können.
5. Er schickt jedes Ereignis an den Viewer (`POST /api/containers/lid`). Der speichert pro Kiste: wie oft geöffnet, zuletzt wann, gerade offen. Das landet in `.viewer/chest_lids.json`.

## Auf der Karte

Kisten, die jemand anderes geöffnet hat, bekommen einen **lila Ring**. Ist die Kiste gerade
offen, ist der Ring pink. Der Tooltip zeigt, wie oft und wann zuletzt. Im Kisten-Tab steht
die Anzahl („N von anderen benutzt“).
