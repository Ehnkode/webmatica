package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;

/**
 * Control from the web page: Webmatica's own printer, the server-safe switch
 * and which layers Litematica shows.
 *
 * The viewer holds the desired state (printer on/off, range, speed, which
 * layer Litematica shows) with a sequence number. When the number changes,
 * the state is applied here on the client thread. The real in-game state is
 * sent back so the web page also sees changes made via hotkey.
 *
 * Litematica via reflection: without it only the layers are missing. A
 * Litematica Printer, if installed, is only ever switched off (server-safe, or
 * while Webmatica's printer runs).
 */
public final class BuildControl {

    private static final BuildControl INSTANCE = new BuildControl();
    private static final String PRINTER = "me.aleksilassila.litematica.printer.config.Configs";

    public static BuildControl getInstance() {
        return INSTANCE;
    }

    private volatile String serverId;
    private volatile JsonObject wanted;
    private volatile long wantedAt;
    private volatile String statusJson;
    private long appliedSeq = -1;
    private int tickCount;
    private Thread thread;

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "webmatica-control");
        thread.setDaemon(true);
        thread.start();
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
                    URI uri = URI.create(base() + "/api/control?srv=" + URLEncoder.encode(srv, StandardCharsets.UTF_8));
                    HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
                    c.setConnectTimeout(800);
                    c.setReadTimeout(2000);
                    try (InputStream in = c.getInputStream()) {
                        JsonElement e = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        wanted = e.isJsonObject() ? e.getAsJsonObject() : null;
                        wantedAt = System.currentTimeMillis();
                    } finally {
                        c.disconnect();
                    }
                    String st = statusJson;
                    if (st != null) {
                        post("/api/control/status", st);
                    }
                }
                Thread.sleep(700L);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try { Thread.sleep(3000L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static void post(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(1500);
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
    }

    // ------------------------------------------------------------ Client thread

    /** Guard switches for auto-build, or null if the viewer hasn't answered for 5 s. */
    public JsonObject guard() {
        JsonObject w = wanted;
        if (w == null || System.currentTimeMillis() - wantedAt > 5000L || !w.has("guard") || !w.get("guard").isJsonObject()) {
            return null;
        }
        return w.getAsJsonObject("guard");
    }

    public void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            serverId = null;
            return;
        }
        serverId = RecordingTarget.serverId(mc);
        if (++tickCount % 5 != 0) {
            return;
        }
        JsonObject w = wanted;
        boolean fresh = w != null && System.currentTimeMillis() - wantedAt < 5000L;
        if (fresh) {
            ServerSafe.set(mc, serverId, w.has("safe") && w.get("safe").getAsBoolean());
            if (w.has("web") && w.get("web").isJsonObject()) {
                JsonObject web = w.getAsJsonObject("web");
                WebPrinter.getInstance().configure(mc, web.has("on") && web.get("on").getAsBoolean(),
                        web.has("range") ? web.get("range").getAsDouble() : 4.5,
                        web.has("bps") ? web.get("bps").getAsDouble() : 10.0);
            }
        }
        if (ServerSafe.on() || WebPrinter.getInstance().isOn()) {
            // Litematica Printer off: forbidden in server-safe, and two printers at once get in each other's way
            stopLitematicaPrinter(mc);
        }
        if (w != null && w.has("seq")) {
            long seq = w.get("seq").getAsLong();
            if (seq != appliedSeq) {
                appliedSeq = seq;
                String msg = apply(w);
                if (msg != null) {
                    Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(msg)).withStyle(ChatFormatting.AQUA));
                }
            }
        }
        JsonObject st = new JsonObject();
        st.addProperty("server", serverId);
        st.addProperty("seq", appliedSeq);
        st.add("layer", layerState());
        st.add("web", WebPrinter.getInstance().status());
        st.addProperty("safe", ServerSafe.on());
        statusJson = st.toString();
    }

    private static void stopLitematicaPrinter(Minecraft mc) {
        try {
            if ((Boolean) getCfg("PRINT_MODE", "getBooleanValue")) {
                setCfg("PRINT_MODE", "setBooleanValue", boolean.class, false);
                Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(ServerSafe.on()
                        ? "Litematica Printer aus (Server-safe)" : "Litematica Printer aus (Webmatica-Printer läuft)"))
                        .withStyle(ChatFormatting.YELLOW));
            }
        } catch (Throwable ignored) {
            // no Litematica Printer installed
        }
    }

    private String apply(JsonObject w) {
        StringBuilder msg = new StringBuilder();
        if (w.has("layer") && w.get("layer").isJsonObject()) {
            JsonObject l = w.getAsJsonObject("layer");
            try {
                Object range = layerRange();
                Class<?> modeCls = Class.forName("fi.dy.masa.malilib.util.LayerMode");
                String mode = l.has("mode") ? l.get("mode").getAsString() : "all";
                range.getClass().getMethod("setAxis", Direction.Axis.class).invoke(range, Direction.Axis.Y);
                if ("all".equals(mode)) {
                    setMode(range, modeCls, "ALL");
                    append(msg, "Litematica: alle Schichten");
                } else {
                    int min = l.get("min").getAsInt();
                    int max = l.has("max") ? l.get("max").getAsInt() : min;
                    if (min == max) {
                        range.getClass().getMethod("setLayerSingle", int.class).invoke(range, min);
                        setMode(range, modeCls, "SINGLE_LAYER");
                        append(msg, "Litematica: Schicht Y " + min);
                    } else {
                        Method smin = range.getClass().getMethod("setLayerRangeMin", int.class);
                        Method smax = range.getClass().getMethod("setLayerRangeMax", int.class);
                        // set limits in an order that never yields min > max
                        smax.invoke(range, max);
                        smin.invoke(range, min);
                        smax.invoke(range, max);
                        setMode(range, modeCls, "LAYER_RANGE");
                        append(msg, "Litematica: Y " + min + " bis " + max);
                    }
                }
            } catch (ClassNotFoundException e) {
                append(msg, "Litematica nicht gefunden");
            } catch (Throwable t) {
                MapperClient.LOGGER.warn("[webmatica] Schicht setzen fehlgeschlagen", t);
            }
        }
        return msg.length() > 0 ? msg.toString() : null;
    }

    private static void append(StringBuilder sb, String s) {
        if (sb.length() > 0) {
            sb.append(" · ");
        }
        sb.append(s);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void setMode(Object range, Class<?> modeCls, String name) throws Exception {
        Object m = Enum.valueOf((Class) modeCls, name);
        range.getClass().getMethod("setLayerMode", modeCls).invoke(range, m);
    }

    private static Object layerRange() throws Exception {
        return Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getRenderLayerRange").invoke(null);
    }

    private static Object cfg(String field) throws Exception {
        return Class.forName(PRINTER).getField(field).get(null);
    }

    private static void setCfg(String field, String setter, Class<?> type, Object value) throws Exception {
        Object c = cfg(field);
        c.getClass().getMethod(setter, type).invoke(c, value);
    }

    private static Object getCfg(String field, String getter) throws Exception {
        Object c = cfg(field);
        return c.getClass().getMethod(getter).invoke(c);
    }

    private static JsonObject layerState() {
        JsonObject o = new JsonObject();
        try {
            Object r = layerRange();
            Class<?> c = r.getClass();
            String mode = ((Enum<?>) c.getMethod("getLayerMode").invoke(r)).name();
            o.addProperty("mode", mode);
            o.addProperty("axis", String.valueOf(c.getMethod("getAxis").invoke(r) == Direction.Axis.Y ? "y" : "other"));
            o.addProperty("single", (Integer) c.getMethod("getLayerSingle").invoke(r));
            o.addProperty("min", (Integer) c.getMethod("getLayerRangeMin").invoke(r));
            o.addProperty("max", (Integer) c.getMethod("getLayerRangeMax").invoke(r));
            o.addProperty("available", true);
        } catch (Throwable t) {
            o = new JsonObject();
            o.addProperty("available", false);
        }
        return o;
    }
}
