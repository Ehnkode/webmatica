package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.KeyError;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;

/** The small stores: chests, lids, commands for the mod, printer/server-safe/layer control, radar. */
public final class Stores {
    private Stores() {
    }

    static double now() {
        return System.currentTimeMillis() / 1000.0;
    }

    static double mono() {
        return System.nanoTime() / 1e9;
    }

    static JsonObject loadObj(Path p) {
        JsonElement e = J.load(p);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
    }

    // ------------------------------------------------------------------ chests

    /** The mod reports the contents of every opened chest, kept in .viewer/containers.json. */
    public static final class Containers {
        final Path path;
        final JsonObject data;

        Containers(Path recRoot) {
            path = recRoot.resolve(".viewer").resolve("containers.json");
            data = loadObj(path);
        }

        static JsonArray cleanItems(JsonElement items) {
            JsonArray out = new JsonArray();
            for (JsonElement e : J.list(items)) {
                JsonObject it = J.obj(e);
                if (it == null || !it.has("id")) {
                    continue;
                }
                JsonObject o = new JsonObject();
                o.addProperty("id", J.str(it.get("id")));
                o.addProperty("count", J.has(it, "count") ? J.lng(it.get("count")) : 1);
                if (J.bool(it, "name")) {
                    o.addProperty("name", cut(J.str(it.get("name")), 60));
                }
                if (J.isInt(J.get(it, "slot"))) {
                    o.add("slot", it.get("slot"));
                }
                if (J.arr(it, "contents") != null) {
                    o.add("contents", cleanItems(it.get("contents")));
                }
                out.add(o);
                if (out.size() >= 64) {
                    break;
                }
            }
            return out;
        }

        synchronized JsonObject put(JsonObject req) {
            String srv = J.str(req, "server"), dim = J.str(req, "dim", "overworld");
            if (!req.has("server")) {
                throw new KeyError("server");
            }
            int x = J.i(req, "x"), y = J.i(req, "y"), z = J.i(req, "z");
            String block = J.str(req, "block", "");
            boolean ender = block.endsWith("ender_chest");
            JsonArray other = J.arr(req, "other");
            List<String> oldKeys = new ArrayList<>();
            if (!ender && other != null && other.size() == 3) {
                // Double chest: always stored under the smaller half, whichever was clicked
                int ox = J.i(other.get(0)), oy = J.i(other.get(1)), oz = J.i(other.get(2));
                oldKeys.add(srv + "|" + dim + "|" + x + "," + y + "," + z);
                oldKeys.add(srv + "|" + dim + "|" + ox + "," + oy + "," + oz);
                if (compare(ox, oy, oz, x, y, z) < 0) {
                    x = ox;
                    y = oy;
                    z = oz;
                }
            }
            String key = ender ? srv + "|ender" : srv + "|" + dim + "|" + x + "," + y + "," + z;
            JsonObject e = new JsonObject();
            e.addProperty("key", key);
            e.addProperty("server", srv);
            e.addProperty("dim", ender ? "ender" : dim);
            e.addProperty("x", x);
            e.addProperty("y", y);
            e.addProperty("z", z);
            e.addProperty("block", block);
            e.addProperty("title", cut(J.str(req, "title", ""), 60));
            e.addProperty("slots", J.i(req, "slots", 0));
            e.add("items", cleanItems(req.get("items")));
            e.addProperty("t", J.bool(req, "t") ? J.lng(req.get("t")) : System.currentTimeMillis());
            for (String k : oldKeys) {
                data.remove(k);
            }
            data.add(key, e);
            J.saveQuiet(path, data);
            return e;
        }

        synchronized void delete(String key) {
            if (data.remove(key) != null) {
                J.saveQuiet(path, data);
            }
        }

        synchronized List<JsonElement> list(String server) {
            List<JsonElement> out = new ArrayList<>();
            for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                if (server == null || server.isEmpty() || server.equals(J.str(e.getValue().getAsJsonObject(), "server"))) {
                    out.add(e.getValue());
                }
            }
            return out;
        }
    }

    static int compare(int ax, int ay, int az, int bx, int by, int bz) {
        if (ax != bx) {
            return Integer.compare(ax, bx);
        }
        if (ay != by) {
            return Integer.compare(ay, by);
        }
        return Integer.compare(az, bz);
    }

    static String cut(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    // ------------------------------------------------------------------ chest lids

    /** Chests someone else opened within view distance (block event from the mod). */
    public static final class LidLog {
        final Path path;
        final JsonObject data;
        boolean dirty;
        double saved;

        LidLog(Path recRoot) {
            path = recRoot.resolve(".viewer").resolve("chest_lids.json");
            data = loadObj(path);
        }

        synchronized JsonObject put(JsonObject req) {
            if (!req.has("server")) {
                throw new KeyError("server");
            }
            String srv = J.str(req, "server"), dim = J.str(req, "dim", "overworld");
            int x = J.i(req, "x"), y = J.i(req, "y"), z = J.i(req, "z");
            String key = srv + "|" + dim + "|" + x + "," + y + "," + z;
            long t = J.bool(req, "t") ? J.lng(req.get("t")) : System.currentTimeMillis();
            boolean opened = J.bool(req, "open");
            JsonObject e = J.obj(data, key);
            if (e == null) {
                e = new JsonObject();
                e.addProperty("key", key);
                e.addProperty("server", srv);
                e.addProperty("dim", dim);
                e.addProperty("x", x);
                e.addProperty("y", y);
                e.addProperty("z", z);
                e.addProperty("block", J.str(req, "block", ""));
                e.addProperty("opens", 0);
                e.addProperty("first", t);
            }
            if (opened) {
                e.addProperty("opens", J.lng(e.get("opens")) + 1);
                e.addProperty("last", t);
            }
            e.addProperty("open", opened);
            e.addProperty("seen", t);
            data.add(key, e);
            if (data.size() > 20000) {
                List<String> keys = new ArrayList<>(data.keySet());
                keys.sort((a, b) -> Long.compare(seen(a), seen(b)));
                for (String k : keys.subList(0, 2000)) {
                    data.remove(k);
                }
            }
            // Busy chests send many events: write the (large) file at most every 5 s
            dirty = true;
            if (mono() - saved > 5.0) {
                save0();
            }
            return e;
        }

        private long seen(String k) {
            JsonObject o = J.obj(data, k);
            return o != null && o.has("seen") ? J.lng(o.get("seen")) : 0;
        }

        synchronized void save() {
            if (dirty) {
                save0();
            }
        }

        private void save0() {
            saved = mono();
            dirty = false;
            J.saveQuiet(path, data);
        }

        synchronized List<JsonElement> list(String server, String dim) {
            List<JsonElement> out = new ArrayList<>();
            for (JsonElement v : data.asMap().values()) {
                JsonObject o = v.getAsJsonObject();
                if ((server == null || server.isEmpty() || server.equals(J.str(o, "server")))
                        && (dim == null || dim.isEmpty() || dim.equals(J.str(o, "dim")))) {
                    out.add(o);
                }
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ commands for the mod

    static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    static final Set<String> KINDS = Set.of("goto", "stop", "pause", "resume", "clear", "litematica", "highlight", "fetch",
            "unpack", "store", "breakboxes", "chestdump");

    /** The browser drops goals, the mod picks them up with its next poll. */
    public static final class Commands {
        static final double TTL = 15.0;
        private final Map<String, List<Object[]>> queue = new HashMap<>();
        volatile JsonObject last;
        final Map<String, JsonObject> results = Collections.synchronizedMap(new LinkedHashMap<>());
        private long lastId;

        static JsonArray items(JsonElement src, int max) {
            JsonArray out = new JsonArray();
            for (JsonElement e : J.list(src)) {
                if (out.size() >= max) {
                    break;
                }
                JsonObject i = e.getAsJsonObject();
                JsonObject o = new JsonObject();
                if (!i.has("id")) {
                    throw new KeyError("id");
                }
                o.addProperty("id", J.str(i.get("id")));
                o.addProperty("count", J.has(i, "count") ? J.lng(i.get("count")) : 64);
                out.add(o);
            }
            return out;
        }

        static JsonArray ints(JsonElement src, int max) {
            JsonArray out = new JsonArray();
            for (JsonElement e : J.list(src)) {
                if (out.size() >= max) {
                    break;
                }
                out.add(J.lng(e));
            }
            return out;
        }

        static JsonArray strs(JsonElement src, int max) {
            JsonArray out = new JsonArray();
            for (JsonElement e : J.list(src)) {
                if (out.size() >= max) {
                    break;
                }
                out.add(J.str(e));
            }
            return out;
        }

        static JsonArray boxes(JsonElement src, int max, int width) {
            JsonArray out = new JsonArray();
            for (JsonElement b : J.list(src)) {
                if (out.size() >= max) {
                    break;
                }
                JsonArray a = b.getAsJsonArray();
                JsonArray row = new JsonArray();
                for (int k = 0; k < Math.min(width, a.size()); k++) {
                    row.add(J.lng(a.get(k)));
                }
                out.add(row);
            }
            return out;
        }

        private static long lastNs;

        /** Like Python's str(time.time_ns()), never twice the same. */
        static synchronized String newId() {
            long t = System.currentTimeMillis() * 1_000_000L + (System.nanoTime() % 1_000_000L + 1_000_000L) % 1_000_000L;
            if (t <= lastNs) {
                t = lastNs + 1;
            }
            lastNs = t;
            return Long.toString(t);
        }

        public JsonObject push(JsonObject req) {
            String kind = J.str(req, "kind");
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException("unbekannter Befehl");
            }
            JsonObject cmd = new JsonObject();
            cmd.addProperty("id", newId());
            cmd.addProperty("kind", kind);
            switch (kind) {
                case "goto" -> {
                    cmd.addProperty("x", J.lng(need(req, "x")));
                    cmd.addProperty("z", J.lng(need(req, "z")));
                    if (J.get(req, "y") != null) {
                        cmd.addProperty("y", J.lng(req.get("y")));
                    }
                }
                case "clear" -> {
                    for (String k : new String[] {"x0", "y0", "z0", "x1", "y1", "z1"}) {
                        cmd.addProperty(k, J.lng(need(req, k)));
                    }
                }
                case "litematica" -> cmd.addProperty("pid", J.str(need(req, "pid")));
                case "fetch" -> {
                    cmd.addProperty("x", J.lng(need(req, "x")));
                    cmd.addProperty("y", J.lng(need(req, "y")));
                    cmd.addProperty("z", J.lng(need(req, "z")));
                    cmd.add("slots", ints(req.get("slots"), 64));
                    cmd.add("items", items(req.get("items"), 64));
                    if (J.bool(req, "noShulker")) {
                        cmd.addProperty("noShulker", true);
                    }
                }
                case "unpack" -> {
                    cmd.add("items", items(need(req, "items"), 64));
                    if (J.bool(req, "box")) {
                        cmd.add("box", ints(req.get("box"), 6));
                    }
                }
                case "store" -> {
                    cmd.addProperty("x", J.lng(need(req, "x")));
                    cmd.addProperty("y", J.lng(need(req, "y")));
                    cmd.addProperty("z", J.lng(need(req, "z")));
                    JsonArray put = new JsonArray();
                    for (JsonElement e : J.list(need(req, "put"))) {
                        if (put.size() >= 41) {
                            break;
                        }
                        JsonObject i = e.getAsJsonObject();
                        JsonObject o = new JsonObject();
                        o.addProperty("slot", J.lng(need(i, "slot")));
                        o.addProperty("id", J.str(need(i, "id")));
                        put.add(o);
                    }
                    cmd.add("put", put);
                }
                case "highlight" -> cmd.add("pts", boxes(need(req, "pts"), 64, 3));
                case "breakboxes" -> {
                    cmd.add("boxes", boxes(need(req, "boxes"), 1500, 6));
                    cmd.addProperty("label", cut(J.bool(req, "label") ? J.str(req, "label") : "Abbauen", 40));
                    cmd.addProperty("pickup", J.bool(req, "pickup", true));
                    if (J.bool(req, "timeout")) {
                        cmd.addProperty("timeout", Math.max(30, Math.min(1800, J.lng(req.get("timeout")))));
                    }
                    cmd.add("ids", strs(req.get("ids"), 64));
                    if (J.bool(req, "fill")) {
                        String fill = J.str(req, "fill");
                        if (!BLOCK_ID.matcher(fill).matches()) {
                            throw new IllegalArgumentException("ungültiger Block");
                        }
                        cmd.addProperty("fill", fill);
                    }
                }
                case "chestdump" -> {
                    cmd.add("slots", ints(req.get("slots"), 36));
                    cmd.add("ids", strs(req.get("ids"), 64));
                }
                default -> { }
            }
            if (!req.has("srv")) {
                throw new KeyError("srv");
            }
            String srv = J.str(req, "srv");
            synchronized (queue) {
                List<Object[]> q = queue.computeIfAbsent(srv, k -> new ArrayList<>());
                if (kind.equals("stop")) {
                    q.clear();
                }
                q.add(new Object[] {cmd, mono()});
                while (q.size() > 5) {
                    q.remove(0);
                }
            }
            return cmd;
        }

        static JsonElement need(JsonObject o, String k) {
            if (o == null || !o.has(k)) {
                throw new KeyError(k);
            }
            return o.get(k);
        }

        public List<JsonObject> pop(String srv) {
            double now = mono();
            List<Object[]> q;
            synchronized (queue) {
                q = queue.remove(srv);
            }
            List<JsonObject> out = new ArrayList<>();
            if (q != null) {
                for (Object[] c : q) {
                    if (now - (double) c[1] < TTL) {
                        out.add((JsonObject) c[0]);
                    }
                }
            }
            return out;
        }

        public void result(JsonObject req) {
            JsonObject r = new JsonObject();
            r.addProperty("id", J.str(req, "id", ""));
            r.addProperty("ok", J.bool(req, "ok"));
            r.addProperty("msg", cut(J.str(req, "msg", ""), 200));
            r.addProperty("t", now());
            last = r;
            synchronized (results) {
                results.put(J.str(r, "id"), r);
                if (results.size() > 64) {
                    Iterator<String> it = results.keySet().iterator();
                    for (int k = 0; k < 16 && it.hasNext(); k++) {
                        it.next();
                        it.remove();
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ printer, server-safe, layers

    /**
     * Desired state for the Webmatica printer, server-safe and Litematica layers (per server). The
     * browser changes parts of it, each change increments {@code seq}; the mod applies the state
     * when it sees a new number and reports the real in-game state back. "Layer by layer" is
     * computed here: the lowest layer of the chosen schematic in which something is still missing.
     */
    public static final class Control {
        static final double AUTO_EVERY = 3.0;
        final Map<String, JsonObject> state = new HashMap<>();
        final Map<String, JsonObject> status = new HashMap<>();
        final Map<String, Double> statusTs = new HashMap<>();
        final Map<String, Double> autoTs = new HashMap<>();
        final Path path;
        final JsonObject saved = new JsonObject();

        Control(Path recRoot) {
            path = recRoot == null ? null : recRoot.resolve(".viewer").resolve("safety.json");
            saved.add("safe", new JsonObject());
            saved.add("web", new JsonObject());
            if (path != null) {
                JsonObject data = loadObj(path);
                for (String k : new String[] {"safe", "web"}) {
                    if (J.obj(data, k) != null) {
                        saved.add(k, data.get(k));
                    }
                }
            }
        }

        /** Server-safe for this server: everything most public servers forbid is off. */
        public synchronized boolean safe(String srv) {
            return J.bool(saved.getAsJsonObject("safe").get(srv));
        }

        synchronized JsonObject web(String srv) {
            JsonObject out = new JsonObject();
            out.addProperty("on", false);
            out.addProperty("range", 4.5);
            out.addProperty("bps", 10.0);
            JsonObject keep = J.obj(saved.getAsJsonObject("web"), srv);
            if (keep != null) {
                keep.entrySet().forEach(e -> out.add(e.getKey(), e.getValue()));
            }
            out.addProperty("on", J.bool(st(srv), "webOn"));
            return out;
        }

        private void save() {
            if (path != null) {
                try {
                    J.save(path, saved, true);
                } catch (java.io.IOException ignored) {
                    // next change tries again
                }
            }
        }

        JsonObject st(String srv) {
            return state.computeIfAbsent(srv, k -> {
                JsonObject o = new JsonObject();
                o.addProperty("seq", System.currentTimeMillis() / 1000);
                o.add("layer", J.nul());
                o.add("auto", J.nul());
                return o;
            });
        }

        private static void bump(JsonObject st) {
            st.addProperty("seq", J.lng(st.get("seq")) + 1);
        }

        public synchronized JsonObject push(JsonObject req) {
            if (!req.has("srv")) {
                throw new KeyError("srv");
            }
            String srv = J.str(req, "srv");
            JsonObject st = st(srv);
            boolean changed = false;
            if (req.has("safe")) {
                saved.getAsJsonObject("safe").addProperty(srv, J.bool(req.get("safe")));
                save();
                changed = true;
            }
            JsonObject w = J.obj(req, "web");
            if (w != null) {
                if (w.has("on")) {
                    st.addProperty("webOn", J.bool(w.get("on")));
                }
                JsonObject old = J.obj(saved.getAsJsonObject("web"), srv);
                JsonObject keep = old == null ? new JsonObject() : old.deepCopy();
                if (w.has("range")) {
                    keep.addProperty("range", Math.min(6.0, Math.max(1.0, J.d(w.get("range")))));
                }
                if (w.has("bps")) {
                    keep.addProperty("bps", Math.min(20.0, Math.max(1.0, J.d(w.get("bps")))));
                }
                saved.getAsJsonObject("web").add(srv, keep);
                save();
                changed = true;
            }
            if (req.has("layer")) {
                st.add("layer", layer(req.get("layer")));
                changed = true;
            }
            if (req.has("auto")) {
                st.add("auto", J.bool(req.get("auto")) ? new JsonPrimitive(J.str(req.get("auto"))) : J.nul());
                autoTs.remove(srv);
                if (!J.bool(st.get("auto")) && !req.has("layer")) {
                    JsonObject all = new JsonObject();
                    all.addProperty("mode", "all");
                    st.add("layer", all);
                    changed = true;
                }
            }
            if (changed) {
                bump(st);
            }
            return view(srv);
        }

        static JsonElement layer(JsonElement lay) {
            JsonObject l = J.obj(lay);
            if (l == null) {
                return J.nul();
            }
            JsonObject out = new JsonObject();
            if ("all".equals(J.str(l, "mode"))) {
                out.addProperty("mode", "all");
                return out;
            }
            int lo = J.i(l, "min");
            int hi = J.i(l, "max", lo);
            out.addProperty("mode", "range");
            out.addProperty("min", Math.min(lo, hi));
            out.addProperty("max", Math.max(lo, hi));
            return out;
        }

        public synchronized JsonObject forMod(String srv) {
            JsonObject st = st(srv);
            JsonObject out = new JsonObject();
            out.add("seq", st.get("seq"));
            out.add("layer", st.get("layer"));
            out.addProperty("safe", safe(srv));
            out.add("web", web(srv));
            return out;
        }

        public synchronized void setStatus(JsonObject req) {
            String srv = J.bool(req, "server") ? J.str(req, "server") : "";
            status.put(srv, req);
            statusTs.put(srv, mono());
        }

        public synchronized JsonObject view(String srv) {
            boolean fresh = mono() - statusTs.getOrDefault(srv, -1e9) < 6.0;
            JsonObject st = st(srv);
            JsonObject out = new JsonObject();
            out.add("seq", st.get("seq"));
            out.add("auto", st.get("auto"));
            out.add("layer", st.get("layer"));
            out.add("autoInfo", st.has("autoInfo") ? st.get("autoInfo") : J.nul());
            out.addProperty("safe", safe(srv));
            out.add("web", web(srv));
            out.addProperty("online", fresh);
            out.add("game", fresh && status.get(srv) != null ? status.get(srv) : J.nul());
            return out;
        }

        /** Layer by layer: sets the lowest open layer every few seconds. */
        public void autoStep(String srv, Function<String, JsonObject> verify) {
            String pid;
            JsonObject st;
            synchronized (this) {
                st = st(srv);
                pid = J.bool(st.get("auto")) ? J.str(st.get("auto")) : null;
                if (pid == null || mono() - autoTs.getOrDefault(srv, 0.0) < AUTO_EVERY) {
                    return;
                }
                autoTs.put(srv, mono());
            }
            JsonObject res;
            try {
                res = verify.apply(pid);
            } catch (RuntimeException e) {   // placement gone or similar: automation off
                synchronized (this) {
                    st.add("auto", J.nul());
                    st.addProperty("autoInfo", "aus: " + App.pyErr(e));
                }
                return;
            }
            JsonObject lowest = J.obj(res, "lowestMissing");
            synchronized (this) {
                JsonObject want = new JsonObject();
                if (lowest == null) {
                    want.addProperty("mode", "all");
                    st.addProperty("autoInfo", !J.bool(res, "todoTotal") ? "fertig" : "nur noch falsche Bloecke");
                } else {
                    int y = J.i(lowest, "y");
                    want.addProperty("mode", "range");
                    want.addProperty("min", y);
                    want.addProperty("max", y);
                    st.addProperty("autoInfo", "Y " + y + ": noch " + J.str(lowest, "count") + " Bloecke");
                }
                if (!want.equals(st.get("layer"))) {
                    st.add("layer", want);
                    bump(st);
                }
            }
        }
    }

    // ------------------------------------------------------------------ radar

    /** Other players within view distance: current state plus a log (radar.json). */
    public static final class Radar {
        final Path path;
        final List<JsonObject> log = new ArrayList<>();
        final Map<String, JsonObject> open = new HashMap<>();
        double saved = mono();

        Radar(Path recRoot) {
            path = recRoot.resolve(".viewer").resolve("radar.json");
            JsonElement e = J.load(path);
            if (e != null && e.isJsonArray()) {
                for (JsonElement x : e.getAsJsonArray()) {
                    if (x.isJsonObject()) {
                        log.add(x.getAsJsonObject());
                        open.put(J.str(x.getAsJsonObject(), "server") + "\u0000" + J.str(x.getAsJsonObject(), "name"), x.getAsJsonObject());
                    }
                }
            }
        }

        public synchronized JsonArray update(JsonObject me, JsonArray others) {
            double now = now();
            JsonArray clean = new JsonArray();
            int n = 0;
            for (JsonElement oe : others) {
                if (n++ >= 64) {
                    break;
                }
                JsonObject e;
                try {
                    JsonObject o = oe.getAsJsonObject();
                    e = new JsonObject();
                    e.addProperty("name", cut(J.str(Commands.need(o, "name")), 32));
                    e.addProperty("uuid", cut(J.str(o, "uuid", ""), 40));
                    e.addProperty("x", J.d(o, "x"));
                    e.addProperty("y", J.d(o, "y"));
                    e.addProperty("z", J.d(o, "z"));
                    e.addProperty("yaw", J.d(o, "yaw", 0));
                } catch (RuntimeException ex) {
                    continue;
                }
                double dx = J.d(e.get("x")) - J.d(me.get("x")), dz = J.d(e.get("z")) - J.d(me.get("z"));
                e.addProperty("dist", J.round(Math.sqrt(dx * dx + dz * dz), 1));
                clean.add(e);
            }
            String server = J.str(me, "server");
            for (JsonElement ce : clean) {
                JsonObject e = ce.getAsJsonObject();
                String name = J.str(e, "name");
                String key = server + "\u0000" + name;
                JsonObject last = open.get(key);
                double dist = J.d(e.get("dist"));
                if (last == null || now - J.d(last, "last", 0) >= 120) {
                    last = new JsonObject();
                    last.addProperty("name", name);
                    last.addProperty("server", server);
                    last.add("dim", me.get("dim"));
                    last.addProperty("first", now);
                    last.addProperty("x", Math.round(J.d(e.get("x"))));
                    last.addProperty("z", Math.round(J.d(e.get("z"))));
                    last.addProperty("closest", dist);
                    log.add(last);
                    open.put(key, last);
                }
                last.addProperty("last", now);
                if (dist <= J.d(last.get("closest"))) {
                    last.addProperty("closest", dist);
                    last.addProperty("x", Math.round(J.d(e.get("x"))));
                    last.addProperty("z", Math.round(J.d(e.get("z"))));
                }
            }
            if (log.size() > 500) {
                log.subList(0, log.size() - 500).clear();
                Set<JsonObject> kept = Collections.newSetFromMap(new IdentityHashMap<>());
                kept.addAll(log);
                open.values().removeIf(v -> !kept.contains(v));
            }
            if (!clean.isEmpty() && mono() - saved > 10) {
                save0();
            }
            return clean;
        }

        public synchronized void save() {
            save0();
        }

        private void save0() {
            saved = mono();
            J.saveQuiet(path, log);
        }

        public synchronized List<JsonObject> list(String server) {
            List<JsonObject> sel = new ArrayList<>();
            for (JsonObject s : log) {
                if (server == null || server.isEmpty() || server.equals(J.str(s, "server"))) {
                    sel.add(s);
                }
            }
            List<JsonObject> tail = new ArrayList<>(sel.subList(Math.max(0, sel.size() - 200), sel.size()));
            Collections.reverse(tail);
            return tail;
        }
    }
}
