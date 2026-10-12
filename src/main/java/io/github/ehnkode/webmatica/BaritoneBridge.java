package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Baritone von der Webseite: holt Befehle vom Viewer (Ziel per Klick auf die
 * Karte, Stopp) und gibt sie an Baritones Befehls-Verwaltung, als haettest du
 * "#goto x z" getippt. Es wird nie etwas in den Server-Chat geschickt.
 *
 * Baritone nur per Reflection: ohne Baritone meldet der Viewer das, mehr nicht.
 */
public final class BaritoneBridge {

    private static final BaritoneBridge INSTANCE = new BaritoneBridge();

    public static BaritoneBridge getInstance() {
        return INSTANCE;
    }

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

    public void tick(MinecraftClient mc) {
        serverId = mc.world != null && mc.player != null ? RecordingTarget.serverId(mc) : null;
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
                        MinecraftClient.getInstance().execute(() -> run(cmd));
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

    /** Client-Thread. */
    private void run(JsonObject cmd) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String id = cmd.has("id") ? cmd.get("id").getAsString() : "";
        String kind = cmd.has("kind") ? cmd.get("kind").getAsString() : "";
        java.util.List<String> lines = new java.util.ArrayList<>();
        if ("fetch".equals(kind) || "store".equals(kind)) {
            FetchTask.getInstance().start(id, cmd);
            return;
        }
        if ("unpack".equals(kind)) {
            UnpackTask.getInstance().start(id, cmd);
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
            BaritoneSafety.restore();
        }
        if ("highlight".equals(kind)) {
            // Kisten im Spiel markieren: kein Baritone, nur Partikel
            int n = Highlighter.getInstance().set(cmd.getAsJsonArray("pts"), cmd.has("label") ? cmd.get("label").getAsString() : "");
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("[Webmatica] " + n + " Kiste" + (n == 1 ? "" : "n") + " markiert")
                        .formatted(Formatting.GOLD), true);
            }
            report(id, true, n + " markiert");
            return;
        }
        if ("goto".equals(kind)) {
            BaritoneSafety.walkOnly();
            int x = cmd.get("x").getAsInt(), z = cmd.get("z").getAsInt();
            lines.add(cmd.has("y") && !cmd.get("y").isJsonNull() ? "goto " + x + " " + cmd.get("y").getAsInt() + " " + z : "goto " + x + " " + z);
        } else if ("stop".equals(kind) || "pause".equals(kind) || "resume".equals(kind)) {
            lines.add(kind);
        } else if ("clear".equals(kind)) {
            // Bauplatz freiraeumen: Auswahl setzen und von Baritone leer machen lassen
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
            Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
            // ueber die API-Interfaces aufrufen: die Implementierungen sind nicht oeffentlich
            Object baritone = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
            Object cm = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(baritone);
            java.lang.reflect.Method exec = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class);
            for (String l : lines) {
                Object ok = exec.invoke(cm, l);
                if (Boolean.FALSE.equals(ok)) {
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
            mc.player.sendMessage(Text.literal("[Webmatica] Baritone #" + line + (err == null ? "" : " – " + err))
                    .formatted(err == null ? Formatting.AQUA : Formatting.RED), true);
        }
        report(id, err == null, err == null ? "#" + line : err);
    }

    /** Client-Thread: eine Baritone-Befehlszeile ohne "#" ausfuehren, ohne Meldung. */
    static boolean exec(String line) {
        try {
            Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
            Object baritone = Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
            Object cm = Class.forName("baritone.api.IBaritone").getMethod("getCommandManager").invoke(baritone);
            Object ok = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class).invoke(cm, line);
            return !Boolean.FALSE.equals(ok);
        } catch (Throwable t) {
            return false;
        }
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
