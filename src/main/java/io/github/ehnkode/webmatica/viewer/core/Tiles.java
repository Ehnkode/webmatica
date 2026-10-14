package io.github.ehnkode.webmatica.viewer.core;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Map tiles.
 *
 * <p>Zoom level 0: one tile = one region (512 x 512 blocks, 1 pixel per block). Zoom level -k:
 * one tile combines 2^k x 2^k regions (downscaled from the four child tiles). Zooming in beyond
 * 0 is scaled up by the browser.
 *
 * <p>Everything is kept on disk under the cache folder. For zoom level 0 a ".grid" file next
 * to the PNG keeps colors, heights and the region's chunk timestamps; when single chunks
 * change, only those are recomputed.
 */
public class Tiles {
    public static final int TILE = 512;
    private static final Gson GSON = new Gson();

    /** Which layer is shown: "surface" = topmost block below yMax, "cave" = first block below the ceiling. */
    public record Layer(String mode, int yMax) {
        public static final Layer DEFAULT = new Layer("surface", 320);

        public String key() {
            return mode + yMax;
        }

        public static Layer parse(String s) {
            for (String mode : new String[] {"surface", "cave"}) {
                if (s.startsWith(mode)) {
                    try {
                        return new Layer(mode, Integer.parseInt(s.substring(mode.length())));
                    } catch (NumberFormatException e) {
                        break;
                    }
                }
            }
            return DEFAULT;
        }
    }

    /** One chunk rendered for a layer: colors, heights, which columns have a block, which show water. */
    public static final class Rendered {
        public final float[] rgb = new float[256 * 3];
        public final int[] height = new int[256];
        public final boolean[] has = new boolean[256];
        public final boolean[] water = new boolean[256];
    }

    public static Rendered renderChunk(Chunk ch, Layer layer) {
        Palette.Tables p = Palette.sync();
        Rendered out = new Rendered();
        int topRel = Math.min(ch.height - 1, layer.yMax() - ch.minY);
        if (topRel < 0) {
            return out;
        }
        boolean cave = layer.mode().equals("cave");
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int col = z * 16 + x;
                int start = 0;        // row (counted from the top) where solid blocks may begin
                boolean possible = true;
                if (cave) {
                    // Find the first air below the ceiling; columns without air stay empty.
                    int firstAir = -1;
                    for (int r = 0; r <= topRel; r++) {
                        if (p.empty[ch.id(topRel - r, z, x)]) {
                            firstAir = r;
                            break;
                        }
                    }
                    if (firstAir < 0) {
                        possible = false;
                    } else {
                        start = firstAir;
                    }
                }
                int top = -1;
                if (possible) {
                    for (int r = start; r <= topRel; r++) {
                        if (!p.empty[ch.id(topRel - r, z, x)]) {
                            top = r;
                            break;
                        }
                    }
                }
                boolean has = top >= 0;
                if (!has) {
                    top = 0;
                }
                int topId = ch.id(topRel - top, z, x);
                int under = top;
                boolean hasUnder = false;
                if (has) {
                    for (int r = top + 1; r <= topRel; r++) {
                        int id = ch.id(topRel - r, z, x);
                        if (!p.empty[id] && p.alpha[id] >= 0.99f && !p.water[id]) {
                            under = r;
                            hasUnder = true;
                            break;
                        }
                    }
                }
                int underId = ch.id(topRel - under, z, x);
                int yTop = ch.minY + topRel - top;
                int yUnder = ch.minY + topRel - under;
                int biome = ch.biome(yTop, z, x);
                float[] cTop = tinted(p, topId, biome);
                float[] cUnder = tinted(p, underId, biome);
                boolean isWater = p.water[topId];
                int depth = Math.max(0, yTop - yUnder);
                double a = isWater ? Math.max(0.55, Math.min(0.92, 0.55 + depth * 0.04)) : p.alpha[topId];
                if (!(hasUnder || isWater)) {
                    a = 1.0;
                }
                for (int c = 0; c < 3; c++) {
                    out.rgb[col * 3 + c] = (float) (cTop[c] * a + cUnder[c] * (1 - a));
                }
                out.height[col] = yTop;
                out.has[col] = has;
                out.water[col] = has && isWater;
            }
        }
        return out;
    }

    static float[] tinted(Palette.Tables p, int id, int biome) {
        float[] rgb = {p.rgb[id * 3], p.rgb[id * 3 + 1], p.rgb[id * 3 + 2]};
        int t = p.tint[id];
        int slot = t == Palette.TINT_GRASS ? 0 : t == Palette.TINT_FOLIAGE ? 1 : t == Palette.TINT_WATER ? 2 : -1;
        if (slot >= 0) {
            for (int c = 0; c < 3; c++) {
                rgb[c] = rgb[c] * p.biomeRgb[biome * 9 + slot * 3 + c] / 255.0f;
            }
        }
        return rgb;
    }

    /** Region-sized arrays of one tile. */
    static final class Grid {
        float[] rgb = new float[TILE * TILE * 3];
        int[] height = new int[TILE * TILE];
        boolean[] has = new boolean[TILE * TILE];
        boolean[] water = new boolean[TILE * TILE];
        long[] stamp = new long[1024];

        Grid() {
            java.util.Arrays.fill(height, -64);
            java.util.Arrays.fill(stamp, -1);
        }

        void paint(Rendered r, int lx, int lz) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = (lz * 16 + z) * TILE + lx * 16 + x, j = z * 16 + x;
                    rgb[3 * i] = r.rgb[3 * j];
                    rgb[3 * i + 1] = r.rgb[3 * j + 1];
                    rgb[3 * i + 2] = r.rgb[3 * j + 2];
                    height[i] = r.height[j];
                    has[i] = r.has[j];
                    water[i] = r.water[j];
                }
            }
        }

        void clear(int lx, int lz) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    has[(lz * 16 + z) * TILE + lx * 16 + x] = false;
                }
            }
        }

        private static final int MAGIC = 0x57474431;   // "WGD1"

        void save(Path p) throws IOException {
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            try (OutputStream os = new java.io.BufferedOutputStream(Files.newOutputStream(tmp), 1 << 16);
                 DataOutputStream out = new DataOutputStream(os)) {
                out.writeInt(MAGIC);
                ByteBuffer b = ByteBuffer.allocate(1024 * 8 + TILE * TILE * (6 + 2 + 1));
                for (long s : stamp) {
                    b.putLong(s);
                }
                for (float f : rgb) {
                    b.putShort(Float.floatToFloat16(f));
                }
                for (int i = 0; i < height.length; i++) {
                    b.putShort((short) Math.max(-32768, Math.min(32767, height[i])));
                }
                for (int i = 0; i < has.length; i++) {
                    b.put((byte) ((has[i] ? 1 : 0) | (water[i] ? 2 : 0)));
                }
                out.write(b.array());
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }

        static Grid load(Path p) {
            try (InputStream is = Files.newInputStream(p); DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(is, 1 << 16))) {
                if (in.readInt() != MAGIC) {
                    return null;
                }
                byte[] all = in.readNBytes(1024 * 8 + TILE * TILE * 9);
                if (all.length != 1024 * 8 + TILE * TILE * 9) {
                    return null;
                }
                ByteBuffer b = ByteBuffer.wrap(all);
                Grid g = new Grid();
                for (int i = 0; i < 1024; i++) {
                    g.stamp[i] = b.getLong();
                }
                for (int i = 0; i < g.rgb.length; i++) {
                    g.rgb[i] = Float.float16ToFloat(b.getShort());
                }
                for (int i = 0; i < g.height.length; i++) {
                    g.height[i] = b.getShort();
                }
                for (int i = 0; i < g.has.length; i++) {
                    byte f = b.get();
                    g.has[i] = (f & 1) != 0;
                    g.water[i] = (f & 2) != 0;
                }
                return g;
            } catch (IOException | RuntimeException e) {
                return null;
            }
        }

        /** Height shading like vanilla maps: lighter when higher than the neighbor to the north. */
        byte[] shaded() {
            byte[] out = new byte[TILE * TILE * 4];
            for (int z = 0; z < TILE; z++) {
                for (int x = 0; x < TILE; x++) {
                    int i = z * TILE + x;
                    int n = z > 0 ? i - TILE : i;
                    int north = has[n] ? height[n] : height[i];
                    float dh = Math.max(-8, Math.min(8, height[i] - north));
                    float f = water[i] ? 1.0f : 1.0f + dh * 0.045f;
                    for (int c = 0; c < 3; c++) {
                        out[4 * i + c] = (byte) clip255(rgb[3 * i + c] * f);
                    }
                    out[4 * i + 3] = (byte) (has[i] ? 255 : 0);
                }
            }
            return out;
        }

        byte[] raw() {
            byte[] out = new byte[TILE * TILE * 4];
            for (int i = 0; i < TILE * TILE; i++) {
                for (int c = 0; c < 3; c++) {
                    out[4 * i + c] = (byte) clip255(rgb[3 * i + c]);
                }
                out[4 * i + 3] = (byte) (has[i] ? 255 : 0);
            }
            return out;
        }
    }

    static int clip255(float v) {
        if (!(v > 0)) {
            return 0;
        }
        return v >= 255 ? 255 : (int) v;
    }

    // ------------------------------------------------------------------ the store

    public final Recording rec;
    public final Path cacheDir;
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public Tiles(Recording rec, Path cacheDir) {
        this.rec = rec;
        this.cacheDir = cacheDir;
    }

    protected Object lock(Path p) {
        return locks.computeIfAbsent(p.toString(), k -> new Object());
    }

    public Path dir(Recording.Dimension dim, Layer layer, int zoom) {
        Path d = cacheDir.resolve(dim.server.replace("/", "_")).resolve(dim.key.replace(":", "_")).resolve(layer.key()).resolve(Integer.toString(zoom));
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // the write that follows reports it
        }
        return d;
    }

    public Path gridPath(Recording.Dimension dim, Layer layer, int rx, int rz) {
        return dir(dim, layer, 0).resolve(rx + "_" + rz + ".grid");
    }

    static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    /** Path to a region's PNG (updated when needed), null if the region is empty. */
    public Path regionTile(Recording.Dimension dim, Layer layer, int rx, int rz) throws IOException {
        Region region = dim.region(rx, rz);
        if (region == null) {
            return null;
        }
        Region.Header h = region.header();
        if (h == null || !h.any()) {
            return null;
        }
        Path d = dir(dim, layer, 0);
        Path png = d.resolve(rx + "_" + rz + ".png");
        Path gridPath = d.resolve(rx + "_" + rz + ".grid");
        long[] stamp = new long[1024];
        for (int i = 0; i < 1024; i++) {
            stamp[i] = h.timestamps[i] * (h.offsets[i] > 0 ? 1 : 0) + h.offsets[i];   // changes on every rewrite
        }
        synchronized (lock(png)) {
            Grid old = null;
            if (Files.exists(gridPath) && Files.exists(png)) {
                old = Grid.load(gridPath);
                if (old != null && java.util.Arrays.equals(old.stamp, stamp)) {
                    return png;
                }
            }
            Grid g = old != null ? old : new Grid();
            List<Integer> todo = new ArrayList<>();
            for (int i = 0; i < 1024; i++) {
                if (old == null ? h.offsets[i] != 0 : stamp[i] != old.stamp[i]) {
                    todo.add(i);
                }
            }
            Path mpath = d.resolve(rx + "_" + rz + ".markers.json");
            JsonObject markers = new JsonObject();
            if (old != null && Files.exists(mpath)) {
                try {
                    JsonElement e = JsonParser.parseString(Files.readString(mpath, StandardCharsets.UTF_8));
                    if (e.isJsonObject()) {
                        markers = e.getAsJsonObject();
                    }
                } catch (IOException | RuntimeException e) {
                    markers = new JsonObject();
                }
            }
            for (int idx : todo) {
                markers.remove(Integer.toString(idx));
                int lx = idx & 31, lz = idx >> 5;
                Chunk ch = null;
                if (h.offsets[idx] != 0) {
                    try {
                        ch = rec.chunk(dim, rx * 32 + lx, rz * 32 + lz);
                    } catch (RuntimeException e) {   // a broken chunk must not prevent the tile
                        System.out.println("Chunk " + (rx * 32 + lx) + "," + (rz * 32 + lz) + " defekt: " + e);
                    }
                }
                if (ch == null) {
                    g.clear(lx, lz);
                    continue;
                }
                Map<String, Object> m = Markers.of(ch);
                if (!((List<?>) m.get("signs")).isEmpty() || !((Map<?, ?>) m.get("containers")).isEmpty()) {
                    markers.add(Integer.toString(idx), GSON.toJsonTree(m));
                }
                g.paint(renderChunk(ch, layer), lx, lz);
            }
            g.stamp = stamp;
            Png.writeAtomic(mpath, markers.toString().getBytes(StandardCharsets.UTF_8));
            Png.writeAtomic(png, Png.encode(TILE, TILE, g.shaded()));
            g.save(gridPath);
            return png;
        }
    }

    /** Signs and containers of all regions rendered so far. */
    public Map<String, Object> markers(Recording.Dimension dim, Layer layer) {
        Path d = dir(dim, layer, 0);
        List<Object> signs = new ArrayList<>(), stashes = new ArrayList<>();
        for (Region r : dim.regions()) {
            JsonObject data;
            try {
                data = JsonParser.parseString(Files.readString(d.resolve(r.rx + "_" + r.rz + ".markers.json"), StandardCharsets.UTF_8)).getAsJsonObject();
            } catch (IOException | RuntimeException e) {
                continue;
            }
            for (Map.Entry<String, JsonElement> e : data.entrySet()) {
                int i = Integer.parseInt(e.getKey());
                int cx = r.rx * 32 + (i & 31), cz = r.rz * 32 + (i >> 5);
                JsonObject m = e.getValue().getAsJsonObject();
                if (m.has("signs")) {
                    for (JsonElement s : m.getAsJsonArray("signs")) {
                        signs.add(s);
                    }
                }
                JsonObject cont = m.has("containers") ? m.getAsJsonObject("containers") : new JsonObject();
                if (!cont.isEmpty()) {
                    int count = 0;
                    for (Map.Entry<String, JsonElement> c : cont.entrySet()) {
                        count += c.getValue().getAsInt();
                    }
                    Map<String, Object> st = new LinkedHashMap<>();
                    st.put("cx", cx);
                    st.put("cz", cz);
                    st.put("count", count);
                    st.put("types", cont);
                    stashes.add(st);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signs", signs);
        out.put("stashes", stashes);
        return out;
    }

    /** Like {@link #regionTile}, but colors without height shading (the 3D far view shades itself). */
    public Path rawRegionTile(Recording.Dimension dim, Layer layer, int rx, int rz) throws IOException {
        if (regionTile(dim, layer, rx, rz) == null) {
            return null;
        }
        Path d = dir(dim, layer, 0);
        Path grid = d.resolve(rx + "_" + rz + ".grid");
        Path png = d.resolve(rx + "_" + rz + ".raw.png");
        synchronized (lock(png)) {
            long pm = mtime(png);
            if (pm >= 0 && pm >= mtime(grid)) {
                return png;
            }
            Grid g = Grid.load(grid);
            if (g == null) {
                return null;
            }
            Png.writeAtomic(png, Png.encode(TILE, TILE, g.raw()));
            return png;
        }
    }

    /** Regions of a dimension as a set of packed keys. */
    public static Set<Long> keys(Recording.Dimension dim) {
        Set<Long> out = new HashSet<>();
        for (Region r : dim.regions()) {
            out.add(key(r.rx, r.rz));
        }
        return out;
    }

    public static long key(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    public Path tile(Recording.Dimension dim, Layer layer, int zoom, int tx, int tz, boolean raw) throws IOException {
        return tile(dim, layer, zoom, tx, tz, null, raw);
    }

    public Path tile(Recording.Dimension dim, Layer layer, int zoom, int tx, int tz, Set<Long> regions, boolean raw) throws IOException {
        if (zoom >= 0) {
            return raw ? rawRegionTile(dim, layer, tx, tz) : regionTile(dim, layer, tx, tz);
        }
        if (regions == null) {
            regions = keys(dim);
        }
        int span = 1 << (-zoom);
        long x0 = (long) tx * span, z0 = (long) tz * span;
        boolean any = false;
        for (long k : regions) {
            int rx = (int) (k >> 32), rz = (int) k;
            if (x0 <= rx && rx < x0 + span && z0 <= rz && rz < z0 + span) {
                any = true;
                break;
            }
        }
        if (!any) {
            return null;
        }
        Path[] children = new Path[4];
        for (int dz = 0; dz < 2; dz++) {
            for (int dx = 0; dx < 2; dx++) {
                children[dz * 2 + dx] = tile(dim, layer, zoom + 1, tx * 2 + dx, tz * 2 + dz, regions, raw);
            }
        }
        Path png = dir(dim, layer, zoom).resolve(tx + "_" + tz + (raw ? ".raw" : "") + ".png");
        synchronized (lock(png)) {
            long newest = 0;
            for (Path c : children) {
                if (c != null) {
                    newest = Math.max(newest, mtime(c));
                }
            }
            if (Files.exists(png) && mtime(png) >= newest) {
                return png;
            }
            byte[] out = new byte[TILE * TILE * 4];
            int half = TILE / 2;
            for (int k = 0; k < 4; k++) {
                if (children[k] == null) {
                    continue;
                }
                byte[] src = Png.rgba(Png.read(children[k]));
                downscale(src, out, (k % 2) * half, (k / 2) * half);
            }
            Png.writeAtomic(png, Png.encode(TILE, TILE, out));
            return png;
        }
    }

    /**
     * 2x2 box filter written into one quarter of {@code out}, exactly as Pillow's
     * {@code resize(BOX)} on RGBA: premultiply (8 bit), horizontal pass, vertical pass, unpremultiply.
     */
    static void downscale(byte[] src, byte[] out, int ox, int oy) {
        int half = TILE / 2;
        int[] pre = new int[4];
        int[][] row = new int[2][4];
        for (int y = 0; y < half; y++) {
            for (int x = 0; x < half; x++) {
                for (int dy = 0; dy < 2; dy++) {
                    int[] acc = row[dy];
                    java.util.Arrays.fill(acc, 0);
                    for (int dx = 0; dx < 2; dx++) {
                        int i = ((2 * y + dy) * TILE + 2 * x + dx) * 4;
                        premultiply(src, i, pre);
                        for (int c = 0; c < 4; c++) {
                            acc[c] += pre[c];
                        }
                    }
                    for (int c = 0; c < 4; c++) {
                        acc[c] = (acc[c] + 1) >> 1;         // horizontal pass
                    }
                }
                int o = ((oy + y) * TILE + ox + x) * 4;
                int a = (row[0][3] + row[1][3] + 1) >> 1;   // vertical pass
                for (int c = 0; c < 3; c++) {
                    int v = (row[0][c] + row[1][c] + 1) >> 1;
                    out[o + c] = (byte) (a == 0 || a == 255 ? v : Math.min(255, 255 * v / a));
                }
                out[o + 3] = (byte) a;
            }
        }
    }

    /** Pillow's RGBA -> RGBa: c * a / 255, rounded like its MULDIV255. */
    private static void premultiply(byte[] src, int i, int[] out) {
        int a = src[i + 3] & 255;
        for (int c = 0; c < 3; c++) {
            int t = (src[i + c] & 255) * a + 128;
            out[c] = ((t >> 8) + t) >> 8;
        }
        out[3] = a;
    }

    /** Grid of a region tile for the far view: heights and which columns have data, or null. */
    public int[][] heights(Path gridPath) {
        Grid g = Grid.load(gridPath);
        if (g == null) {
            return null;
        }
        int[] h = new int[TILE * TILE];
        for (int i = 0; i < h.length; i++) {
            h[i] = g.has[i] ? g.height[i] : -32768;
        }
        return new int[][] {h};
    }

    // ------------------------------------------------------------------ timelapse

    /**
     * Tiles "this is how it looked on day X". They contain only chunks with a backed-up state
     * from day X on (they changed since), in that old state; everything else is transparent and
     * the map below shows today's state there, which was also valid back then.
     */
    public static final class Past extends Tiles {
        /** {chunk key: [region file, chunk index]} */
        private final Supplier<Map<Long, Object[]>> index;

        public Past(Recording rec, Path cacheDir, Supplier<Map<Long, Object[]>> index) {
            super(rec, cacheDir);
            this.index = index;
        }

        private Map<Long, Map<Long, Object[]>> byRegion() {
            Map<Long, Map<Long, Object[]>> out = new HashMap<>();
            for (Map.Entry<Long, Object[]> e : index.get().entrySet()) {
                int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                out.computeIfAbsent(key(cx >> 5, cz >> 5), k -> new HashMap<>()).put(e.getKey(), e.getValue());
            }
            return out;
        }

        @Override
        public Path regionTile(Recording.Dimension dim, Layer layer, int rx, int rz) throws IOException {
            Map<Long, Object[]> chunks = byRegion().get(key(rx, rz));
            if (chunks == null || chunks.isEmpty()) {
                return null;
            }
            Path png = dir(dim, layer, 0).resolve(rx + "_" + rz + ".png");
            long newest = 0;
            for (Object[] src : chunks.values()) {
                newest = Math.max(newest, mtime((Path) src[0]));
            }
            synchronized (lock(png)) {
                if (Files.exists(png) && mtime(png) >= newest) {
                    return png;
                }
                Grid g = new Grid();
                Map<Path, Region> regions = new HashMap<>();
                for (Map.Entry<Long, Object[]> e : chunks.entrySet()) {
                    int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
                    Path path = (Path) e.getValue()[0];
                    int idx = (Integer) e.getValue()[1];
                    Region reg = regions.computeIfAbsent(path, p -> new Region(p, rx, rz));
                    Chunk ch;
                    try {
                        Map<String, Object> root = reg.readNbt(idx);
                        ch = root == null ? null : Chunk.fromNbt(root, cx, cz);
                    } catch (RuntimeException ex) {
                        ch = null;
                    }
                    if (ch != null) {
                        g.paint(renderChunk(ch, layer), cx & 31, cz & 31);
                    }
                }
                Png.writeAtomic(png, Png.encode(TILE, TILE, g.shaded()));
                return png;
            }
        }

        @Override
        public Path tile(Recording.Dimension dim, Layer layer, int zoom, int tx, int tz, Set<Long> regions, boolean raw) throws IOException {
            return super.tile(dim, layer, zoom, tx, tz, regions != null ? regions : byRegion().keySet(), false);
        }
    }
}
