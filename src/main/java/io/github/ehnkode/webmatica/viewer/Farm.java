package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.KeyError;
import io.github.ehnkode.webmatica.viewer.core.Recording;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Find blocks in an area of the recording (farms, wrong blocks). An area is x0, z0, x1, z1, y0,
 * y1 in world coordinates, all bounds inclusive; read as the mod last saw the chunks.
 */
final class Farm {
    /** 1024 x 1024 blocks are enough for a farm */
    static final int MAX_CHUNKS = 64 * 64;
    /** can't be broken (or picked up): left out of the selection */
    static final Set<String> UNFARMABLE = Set.of("water", "lava", "bedrock", "barrier", "end_portal", "end_portal_frame",
            "nether_portal", "end_gateway", "bubble_column", "fire", "soul_fire", "command_block", "structure_block", "jigsaw",
            "reinforced_deepslate").stream().map(n -> "minecraft:" + n).collect(java.util.stream.Collectors.toUnmodifiableSet());
    /** Placeholder for "all breakable blocks" (flattening). */
    static final String ANY = "*";

    private Farm() {
    }

    record Area(int x0, int z0, int x1, int z1, int y0, int y1) {
        Map<String, Object> toMap() {
            return J.map("x0", x0, "z0", z0, "x1", x1, "z1", z1, "y0", y0, "y1", y1);
        }

        Area withY(int ny0, int ny1) {
            return new Area(x0, z0, x1, z1, ny0, ny1);
        }
    }

    static int need(Map<String, ?> a, String k) {
        Object v = a.get(k);
        if (v == null && !a.containsKey(k)) {
            throw new KeyError(k);
        }
        return v instanceof JsonElement e ? J.i(e) : v instanceof Number n ? n.intValue() : Http.pyInt(String.valueOf(v));
    }

    static int opt(Map<String, ?> a, String k, int def) {
        return a.containsKey(k) ? need(a, k) : def;
    }

    static Area normArea(Map<String, ?> a) {
        int x0 = need(a, "x0"), z0 = need(a, "z0"), x1 = need(a, "x1"), z1 = need(a, "z1");
        int y0 = opt(a, "y0", -64), y1 = opt(a, "y1", 319);
        Area out = new Area(Math.min(x0, x1), Math.min(z0, z1), Math.max(x0, x1), Math.max(z0, z1), Math.min(y0, y1), Math.max(y0, y1));
        long n = (long) ((out.x1 >> 4) - (out.x0 >> 4) + 1) * ((out.z1 >> 4) - (out.z0 >> 4) + 1);
        if (n > MAX_CHUNKS) {
            throw new IllegalArgumentException("Bereich zu groß (höchstens 1024 × 1024 Blöcke)");
        }
        return out;
    }

    static Area normArea(JsonObject o) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            m.put(e.getKey(), e.getValue());
        }
        return normArea(m);
    }

    static boolean solid(String name) {
        return !Chunk.AIR_NAMES.contains(name) && !UNFARMABLE.contains(name);
    }

    /** Block state id -> matches by block name; grows with the registry. */
    static final class Mask {
        final Predicate<String> test;
        boolean[] m = new boolean[0];

        Mask(Set<String> names) {
            this.test = names.contains(ANY) ? Farm::solid : names::contains;
        }

        Mask(Predicate<String> test) {
            this.test = test;
        }

        boolean get(int id) {
            if (id >= m.length) {
                String[] names = Chunk.BLOCKS.names();
                boolean[] n = new boolean[Math.max(names.length, id + 1)];
                for (int i = 0; i < names.length; i++) {
                    n[i] = test.test(Chunk.baseName(names[i]));
                }
                m = n;
            }
            return m[id];
        }
    }

    /** A recorded chunk's part inside the area: world origin and size, ids via at(). */
    record Part(Chunk ch, int ox, int oy, int oz, int w, int h, int l) {
        int at(int y, int z, int x) {
            return ch.id(oy + y - ch.minY, (oz + z) & 15, (ox + x) & 15);
        }
    }

    static List<Part> chunks(Recording rec, Recording.Dimension dim, Area a) {
        List<Part> out = new ArrayList<>();
        for (int cz = a.z0 >> 4; cz <= a.z1 >> 4; cz++) {
            for (int cx = a.x0 >> 4; cx <= a.x1 >> 4; cx++) {
                Chunk ch = rec.chunk(dim, cx, cz);
                if (ch == null) {
                    continue;
                }
                int ax = Math.max(a.x0, cx * 16), bx = Math.min(a.x1, cx * 16 + 15);
                int az = Math.max(a.z0, cz * 16), bz = Math.min(a.z1, cz * 16 + 15);
                int ay = Math.max(a.y0, ch.minY), by = Math.min(a.y1, ch.minY + ch.height - 1);
                if (ay > by) {
                    continue;
                }
                out.add(new Part(ch, ax, ay, az, bx - ax + 1, by - ay + 1, bz - az + 1));
            }
        }
        return out;
    }

    static List<List<Object>> topCounts(Map<String, Long> by, int top) {
        List<Map.Entry<String, Long>> es = new ArrayList<>(by.entrySet());
        es.sort((p, q) -> Long.compare(q.getValue(), p.getValue()));
        List<List<Object>> out = new ArrayList<>();
        for (int i = 0; i < Math.min(top, es.size()); i++) {
            out.add(List.of(es.get(i).getKey(), es.get(i).getValue()));
        }
        return out;
    }

    /** Counts per block id over the parts (sorted by id). */
    static TreeMap<Integer, Long> idCounts(List<Part> parts, Mask only) {
        TreeMap<Integer, Long> c = new TreeMap<>();
        for (Part p : parts) {
            for (int y = 0; y < p.h; y++) {
                for (int z = 0; z < p.l; z++) {
                    for (int x = 0; x < p.w; x++) {
                        int id = p.at(y, z, x);
                        if (only == null || only.get(id)) {
                            c.merge(id, 1L, Long::sum);
                        }
                    }
                }
            }
        }
        return c;
    }

    /** Which blocks exist in the area, with counts (most common first). */
    static Map<String, Object> scan(Recording rec, Recording.Dimension dim, Map<String, ?> area) {
        Area a = normArea(area);
        List<Part> parts = chunks(rec, dim, a);
        Map<String, Long> by = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : idCounts(parts, null).entrySet()) {
            String name = Chunk.baseName(Chunk.BLOCKS.name(e.getKey()));
            if (!Chunk.AIR_NAMES.contains(name) && !UNFARMABLE.contains(name)) {
                by.merge(name, e.getValue(), Long::sum);
            }
        }
        return J.map("blocks", topCounts(by, 60), "chunks", parts.size(), "area", a.toMap());
    }

    /** How many blocks per name are still in the area. */
    static Map<String, Long> count(Recording rec, Recording.Dimension dim, Area a, Set<String> names) {
        Mask want = new Mask(names);
        Map<String, Long> out = new LinkedHashMap<>();
        for (String n : names) {
            out.put(n, 0L);
        }
        for (Map.Entry<Integer, Long> e : idCounts(chunks(rec, dim, a), want).entrySet()) {
            if (names.contains(ANY)) {
                out.merge(ANY, e.getValue(), Long::sum);
            } else {
                out.merge(Chunk.baseName(Chunk.BLOCKS.name(e.getKey())), e.getValue(), Long::sum);
            }
        }
        return out;
    }

    /**
     * Empty spots below level that must be filled for flattening. Per column from the topmost
     * solid block (or from y0) up to level; with every, every empty spot. Bottom first.
     */
    static List<int[]> holes(Recording rec, Recording.Dimension dim, Area area, int level, double[] near, Integer limit, boolean every) {
        Area a = area.withY(area.y0, Math.min(area.y1, level));
        if (a.y0 > a.y1) {
            return new ArrayList<>();
        }
        Mask empty = new Mask(n -> Chunk.AIR_NAMES.contains(n) || Chunk.REPLACEABLE.matcher(n).matches());
        List<int[]> pts = new ArrayList<>();
        for (Part p : chunks(rec, dim, a)) {
            boolean[] e = new boolean[p.h * p.l * p.w];
            for (int y = 0; y < p.h; y++) {
                for (int z = 0; z < p.l; z++) {
                    for (int x = 0; x < p.w; x++) {
                        e[(y * p.l + z) * p.w + x] = empty.get(p.at(y, z, x));
                    }
                }
            }
            boolean[] hole = new boolean[e.length];
            for (int z = 0; z < p.l; z++) {
                for (int x = 0; x < p.w; x++) {
                    boolean aboveSolid = false;
                    for (int y = p.h - 1; y >= 0; y--) {
                        int i = (y * p.l + z) * p.w + x;
                        aboveSolid |= !e[i];
                        hole[i] = every ? e[i] : e[i] && !aboveSolid;
                    }
                }
            }
            for (int y = 0; y < p.h; y++) {
                for (int z = 0; z < p.l; z++) {
                    for (int x = 0; x < p.w; x++) {
                        if (hole[(y * p.l + z) * p.w + x]) {
                            pts.add(new int[] {x + p.ox, y + p.oy, z + p.oz});
                        }
                    }
                }
            }
        }
        if (near != null) {
            pts.sort(Comparator.<int[]>comparingInt(q -> q[1]).thenComparingDouble(q -> sq(q[0] - near[0]) + sq(q[2] - near[2])));
        } else {
            pts.sort(Comparator.comparingInt(q -> q[1]));
        }
        return limit != null && limit != 0 && pts.size() > limit ? new ArrayList<>(pts.subList(0, limit)) : pts;
    }

    static double sq(double v) {
        return v * v;
    }

    /** Preview for flattening: blocks broken and filled per chunk, and what gets mined. */
    static Map<String, Object> flatPreview(Recording rec, Recording.Dimension dim, Area a, String kind, int level) {
        if (kind.equals("clear")) {
            level = a.y0 - 1;
        } else if (kind.equals("fill")) {
            level = a.y1;
        }
        TreeMap<Long, long[]> per = new TreeMap<>((p, q) -> {
            int px = (int) (p >> 32), pz = (int) (long) p, qx = (int) (q >> 32), qz = (int) (long) q;
            return px != qx ? Integer.compare(px, qx) : Integer.compare(pz, qz);
        });
        Map<Integer, Long> mined = new TreeMap<>();
        Area above = a.withY(level + 1, a.y1);
        Mask solidMask = new Mask(Set.of(ANY));
        if (above.y0 <= above.y1) {
            for (Part p : chunks(rec, dim, above)) {
                long n = 0;
                for (int y = 0; y < p.h; y++) {
                    for (int z = 0; z < p.l; z++) {
                        for (int x = 0; x < p.w; x++) {
                            int id = p.at(y, z, x);
                            if (solidMask.get(id)) {
                                n++;
                                mined.merge(id, 1L, Long::sum);
                            }
                        }
                    }
                }
                if (n > 0) {
                    per.computeIfAbsent(History.ck(p.ox >> 4, p.oz >> 4), k -> new long[2])[0] += n;
                }
            }
        }
        for (int[] q : holes(rec, dim, a, level, null, null, kind.equals("fill"))) {
            per.computeIfAbsent(History.ck(q[0] >> 4, q[2] >> 4), k -> new long[2])[1]++;
        }
        Map<String, Long> by = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : mined.entrySet()) {
            by.merge(Chunk.baseName(Chunk.BLOCKS.name(e.getKey())), e.getValue(), Long::sum);
        }
        long clear = 0, fill = 0;
        List<List<Long>> chunks = new ArrayList<>();
        for (Map.Entry<Long, long[]> e : per.entrySet()) {
            clear += e.getValue()[0];
            fill += e.getValue()[1];
            chunks.add(List.of((long) (int) (e.getKey() >> 32), (long) (int) (long) e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return J.map("clear", clear, "fill", fill, "chunks", chunks, "mined", topCounts(by, 12));
    }

    /**
     * The limit nearest matching blocks to near (x, y, z); exposed ones (next to air) first, top
     * first at equal distance. Then sorted around the first hit, so one job works through one spot.
     */
    static List<int[]> positions(Recording rec, Recording.Dimension dim, Area a, Set<String> names, double[] near, int limit,
                                 Set<List<Integer>> skip) {
        Mask want = new Mask(names);
        Mask air = new Mask(Chunk.AIR_NAMES);
        List<long[]> cand = new ArrayList<>();      // key, -y, x, y, z
        for (Part p : chunks(rec, dim, a)) {
            int n = p.h * p.l * p.w;
            boolean[] m = new boolean[n], ai = new boolean[n];
            boolean any = false;
            for (int y = 0; y < p.h; y++) {
                for (int z = 0; z < p.l; z++) {
                    for (int x = 0; x < p.w; x++) {
                        int i = (y * p.l + z) * p.w + x, id = p.at(y, z, x);
                        m[i] = want.get(id);
                        ai[i] = air.get(id);
                        any |= m[i];
                    }
                }
            }
            if (!any) {
                continue;
            }
            List<long[]> local = new ArrayList<>();
            for (int y = 0; y < p.h; y++) {
                for (int z = 0; z < p.l; z++) {
                    for (int x = 0; x < p.w; x++) {
                        int i = (y * p.l + z) * p.w + x;
                        if (!m[i]) {
                            continue;
                        }
                        boolean free = (y > 0 && ai[i - p.l * p.w]) || (y < p.h - 1 && ai[i + p.l * p.w])
                                || (z > 0 && ai[i - p.w]) || (z < p.l - 1 && ai[i + p.w])
                                || (x > 0 && ai[i - 1]) || (x < p.w - 1 && ai[i + 1]);
                        long wx = x + p.ox, wy = y + p.oy, wz = z + p.oz;
                        long d = (long) (sq(wx - near[0]) + sq(wz - near[2]) + 4 * sq(wy - near[1]));
                        long key = d + (free ? 0 : 1L << 40);
                        local.add(new long[] {key, -wy, wx, wy, wz});
                    }
                }
            }
            int k = Math.min(local.size(), limit * 4 + (skip == null ? 0 : skip.size()));
            if (k < local.size()) {
                local.sort(Comparator.comparingLong(v -> v[0]));
                local = local.subList(0, k);
            }
            for (long[] v : local) {
                if (skip != null && skip.contains(List.of((int) v[2], (int) v[3], (int) v[4]))) {
                    continue;
                }
                cand.add(v);
            }
        }
        cand.sort((p, q) -> {
            for (int i = 0; i < 5; i++) {
                if (p[i] != q[i]) {
                    return Long.compare(p[i], q[i]);
                }
            }
            return 0;
        });
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < Math.min(cand.size(), limit * 4); i++) {
            long[] v = cand.get(i);
            out.add(new int[] {(int) v[2], (int) v[3], (int) v[4]});
        }
        if (!out.isEmpty()) {
            int[] f = out.get(0);
            out.sort(Comparator.comparingLong(q -> (long) (q[0] - f[0]) * (q[0] - f[0]) + (long) (q[2] - f[2]) * (q[2] - f[2])
                    + (long) (q[1] - f[1]) * (q[1] - f[1])));
        }
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Merge single blocks into runs along x: [x0, y0, z0, x1, y1, z1]. */
    static List<List<Integer>> boxes(List<int[]> points, int cap) {
        TreeSet<int[]> pts = new TreeSet<>((p, q) -> p[1] != q[1] ? Integer.compare(p[1], q[1])
                : p[2] != q[2] ? Integer.compare(p[2], q[2]) : Integer.compare(p[0], q[0]));
        for (int[] p : points) {
            pts.add(new int[] {p[0], p[1], p[2]});
        }
        List<int[]> out = new ArrayList<>();
        for (int[] p : pts) {
            int x = p[0], y = p[1], z = p[2];
            int[] last = out.isEmpty() ? null : out.get(out.size() - 1);
            if (last != null && last[1] == y && last[2] == z && last[3] == x - 1) {
                last[3] = x;
            } else {
                out.add(new int[] {x, y, z, x, y, z});
            }
            if (out.size() > cap) {
                out.remove(out.size() - 1);
                break;
            }
        }
        List<List<Integer>> res = new ArrayList<>();
        for (int[] b : out) {
            res.add(Arrays.stream(b).boxed().toList());
        }
        return res;
    }

    static List<List<Integer>> boxes(List<int[]> points) {
        return boxes(points, 1500);
    }

    static List<int[]> flatPoints(List<? extends Number> arr) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i + 2 < arr.size(); i += 3) {
            out.add(new int[] {arr.get(i).intValue(), arr.get(i + 1).intValue(), arr.get(i + 2).intValue()});
        }
        return out;
    }
}
