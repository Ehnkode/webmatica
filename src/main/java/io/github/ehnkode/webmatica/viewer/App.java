package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.LruCache;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Region;
import io.github.ehnkode.webmatica.viewer.core.Tiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;

/** State of a running viewer: the recording, caches, the player and every feature's store. */
public final class App {
    /** Seconds without a position from the mod: player shown as offline. */
    static final double PLAYER_STALE = 5.0;

    public final Recording rec;
    public final Tiles tiles;
    public final Path cacheDir;
    public final Path recRoot;
    public final String version;
    private final Map<List<String>, Tiles.Past> past = new HashMap<>();

    // player (written by /api/position, read by the page)
    volatile JsonObject player;
    volatile double playerTs;
    long playerSeq;
    final Object playerCond = new Object();
    volatile byte[] skinFace, skinFull;
    volatile int skinVersion;
    volatile String skinFetchName;
    int streams;                       // open /api/player/stream connections = visible viewer tabs
    volatile JsonObject inventory;     // last player inventory from the mod

    final Object wpLock = new Object();
    public final LruCache<Object, Object> meshes = new LruCache<>(8192);

    final Stores.Containers containers;
    final Stores.Commands commands = new Stores.Commands();
    final History history;
    final Stores.Control control;
    final Stores.Radar radar;
    final Stores.LidLog lids;
    final Placements placements;
    final Editor editor;
    final AutoBuild auto;
    final Map<String, Object[]> worldChests = new ConcurrentHashMap<>();
    /** server -> last /api/control poll of its mod */
    final Map<String, Double> polling = new ConcurrentHashMap<>();
    volatile JsonObject lmStatus = new JsonObject();   // last feedback from the mod on the Litematica bridge
    volatile double lmStatusTs;

    final Path trailPath;
    final JsonObject trail;
    double trailSaved = Stores.mono();

    /** Folder with blocks.json/blocks.png/items.*, set by the server. */
    volatile Path assets;

    /** Set by the server: stops it (POST /api/quit). */
    volatile Runnable shutdown = () -> { };

    public App(Path recording, Path cacheDir, String version) {
        this.recRoot = recording;
        this.rec = new Recording(recording);
        this.tiles = new Tiles(rec, cacheDir);
        this.cacheDir = cacheDir;
        this.version = version;
        try {
            Files.createDirectories(recording.resolve(".viewer"));
        } catch (IOException ignored) {
            // the stores report write errors themselves
        }
        this.containers = new Stores.Containers(recording);
        this.history = new History(recording);
        this.control = new Stores.Control(recording);
        this.radar = new Stores.Radar(recording);
        this.lids = new Stores.LidLog(recording);
        this.placements = new Placements(recording);
        this.editor = new Editor(placements, rec, () -> assets);
        this.auto = new AutoBuild(this, recording);
        this.trailPath = recording.resolve(".viewer").resolve("trail.json");
        JsonElement t = J.load(trailPath);
        this.trail = t != null && t.isJsonObject() ? t.getAsJsonObject() : new JsonObject();
    }

    /** Python's str(exception) for messages shown in the page. */
    static String pyErr(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ timelapse / Litematica

    /** Tiles for the timelapse: map at the start of {@code date}. */
    public synchronized Tiles.Past past(String server, String dimKey, String date) {
        List<String> key = List.of(server, dimKey, date);
        Tiles.Past pt = past.get(key);
        if (pt == null) {
            if (past.size() > 64) {
                past.clear();
            }
            pt = new Tiles.Past(rec, cacheDir.resolve("_past").resolve(date), () -> history.index(server, dimKey, date));
            past.put(key, pt);
        }
        return pt;
    }

    /** Adds to each placement whether it is currently in game in Litematica. */
    JsonArray withIngame(JsonArray pls) {
        boolean fresh = Stores.mono() - lmStatusTs < 6.0;
        JsonObject st = fresh ? lmStatus : new JsonObject();
        List<String> have = new ArrayList<>();
        for (JsonElement e : J.list(st.get("ids"))) {
            have.add(J.str(e));
        }
        JsonObject errs = J.obj(st, "errors");
        for (JsonElement pe : pls) {
            JsonObject pl = pe.getAsJsonObject();
            String id = J.str(pl, "id");
            String state;
            if (!J.bool(pl, "ingame")) {
                state = null;
            } else if (!fresh) {
                state = "offline";
            } else if (!J.bool(st, "available", false)) {
                state = "kein Litematica";
            } else if (errs != null && errs.has(id)) {
                String m = J.str(errs.get(id));
                state = "Fehler: " + m.substring(0, Math.min(120, m.length()));
            } else if (have.contains(id)) {
                state = "ok";
            } else {
                state = J.bool(st, "dim") && !J.str(st, "dim").equals(J.str(pl, "dim", "None")) ? "andere Dimension" : "wartet";
            }
            pl.add("ingameState", state == null ? J.nul() : new com.google.gson.JsonPrimitive(state));
        }
        return pls;
    }

    // ------------------------------------------------------------------ trail

    synchronized void addTrail(JsonObject pl) {
        String key = J.str(pl, "server") + "/" + J.str(pl, "dim");
        JsonArray pts = trail.has(key) && trail.get(key).isJsonArray() ? trail.getAsJsonArray(key) : null;
        if (pts == null) {
            pts = new JsonArray();
            trail.add(key, pts);
        }
        double x = J.round(J.d(pl, "x"), 1), y = J.round(J.d(pl, "y"), 1), z = J.round(J.d(pl, "z"), 1);
        long now = (long) Stores.now();
        if (!pts.isEmpty()) {
            JsonArray last = pts.get(pts.size() - 1).getAsJsonArray();
            double lx = last.get(0).getAsDouble(), lz = last.get(2).getAsDouble();
            long lt = last.get(3).getAsLong();
            if ((x - lx) * (x - lx) + (z - lz) * (z - lz) < 9 && now - lt < 30) {
                return;
            }
        }
        JsonArray p = new JsonArray();
        p.add(x);
        p.add(y);
        p.add(z);
        p.add(now);
        pts.add(p);
        while (pts.size() > 20000) {
            pts.remove(0);
        }
        if (Stores.mono() - trailSaved > 30) {
            saveTrail();
        }
    }

    synchronized void saveTrail() {
        trailSaved = Stores.mono();
        J.saveQuiet(trailPath, trail);
    }

    // ------------------------------------------------------------------ far view

    /**
     * Height field 129x129 of a far-view tile as int16 (gzip), -32768 = no data, plus its stamp.
     * Level 0 is one region (one point every 4 blocks); each further level combines 4 x 4 tiles of
     * the level before. Row/column 128 comes from the neighbor tiles so no seam remains.
     */
    Object[] lod(Recording.Dimension dim, int rx, int rz, int level) throws IOException {
        Tiles.Layer layer = Tiles.Layer.parse(layersFor(dim.key).get(0).get("id").toString());
        if (tiles.tile(dim, layer, -2 * level, rx, rz, false) == null) {
            return null;
        }
        Object[] res = lodGrid(dim, layer, level, rx, rz, false);
        if (res == null) {
            return null;
        }
        short[] g = (short[]) res[0];
        short[] out = new short[129 * 129];
        for (int z = 0; z < 129; z++) {
            for (int x = 0; x < 129; x++) {
                out[z * 129 + x] = g[Math.min(z, 127) * 128 + Math.min(x, 127)];
            }
        }
        Object[] e = lodGrid(dim, layer, level, rx + 1, rz, true);
        if (e != null) {
            short[] n = (short[]) e[0];
            for (int z = 0; z < 128; z++) {
                out[z * 129 + 128] = n[z * 128];
            }
        }
        Object[] s = lodGrid(dim, layer, level, rx, rz + 1, true);
        if (s != null) {
            short[] n = (short[]) s[0];
            for (int x = 0; x < 128; x++) {
                out[128 * 129 + x] = n[x];
            }
        }
        Object[] se = lodGrid(dim, layer, level, rx + 1, rz + 1, true);
        if (se != null) {
            out[128 * 129 + 128] = ((short[]) se[0])[0];
        }
        ByteBuffer bb = ByteBuffer.allocate(out.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : out) {
            bb.putShort(v);
        }
        return new Object[] {gzip(bb.array(), 5), res[1]};
    }

    static byte[] gzip(byte[] data, int level) {
        ByteArrayOutputStream bo = new ByteArrayOutputStream(data.length / 3 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(bo, 65536) {
            {
                def.setLevel(level);
            }
        }) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bo.toByteArray();
    }

    private static short[] maxPool(short[] src, int n, int f) {
        int m = n / f;
        short[] out = new short[m * m];
        for (int z = 0; z < m; z++) {
            for (int x = 0; x < m; x++) {
                short best = Short.MIN_VALUE;
                for (int j = 0; j < f; j++) {
                    for (int i = 0; i < f; i++) {
                        short v = src[(z * f + j) * n + x * f + i];
                        if (v > best) {
                            best = v;
                        }
                    }
                }
                out[z * m + x] = best;
            }
        }
        return out;
    }

    /** [128x128 short[], stamp] or null. Level 0 from the region tile's grid, higher levels cached on disk. */
    private Object[] lodGrid(Recording.Dimension dim, Tiles.Layer layer, int level, int x, int z, boolean cachedOnly) {
        Path d = tiles.dir(dim, layer, -2 * level);
        if (level == 0) {
            Path grid = d.resolve(x + "_" + z + ".grid");
            long mtime;
            try {
                mtime = Files.getLastModifiedTime(grid).toMillis();
            } catch (IOException e) {
                return null;
            }
            String stamp = Long.toString(mtime);
            List<Object> key = List.of("lodg", dim.id(), x, z, stamp);
            short[] hit = (short[]) meshes.get(key);
            if (hit == null) {
                int[][] hs = tiles.heights(grid);
                if (hs == null) {
                    return null;
                }
                short[] full = new short[512 * 512];
                for (int i = 0; i < full.length; i++) {
                    full[i] = (short) Math.max(-32768, Math.min(32767, hs[0][i]));
                }
                hit = maxPool(full, 512, 4);
                meshes.put(key, hit);
            }
            return new Object[] {hit, stamp};
        }
        Path path = d.resolve(x + "_" + z + ".lod");
        if (cachedOnly) {
            return readLod(path);
        }
        List<Object[]> kids = new ArrayList<>();
        long newest = -1;
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                Object[] k = lodGrid(dim, layer, level - 1, x * 4 + i, z * 4 + j, false);
                kids.add(k);
                if (k != null) {
                    newest = Math.max(newest, Long.parseLong((String) k[1]));
                }
            }
        }
        if (newest < 0) {
            return null;
        }
        try {
            if (Files.getLastModifiedTime(path).toMillis() >= newest) {
                Object[] r = readLod(path);
                if (r != null) {
                    return r;
                }
            }
        } catch (IOException ignored) {
            // build it below
        }
        short[] g = new short[128 * 128];
        java.util.Arrays.fill(g, Short.MIN_VALUE);
        for (int j = 0; j < 4; j++) {
            for (int i = 0; i < 4; i++) {
                Object[] k = kids.get(j * 4 + i);
                if (k == null) {
                    continue;
                }
                short[] p = maxPool((short[]) k[0], 128, 4);
                for (int zz = 0; zz < 32; zz++) {
                    System.arraycopy(p, zz * 32, g, (j * 32 + zz) * 128 + i * 32, 32);
                }
            }
        }
        ByteBuffer bb = ByteBuffer.allocate(g.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (short v : g) {
            bb.putShort(v);
        }
        try {
            io.github.ehnkode.webmatica.viewer.core.Png.writeAtomic(path, bb.array());
            return new Object[] {g, Long.toString(Files.getLastModifiedTime(path).toMillis())};
        } catch (IOException e) {
            return new Object[] {g, Long.toString(newest)};
        }
    }

    private static Object[] readLod(Path path) {
        try {
            byte[] b = Files.readAllBytes(path);
            if (b.length != 128 * 128 * 2) {
                return null;
            }
            ByteBuffer bb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            short[] g = new short[128 * 128];
            for (int i = 0; i < g.length; i++) {
                g[i] = bb.getShort();
            }
            return new Object[] {g, Long.toString(Files.getLastModifiedTime(path).toMillis())};
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 3D

    /** Changes as soon as the chunk or one of its 4 neighbors was rewritten. */
    String chunkStamp(Recording.Dimension dim, int cx, int cz) {
        StringBuilder sb = new StringBuilder();
        int[][] off = {{0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int k = 0; k < off.length; k++) {
            int x = cx + off[k][0], z = cz + off[k][1];
            Region r = dim.region(x >> 5, z >> 5);
            Region.Header h = r == null ? null : r.header();
            int i = (x & 31) | ((z & 31) << 5);
            if (k > 0) {
                sb.append('-');
            }
            sb.append(h == null ? "-" : h.timestamps[i] + "." + h.offsets[i]);
        }
        return sb.toString();
    }

    /** [gzip bytes, stamp] of a chunk's 3D mesh, or null. */
    Object[] mesh(Recording.Dimension dim, int cx, int cz, boolean full, Integer ylo, Integer yhi, List<String> names, String at) {
        String stamp = chunkStamp(dim, cx, cz) + (full ? "F" : "") + (yhi != null ? "C" + (ylo == null ? "None" : ylo) + ":" + yhi : "");
        if (at != null && !at.isEmpty()) {
            stamp += "A" + at;
        }
        if (!names.isEmpty()) {
            stamp += "X" + Mesher.sha1(String.join(",", names)).substring(0, 10);
        }
        List<Object> key = List.of(dim.id(), cx, cz, stamp);
        byte[] hit = (byte[]) meshes.get(key);
        if (hit != null) {
            return new Object[] {hit, stamp};
        }
        byte[] data = Mesher.compute(rec, dim, cx, cz, full, ylo, yhi, names, at, history);
        if (data == null) {
            return null;
        }
        meshes.put(key, data);
        return new Object[] {data, stamp};
    }

    /** Searched blocks in the chunks around (cx, cz) for the 2D map, per chunk cached. */
    Map<String, Object> xrayFind(Recording.Dimension dim, int cx, int cz, int r, List<String> names) {
        Xray.Lookup look = Xray.lookup(names);
        long[] counts = new long[names.size()];
        List<Integer> pts = new ArrayList<>();
        int chunks = 0;
        List<Object[]> todo = new ArrayList<>();
        Map<Long, Object[]> found = new LinkedHashMap<>();
        for (int z = cz - r; z <= cz + r; z++) {
            for (int x = cx - r; x <= cx + r; x++) {
                List<Object> key = List.of("xray", dim.id(), x, z, chunkStamp(dim, x, z), names);
                Object[] h = (Object[]) meshes.get(key);
                if (h == null) {
                    todo.add(new Object[] {x, z, key});
                }
                found.put(Tiles.key(x, z), h);
            }
        }
        List<Object[]> res = todo.parallelStream().map(t -> {
            Object[] h = Xray.finds(rec.chunk(dim, (Integer) t[0], (Integer) t[1]), look, names.size());
            return h != null ? h : new Object[] {new long[names.size()], new int[0]};
        }).toList();
        for (int i = 0; i < todo.size(); i++) {
            Object[] t = todo.get(i);
            meshes.put(t[2], res.get(i));
            found.put(Tiles.key((Integer) t[0], (Integer) t[1]), res.get(i));
        }
        for (Object[] h : found.values()) {
            chunks++;
            long[] c = (long[]) h[0];
            for (int i = 0; i < c.length; i++) {
                counts[i] += c[i];
            }
            for (int v : (int[]) h[1]) {
                if (pts.size() < 4 * 20000) {
                    pts.add(v);
                }
            }
        }
        List<Long> cl = new ArrayList<>();
        for (long c : counts) {
            cl.add(c);
        }
        return J.map("names", names, "counts", cl, "pts", pts, "chunks", chunks);
    }

    // ------------------------------------------------------------------ data

    List<Map<String, Object>> worlds() {
        Map<String, List<Map<String, Object>>> servers = new TreeMap<>((a, b) -> {
            boolean la = a.equals(Recording.LEGACY_SERVER), lb = b.equals(Recording.LEGACY_SERVER);
            return la != lb ? Boolean.compare(la, lb) : a.compareTo(b);
        });
        for (Recording.Dimension d : rec.dimensions()) {
            List<Region> regs = d.regions();
            long[] bounds = null;
            long[] latest = null;
            for (Region r : regs) {
                Region.Header h = r.header();
                if (h == null || !h.any()) {
                    continue;
                }
                long minx = Long.MAX_VALUE, minz = Long.MAX_VALUE, maxx = Long.MIN_VALUE, maxz = Long.MIN_VALUE;
                int best = -1;
                for (int i = 0; i < 1024; i++) {
                    if (h.offsets[i] == 0) {
                        continue;
                    }
                    long cx = r.rx * 32L + (i & 31), cz = r.rz * 32L + (i >> 5);
                    minx = Math.min(minx, cx);
                    maxx = Math.max(maxx, cx);
                    minz = Math.min(minz, cz);
                    maxz = Math.max(maxz, cz);
                    if (best < 0 || h.timestamps[i] > h.timestamps[best]) {
                        best = i;
                    }
                }
                long[] b = {minx * 16, minz * 16, maxx * 16 + 16, maxz * 16 + 16};
                bounds = bounds == null ? b : new long[] {Math.min(bounds[0], b[0]), Math.min(bounds[1], b[1]),
                        Math.max(bounds[2], b[2]), Math.max(bounds[3], b[3])};
                long t = h.timestamps[best];
                if (latest == null || t > latest[0]) {
                    latest = new long[] {t, (r.rx * 32L + (best & 31)) * 16 + 8, (r.rz * 32L + (best >> 5)) * 16 + 8};
                }
            }
            if (bounds == null) {
                continue;
            }
            servers.computeIfAbsent(d.server, k -> new ArrayList<>()).add(J.map(
                    "key", d.key,
                    "label", Recording.DIM_LABELS.getOrDefault(d.key, d.key),
                    "layers", layersFor(d.key),
                    "regions", regs.size(),
                    "bounds", List.of(bounds[0], bounds[1], bounds[2], bounds[3]),
                    "latest", List.of(latest[1], latest[2])));
        }
        Map<String, Integer> order = Map.of("overworld", 0, "the_nether", 1, "the_end", 2);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<Map<String, Object>>> e : servers.entrySet()) {
            List<Map<String, Object>> dims = e.getValue();
            dims.sort((a, b) -> Integer.compare(order.getOrDefault((String) a.get("key"), 9), order.getOrDefault((String) b.get("key"), 9)));
            out.add(J.map("id", e.getKey(), "label", worldLabel(e.getKey()), "dims", dims));
        }
        return out;
    }

    static List<Map<String, Object>> layersFor(String dimKey) {
        if (dimKey.equals("the_nether")) {
            return List.of(J.map("id", "cave127", "label", "Unter der Decke"), J.map("id", "surface320", "label", "Auf der Decke"));
        }
        return List.of(J.map("id", "surface320", "label", "Oberfläche"));
    }

    Map<String, Object> column(Recording.Dimension dim, int x, int z, Tiles.Layer layer) {
        Chunk ch = rec.chunk(dim, x >> 4, z >> 4);
        if (ch == null) {
            return J.map("x", x, "z", z, "known", false);
        }
        Tiles.Rendered r = Tiles.renderChunk(ch, layer);
        int lz = z & 15, lx = x & 15, i = lz * 16 + lx;
        if (!r.has[i]) {
            return J.map("x", x, "z", z, "known", true, "block", null);
        }
        int y = r.height[i];
        int bi = ch.biome(y, lz, lx);
        return J.map("x", x, "z", z, "y", y, "known", true, "block", ch.block(x, y, z), "biome", Chunk.BIOMES.name(bi));
    }

    List<List<Integer>> changedRegions(Recording.Dimension dim, double since) {
        List<List<Integer>> out = new ArrayList<>();
        for (Region r : dim.regions()) {
            try {
                if (Files.getLastModifiedTime(r.path).toMillis() / 1000.0 > since) {
                    out.add(List.of(r.rx, r.rz));
                }
            } catch (IOException ignored) {
                // file just replaced
            }
        }
        return out;
    }

    /** Chunks of the changed regions whose timestamp (seconds) is newer than since. */
    List<List<Integer>> changedChunks(Recording.Dimension dim, List<List<Integer>> regs, double since) {
        List<List<Integer>> out = new ArrayList<>();
        long cut = (long) since - 1;
        for (List<Integer> rr : regs) {
            Region r = dim.region(rr.get(0), rr.get(1));
            Region.Header h = r == null ? null : r.header();
            if (h == null) {
                continue;
            }
            for (int i = 0; i < 1024; i++) {
                if (h.timestamps[i] >= cut) {
                    out.add(List.of(rr.get(0) * 32 + (i & 31), rr.get(1) * 32 + (i >> 5)));
                }
            }
        }
        return out.size() > 4000 ? out.subList(0, 4000) : out;
    }

    // ------------------------------------------------------------------ waypoints

    Path wpPath() {
        return rec.root.resolve("waypoints.json");
    }

    JsonElement waypoints() {
        JsonElement e = J.load(wpPath());
        return e != null ? e : new JsonArray();
    }

    JsonArray saveWaypoints(JsonArray wps) throws IOException {
        JsonArray clean = new JsonArray();
        for (JsonElement we : wps) {
            try {
                JsonObject w = we.getAsJsonObject();
                JsonObject c = new JsonObject();
                c.addProperty("id", J.bool(w, "id") ? J.str(w, "id") : Long.toString(System.currentTimeMillis() * 1_000_000L + System.nanoTime() % 1_000_000L));
                String name = J.str(w, "name", "");
                c.addProperty("name", name.substring(0, Math.min(80, name.length())));
                c.addProperty("server", J.str(J.get(w, "server")));
                c.addProperty("dim", J.str(J.get(w, "dim")));
                c.addProperty("x", J.i(w, "x"));
                c.addProperty("y", J.i(w, "y", 64));
                c.addProperty("z", J.i(w, "z"));
                String color = J.str(w, "color", "#e5484d");
                c.addProperty("color", color.substring(0, Math.min(16, color.length())));
                clean.add(c);
            } catch (RuntimeException e) {
                // skip broken entries
            }
        }
        synchronized (wpLock) {
            J.save(wpPath(), clean, true);
        }
        return clean;
    }

    static final Pattern PORT = Pattern.compile("(.+)_(\\d{1,5})");

    /** Name in the world picker: the server address, or the singleplayer save. */
    static String worldLabel(String server) {
        if (server.equals(Recording.LEGACY_SERVER)) {
            return "Alte Aufnahme (gemischt)";
        }
        if (server.equals("singleplayer")) {
            return "Einzelspieler (alle alten Welten gemischt)";
        }
        if (server.startsWith("singleplayer-")) {
            return "Einzelspieler: " + server.substring("singleplayer-".length()).replace("_", " ");
        }
        Matcher m = PORT.matcher(server);
        if (m.matches()) {
            return m.group(1) + ":" + m.group(2);
        }
        return server;
    }

    /** Open viewer tabs (for the launcher). */
    public int streams() {
        return streams;
    }
}
