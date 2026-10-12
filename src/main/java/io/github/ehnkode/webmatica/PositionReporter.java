package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.PlayerSkinProvider;
import net.minecraft.entity.player.SkinTextures;
import net.minecraft.util.Identifier;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Meldet die Live-Spielerposition (und Blickrichtung) an das lokale
 * Mapper-Backend (http://127.0.0.1:8770/api/position).
 *
 * Absichtlich konservativ:
 *  - daemon-Thread, blockiert nie den Render-/Netzwerktakt des Clients
 *  - nur POST, keine Antwort erwartet (Verbindung wird gleich wieder geschlossen)
 *  - Fehler werden geschluckt (Server aus -> einfach weiterlaufen)
 *
 * Reine Selbst-Daten (Position/Rotation/UUID/Skin). Kein Send, kein
 * Movement, kein Anticheat-Eingriff — dieselbe Linie wie der Recorder.
 */
public final class PositionReporter {

    public static final String DEFAULT_URL = "http://127.0.0.1:8770/api/position";

    private static PositionReporter instance;

    private final Thread thread;
    private volatile boolean running = true;
    private volatile long intervalMs = 50L;    // 20 Hz = jeder Client-Tick; der Viewer interpoliert dazwischen
    private final AtomicReference<String> payload = new AtomicReference<>(null);
    private volatile boolean enabled = true;

    // Skin-Gesicht (8x8 px, Kopf-Vorderseite) als base64-PNG. Wird im
    // Render-Thread einmalig extrahiert und dann wiederverwendet.
    private volatile String skinFaceB64 = null;
    // Voller 64x64-Skin als base64-PNG (fuer die echte Skin-Figur im Viewer).
    private volatile String skinFullB64 = null;
    private volatile long skinStamp = 0L;
    private static final long SKIN_RETRY_MS = 2000L;
    private static final long SKIN_RESEND_MS = 5000L;
    private long lastSkinSent = 0L;
    private String lastInv = null;
    private long lastInvSent = 0L;

    private PositionReporter() {
        this.thread = new Thread(this::loop, "webmatica-position");
        this.thread.setDaemon(true);
    }

    public static String url() {
        return System.getProperty("webmatica.positionUrl", DEFAULT_URL);
    }

    public static synchronized PositionReporter getInstance() {
        if (instance == null) {
            instance = new PositionReporter();
        }
        return instance;
    }

    public void start() {
        if (!thread.isAlive()) {
            thread.start();
        }
    }

    public void setEnabled(boolean on) {
        this.enabled = on;
    }

    public void setIntervalMs(long ms) {
        if (ms >= 50 && ms <= 5000) {
            this.intervalMs = ms;
        }
    }

    /**
     * Wird jeden Client-Tick aufgerufen. Baut das JSON nur, wenn sich etwas
     * geaendert hat, und legt es fuer den Sender-Thread bereit.
     */
    public void tick(MinecraftClient mc) {
        if (!enabled || mc == null) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null) {
            return;
        }

        try {
            JsonObject o = new JsonObject();
            o.addProperty("x", p.getX());
            o.addProperty("y", p.getY());
            o.addProperty("z", p.getZ());
            o.addProperty("yaw", p.getYaw());
            o.addProperty("pitch", p.getPitch());
            o.addProperty("dim", mc.world.getRegistryKey().getValue().toString());
            o.addProperty("t", System.currentTimeMillis());

            try {
                o.addProperty("name", mc.getSession().getUsername());
                o.addProperty("uuid", mc.getSession().getUuidOrNull() != null
                        ? mc.getSession().getUuidOrNull().toString() : "");
            } catch (Throwable ignored) {
            }

            // Skin-URL aus dem Tab-Listeneintrag (enthält die signierte
            // textur-Property). Fallback: keine URL -> Server nutzt Username-API.
            try {
                if (mc.getNetworkHandler() != null) {
                    PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
                    if (entry != null) {
                        SkinTextures skin = entry.getSkinTextures();
                        if (skin != null) {
                            // Yarn 1.21.11: SkinTextures ist ein Record mit
                            // body/cape/elytra/model. body() -> AssetInfo.TextureAsset,
                            // texturePath() liefert den Identifier.
                            if (skin.body() != null && skin.body().texturePath() != null) {
                                o.addProperty("skin", skin.body().texturePath().toString());
                            }
                            if (skin.model() != null) {
                                o.addProperty("skinModel", skin.model().asString());
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // Skin-Gesicht aus der Client-Textur ziehen (unabhaengig von externen
            // Skin-APIs). Das Bild liegt bereits auf der GPU und ist auch ohne
            // Multiplayer-Skin-Fetch im TabListEntry verfuegbar.
            // Der Skin aendert sich nicht; nur alle paar Sekunden mitschicken,
            // der Viewer merkt ihn sich.
            String face = ensureSkinFace(mc, p);
            long nowMs = System.currentTimeMillis();
            if (face != null && nowMs - lastSkinSent > SKIN_RESEND_MS) {
                lastSkinSent = nowMs;
                o.addProperty("skinFace", face);
                if (skinFullB64 != null) {
                    o.addProperty("skinFull", skinFullB64);
                }
            }
            // Inventar: nur bei Aenderung oder alle 5 s mitschicken (Packliste zieht es ab)
            try {
                JsonArray inv = new JsonArray();
                net.minecraft.entity.player.PlayerInventory pi = p.getInventory();
                for (int i = 0; i < pi.size(); i++) {
                    net.minecraft.item.ItemStack st = pi.getStack(i);
                    if (!st.isEmpty()) {
                        JsonObject it = ContainerTracker.itemWithContents(st);
                        it.addProperty("slot", i);
                        inv.add(it);
                    }
                }
                String invJson = inv.toString();
                if (!invJson.equals(lastInv) || nowMs - lastInvSent > 2000L) {
                    lastInv = invJson;
                    lastInvSent = nowMs;
                    o.add("inv", inv);
                }
            } catch (Throwable ignored) {
            }
            // Radar: andere Spieler, die der Client gerade sieht (nur was der Server ohnehin schickt)
            try {
                JsonArray others = new JsonArray();
                for (net.minecraft.client.network.AbstractClientPlayerEntity e : mc.world.getPlayers()) {
                    if (e == p) {
                        continue;
                    }
                    JsonObject q = new JsonObject();
                    q.addProperty("name", e.getName().getString());
                    q.addProperty("uuid", e.getUuidAsString());
                    q.addProperty("x", e.getX());
                    q.addProperty("y", e.getY());
                    q.addProperty("z", e.getZ());
                    q.addProperty("yaw", e.getYaw());
                    others.add(q);
                }
                o.add("others", others);
            } catch (Throwable ignored) {
            }
            o.addProperty("server", RecordingTarget.serverId(mc));
            o.addProperty("recording", RegionStore.getInstance().isEnabled());
            o.addProperty("written", RegionStore.getInstance().getWrittenCount());

            payload.set(o.toString());
        } catch (Throwable t) {
            MapperClient.LOGGER.debug("[webmatica] Position-JSON fehlgeschlagen", t);
        }
    }

    private void loop() {
        while (running) {
            try {
                String body = payload.getAndSet(null);
                if (body != null) {
                    post(body);
                }
                Thread.sleep(intervalMs);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // Server aus / Port zu -> still weiterlaufen.
                try { Thread.sleep(500L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /**
     * Extrahiert das 8x8-Gesicht (Kopf-Vorderseite bei 8,8 im 64x64-Skin) aus
     * der Skin-Textur des Clients und gibt es als base64-PNG zurueck.
     *
     * Laeuft im Render-/Tick-Thread (Aufruf aus tick()), daher kein GPU-Zugriff
     * noetig: die NativeImage haengt an der NativeImageBackedTexture und
     * writeTo(...) encodiert PNG auf der CPU.
     *
     * Wird alle SKIN_RETRY_MS erneut versucht, solange noch kein Gesicht da ist
     * (die Skin-Textur kann erst kurz nach dem Welt-Join registriert sein).
     */
    private String ensureSkinFace(MinecraftClient mc, ClientPlayerEntity p) {
        if (skinFaceB64 != null) {
            return skinFaceB64;
        }
        long now = System.currentTimeMillis();
        if (now - skinStamp < SKIN_RETRY_MS) {
            return null;
        }
        skinStamp = now;
        try {
            Identifier tex = null;

            // 1) Direkt aus dem Tab-Listen-Eintrag des eigenen Spielers.
            try {
                if (mc.getNetworkHandler() != null) {
                    PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(p.getUuid());
                    if (entry != null) {
                        SkinTextures st = entry.getSkinTextures();
                        if (st != null && st.body() != null) {
                            tex = st.body().texturePath();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2) Fallback: Skin-Provider des Clients (eigener Spieler). Das
            //    GameProfile wird aus der Session gebaut (kein getGameProfile
            //    in Yarn 1.21.11).
            if (tex == null) {
                try {
                    PlayerSkinProvider provider = mc.getSkinProvider();
                    if (provider != null && mc.getSession() != null) {
                        var profile = new com.mojang.authlib.GameProfile(
                                mc.getSession().getUuidOrNull(), mc.getSession().getUsername());
                        SkinTextures st = provider.supplySkinTextures(profile, false).get();
                        if (st != null && st.body() != null) {
                            tex = st.body().texturePath();
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            if (tex == null) {
                return null;
            }

            AbstractTexture at = mc.getTextureManager().getTexture(tex);
            if (!(at instanceof NativeImageBackedTexture nibt)) {
                return null;
            }
            NativeImage img = nibt.getImage();
            if (img == null || img.getWidth() < 64 || img.getHeight() < 64) {
                return null;
            }

            // 1) Vollen 64x64-Skin als PNG mitschicken (fuer die echte Figur).
            if (skinFullB64 == null) {
                try {
                    Path tmpFull = Files.createTempFile("mapper-skinfull", ".png");
                    try {
                        img.writeTo(tmpFull);
                        byte[] full = Files.readAllBytes(tmpFull);
                        skinFullB64 = Base64.getEncoder().encodeToString(full);
                        MapperClient.LOGGER.info("[webmatica] Voll-Skin extrahiert ({} Bytes)", full.length);
                    } finally {
                        try { Files.deleteIfExists(tmpFull); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    MapperClient.LOGGER.debug("[webmatica] Voll-Skin-Extraktion fehlgeschlagen", t);
                }
            }

            // 2) Gesichts-Kachel (8,8)-(16,16) in ein 8x8-Bild kopieren.
            NativeImage face = new NativeImage(8, 8, false);
            try {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        face.setColorArgb(x, y, img.getColorArgb(8 + x, 8 + y));
                    }
                }
                // NativeImage.writeTo(Path) schreibt PNG; ueber Temp-Datei holen.
                Path tmp = Files.createTempFile("mapper-skin", ".png");
                try {
                    face.writeTo(tmp);
                    byte[] png = Files.readAllBytes(tmp);
                    skinFaceB64 = Base64.getEncoder().encodeToString(png);
                    MapperClient.LOGGER.info("[webmatica] Skin-Gesicht extrahiert ({} Bytes)", png.length);
                } finally {
                    try { Files.deleteIfExists(tmp); } catch (Throwable ignored) {}
                }
            } finally {
                face.close();
            }
        } catch (Throwable t) {
            MapperClient.LOGGER.debug("[webmatica] Skin-Extraktion fehlgeschlagen", t);
        }
        return skinFaceB64;
    }

    private void post(String body) {
        HttpURLConnection con = null;
        try {
            URL url = URI.create(url()).toURL();
            con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(400);
            con.setReadTimeout(400);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            con.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream os = con.getOutputStream()) {
                os.write(bytes);
            }
            con.getResponseCode(); // erzwingt das Senden
        } catch (Throwable t) {
            // bewusst still
        } finally {
            if (con != null) {
                try { con.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
}
