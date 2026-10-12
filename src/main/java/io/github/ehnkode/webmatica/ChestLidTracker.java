package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import net.minecraft.block.Block;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.EnderChestBlock;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Truhen-Deckel: Der Server schickt ein Block-Event (Typ 1, Daten = Anzahl der
 * Spieler, die die Kiste gerade offen haben), sobald sich diese Anzahl aendert.
 * Damit sieht man, wenn jemand anderes in Sichtweite eine Kiste benutzt.
 *
 * Eigene Kisten (gerade selbst angeklickt) werden nicht gemeldet.
 */
public final class ChestLidTracker {

    private static final ChestLidTracker INSTANCE = new ChestLidTracker();
    private static final int MAX_RECENT = 500;

    public static ChestLidTracker getInstance() {
        return INSTANCE;
    }

    /** Ein beobachtetes Oeffnen/Schliessen, fuer HUD oder Overlay. */
    public record LidEvent(BlockPos pos, String block, boolean open, int viewers, long time) {
    }

    private final Deque<LidEvent> recent = new ArrayDeque<>();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webmatica-lids");
        t.setDaemon(true);
        return t;
    });

    /** Client-Thread (aus dem Mixin am Ende von onBlockEvent). */
    public void onBlockEvent(BlockPos pos, Block block, int type, int data) {
        if (type != 1 || !(block instanceof ChestBlock || block instanceof EnderChestBlock || block instanceof ShulkerBoxBlock)) {
            return;
        }
        if (ContainerTracker.getInstance().isOwn(pos)) {
            return;
        }
        boolean open = data > 0;
        String id = Registries.BLOCK.getId(block).toString();
        MapperClient.LOGGER.info("Truhe bei [{}, {}, {}] wurde {} ({} Spieler)", pos.getX(), pos.getY(), pos.getZ(),
                open ? "GEÖFFNET" : "GESCHLOSSEN", data);
        LidEvent e = new LidEvent(pos.toImmutable(), id, open, data, System.currentTimeMillis());
        synchronized (recent) {
            recent.addFirst(e);
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
        }
        send(e);
    }

    /** Die letzten Ereignisse, neuestes zuerst. */
    public List<LidEvent> recent() {
        synchronized (recent) {
            return new ArrayList<>(recent);
        }
    }

    private void send(LidEvent e) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("server", RecordingTarget.serverId(mc));
        Identifier dim = mc.world.getRegistryKey().getValue();
        o.addProperty("dim", "minecraft".equals(dim.getNamespace()) ? dim.getPath() : dim.toString());
        o.addProperty("x", e.pos().getX());
        o.addProperty("y", e.pos().getY());
        o.addProperty("z", e.pos().getZ());
        o.addProperty("block", e.block());
        o.addProperty("open", e.open());
        o.addProperty("viewers", e.viewers());
        o.addProperty("t", e.time());
        String body = o.toString();
        sender.submit(() -> {
            try {
                String base = PositionReporter.url();
                int i = base.indexOf("/api/");
                URI uri = URI.create((i > 0 ? base.substring(0, i) : "http://127.0.0.1:8770") + "/api/containers/lid");
                HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(2000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) {
                // Viewer aus: Ereignis geht nur ins Log
            }
        });
    }
}
