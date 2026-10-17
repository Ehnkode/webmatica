package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.resources.SkinManager;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

/**
 * Reports the live player position (and view direction) to the local
 * viewer (http://127.0.0.1:8770/api/position).
 *
 * Deliberately conservative:
 *  - daemon thread, never blocks the client's render/network tick
 *  - POST only, no response expected (connection is closed right away)
 *  - errors are swallowed (server off -> just keep running)
 *
 * Pure self data (position/rotation/UUID/skin). No send, no
 * movement, no anticheat interference, same line as the recorder.
 */
public final class PositionReporter {

    public static final String DEFAULT_URL = "http://127.0.0.1:8770/api/position";

    private static PositionReporter instance;

    private final Thread thread;
    private volatile boolean running = true;
    private volatile long intervalMs = 50L;    // 20 Hz = every client tick; the viewer interpolates in between
    private final AtomicReference<String> payload = new AtomicReference<>(null);
    private volatile boolean enabled = true;

    // Skin face (8x8 px, front of head) as base64 PNG. Extracted once on
    // the render thread and then reused.
    private volatile String skinFaceB64 = null;
    // Full 64x64 skin as base64 PNG (for the real skin figure in the viewer).
    private volatile String skinFullB64 = null;
    private volatile long skinStamp = 0L;
    private static final long SKIN_RETRY_MS = 2000L;
    private static final long SKIN_RESEND_MS = 5000L;
    private long lastSkinSent = 0L;
    private String lastInv = null;
    private long lastInvSent = 0L;
    private long lastInvScan = 0L;
    private long lastBuilt = 0L;

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
     * Called every client tick. Builds the JSON at most once per send interval
     * (not 20 times a second) and hands it to the sender thread.
     */
    public void tick(Minecraft mc) {
        if (!enabled || mc == null) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return;
        }
        long built = System.currentTimeMillis();
        if (built - lastBuilt < intervalMs) {
            return;
        }
        lastBuilt = built;

        try {
            JsonObject o = new JsonObject();
            o.addProperty("x", p.getX());
            o.addProperty("y", p.getY());
            o.addProperty("z", p.getZ());
            o.addProperty("yaw", p.getYRot());
            o.addProperty("pitch", p.getXRot());
            o.addProperty("dim", mc.level.dimension().identifier().toString());
            o.addProperty("t", System.currentTimeMillis());

            try {
                o.addProperty("name", mc.getUser().getName());
                o.addProperty("uuid", mc.getUser().getProfileId() != null
                        ? mc.getUser().getProfileId().toString() : "");
            } catch (Throwable t) {
                failedOnce("name", t);
            }

            // Skin URL from the tab list entry (contains the signed
            // textures property). Fallback: no URL -> server uses the username API.
            try {
                if (mc.getConnection() != null) {
                    PlayerInfo entry = mc.getConnection().getPlayerInfo(p.getUUID());
                    if (entry != null) {
                        PlayerSkin skin = entry.getSkin();
                        if (skin != null) {
                            // Yarn 1.21.11: SkinTextures is a record with
                            // body/cape/elytra/model. body() -> AssetInfo.TextureAsset,
                            // texturePath() returns the Identifier.
                            if (skin.body() != null && skin.body().texturePath() != null) {
                                o.addProperty("skin", skin.body().texturePath().toString());
                            }
                            if (skin.model() != null) {
                                o.addProperty("skinModel", skin.model().getSerializedName());
                            }
                        }
                    }
                }
            } catch (Throwable t) {
                failedOnce("skin", t);
            }

            // Pull the skin face from the client texture (independent of external
            // skin APIs). The image is already on the GPU and available even without
            // the multiplayer skin fetch in the TabListEntry.
            // The skin doesn't change; only send it every few seconds,
            // the viewer remembers it.
            String face = ensureSkinFace(mc, p);
            long nowMs = System.currentTimeMillis();
            if (face != null && nowMs - lastSkinSent > SKIN_RESEND_MS) {
                lastSkinSent = nowMs;
                o.addProperty("skinFace", face);
                if (skinFullB64 != null) {
                    o.addProperty("skinFull", skinFullB64);
                }
            }
            // Inventory: looked at 4 times a second (shulker contents make it costly),
            // sent on change, otherwise every 2 s (the pack list subtracts it)
            if (nowMs - lastInvScan >= 250L) {
                lastInvScan = nowMs;
                try {
                    JsonArray inv = new JsonArray();
                    net.minecraft.world.entity.player.Inventory pi = p.getInventory();
                    for (int i = 0; i < pi.getContainerSize(); i++) {
                        net.minecraft.world.item.ItemStack st = pi.getItem(i);
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
                } catch (Throwable t) {
                    failedOnce("inventory", t);
                }
            }
            // Radar: other players the client currently sees (only what the server sends anyway)
            try {
                JsonArray others = new JsonArray();
                for (net.minecraft.client.player.AbstractClientPlayer e : mc.level.players()) {
                    if (e == p) {
                        continue;
                    }
                    JsonObject q = new JsonObject();
                    q.addProperty("name", e.getName().getString());
                    q.addProperty("uuid", e.getStringUUID());
                    q.addProperty("x", e.getX());
                    q.addProperty("y", e.getY());
                    q.addProperty("z", e.getZ());
                    q.addProperty("yaw", e.getYRot());
                    others.add(q);
                }
                o.add("others", others);
            } catch (Throwable t) {
                failedOnce("radar", t);
            }
            o.add("mods", mods());
            JsonObject sel = SelectionTool.json(mc);
            if (sel != null) {
                o.add("sel", sel);
            }
            o.addProperty("server", RecordingTarget.serverId(mc));
            o.addProperty("recording", RegionStore.getInstance().isEnabled());
            o.addProperty("written", RegionStore.getInstance().getWrittenCount());

            payload.set(o.toString());
        } catch (Throwable t) {
            WebmaticaClient.LOGGER.debug("[webmatica] Position JSON failed", t);
        }
    }

    private JsonObject mods;

    /** Which helper mods are installed, for the viewer's status chip (doesn't change while running). */
    private JsonObject mods() {
        if (mods == null) {
            JsonObject m = new JsonObject();
            m.addProperty("litematica", net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("litematica"));
            m.addProperty("baritone", hasClass("baritone.api.BaritoneAPI"));
            mods = m;
        }
        return mods;
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name, false, PositionReporter.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private final java.util.Set<String> failed = new java.util.HashSet<>();

    /** A part of the JSON failed: log it once (it would fail every tick), then stay quiet. */
    private void failedOnce(String part, Throwable t) {
        if (failed.add(part)) {
            WebmaticaClient.LOGGER.warn("[webmatica] Position data: {} missing ({})", part, t.toString());
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
                // Server off / port closed -> keep running silently.
                try { Thread.sleep(500L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    /**
     * Extracts the 8x8 face (front of head at 8,8 in the 64x64 skin) from
     * the client's skin texture and returns it as a base64 PNG.
     *
     * Runs on the render/tick thread (called from tick()), so no GPU access
     * needed: the NativeImage belongs to the NativeImageBackedTexture and
     * writeTo(...) encodes PNG on the CPU.
     *
     * Retried every SKIN_RETRY_MS as long as there is no face yet
     * (the skin texture may only be registered shortly after joining the world).
     */
    private String ensureSkinFace(Minecraft mc, LocalPlayer p) {
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

            // 1) Directly from the own player's tab list entry.
            try {
                if (mc.getConnection() != null) {
                    PlayerInfo entry = mc.getConnection().getPlayerInfo(p.getUUID());
                    if (entry != null) {
                        PlayerSkin st = entry.getSkin();
                        if (st != null && st.body() != null) {
                            tex = st.body().texturePath();
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            // 2) Fallback: the client's skin provider (own player). The
            //    GameProfile is built from the session (no getGameProfile
            //    in Yarn 1.21.11).
            if (tex == null) {
                try {
                    SkinManager provider = mc.getSkinManager();
                    if (provider != null && mc.getUser() != null) {
                        var profile = new com.mojang.authlib.GameProfile(
                                mc.getUser().getProfileId(), mc.getUser().getName());
                        PlayerSkin st = provider.createLookup(profile, false).get();
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
            if (!(at instanceof DynamicTexture nibt)) {
                return null;
            }
            NativeImage img = nibt.getPixels();
            if (img == null || img.getWidth() < 64 || img.getHeight() < 64) {
                return null;
            }

            // 1) Send the full 64x64 skin as PNG (for the real figure).
            if (skinFullB64 == null) {
                try {
                    Path tmpFull = Files.createTempFile("webmatica-skinfull", ".png");
                    try {
                        img.writeToFile(tmpFull);
                        byte[] full = Files.readAllBytes(tmpFull);
                        skinFullB64 = Base64.getEncoder().encodeToString(full);
                        WebmaticaClient.LOGGER.info("[webmatica] Full skin extracted ({} bytes)", full.length);
                    } finally {
                        try { Files.deleteIfExists(tmpFull); } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    WebmaticaClient.LOGGER.debug("[webmatica] Full skin extraction failed", t);
                }
            }

            // 2) Copy the face tile (8,8)-(16,16) into an 8x8 image.
            NativeImage face = new NativeImage(8, 8, false);
            try {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        face.setPixel(x, y, img.getPixel(8 + x, 8 + y));
                    }
                }
                // NativeImage.writeTo(Path) writes PNG; read it back via a temp file.
                Path tmp = Files.createTempFile("webmatica-skin", ".png");
                try {
                    face.writeToFile(tmp);
                    byte[] png = Files.readAllBytes(tmp);
                    skinFaceB64 = Base64.getEncoder().encodeToString(png);
                    WebmaticaClient.LOGGER.info("[webmatica] Skin face extracted ({} bytes)", png.length);
                } finally {
                    try { Files.deleteIfExists(tmp); } catch (Throwable ignored) {}
                }
            } finally {
                face.close();
            }
        } catch (Throwable t) {
            WebmaticaClient.LOGGER.debug("[webmatica] Skin extraction failed", t);
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
            con.getResponseCode(); // forces the send
        } catch (Throwable t) {
            // deliberately silent
        } finally {
            if (con != null) {
                try { con.disconnect(); } catch (Throwable ignored) {}
            }
        }
    }
}
