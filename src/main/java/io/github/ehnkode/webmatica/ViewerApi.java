package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;

/**
 * The in-game menu talks to the viewer through the same endpoints as the web page, so every
 * check (server-safe, queue, auto-build) stays in one place. Requests run on a background
 * thread; answers come back on the client thread.
 */
final class ViewerApi {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webmatica-ingame");
        t.setDaemon(true);
        return t;
    });

    private ViewerApi() {
    }

    static String base() {
        String u = PositionReporter.url();
        int i = u.indexOf("/api/");
        return i > 0 ? u.substring(0, i) : "http://127.0.0.1:8770";
    }

    static String q(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Blocking GET, background threads only. */
    static JsonElement getNow(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(3000);
        try (InputStream in = c.getInputStream()) {
            return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            c.disconnect();
        }
    }

    /** Blocking GET of a file (schematic export), background threads only. */
    static byte[] getBytesNow(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(60000);
        try {
            if (c.getResponseCode() != 200) {
                throw new IOException(errorText(c));
            }
            try (InputStream in = c.getInputStream()) {
                return in.readAllBytes();
            }
        } catch (java.net.ConnectException e) {
            throw new IOException("Viewer läuft nicht");
        } finally {
            c.disconnect();
        }
    }

    /**
     * POST in the background. {@code done} gets null on success, else the viewer's error message
     * (or a hint that the viewer is not running), on the client thread.
     */
    static void post(String path, JsonObject body, Consumer<String> done) {
        POOL.submit(() -> {
            String err;
            try {
                HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(3000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
                int code = c.getResponseCode();
                err = code == 200 ? null : errorText(c);
                c.disconnect();
            } catch (IOException e) {
                err = "Viewer läuft nicht";
            }
            String msg = err;
            if (done != null) {
                Minecraft.getInstance().execute(() -> done.accept(msg));
            }
        });
    }

    /** A command for the bot, same as the buttons on the web page. */
    static void command(Minecraft mc, JsonObject cmd, Consumer<String> done) {
        cmd.addProperty("srv", RecordingTarget.serverId(mc));
        post("/api/commands", cmd, done);
    }

    static void background(Runnable r) {
        POOL.submit(r);
    }

    private static String errorText(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream()) {
            if (in == null) {
                return "Fehler " + c.getResponseCode();
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            JsonElement e = JsonParser.parseString(body);
            if (e.isJsonObject() && e.getAsJsonObject().has("error")) {
                return e.getAsJsonObject().get("error").getAsString();
            }
            return body;
        } catch (Exception e) {
            return "Fehler";
        }
    }
}
