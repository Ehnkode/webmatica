package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Baritone from the web page: fetches commands from the viewer (goal by clicking
 * the map, stop) and hands them to Baritone's command manager, as if you had
 * typed "#goto x z". Nothing is ever sent to the server chat.
 *
 * Baritone only through BaritoneApi: without Baritone the viewer reports that, nothing more.
 */
public final class BaritoneBridge {

    private static final BaritoneBridge INSTANCE = new BaritoneBridge();

    public static BaritoneBridge getInstance() {
        return INSTANCE;
    }

    /** What still works in server-safe: stopping the bot and marking chests. */
    private static final java.util.Set<String> SAFE_KINDS = java.util.Set.of("highlight", "stop", "pause", "resume");

    private volatile String serverId;
    private Thread thread;

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "webmatica-baritone");
        thread.setDaemon(true);
        thread.start();
    }

    public void tick(Minecraft mc) {
        serverId = mc.level != null && mc.player != null ? RecordingTarget.serverId(mc) : null;
    }

    private static String base() {
        String u = PositionReporter.url();
        int i = u.indexOf("/api/");
        return i > 0 ? u.substring(0, i) : "http://127.0.0.1:8770";
    }

    private void loop() {
        while (true) {
            try {
                String srv = serverId;
                if (srv != null) {
                    for (JsonElement e : fetch(srv)) {
                        JsonObject cmd = e.getAsJsonObject();
                        Minecraft.getInstance().execute(() -> run(cmd));
                    }
                }
                Thread.sleep(500L);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try { Thread.sleep(3000L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static JsonArray fetch(String srv) throws Exception {
        URI uri = URI.create(base() + "/api/commands?srv=" + URLEncoder.encode(srv, StandardCharsets.UTF_8));
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(2000);
        try (InputStream in = c.getInputStream()) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
        } finally {
            c.disconnect();
        }
    }

    /** Stop, pause or resume from the in-game menu while the viewer is not running. Client thread. */
    void runLocal(JsonObject cmd) {
        run(cmd);
    }

    /** Client thread. */
    private void run(JsonObject cmd) {
        Minecraft mc = Minecraft.getInstance();
        String id = cmd.has("id") ? cmd.get("id").getAsString() : "";
        String kind = cmd.has("kind") ? cmd.get("kind").getAsString() : "";
        java.util.List<String> lines = new java.util.ArrayList<>();
        if (ServerSafe.on() && !SAFE_KINDS.contains(kind)) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr("Server-safe ist an: Bot-Funktionen sind aus"))
                    .withStyle(ChatFormatting.RED));
            report(id, false, "Server-safe ist an: Bot-Funktionen sind aus");
            return;
        }
        if ("fetch".equals(kind) || "store".equals(kind)) {
            FetchTask.getInstance().start(id, cmd);
            return;
        }
        if ("unpack".equals(kind)) {
            UnpackTask.getInstance().start(id, cmd);
            return;
        }
        if ("breakboxes".equals(kind) || "chestdump".equals(kind)) {
            FarmTask.getInstance().start(id, cmd);
            return;
        }
        if ("pause".equals(kind)) {
            AutoGuard.setPaused(true);
        } else if ("resume".equals(kind)) {
            AutoGuard.setPaused(false);
        }
        if ("stop".equals(kind)) {
            AutoGuard.setPaused(false);
            FetchTask.getInstance().cancel();
            UnpackTask.getInstance().cancel();
            FarmTask.getInstance().cancel();
            BaritoneSafety.restore();
        }
        if ("highlight".equals(kind)) {
            // highlight chests in game: no Baritone, just particles
            int n = Highlighter.getInstance().set(cmd.getAsJsonArray("pts"), cmd.has("label") ? cmd.get("label").getAsString() : "");
            if (mc.player != null) {
                Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(n + " Kiste" + (n == 1 ? "" : "n") + " markiert"))
                        .withStyle(ChatFormatting.GOLD));
            }
            report(id, true, n + " markiert");
            return;
        }
        if ("goto".equals(kind)) {
            gotoPos(mc, id, cmd);
            return;
        }
        if ("stop".equals(kind)) {
            Walker.stop();
            if (!Walker.baritoneInstalled()) {
                report(id, true, "gestoppt");
                return;
            }
        }
        if ("stop".equals(kind) || "pause".equals(kind) || "resume".equals(kind)) {
            lines.add(kind);
        } else if ("clear".equals(kind)) {
            // clear build site: set selection and let Baritone empty it
            BaritoneSafety.restore();
            lines.add("sel clear");
            lines.add("sel pos1 " + cmd.get("x0").getAsInt() + " " + cmd.get("y0").getAsInt() + " " + cmd.get("z0").getAsInt());
            lines.add("sel pos2 " + cmd.get("x1").getAsInt() + " " + cmd.get("y1").getAsInt() + " " + cmd.get("z1").getAsInt());
            lines.add("sel cleararea");
        } else if ("litematica".equals(kind)) {
            int idx = LitematicaBridge.getInstance().placementIndex(cmd.get("pid").getAsString());
            if (idx < 0) {
                report(id, false, "Schematic ist nicht in Litematica (erst \"Ins Spiel\")");
                return;
            }
            BaritoneSafety.restore();
            lines.add("litematica " + (idx + 1));
        } else {
            report(id, false, "unbekannter Befehl");
            return;
        }
        String err = null;
        String line = String.join(" · #", lines);
        try {
            for (String l : lines) {
                if (!BaritoneApi.execute(l)) {
                    err = "Baritone hat \"" + l + "\" abgelehnt";
                    break;
                }
            }
        } catch (ClassNotFoundException e) {
            err = "Baritone nicht gefunden";
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            err = c.getClass().getSimpleName() + ": " + c.getMessage();
        }
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] Baritone #" + line + (err == null ? "" : " – " + Lang.tr(err)))
                    .withStyle(err == null ? ChatFormatting.AQUA : ChatFormatting.RED));
        }
        report(id, err == null, err == null ? "#" + line : err);
    }

    /** "goto" from the map: Baritone or the own engine, see {@link Walker}. */
    private static void gotoPos(Minecraft mc, String id, JsonObject cmd) {
        int x = cmd.get("x").getAsInt(), z = cmd.get("z").getAsInt();
        Integer y = cmd.has("y") && !cmd.get("y").isJsonNull() ? cmd.get("y").getAsInt() : null;
        String where = x + " " + (y != null ? y + " " : "") + z;
        String err = null;
        try {
            BaritoneSafety.walkOnly();
            Walker.gotoPos(x, y, z);
        } catch (IllegalStateException e) {
            err = e.getMessage();
        } catch (Throwable t) {
            Throwable c = t.getCause() != null ? t.getCause() : t;
            err = c.getClass().getSimpleName() + ": " + c.getMessage();
        }
        String msg = err != null ? err : (Walker.usingBaritone() ? "Baritone läuft nach " : "läuft nach ") + where;
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(msg)).withStyle(err == null ? ChatFormatting.AQUA : ChatFormatting.RED));
        }
        report(id, err == null, msg);
    }

    static void report(String id, boolean ok, String msg) {
        new Thread(() -> {
            try {
                JsonObject o = new JsonObject();
                o.addProperty("id", id);
                o.addProperty("ok", ok);
                o.addProperty("msg", msg);
                byte[] b = o.toString().getBytes(StandardCharsets.UTF_8);
                HttpURLConnection c = (HttpURLConnection) URI.create(base() + "/api/commands/result").toURL().openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(1500);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }, "webmatica-baritone-report").start();
    }
}
