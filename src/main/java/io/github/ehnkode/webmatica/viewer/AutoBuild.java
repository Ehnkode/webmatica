package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.ehnkode.webmatica.viewer.core.KeyError;
import io.github.ehnkode.webmatica.viewer.core.LookupError;
import io.github.ehnkode.webmatica.viewer.core.Recording;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Automations, one per server at a time: auto-build (fetch material, build with Baritone from
 * Litematica, break wrong blocks), storing, farming and flattening/clearing/filling/lighting.
 * {@link #step} advances it; the server's automation loop calls it while the mod polls.
 */
final class AutoBuild {
    // ------------------------------------------------------------------ timings and limits
    static final double FRESH_PLAYER = 6.0, STEP_EVERY = 2.0, FETCH_TIMEOUT = 240.0, SETTLE = 2.5, STALL = 45.0,
            BAD_CHEST = 600.0, NEAR = 20.0, UNSEEN_WAIT = 8.0, RADAR_CLEAR = 15.0, MINE_TIMEOUT = 360.0, STORE_RADIUS = 128.0;
    static final int MAIN_SLOTS = 36, FIX_ROUNDS = 2, MINE_BATCH = 48, FREE_MIN = 3, FILL_BATCH = 128, LIGHT_BATCH = 8;
    static final Set<String> RUNNING = Set.of("plan", "fetch", "build", "approach", "unpack", "return", "fix", "fplan", "mine", "deposit");
    static final List<String> ENGINES = List.of("auto", "native", "baritone");
    /** stays in the inventory when storing */
    static final Pattern KEEP = Pattern.compile("pickaxe|_axe|shovel|_hoe|sword|shears|chest$|shulker_box|totem|elytra|helmet|chestplate|leggings|boots|"
            + "flint_and_steel|bucket|ender_pearl|golden_apple|golden_carrot|bread|cooked_|steak|baked_potato|firework");
    static final Pattern BLOCK_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_/.-]+");
    static final Pattern CHEST_AT = Pattern.compile("bei (-?\\d+) (-?\\d+) (-?\\d+)");

    static Map<String, Object> defaults() {
        return J.map("radar", true, "radarDist", 64, "friends", List.of(), "eat", false, "tools", true, "leftovers", true,
                "fix", true, "dig", false, "engine", "auto");
    }

    static String full(String it) {
        return it.contains(":") ? it : "minecraft:" + it;
    }

    static String shortId(String k) {
        return k.substring(k.lastIndexOf(':') + 1);
    }

    /** State of one server's automation. */
    static final class St {
        String pid, mode = "build", phase, info = "";
        final List<List<Object>> log = new ArrayList<>();
        String cmd;
        double ts, notBefore, next, progressTs;
        int fails, stalls, fetched;
        final Map<String, Double> bad = new HashMap<>();
        Long score;
        Object[] check;              // [chest key, open need]
        String fetchChest;
        Double unseenUntil;
        boolean unpackOff, recOff, judge;
        int unpackFails, fixRounds, returned, deposits, depositFails;
        double lastD;
        Map<String, Object> paused;  // name, since
        Double clearSince;
        List<Map<String, Object>> stops = new ArrayList<>();
        // farm / flatten
        Farm.Area area;
        String dim;
        Map<String, Integer> targets;
        Map<String, Long> start, mined;
        Map<String, Object> chest;
        boolean place, autoChest;
        final Set<List<Integer>> skip = new HashSet<>();
        final Set<List<Integer>> full = new HashSet<>();
        List<int[]> batch;
        Long last;
        Map<String, Object> flatten;
    }

    final App app;
    final Path path, qpath;
    final Map<String, St> state = new HashMap<>();
    final Map<String, Object> settings = defaults();
    final Map<String, List<String>> queue = new LinkedHashMap<>();
    private final Set<String> busy = new HashSet<>();

    AutoBuild(App app, Path recRoot) {
        this.app = app;
        this.path = recRoot.resolve(".viewer").resolve("autobuild.json");
        this.qpath = recRoot.resolve(".viewer").resolve("autobuild-queue.json");
        JsonObject q = J.obj(J.load(qpath));
        if (q != null) {
            for (Map.Entry<String, JsonElement> e : q.entrySet()) {
                List<String> l = new ArrayList<>();
                for (JsonElement x : J.list(e.getValue())) {
                    l.add(J.str(x));
                }
                queue.put(e.getKey(), l);
            }
        }
        JsonObject s = J.obj(J.load(path));
        if (s != null) {
            Map<String, Object> d = defaults();
            for (Map.Entry<String, JsonElement> e : s.entrySet()) {
                if (d.containsKey(e.getKey())) {
                    settings.put(e.getKey(), e.getValue());
                }
            }
        }
    }

    // ------------------------------------------------------------------ settings

    synchronized Map<String, Object> getSettings() {
        return new LinkedHashMap<>(settings);
    }

    private synchronized boolean setting(String k) {
        return J.bool(J.tree(settings.get(k)));
    }

    Map<String, Object> setSettings(JsonObject req) {
        Map<String, Object> out;
        synchronized (this) {
            for (Map.Entry<String, JsonElement> e : req.entrySet()) {
                String k = e.getKey();
                JsonElement v = e.getValue();
                if (!settings.containsKey(k)) {
                    continue;
                }
                Object val;
                if (k.equals("radarDist")) {
                    val = Math.max(8, Math.min(256, J.i(v)));
                } else if (k.equals("engine")) {
                    val = v != null && v.isJsonPrimitive() && ENGINES.contains(v.getAsString()) ? v.getAsString() : "auto";
                } else if (k.equals("friends")) {
                    List<String> src = new ArrayList<>();
                    if (v != null && v.isJsonArray()) {
                        for (JsonElement x : v.getAsJsonArray()) {
                            src.add(J.str(x));
                        }
                    } else {
                        src.addAll(List.of(J.str(v).split(",", -1)));
                    }
                    List<String> f = new ArrayList<>();
                    for (String n : src) {
                        String t = n.strip();
                        if (!t.isEmpty() && f.size() < 50) {
                            f.add(t.substring(0, Math.min(32, t.length())));
                        }
                    }
                    val = f;
                } else {
                    val = J.bool(v);
                }
                settings.put(k, val);
            }
            out = new LinkedHashMap<>(settings);
        }
        J.saveQuiet(path, out);
        return out;
    }

    /** For the mod: eating/tool protection only while an automation runs. */
    synchronized Map<String, Object> guard(String srv) {
        St st = state.get(srv);
        boolean on = st != null && (RUNNING.contains(st.phase) || st.phase.equals("store"));
        return J.map("eat", on && setting("eat"), "tools", on && setting("tools"), "dig", setting("dig"),
                "engine", settings.getOrDefault("engine", "auto"));
    }

    // ------------------------------------------------------------------ queue

    Map<String, Object> setQueue(String srv, List<String> pids) {
        Map<String, List<String>> data;
        synchronized (this) {
            queue.put(srv, new ArrayList<>(pids.subList(0, Math.min(50, pids.size()))));
            data = new LinkedHashMap<>(queue);
        }
        J.saveQuiet(qpath, data);
        return view(srv);
    }

    /** Start the next schematic from the queue (true if one was started). */
    boolean nextInQueue(String srv) {
        while (true) {
            List<String> q;
            synchronized (this) {
                q = new ArrayList<>(queue.getOrDefault(srv, List.of()));
            }
            if (q.isEmpty()) {
                return false;
            }
            String pid = q.remove(0);
            setQueue(srv, q);
            String name;
            try {
                JsonObject up = new JsonObject();
                up.addProperty("id", pid);
                up.addProperty("ingame", true);     // Baritone builds from Litematica
                app.placements.update(up);
                JsonObject pl = app.placements.get(pid);
                name = J.bool(pl, "name") ? J.str(pl, "name") : "Schematic";
            } catch (LookupError e) {
                continue;
            } catch (java.io.IOException e) {
                continue;
            }
            start(srv, pid);
            log(srv, "Warteschlange: als Nächstes " + name);
            return true;
        }
    }

    Map<String, Object> stop(String srv, String why) {
        if (why == null) {
            why = "gestoppt";
        }
        St st;
        boolean active;
        synchronized (this) {
            st = state.get(srv);
            active = st != null && !Set.of("off", "done", "error").contains(st.phase);
        }
        if (active) {
            if (Set.of("fetch", "build", "approach", "unpack", "store", "return", "fix", "mine", "deposit").contains(st.phase) || st.paused != null) {
                cmd(srv, J.map("kind", "stop"));
            }
            end(srv, "off", why);
        }
        return view(srv);
    }

    synchronized Map<String, Object> view(String srv) {
        St st = state.get(srv);
        List<String> q = new ArrayList<>(queue.getOrDefault(srv, List.of()));
        if (st == null) {
            return J.map("phase", "off", "queue", q);
        }
        Map<String, Object> out = J.map("pid", st.pid, "mode", st.mode, "phase", st.phase, "info", st.info,
                "log", new ArrayList<>(st.log.subList(Math.max(0, st.log.size() - 30), st.log.size())), "fetched", st.fetched,
                "paused", st.paused == null ? null : new LinkedHashMap<>(st.paused), "queue", q);
        if (st.mode.equals("flatten")) {
            out.put("farm", J.map("area", st.area.toMap(), "flatten", new LinkedHashMap<>(st.flatten), "chest", st.chest,
                    "deposits", st.deposits));
        }
        if (st.mode.equals("farm")) {
            out.put("farm", J.map("area", st.area.toMap(), "targets", st.targets, "mined", st.mined, "chest", st.chest,
                    "deposits", st.deposits));
        }
        return out;
    }

    // ------------------------------------------------------------------ internal

    void log(String srv, String msg) {
        synchronized (this) {
            St st = state.get(srv);
            if (st == null) {
                return;
            }
            st.info = msg;
            st.log.add(List.of((long) (Stores.now() * 1000), msg));
            while (st.log.size() > 60) {
                st.log.remove(0);
            }
        }
    }

    void end(String srv, String phase, String msg) {
        synchronized (this) {
            St st = state.get(srv);
            if (st != null) {
                st.phase = phase;
            }
        }
        log(srv, msg);
    }

    synchronized void phase(St st, String phase) {
        if (!phase.equals(st.phase)) {
            st.ts = Stores.mono();
        }
        st.phase = phase;
    }

    synchronized void info(St st, String msg) {
        st.info = msg;
    }

    /** Pushes a command for the mod; returns its id. */
    String cmd(String srv, Map<String, Object> cmd) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("srv", srv);
        m.putAll(cmd);
        return J.str(app.commands.push(J.tree(m).getAsJsonObject()), "id");
    }

    /** [loose items by id, free slots], or null when the inventory belongs to another server; inner gets shulker contents. */
    Object[] inventory(String srv, Map<String, Long> inner) {
        JsonObject inv = app.inventory;
        if (inv == null) {
            inv = new JsonObject();
        }
        JsonElement s = J.get(inv, "server");
        if (s != null && !J.str(s).equals(srv)) {
            return null;
        }
        Map<String, Long> counts = new LinkedHashMap<>();
        int used = 0;
        for (JsonElement e : J.list(J.get(inv, "items"))) {
            JsonObject it = e.getAsJsonObject();
            JsonElement slot = J.get(it, "slot");
            if (slot != null && J.i(slot) >= MAIN_SLOTS) {
                continue;                   // armor/offhand don't count
            }
            used++;
            counts.merge(J.str(it, "id"), J.lng(it.get("count")), Long::sum);
            if (inner != null) {
                for (JsonElement c : J.list(J.get(it, "contents"))) {
                    JsonObject co = c.getAsJsonObject();
                    inner.merge(J.str(co, "id"), J.lng(co.get("count")), Long::sum);
                }
            }
        }
        return new Object[] {counts, MAIN_SLOTS - used};
    }

    JsonObject player(String srv) {
        JsonObject p = app.player;
        if (p == null || Stores.mono() - app.playerTs > FRESH_PLAYER || !srv.equals(J.str(p, "server", "None"))) {
            return null;
        }
        return p;
    }

    static List<JsonObject> items(JsonObject inv) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : J.list(J.get(inv, "items"))) {
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    List<JsonObject> containers(String srv) {
        List<JsonObject> out = new ArrayList<>();
        for (JsonElement e : app.containers.list(srv)) {
            out.add(e.getAsJsonObject());
        }
        return out;
    }

    static String desc(List<Object[]> take, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(max, take.size()); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(take.get(i)[1]).append("× ").append(shortId((String) take.get(i)[0]));
        }
        if (take.size() > max) {
            sb.append(" …");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ step

    void step(String srv) {
        if (app.control.safe(srv)) {
            stop(srv, "gestoppt (Server-safe ist an)");
            return;
        }
        double now = Stores.mono();
        boolean queued;
        St st;
        synchronized (this) {
            st = state.get(srv);
            queued = st != null && st.phase.equals("done") && st.mode.equals("build") && !queue.getOrDefault(srv, List.of()).isEmpty()
                    && !busy.contains(srv) && now >= st.next;
            if (queued) {
                st.next = now + STEP_EVERY;
            }
        }
        if (queued) {
            if (player(srv) != null) {
                nextInQueue(srv);
            }
            return;
        }
        synchronized (this) {
            st = state.get(srv);
            if (st == null || Set.of("off", "done", "error").contains(st.phase) || now < st.next || busy.contains(srv)) {
                return;
            }
            st.next = now + STEP_EVERY;
            busy.add(srv);
        }
        try {
            stepOne(srv, st, now);
        } catch (Exception e) {   // never break the mod's polling
            end(srv, "error", "Fehler: " + App.pyErr(e));
        } finally {
            synchronized (this) {
                busy.remove(srv);
            }
        }
    }

    private void stepOne(String srv, St st, double now) throws Exception {
        JsonObject p = player(srv);
        if (p == null) {
            info(st, "wartet auf das Spiel …");
            return;
        }
        if (p.has("recording") && !J.bool(p, "recording") && st.mode.equals("build")) {
            // Progress is only visible through the recording: without it the build "stalls"
            synchronized (this) {
                st.info = "pausiert: Aufnahme ist aus (im Spiel F9 drücken)";
                st.recOff = true;
            }
            return;
        }
        if (st.recOff) {
            st.recOff = false;
            synchronized (this) {
                st.ts = now;
                st.progressTs = now;
            }
        }
        if (radar(srv, st, p, now)) {
            return;
        }
        if (st.mode.equals("store") || st.phase.equals("return")) {
            stepStore(srv, st, now);
        } else if (st.mode.equals("farm")) {
            stepFarm(srv, st, p, now);
        } else if (st.mode.equals("flatten")) {
            stepFlatten(srv, st, p, now);
        } else {
            stepBuild(srv, st, p, now);
        }
    }

    // ------------------------------------------------------------------ radar protection

    /** True while paused because of a player (then no further step). */
    private boolean radar(String srv, St st, JsonObject p, double now) {
        Map<String, Object> cfg = getSettings();
        Set<String> friends = new HashSet<>();
        for (JsonElement f : J.list(J.tree(cfg.get("friends")))) {
            friends.add(J.str(f).toLowerCase(Locale.ROOT));
        }
        double dist = J.d(J.tree(cfg.get("radarDist")));
        JsonObject nearest = null;
        for (JsonElement oe : J.list(J.get(p, "others"))) {
            JsonObject o = oe.getAsJsonObject();
            if (friends.contains(J.str(o, "name", "").toLowerCase(Locale.ROOT))) {
                continue;
            }
            double d = J.d(o, "dist", 1e9);
            if (d <= dist && (nearest == null || d < J.d(nearest, "dist"))) {
                nearest = o;
            }
        }
        boolean radarOn = J.bool(J.tree(cfg.get("radar")));
        if (radarOn && nearest != null) {
            String name = J.str(nearest, "name");
            int d = (int) J.d(nearest, "dist");
            synchronized (this) {
                st.clearSince = null;
                st.info = "pausiert: " + name + " " + d + " m entfernt";
            }
            if (st.paused == null) {
                cmd(srv, J.map("kind", "pause"));
                synchronized (this) {
                    st.paused = J.map("name", name, "since", now);
                }
                log(srv, "Spieler " + name + " in " + d + " m: pausiert");
            }
            return true;
        }
        if (st.paused == null) {
            return false;
        }
        if (radarOn) {
            synchronized (this) {
                if (st.clearSince == null || st.clearSince == 0) {
                    st.clearSince = now;
                }
                double wait = RADAR_CLEAR - (now - st.clearSince);
                if (wait > 0) {
                    st.info = "pausiert: niemand mehr da, weiter in " + ((int) wait + 1) + " s";
                    return true;
                }
            }
        }
        // continue: don't count the wait as "stuck"
        cmd(srv, J.map("kind", "resume"));
        double took = now - (Double) st.paused.get("since");
        synchronized (this) {
            st.paused = null;
            st.clearSince = null;
            st.ts += took;
            st.progressTs += took;
        }
        log(srv, "weiter nach " + (int) took + " s Pause");
        return false;
    }

    // ------------------------------------------------------------------ build

    Map<String, Object> start(String srv, String pid) {
        app.placements.get(pid);          // raises if the placement doesn't exist
        synchronized (this) {
            St st = new St();
            st.pid = pid;
            st.phase = "plan";
            st.info = "startet …";
            st.ts = Stores.mono();
            st.progressTs = Stores.mono();
            state.put(srv, st);
        }
        log(srv, "Autobau gestartet");
        return view(srv);
    }

    private void stepBuild(String srv, St st, JsonObject p, double now) throws Exception {
        JsonObject pl;
        try {
            pl = app.placements.withSize(app.placements.get(st.pid));
        } catch (LookupError e) {
            end(srv, "error", "Schematic gibt es nicht mehr");
            return;
        }
        if (!J.str(pl, "dim", "None").equals(J.str(p, "dim", "None"))) {
            info(st, "du bist in einer anderen Dimension als die Schematic");
            return;
        }
        switch (st.phase) {
            case "plan" -> {
                if (now >= st.notBefore) {
                    plan(srv, st, pl, p);
                }
            }
            case "fetch" -> watchFetch(srv, st, now);
            case "build" -> watchBuild(srv, st, now);
            case "approach" -> watchApproach(srv, st, pl, p, now);
            case "unpack" -> watchUnpack(srv, st, now);
            case "fix" -> watchFix(srv, st, now);
            default -> {
            }
        }
    }

    /** [missing items (full ids) in order, verify result]. */
    Object[] missing(String pid) {
        JsonObject res = app.placements.verify(pid, app.rec, null);
        Map<String, Long> miss = new LinkedHashMap<>();
        for (JsonElement e : J.list(J.get(res, "missingItems"))) {
            JsonArray a = e.getAsJsonArray();
            miss.merge(full(J.str(a.get(0))), J.lng(a.get(1)), Long::sum);
        }
        return new Object[] {miss, res};
    }

    static long sum(Map<String, Long> m) {
        long s = 0;
        for (long v : m.values()) {
            s += v;
        }
        return s;
    }

    @SuppressWarnings("unchecked")
    private void plan(String srv, St st, JsonObject pl, JsonObject p) {
        Object[] mr = missing(st.pid);
        Map<String, Long> miss = (Map<String, Long>) mr[0];
        JsonObject res = (JsonObject) mr[1];
        JsonObject stats = J.obj(res, "stats");
        long unknown = stats == null ? 0 : J.lng(J.get(stats, "unknown") == null ? new com.google.gson.JsonPrimitive(0) : stats.get("unknown"));
        if (miss.isEmpty() && unknown != 0) {
            // Parts of the frame were never recorded: "nothing missing" means nothing there
            unseen(srv, st, pl, p, unknown);
            return;
        }
        st.unseenUntil = null;
        if (miss.isEmpty()) {
            if (startFix(srv, st, res)) {
                return;
            }
            long wrong = stats == null ? 0 : J.lng(stats.get("rotated")) + J.lng(stats.get("extra"));
            finish(srv, st, p, "fertig!" + (wrong != 0 ? " (" + wrong + " Blöcke sind verdreht oder zu viel, bitte selbst prüfen)" : ""));
            return;
        }
        Map<String, Long> inner = new LinkedHashMap<>();
        Object[] iv = inventory(srv, inner);
        if (iv == null) {
            info(st, "wartet auf dein Inventar …");
            return;
        }
        Map<String, Long> inv = (Map<String, Long>) iv[0];
        int free = (Integer) iv[1];
        Map<String, Long> need = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : miss.entrySet()) {
            long n = e.getValue() - inv.getOrDefault(e.getKey(), 0L);
            if (n > 0) {
                need.put(e.getKey(), n);
            }
        }
        // Last fetch brought nothing (chest differs from what was remembered)? Then skip the chest
        long openNeed = sum(need);
        for (Map.Entry<String, Long> e : need.entrySet()) {
            openNeed -= Math.min(e.getValue(), inner.getOrDefault(e.getKey(), 0L));
        }
        Object[] chk = st.check;
        st.check = null;
        if (chk != null && openNeed >= (Long) chk[1]) {
            st.bad.put((String) chk[0], Stores.mono() + BAD_CHEST);
            synchronized (this) {
                st.fails++;
            }
            if (st.fails >= 4) {
                end(srv, "error", "Holen bringt nichts (Kisten anders als gemerkt? einmal öffnen)");
                return;
            }
            log(srv, "Holen hat nichts gebracht, nimmt eine andere Kiste");
        } else if (chk != null) {
            st.fails = 0;
        }
        boolean haveSome = false;
        for (String k : miss.keySet()) {
            haveSome |= inv.getOrDefault(k, 0L) > 0;
        }
        // Shulker in the inventory with matching contents: unpack it first (no walking needed)
        List<Object[]> unpack = new ArrayList<>();
        for (Map.Entry<String, Long> e : need.entrySet()) {
            long in = inner.getOrDefault(e.getKey(), 0L);
            if (in != 0) {
                unpack.add(new Object[] {e.getKey(), Math.min(e.getValue(), in)});
            }
        }
        int[] size = size(pl);
        if (!unpack.isEmpty() && free > 0 && !st.unpackOff) {
            int x = J.i(pl, "x"), y = J.i(pl, "y"), z = J.i(pl, "z");
            List<Map<String, Object>> its = new ArrayList<>();
            for (Object[] u : unpack) {
                its.add(J.map("id", u[0], "count", u[1]));
            }
            String cid = cmd(srv, J.map("kind", "unpack", "items", its,
                    "box", List.of(x, y, z, x + size[0] - 1, y + size[1] - 1, z + size[2] - 1)));
            phase(st, "unpack");
            st.cmd = cid;
            log(srv, "packt Shulker aus (" + desc(unpack, 4) + ")");
            return;
        }
        // Fetch first (while there is room), then build
        if (!need.isEmpty() && free > 0) {
            Object[] stop = bestChest(srv, pl, p, need, free, Stores.mono(), st.bad);
            if (stop != null) {
                JsonObject chest = (JsonObject) stop[0];
                @SuppressWarnings("unchecked")
                List<Object[]> take = (List<Object[]>) stop[1];
                List<Map<String, Object>> its = new ArrayList<>();
                for (Object[] t : take) {
                    its.add(J.map("id", t[0], "count", t[1]));
                }
                String cid = cmd(srv, J.map("kind", "fetch", "x", chest.get("x"), "y", chest.get("y"), "z", chest.get("z"), "items", its));
                phase(st, "fetch");
                st.cmd = cid;
                st.fetchChest = J.str(chest, "key");
                st.check = new Object[] {st.fetchChest, openNeed};
                log(srv, "holt " + desc(take, 4) + " aus " + J.str(chest, "x") + " " + J.str(chest, "y") + " " + J.str(chest, "z"));
                return;
            }
        }
        if (haveSome) {
            // Far away: only walk first (without breaking), otherwise Baritone digs its way to the site
            double[] nb = nearBox(pl, p);
            if (nb[2] > NEAR) {
                cmd(srv, J.map("kind", "goto", "x", (int) nb[0], "z", (int) nb[1]));
                phase(st, "approach");
                st.lastD = nb[2];
                st.progressTs = Stores.mono();
                log(srv, "läuft zur Baustelle (" + (int) nb[2] + " m)");
                return;
            }
            String cid = cmd(srv, J.map("kind", "litematica", "pid", st.pid));
            phase(st, "build");
            st.cmd = cid;
            st.score = null;
            st.progressTs = Stores.mono();
            log(srv, "baut (" + sum(miss) + " Blöcke fehlen noch)");
            return;
        }
        if (!need.isEmpty() && free <= 0) {
            end(srv, "error", "Inventar voll mit anderen Sachen: bitte Platz machen und neu starten");
            return;
        }
        List<Map.Entry<String, Long>> es = new ArrayList<>(need.entrySet());
        es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        StringBuilder names = new StringBuilder();
        for (int i = 0; i < Math.min(6, es.size()); i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(es.get(i).getValue()).append("× ").append(shortId(es.get(i).getKey()));
        }
        end(srv, "error", "Material fehlt (in keiner gemerkten Kiste): " + names);
    }

    static int[] size(JsonObject pl) {
        List<JsonElement> s = J.list(J.get(pl, "size"));
        if (s.size() < 3) {
            return new int[] {1, 1, 1};
        }
        return new int[] {J.i(s.get(0)), J.i(s.get(1)), J.i(s.get(2))};
    }

    /** Walk to the site so the recording sees it; give up if it stays unseen. */
    private void unseen(String srv, St st, JsonObject pl, JsonObject p, long unknown) {
        double now = Stores.mono();
        double[] nb = nearBox(pl, p);
        if (nb[2] > NEAR) {
            cmd(srv, J.map("kind", "goto", "x", (int) nb[0], "z", (int) nb[1]));
            phase(st, "approach");
            st.lastD = nb[2];
            st.progressTs = now;
            log(srv, "läuft zur Baustelle, " + unknown + " Blöcke dort wurden noch nie aufgezeichnet");
            return;
        }
        if (st.unseenUntil == null) {
            synchronized (this) {
                st.unseenUntil = now + UNSEEN_WAIT;
                st.info = "wartet, bis die Aufnahme die Baustelle gespeichert hat …";
            }
            return;
        }
        if (now < st.unseenUntil) {
            return;
        }
        end(srv, "error", unknown + " Blöcke der Schematic wurden noch nie aufgezeichnet "
                + "(Aufnahme an? Einmal über die Baustelle laufen, dann neu starten)");
    }

    private Object[] bestChest(String srv, JsonObject pl, JsonObject p, Map<String, Long> need, int free, double now, Map<String, Double> bad) {
        Object[] best = null;
        double bestScore = 0.0;
        List<Map.Entry<String, Long>> order = new ArrayList<>(need.entrySet());
        order.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        for (JsonObject c : containers(srv)) {
            if (!J.str(c, "dim", "None").equals(J.str(pl, "dim", "None")) || bad.getOrDefault(J.str(c, "key"), 0.0) > now) {
                continue;
            }
            Map<String, Long> have = new HashMap<>();
            for (JsonElement ie : J.list(J.get(c, "items"))) {
                JsonObject it = ie.getAsJsonObject();
                have.merge(J.str(it, "id"), J.lng(it.get("count")), Long::sum);
                for (JsonElement inner : J.list(J.get(it, "contents"))) {      // shulkers: taken along and unpacked
                    JsonObject in = inner.getAsJsonObject();
                    have.merge(J.str(in, "id"), J.lng(in.get("count")), Long::sum);
                }
            }
            List<Object[]> take = new ArrayList<>();
            long slots = free;
            for (Map.Entry<String, Long> e : order) {
                long kHave = Math.min(e.getValue(), have.getOrDefault(e.getKey(), 0L));
                if (kHave <= 0 || slots <= 0) {
                    continue;
                }
                kHave = Math.min(kHave, slots * 64);
                slots -= (kHave + 63) / 64;
                take.add(new Object[] {e.getKey(), kHave});
            }
            if (take.isEmpty()) {
                continue;
            }
            long gain = 0;
            for (Object[] t : take) {
                gain += (Long) t[1];
            }
            double dist = Math.hypot(J.d(c, "x") - J.d(p, "x"), J.d(c, "z") - J.d(p, "z"));
            double score = gain / (dist + 32.0);
            if (score > bestScore) {
                best = new Object[] {c, take};
                bestScore = score;
            }
        }
        return best;
    }

    /** Nearest point just outside the schematic frame (x/z) and the distance to it. */
    static double[] nearBox(JsonObject pl, JsonObject p) {
        int[] s = size(pl);
        int x0 = J.i(pl, "x") - 2, z0 = J.i(pl, "z") - 2, x1 = J.i(pl, "x") + s[0] + 1, z1 = J.i(pl, "z") + s[2] + 1;
        int tx = Math.min(Math.max((int) J.d(p, "x"), x0), x1);
        int tz = Math.min(Math.max((int) J.d(p, "z"), z0), z1);
        if (x0 < tx && tx < x1 && z0 < tz && tz < z1) {
            return new double[] {tx, tz, 0.0};
        }
        return new double[] {tx, tz, Math.hypot(tx - J.d(p, "x"), tz - J.d(p, "z"))};
    }

    private void watchApproach(String srv, St st, JsonObject pl, JsonObject p, double now) {
        double d = nearBox(pl, p)[2];
        if (d <= NEAR) {
            cmd(srv, J.map("kind", "stop"));
            phase(st, "plan");
            st.notBefore = now + 1.0;
            return;
        }
        if (d < st.lastD - 1.0) {
            st.lastD = d;
            st.progressTs = now;
        } else if (now - st.progressTs > STALL) {
            cmd(srv, J.map("kind", "stop"));
            end(srv, "error", "kommt ohne Abbauen nicht zur Baustelle (Weg freimachen oder näher hinstellen)");
        }
    }

    JsonObject result(St st) {
        return st.cmd == null ? null : app.commands.results.get(st.cmd);
    }

    private void watchUnpack(String srv, St st, double now) {
        JsonObject r = result(st);
        if (r == null) {
            if (now - st.ts > 60.0) {
                log(srv, "Auspacken hängt, holt stattdessen aus Kisten");
                phase(st, "plan");
                st.notBefore = now + SETTLE;
                st.unpackOff = true;
            }
            return;
        }
        if (J.bool(r, "ok")) {
            log(srv, J.str(r, "msg"));
        } else {
            synchronized (this) {
                st.unpackFails++;
            }
            boolean off = st.unpackFails >= 2;
            log(srv, J.str(r, "msg") + (off ? " – Auspacken aus" : ""));
            if (off) {
                st.unpackOff = true;
            }
        }
        phase(st, "plan");
        st.notBefore = now + SETTLE;
    }

    private void watchFetch(String srv, St st, double now) {
        JsonObject r = result(st);
        if (r == null) {
            if (now - st.ts > FETCH_TIMEOUT) {
                cmd(srv, J.map("kind", "stop"));
                st.bad.put(st.fetchChest, now + BAD_CHEST);
                log(srv, "Holen dauert zu lange, nächste Kiste");
                phase(st, "plan");
                st.notBefore = now + SETTLE;
            }
            return;
        }
        String msg = J.str(r, "msg");
        boolean took = J.bool(r, "ok") || (msg.contains("Items genommen") && !msg.startsWith("Holen: nichts"));
        if (took) {
            synchronized (this) {
                st.fetched++;
            }
            log(srv, msg);
        } else {
            st.bad.put(st.fetchChest, now + BAD_CHEST);
            synchronized (this) {
                st.fails++;
            }
            if (st.fails >= 4) {
                end(srv, "error", "Holen klappt nicht: " + msg);
                return;
            }
            log(srv, msg + ", versucht eine andere Kiste");
        }
        phase(st, "plan");
        st.notBefore = now + SETTLE;
    }

    @SuppressWarnings("unchecked")
    private void watchBuild(String srv, St st, double now) {
        JsonObject r = result(st);
        if (r != null && !J.bool(r, "ok")) {
            end(srv, "error", "Bauen ging nicht: " + J.str(r, "msg"));
            return;
        }
        if (now - st.ts < 5.0) {
            return;
        }
        Map<String, Long> miss = (Map<String, Long>) missing(st.pid)[0];
        if (miss.isEmpty()) {
            cmd(srv, J.map("kind", "stop"));
            finish(srv, st, player(srv), "fertig!");
            return;
        }
        Object[] iv = inventory(srv, null);
        Map<String, Long> inv = iv == null ? Map.of() : (Map<String, Long>) iv[0];
        long carried = 0;
        for (String k : miss.keySet()) {
            carried += inv.getOrDefault(k, 0L);
        }
        long score = sum(miss) + carried;     // drops with every placed block
        if (st.score == null || score < st.score) {
            synchronized (this) {
                st.score = score;
                st.progressTs = now;
                st.stalls = 0;
                st.info = "baut … noch " + sum(miss) + " Blöcke, " + carried + " passende Items dabei";
            }
        }
        if (carried == 0) {
            cmd(srv, J.map("kind", "stop"));
            log(srv, "Material alle, holt Nachschub");
            phase(st, "plan");
            st.notBefore = now + SETTLE;
            return;
        }
        if (now - st.progressTs > STALL) {
            cmd(srv, J.map("kind", "stop"));
            synchronized (this) {
                st.stalls++;
            }
            if (st.stalls >= 3) {
                end(srv, "error", "Baritone kommt nicht weiter (3× ohne Fortschritt)");
                return;
            }
            log(srv, "geht nicht voran, plant neu (fehlt evtl. ein anderes Material)");
            phase(st, "plan");
            st.notBefore = now + SETTLE;
            st.progressTs = now;
        }
    }

    // ------------------------------------------------------------------ wrong blocks

    static List<int[]> fixPoints(JsonObject res, boolean extra) {
        JsonObject pts = J.obj(res, "points");
        List<int[]> out = new ArrayList<>();
        for (String k : extra ? new String[] {"wrong", "rotated", "extra"} : new String[] {"wrong", "rotated"}) {
            List<Long> flat = new ArrayList<>();
            for (JsonElement e : J.list(pts == null ? null : J.get(pts, k))) {
                flat.add(J.lng(e));
            }
            out.addAll(Farm.flatPoints(flat));
        }
        return out;
    }

    /** At the end: have rotated/extra blocks broken, then continue normally. */
    private boolean startFix(String srv, St st, JsonObject res) {
        if (!setting("fix") || st.fixRounds >= FIX_ROUNDS) {
            return false;
        }
        List<int[]> pts = fixPoints(res, true);
        if (pts.isEmpty()) {
            return false;
        }
        String cid = cmd(srv, J.map("kind", "breakboxes", "boxes", Farm.boxes(pts), "label", "Falsche Blöcke", "pickup", true,
                "timeout", (int) MINE_TIMEOUT));
        phase(st, "fix");
        st.cmd = cid;
        st.fixRounds++;
        log(srv, "baut " + pts.size() + " falsche oder überflüssige Blöcke ab");
        return true;
    }

    private void watchFix(String srv, St st, double now) {
        JsonObject r = result(st);
        if (r == null) {
            if (now - st.ts > MINE_TIMEOUT + 30) {
                cmd(srv, J.map("kind", "stop"));
                log(srv, "Abbauen dauert zu lange, plant neu");
                phase(st, "plan");
                st.notBefore = now + SETTLE;
            }
            return;
        }
        log(srv, J.str(r, "msg"));
        phase(st, "plan");
        st.notBefore = now + SETTLE + 1.0;
    }

    // ------------------------------------------------------------------ storing

    static double dist3(JsonObject c, JsonObject p) {
        double dx = J.d(c, "x") - J.d(p, "x"), dy = J.d(c, "y") - J.d(p, "y"), dz = J.d(c, "z") - J.d(p, "z");
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    static String chestName(JsonObject c) {
        String title = J.bool(c, "title") ? J.str(c, "title") : "Kiste";
        return title + " bei " + J.str(c, "x") + " " + J.str(c, "y") + " " + J.str(c, "z");
    }

    /** Preview for the confirm dialog: which chests storing would walk to, and what stays. */
    Map<String, Object> storePlan(String srv, boolean hotbar) {
        JsonObject p = player(srv);
        JsonObject inv = app.inventory == null ? new JsonObject() : app.inventory;
        JsonElement s = J.get(inv, "server");
        if (p == null || (s != null && !J.str(s).equals(srv))) {
            throw new IllegalArgumentException("Spiel nicht verbunden");
        }
        Object[] plan = planStore(srv, p, items(inv), hotbar, STORE_RADIUS);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stops = (List<Map<String, Object>>) plan[0];
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> st : stops) {
            out.add(J.map("x", st.get("x"), "y", st.get("y"), "z", st.get("z"), "name", st.get("name"), "dist", st.get("dist"),
                    "n", ((List<?>) st.get("put")).size()));
        }
        return J.map("stops", out, "left", plan[1], "radius", STORE_RADIUS);
    }

    /** Storing: each stack from the inventory into a chest nearby that already holds this item. */
    @SuppressWarnings("unchecked")
    Map<String, Object> startStore(String srv, boolean hotbar) {
        JsonObject p = player(srv);
        JsonObject inv = app.inventory == null ? new JsonObject() : app.inventory;
        JsonElement s = J.get(inv, "server");
        if (p == null || (s != null && !J.str(s).equals(srv))) {
            throw new IllegalArgumentException("Spiel nicht verbunden");
        }
        Object[] plan = planStore(srv, p, items(inv), hotbar, STORE_RADIUS);
        List<Map<String, Object>> stops = (List<Map<String, Object>>) plan[0];
        List<String> left = (List<String>) plan[1];
        synchronized (this) {
            St st = new St();
            st.mode = "store";
            st.phase = "store";
            st.ts = Stores.mono();
            st.stops = stops;
            state.put(srv, st);
        }
        int n = 0;
        for (Map<String, Object> stp : stops) {
            n += ((List<?>) stp.get("put")).size();
        }
        String leftTxt = String.join(", ", left.subList(0, Math.min(6, left.size())));
        log(srv, "Einlagern: " + n + " Stacks in " + stops.size() + " Kisten im Umkreis von 128 m"
                + (left.isEmpty() ? "" : ", ohne passende Kiste: " + leftTxt));
        if (stops.isEmpty()) {
            end(srv, "done", "nichts einzulagern: keine Kiste im Umkreis von 128 m hat diese Items"
                    + (left.isEmpty() ? "" : " (ohne passende Kiste: " + leftTxt + ")"));
        }
        return view(srv);
    }

    /** [stops, sorted names without a matching chest]. */
    private Object[] planStore(String srv, JsonObject p, List<JsonObject> items, boolean hotbar, Double radius) {
        List<Object[]> chests = new ArrayList<>();       // c, ids, free[], put
        for (JsonObject c : containers(srv)) {
            if (!J.str(c, "dim", "None").equals(J.str(p, "dim", "None")) || J.str(c, "block", "").endsWith("shulker_box")) {
                continue;
            }
            if (radius != null && dist3(c, p) > radius) {
                continue;                     // far away (other base, old chest): Baritone would wander off
            }
            Map<String, Long> ids = new HashMap<>();
            List<JsonElement> its = J.list(J.get(c, "items"));
            for (JsonElement ie : its) {
                JsonObject it = ie.getAsJsonObject();
                ids.merge(J.str(it, "id"), J.lng(it.get("count")), Long::sum);
            }
            int slots = J.bool(c, "slots") ? J.i(c, "slots") : 27;
            int free = slots - its.size();
            if (!ids.isEmpty() && free > 0) {
                chests.add(new Object[] {c, ids, new int[] {free}, new ArrayList<Map<String, Object>>()});
            }
        }
        TreeSet<String> left = new TreeSet<>();
        int lo = hotbar ? 0 : 9;
        List<JsonObject> sorted = new ArrayList<>(items);
        sorted.sort((a, b) -> Long.compare(J.get(a, "slot") == null ? 0 : J.lng(a.get("slot")), J.get(b, "slot") == null ? 0 : J.lng(b.get("slot"))));
        for (JsonObject it : sorted) {
            JsonElement se = J.get(it, "slot");
            if (se == null) {
                continue;
            }
            int slot = J.i(se);
            if (slot < lo || slot >= MAIN_SLOTS) {
                continue;
            }
            Object[] best = null;
            double bestScore = 0.0;
            String id = J.str(it, "id");
            for (Object[] ch : chests) {
                @SuppressWarnings("unchecked")
                long have = ((Map<String, Long>) ch[1]).getOrDefault(id, 0L);
                if (have == 0 || ((int[]) ch[2])[0] <= 0) {
                    continue;
                }
                JsonObject c = (JsonObject) ch[0];
                double dist = Math.hypot(J.d(c, "x") - J.d(p, "x"), J.d(c, "z") - J.d(p, "z"));
                double score = (1.0 + Math.log1p(have)) / (dist + 32.0);
                if (score > bestScore) {
                    best = ch;
                    bestScore = score;
                }
            }
            if (best == null) {
                left.add(shortId(id));
                continue;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> put = (List<Map<String, Object>>) best[3];
            put.add(J.map("slot", slot, "id", id));
            ((int[]) best[2])[0]--;
        }
        // nearest chest first, then the next nearest each time
        List<Object[]> todo = new ArrayList<>();
        for (Object[] ch : chests) {
            if (!((List<?>) ch[3]).isEmpty()) {
                todo.add(ch);
            }
        }
        List<Map<String, Object>> stops = new ArrayList<>();
        double cx = J.d(p, "x"), cz = J.d(p, "z");
        while (!todo.isEmpty()) {
            Object[] bestCh = null;
            double bd = Double.MAX_VALUE;
            for (Object[] ch : todo) {
                JsonObject c = (JsonObject) ch[0];
                double d = Math.hypot(J.d(c, "x") - cx, J.d(c, "z") - cz);
                if (d < bd) {
                    bd = d;
                    bestCh = ch;
                }
            }
            todo.remove(bestCh);
            JsonObject c = (JsonObject) bestCh[0];
            stops.add(J.map("x", c.get("x"), "y", c.get("y"), "z", c.get("z"), "put", bestCh[3],
                    "dist", (long) Math.rint(dist3(c, p)), "name", chestName(c)));
            cx = J.d(c, "x");
            cz = J.d(c, "z");
        }
        return new Object[] {stops, new ArrayList<>(left)};
    }

    private void stepStore(String srv, St st, double now) {
        if (st.cmd != null) {
            JsonObject r = result(st);
            if (r == null) {
                if (now - st.ts < FETCH_TIMEOUT) {
                    return;
                }
                cmd(srv, J.map("kind", "stop"));
                r = new JsonObject();
                r.addProperty("ok", false);
                r.addProperty("msg", "Einlagern dauert zu lange");
            }
            log(srv, J.str(r, "msg"));
            synchronized (this) {
                if (J.bool(r, "ok")) {
                    if (st.phase.equals("return")) {
                        st.returned++;
                    } else {
                        st.fetched++;
                    }
                }
                st.cmd = null;
            }
        }
        if (st.stops.isEmpty()) {
            if (st.phase.equals("return")) {
                int n = st.returned;
                end(srv, "done", "fertig!" + (n != 0 ? " Reste in " + n + " Kisten zurückgebracht" : ""));
                return;
            }
            end(srv, "done", "eingelagert in " + st.fetched + " Kisten");
            return;
        }
        Map<String, Object> stop = st.stops.remove(0);
        String cid = cmd(srv, J.map("kind", "store", "x", stop.get("x"), "y", stop.get("y"), "z", stop.get("z"), "put", stop.get("put")));
        synchronized (this) {
            st.cmd = cid;
            st.ts = Stores.mono();
        }
        log(srv, "bringt " + ((List<?>) stop.get("put")).size() + " Stacks zu " + stop.get("name") + " (" + stop.get("dist") + " m vom Start)");
    }

    /** Finished building: leftover materials back into the chests (if enabled). */
    @SuppressWarnings("unchecked")
    private void finish(String srv, St st, JsonObject p, String msg) {
        if (!setting("leftovers") || p == null) {
            end(srv, "done", msg);
            return;
        }
        Set<String> mats = new HashSet<>();
        try {
            for (List<Object> it : (List<List<Object>>) app.placements.materials(st.pid).get("items")) {
                mats.add(full((String) it.get(0)));
            }
        } catch (Exception e) {
            mats.clear();
        }
        JsonObject inv = app.inventory == null ? new JsonObject() : app.inventory;
        List<JsonObject> items = new ArrayList<>();
        for (JsonObject it : items(inv)) {
            if (mats.contains(J.str(it, "id", "None"))) {
                items.add(it);
            }
        }
        if (items.isEmpty()) {
            end(srv, "done", msg);
            return;
        }
        Object[] plan = planStore(srv, p, items, true, null);
        List<Map<String, Object>> stops = (List<Map<String, Object>>) plan[0];
        List<String> left = (List<String>) plan[1];
        if (stops.isEmpty()) {
            end(srv, "done", msg + (left.isEmpty() ? "" : " Reste ohne passende Kiste: " + String.join(", ", left.subList(0, Math.min(6, left.size())))));
            return;
        }
        int n = 0;
        for (Map<String, Object> stp : stops) {
            n += ((List<?>) stp.get("put")).size();
        }
        synchronized (this) {
            st.phase = "return";
            st.stops = stops;
            st.cmd = null;
            st.returned = 0;
            st.ts = Stores.mono();
        }
        log(srv, msg + " Bringt " + n + " Reste-Stacks in " + stops.size() + " Kisten zurück"
                + (left.isEmpty() ? "" : " (ohne passende Kiste: " + String.join(", ", left.subList(0, Math.min(4, left.size()))) + ")"));
    }

    // ------------------------------------------------------------------ farming

    Map<String, Object> chestByKey(String srv, String key) {
        for (JsonObject c : containers(srv)) {
            if (key.equals(J.str(c, "key", null))) {
                return J.map("x", c.get("x"), "y", c.get("y"), "z", c.get("z"), "name", chestName(c), "own", false);
            }
        }
        throw new IllegalArgumentException("Kiste unbekannt");
    }

    static JsonObject need(JsonObject req, String k) {
        if (!req.has(k)) {
            throw new KeyError(k);
        }
        JsonObject o = J.obj(req.get(k));
        if (o == null) {
            throw new IllegalArgumentException("'" + k + "' ist kein Objekt");
        }
        return o;
    }

    Map<String, Object> startFarm(String srv, JsonObject req) {
        JsonObject p = player(srv);
        if (p == null) {
            throw new IllegalArgumentException("Spiel nicht verbunden");
        }
        Farm.Area area = Farm.normArea(need(req, "area"));
        String dimKey = J.bool(req, "dim") ? J.str(req, "dim") : J.str(p, "dim", "None");
        Recording.Dimension dim = app.rec.dimension(srv, dimKey);
        if (dim == null) {
            throw new IllegalArgumentException("für diese Dimension gibt es keine Aufnahme");
        }
        Map<String, Integer> targets = new LinkedHashMap<>();
        JsonObject t = J.obj(req, "targets");
        if (t != null) {
            for (Map.Entry<String, JsonElement> e : t.entrySet()) {
                int v = J.i(e.getValue());
                if (v > 0) {
                    targets.put(full(e.getKey()), Math.max(1, v));
                }
            }
        }
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("keine Blöcke gewählt");
        }
        Map<String, Long> have = Farm.count(app.rec, dim, area, targets.keySet());
        Map<String, Object> chest = J.bool(req, "chest") ? chestByKey(srv, J.str(req, "chest")) : null;
        synchronized (this) {
            St st = new St();
            st.mode = "farm";
            st.phase = "fplan";
            st.ts = Stores.mono();
            st.area = area;
            st.dim = dimKey;
            st.targets = targets;
            st.start = have;
            st.mined = new LinkedHashMap<>();
            for (String k : targets.keySet()) {
                st.mined.put(k, 0L);
            }
            st.chest = chest;
            st.place = J.bool(req, "placeChest", true);
            state.put(srv, st);
        }
        List<String> parts = new ArrayList<>();
        List<String> lack = new ArrayList<>();
        for (Map.Entry<String, Integer> e : targets.entrySet()) {
            parts.add(e.getValue() + "× " + shortId(e.getKey()));
            if (have.getOrDefault(e.getKey(), 0L) == 0) {
                lack.add(shortId(e.getKey()));
            }
        }
        log(srv, "Farmen gestartet: " + String.join(", ", parts));
        if (!lack.isEmpty()) {
            log(srv, "Im Bereich nicht aufgezeichnet: " + String.join(", ", lack));
        }
        return view(srv);
    }

    static List<Integer> key(int[] p) {
        return List.of(p[0], p[1], p[2]);
    }

    private void stepFarm(String srv, St st, JsonObject p, double now) {
        if (!J.str(p, "dim", "None").equals(st.dim)) {
            info(st, "du bist in einer anderen Dimension als der Farm-Bereich");
            return;
        }
        Recording.Dimension dim = app.rec.dimension(srv, st.dim);
        if (st.phase.equals("mine")) {
            JsonObject r = result(st);
            if (r == null) {
                if (now - st.ts > MINE_TIMEOUT + 30) {
                    cmd(srv, J.map("kind", "stop"));
                    log(srv, "Abbauen hängt, nimmt die nächste Stelle");
                    if (st.batch != null) {
                        for (int[] b : st.batch) {
                            st.skip.add(key(b));
                        }
                    }
                    phase(st, "fplan");
                    st.notBefore = now + SETTLE;
                }
                return;
            }
            phase(st, "fplan");
            st.notBefore = now + SETTLE + 1.0;
            st.cmd = null;
            st.judge = true;
            return;
        }
        if (st.phase.equals("deposit")) {
            JsonObject r = result(st);
            if (r == null) {
                if (now - st.ts > 240.0) {
                    cmd(srv, J.map("kind", "stop"));
                    end(srv, "error", "Einlagern hängt");
                }
                return;
            }
            String msg = J.str(r, "msg");
            boolean ok = J.bool(r, "ok");
            log(srv, msg);
            Matcher m = CHEST_AT.matcher(msg);
            if (msg.startsWith("Kiste:") && m.find() && ok) {
                int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)), z = Integer.parseInt(m.group(3));
                st.chest = J.map("x", x, "y", y, "z", z, "name", "neue Kiste " + x + " " + y + " " + z, "own", true);
            }
            if (!ok || msg.contains("Kiste voll")) {
                if (st.chest != null && (msg.contains("Kiste voll") || Boolean.TRUE.equals(st.chest.get("auto")))) {
                    st.full.add(List.of(num(st.chest.get("x")), num(st.chest.get("y")), num(st.chest.get("z"))));
                    st.chest = null;           // another chest (or place a new one) next time
                }
                if (!ok && !st.place && !st.autoChest) {
                    end(srv, "error", "Einlagern ging nicht: " + msg);
                    return;
                }
                st.depositFails++;
                if (st.depositFails >= 3) {
                    end(srv, "error", "Einlagern klappt nicht: " + msg);
                    return;
                }
            } else {
                st.depositFails = 0;
                st.deposits++;
            }
            phase(st, "fplan");
            st.notBefore = now + SETTLE;
            return;
        }
        if (now < st.notBefore) {
            return;
        }
        // fplan: count progress
        Map<String, Long> left = Farm.count(app.rec, dim, st.area, st.targets.keySet());
        Map<String, Long> mined = new LinkedHashMap<>();
        Map<String, Long> open = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : st.targets.entrySet()) {
            String k = e.getKey();
            long mk = Math.max(0, st.start.getOrDefault(k, 0L) - left.getOrDefault(k, 0L));
            mined.put(k, mk);
            if (e.getValue() - mk > 0 && left.getOrDefault(k, 0L) > 0) {
                open.put(k, e.getValue() - mk);
            }
        }
        if (st.judge) {
            st.judge = false;
            long gained = sum(mined) - sum(st.mined);
            if (gained <= 0) {
                if (st.batch != null) {
                    for (int[] b : st.batch) {
                        st.skip.add(key(b));
                    }
                }
                st.fails++;
                if (st.fails >= 4) {
                    end(srv, "error", "kommt nicht weiter (4× nichts abgebaut)");
                    return;
                }
                log(srv, "an dieser Stelle ging nichts, nimmt eine andere");
            } else {
                st.fails = 0;
            }
        }
        synchronized (this) {
            st.mined = mined;
        }
        if (open.isEmpty()) {
            List<String> done = new ArrayList<>(), shortOf = new ArrayList<>();
            for (Map.Entry<String, Integer> e : st.targets.entrySet()) {
                done.add(mined.get(e.getKey()) + "× " + shortId(e.getKey()));
                if (mined.get(e.getKey()) < e.getValue()) {
                    shortOf.add(shortId(e.getKey()));
                }
            }
            end(srv, "done", "fertig: " + String.join(", ", done)
                    + (shortOf.isEmpty() ? "" : " (im Bereich gibt es nicht mehr: " + String.join(", ", shortOf) + ")"));
            return;
        }
        Object[] iv = inventory(srv, null);
        if (iv == null) {
            info(st, "wartet auf dein Inventar …");
            return;
        }
        int free = (Integer) iv[1];
        if (free < FREE_MIN && (free <= 0 || canDeposit(srv, st, p))) {
            farmDeposit(srv, st, now, p, free);
            return;
        }
        List<int[]> pts = Farm.positions(app.rec, dim, st.area, open.keySet(),
                new double[] {J.d(p, "x"), J.d(p, "y"), J.d(p, "z")}, MINE_BATCH, st.skip);
        if (pts.isEmpty()) {
            end(srv, "error", "keine erreichbaren Blöcke mehr im Bereich");
            return;
        }
        String cid = cmd(srv, J.map("kind", "breakboxes", "boxes", Farm.boxes(pts), "label", "Farmen", "pickup", true,
                "timeout", (int) MINE_TIMEOUT));
        phase(st, "mine");
        st.cmd = cid;
        st.batch = pts;
        List<String> todo = new ArrayList<>();
        for (Map.Entry<String, Long> e : open.entrySet()) {
            todo.add(e.getValue() + "× " + shortId(e.getKey()));
        }
        log(srv, "baut " + pts.size() + " Blöcke ab bei " + pts.get(0)[0] + " " + pts.get(0)[1] + " " + pts.get(0)[2]
                + " (noch " + String.join(", ", todo) + ")");
    }

    static int num(Object o) {
        return o instanceof JsonElement e ? J.i(e) : ((Number) o).intValue();
    }

    /** Is there somewhere to put the drops? Otherwise mining goes on until the inventory is really full. */
    private boolean canDeposit(String srv, St st, JsonObject p) {
        if (st.chest != null || nearChest(srv, st, p) != null) {
            return true;
        }
        JsonObject inv = app.inventory == null ? new JsonObject() : app.inventory;
        if (!st.place) {
            return false;
        }
        for (JsonObject it : items(inv)) {
            if ("minecraft:chest".equals(J.str(it, "id", null))) {
                return true;
            }
        }
        return false;
    }

    /** The nearest remembered chest with free slots (same dimension, within STORE_RADIUS). */
    private JsonObject nearChest(String srv, St st, JsonObject p) {
        JsonObject best = null;
        double bestD = STORE_RADIUS;
        for (JsonObject c : containers(srv)) {
            String block = J.str(c, "block", "");
            if (!J.str(c, "dim", "None").equals(st.dim) || block.endsWith("shulker_box") || block.endsWith("ender_chest")) {
                continue;
            }
            int slots = J.bool(c, "slots") ? J.i(c, "slots") : 27;
            if (st.full.contains(List.of(J.i(c, "x"), J.i(c, "y"), J.i(c, "z"))) || slots - J.list(J.get(c, "items")).size() <= 0) {
                continue;
            }
            double d = dist3(c, p);
            if (d < bestD) {
                best = c;
                bestD = d;
            }
        }
        return best;
    }

    private void farmDeposit(String srv, St st, double now, JsonObject p, int free) {
        JsonObject inv = app.inventory == null ? new JsonObject() : app.inventory;
        int used = MAIN_SLOTS - free;
        List<Map<String, Object>> put = new ArrayList<>();
        boolean hasChest = false;
        for (JsonObject it : items(inv)) {
            String id = J.str(it, "id", "None");
            hasChest |= id.equals("minecraft:chest");
            JsonElement se = J.get(it, "slot");
            if (se != null && J.i(se) >= 9 && J.i(se) < MAIN_SLOTS && !KEEP.matcher(id).find()) {
                put.add(J.map("slot", J.i(se), "id", id));
            }
        }
        if (put.isEmpty()) {
            end(srv, "error", "Inventar voll (" + used + "/" + MAIN_SLOTS + " Plätze belegt), aber nichts zum Einlagern (nur Werkzeug/Essen/Hotbar)");
            return;
        }
        Map<String, Object> ch = st.chest;
        if (ch == null && p != null) {
            JsonObject c = nearChest(srv, st, p);
            if (c != null) {
                ch = J.map("x", c.get("x"), "y", c.get("y"), "z", c.get("z"), "own", false, "auto", true,
                        "name", "Kiste bei " + J.str(c, "x") + " " + J.str(c, "y") + " " + J.str(c, "z"));
                st.chest = ch;
                st.autoChest = true;
            }
        }
        if (ch != null) {
            String cid = cmd(srv, J.map("kind", "store", "x", ch.get("x"), "y", ch.get("y"), "z", ch.get("z"), "put", put));
            phase(st, "deposit");
            st.cmd = cid;
            String dist = "";
            if (p != null) {
                double dx = num(ch.get("x")) - J.d(p, "x"), dy = num(ch.get("y")) - J.d(p, "y"), dz = num(ch.get("z")) - J.d(p, "z");
                dist = " (" + String.format(Locale.ROOT, "%.0f", Math.sqrt(dx * dx + dy * dy + dz * dz)) + " m)";
            }
            log(srv, "Inventar fast voll (" + used + "/" + MAIN_SLOTS + "): bringt " + put.size() + " Stacks zu " + ch.get("name") + dist);
            return;
        }
        if (!(st.place && hasChest)) {
            end(srv, "error", "Inventar voll (" + used + "/" + MAIN_SLOTS + " Plätze belegt) und keine Kiste in der Nähe "
                    + "oder im Inventar (Kiste mitnehmen oder eine wählen)");
            return;
        }
        List<Object> slots = new ArrayList<>();
        for (Map<String, Object> x : put) {
            slots.add(x.get("slot"));
        }
        String cid = cmd(srv, J.map("kind", "chestdump", "slots", slots));
        phase(st, "deposit");
        st.cmd = cid;
        log(srv, "Inventar fast voll (" + used + "/" + MAIN_SLOTS + "): stellt eine Kiste auf und packt " + put.size() + " Stacks hinein");
    }

    // ------------------------------------------------------------------ flattening

    Map<String, Object> startFlatten(String srv, JsonObject req) {
        JsonObject p = player(srv);
        if (p == null) {
            throw new IllegalArgumentException("Spiel nicht verbunden");
        }
        Farm.Area area = Farm.normArea(need(req, "area"));
        String dimKey = J.bool(req, "dim") ? J.str(req, "dim") : J.str(p, "dim", "None");
        Recording.Dimension dim = app.rec.dimension(srv, dimKey);
        if (dim == null) {
            throw new IllegalArgumentException("für diese Dimension gibt es keine Aufnahme");
        }
        JsonObject fl = J.obj(req, "flatten");
        if (fl == null || fl.isEmpty()) {
            fl = new JsonObject();
        }
        String kind = J.bool(fl, "kind") ? J.str(fl, "kind") : "flat";
        if (!Set.of("flat", "clear", "fill", "light").contains(kind)) {
            throw new IllegalArgumentException("unbekannte Automation");
        }
        int level;
        boolean clear, fill;
        String blockRaw = J.bool(fl, "block") ? J.str(fl, "block") : "";
        switch (kind) {
            case "clear" -> {
                level = area.y0() - 1;
                clear = true;
                fill = false;
            }
            case "fill" -> {
                level = area.y1();
                clear = false;
                fill = true;
            }
            case "light" -> {
                // torches onto the spawn spots, until nothing spawns any more
                level = area.y1();
                clear = false;
                fill = true;
                if (blockRaw.isEmpty()) {
                    blockRaw = "minecraft:torch";
                }
            }
            default -> {
                level = J.i(fl, "level");
                clear = J.bool(fl, "clear", true);
                fill = J.bool(fl, "fill", true);
            }
        }
        String block = fill ? full(blockRaw.strip()) : null;
        if (fill && !BLOCK_ID.matcher(block).matches()) {
            throw new IllegalArgumentException("Block zum Auffüllen fehlt");
        }
        if (!clear && !fill) {
            throw new IllegalArgumentException("nichts zu tun (weder abtragen noch auffüllen)");
        }
        Map<String, Object> chest = J.bool(req, "chest") ? chestByKey(srv, J.str(req, "chest")) : null;
        synchronized (this) {
            St st = new St();
            st.mode = "flatten";
            st.phase = "fplan";
            st.ts = Stores.mono();
            st.area = area;
            st.dim = dimKey;
            st.chest = chest;
            st.place = J.bool(req, "placeChest", true);
            st.flatten = J.map("kind", kind, "level", level, "clear", clear, "fill", fill, "block", block,
                    "stage", clear ? "clear" : "fill", "clearLeft", null, "fillLeft", null);
            state.put(srv, st);
        }
        String y = "Y " + area.y0() + " bis " + area.y1();
        switch (kind) {
            case "clear" -> log(srv, "Abbauen gestartet: " + y);
            case "fill" -> log(srv, "Füllen gestartet: mit " + shortId(block) + ", " + y);
            case "light" -> log(srv, "Ausleuchten gestartet: " + shortId(block) + " auf die Spawnstellen, " + y);
            default -> {
                List<String> what = new ArrayList<>();
                if (clear) {
                    what.add("abtragen");
                }
                if (fill) {
                    what.add("mit " + shortId(block) + " auffüllen");
                }
                log(srv, "Ebnen auf Y " + level + " gestartet: " + String.join(" und ", what));
            }
        }
        return view(srv);
    }

    /** After a job: did anything progress? False after 4 failures in a row. */
    private boolean judge(String srv, St st, long left) {
        boolean j = st.judge;
        st.judge = false;
        if (j && st.last != null) {
            if (left >= st.last) {
                if (st.batch != null) {
                    for (int[] b : st.batch) {
                        st.skip.add(key(b));
                    }
                }
                st.fails++;
                if (st.fails >= 4) {
                    return false;
                }
                log(srv, "an dieser Stelle ging nichts, nimmt eine andere");
            } else {
                st.fails = 0;
            }
        }
        st.last = left;
        return true;
    }

    private void stepFlatten(String srv, St st, JsonObject p, double now) {
        if (st.phase.equals("mine") || st.phase.equals("deposit")) {
            stepFarm(srv, st, p, now);        // same job tracking as for farming
            return;
        }
        if (!J.str(p, "dim", "None").equals(st.dim)) {
            info(st, "du bist in einer anderen Dimension als der Bereich");
            return;
        }
        if (now < st.notBefore) {
            return;
        }
        Recording.Dimension dim = app.rec.dimension(srv, st.dim);
        Map<String, Object> fl = st.flatten;
        double[] near = {J.d(p, "x"), J.d(p, "y"), J.d(p, "z")};
        int level = (Integer) fl.get("level");
        String flKind = (String) fl.get("kind");
        if ("clear".equals(fl.get("stage"))) {
            Farm.Area above = st.area.withY(level + 1, st.area.y1());
            long left = above.y0() <= above.y1() ? Farm.count(app.rec, dim, above, Set.of(Farm.ANY)).get(Farm.ANY) : 0;
            boolean ok = judge(srv, st, left);
            synchronized (this) {
                fl.put("clearLeft", left);
                Object ct = fl.get("clearTotal");
                fl.put("clearTotal", Math.max(ct == null ? 0L : ((Number) ct).longValue(), left));   // for the progress bar
            }
            List<int[]> pts = left != 0 && ok ? Farm.positions(app.rec, dim, above, Set.of(Farm.ANY), near, MINE_BATCH, st.skip) : List.of();
            if (pts.isEmpty()) {
                log(srv, left == 0 ? "abgetragen" : "abgetragen, " + left + " Blöcke kommen nicht weg");
                if (!(Boolean) fl.get("fill")) {
                    if ("clear".equals(flKind)) {
                        end(srv, "done", "fertig: Bereich abgebaut");
                    } else {
                        end(srv, "done", "fertig: Bereich über Y " + level + " abgetragen");
                    }
                    return;
                }
                synchronized (this) {
                    fl.put("stage", "fill");
                    st.last = null;
                    st.fails = 0;
                }
                return;
            }
            Object[] iv = inventory(srv, null);
            if (iv == null) {
                info(st, "wartet auf dein Inventar …");
                return;
            }
            int free = (Integer) iv[1];
            if (free < FREE_MIN && (free <= 0 || canDeposit(srv, st, p))) {
                farmDeposit(srv, st, now, p, free);
                return;
            }
            String cid = cmd(srv, J.map("kind", "breakboxes", "boxes", Farm.boxes(pts), "label", "clear".equals(flKind) ? "Abbauen" : "Ebnen",
                    "pickup", true, "timeout", (int) MINE_TIMEOUT));
            phase(st, "mine");
            st.cmd = cid;
            st.batch = pts;
            log(srv, "trägt " + pts.size() + " Blöcke ab bei " + pts.get(0)[0] + " " + pts.get(0)[1] + " " + pts.get(0)[2] + " (noch " + left + ")");
            return;
        }
        // filling
        boolean light = "light".equals(flKind);
        List<int[]> every = light ? Spawn.torchPlan(app.rec, dim, st.area, near).torches()
                : Farm.holes(app.rec, dim, st.area, level, near, null, "fill".equals(flKind));
        synchronized (this) {
            fl.put("fillLeft", every.size());
            Object ft = fl.get("fillTotal");
            fl.put("fillTotal", Math.max(ft == null ? 0 : ((Number) ft).intValue(), every.size()));
        }
        if (every.isEmpty()) {
            if (light) {
                end(srv, "done", "fertig: im Bereich spawnt nichts mehr");
            } else if ("fill".equals(flKind)) {
                end(srv, "done", "fertig: Bereich gefüllt");
            } else {
                end(srv, "done", "fertig: Bereich ist auf Y " + level + " eben");
            }
            return;
        }
        if (!judge(srv, st, every.size())) {
            end(srv, "error", "kommt beim " + (light ? "Ausleuchten" : "Auffüllen") + " nicht weiter, "
                    + every.size() + " " + (light ? "Fackeln" : "Löcher") + " offen");
            return;
        }
        List<int[]> holes = new ArrayList<>();
        for (int[] h : every) {
            if (!st.skip.contains(key(h))) {
                holes.add(h);
            }
        }
        if (holes.isEmpty()) {
            end(srv, "error", every.size() + " " + (light ? "Stellen lassen sich nicht ausleuchten" : "Löcher lassen sich nicht füllen") + " (nicht erreichbar?)");
            return;
        }
        @SuppressWarnings("unchecked")
        Object[] iv = inventory(srv, null);
        if (iv == null) {
            info(st, "wartet auf dein Inventar …");
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Long> inv = (Map<String, Long>) iv[0];
        String block = (String) fl.get("block");
        long have = inv.getOrDefault(block, 0L);
        String name = shortId(block);
        if (have <= 0) {
            end(srv, "error", "kein " + name + " mehr im Inventar, es fehlen noch " + holes.size() + " Blöcke");
            return;
        }
        List<int[]> batch = new ArrayList<>(holes.subList(0, (int) Math.min(holes.size(), Math.min(have, light ? LIGHT_BATCH : FILL_BATCH))));
        String label = light ? "Ausleuchten" : "fill".equals(flKind) ? "Füllen" : "Auffüllen";
        String cid = cmd(srv, J.map("kind", "breakboxes", "boxes", Farm.boxes(batch), "label", label, "pickup", false,
                "fill", block, "timeout", (int) MINE_TIMEOUT));
        phase(st, "mine");
        st.cmd = cid;
        st.batch = batch;
        if (light) {
            log(srv, "setzt " + batch.size() + " " + name + " (noch etwa " + holes.size() + " nötig, " + have + " dabei)");
        } else {
            log(srv, "füllt " + batch.size() + " Löcher mit " + name + " (noch " + holes.size() + ", " + have + " dabei)");
        }
    }
}
