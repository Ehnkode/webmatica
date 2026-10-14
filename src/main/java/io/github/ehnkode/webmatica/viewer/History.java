package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Region;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

/**
 * Timeline: before overwriting, the mod backs up a chunk's old state once per day to
 * {@code <recording>/<server>/.history/<date>/...}. Here "state on day X" is compared with today.
 */
public final class History {
    static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");

    final Path root;
    private final Map<List<Object>, Object[]> idxCache = new HashMap<>();
    private final Map<List<Object>, Chunk> chunks = new HashMap<>();
    private final Map<List<Object>, Map<String, Object>> diffCache = new HashMap<>();

    public History(Path root) {
        this.root = root;
    }

    Path dir(String server, String date, String dim) {
        String sub = Recording.DIMENSIONS.getOrDefault(dim, "");
        Path base = root.resolve(server).resolve(".history").resolve(date);
        return sub.isEmpty() ? base.resolve("region") : base.resolve(sub).resolve("region");
    }

    public List<String> dates(String server, String dim) {
        List<String> out = new ArrayList<>();
        for (String d : Recording.list(root.resolve(server).resolve(".history"))) {
            if (Files.isDirectory(dir(server, d, dim))) {
                out.add(d);
            }
        }
        return out;
    }

    /**
     * {chunk key: [region file, index]} of the earliest backed-up state from {@code date} on: the
     * chunk's state at the start of that day. Briefly cached; the mod only ever adds backups.
     */
    public synchronized Map<Long, Object[]> index(String server, String dim, String date) {
        List<Object> key = List.of(server, dim, date);
        Object[] hit = idxCache.get(key);
        if (hit != null && Stores.mono() - (double) hit[0] < 10.0) {
            @SuppressWarnings("unchecked")
            Map<Long, Object[]> m = (Map<Long, Object[]>) hit[1];
            return m;
        }
        Map<Long, Object[]> first = new LinkedHashMap<>();
        for (String d : dates(server, dim)) {
            if (d.compareTo(date) < 0) {
                continue;
            }
            Path rdir = dir(server, d, dim);
            for (String n : Recording.list(rdir)) {
                Matcher m = Recording.REGION_RE.matcher(n);
                if (!m.matches()) {
                    continue;
                }
                Path p = rdir.resolve(n);
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                for (int idx : new Region(p, rx, rz).chunkIndices()) {
                    first.putIfAbsent(ck(rx * 32 + (idx & 31), rz * 32 + (idx >> 5)), new Object[] {p, idx});
                }
            }
        }
        if (idxCache.size() > 40) {
            idxCache.clear();
        }
        idxCache.put(key, new Object[] {Stores.mono(), first});
        return first;
    }

    static long ck(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    /** The chunk as it was at the start of {@code date} (today's state if it has not changed since). */
    public Chunk chunkAt(Recording rec, Recording.Dimension dim, String date, int cx, int cz) {
        Object[] src = index(dim.server, dim.key, date).get(ck(cx, cz));
        if (src == null) {
            return rec.chunk(dim, cx, cz);
        }
        Path path = (Path) src[0];
        int idx = (Integer) src[1];
        List<Object> key = List.of(path.toString(), idx, mtime(path));
        synchronized (chunks) {
            if (chunks.containsKey(key)) {
                return chunks.get(key);
            }
        }
        Matcher m = Recording.REGION_RE.matcher(path.getFileName().toString());
        m.matches();
        Map<String, Object> rootNbt = new Region(path, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))).readNbt(idx);
        Chunk hit = rootNbt == null ? null : Chunk.fromNbt(rootNbt, cx, cz);
        synchronized (chunks) {
            if (chunks.size() > 512) {
                chunks.clear();
            }
            chunks.put(key, hit);
        }
        return hit;
    }

    static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    /** Evenly spread sample of n of the points (like numpy's linspace over the indices). */
    static List<Integer> sample(List<int[]> pts, int n) {
        List<Integer> out = new ArrayList<>();
        int total = pts.size();
        if (total == 0) {
            return out;
        }
        if (total <= n) {
            for (int[] p : pts) {
                out.add(p[0]);
                out.add(p[1]);
                out.add(p[2]);
            }
            return out;
        }
        double step = n > 1 ? (double) (total - 1) / (n - 1) : 0;
        for (int i = 0; i < n; i++) {
            int k = i == n - 1 ? total - 1 : (int) (i * step);
            int[] p = pts.get(k);
            out.add(p[0]);
            out.add(p[1]);
            out.add(p[2]);
        }
        return out;
    }

    public Map<String, Object> diff(Recording rec, Recording.Dimension dim, String server, String dimKey, String date) {
        int maxPoints = 80000;
        List<String> dates = new ArrayList<>();
        for (String d : dates(server, dimKey)) {
            if (d.compareTo(date) >= 0) {
                dates.add(d);
            }
        }
        // Chunk -> (region, index) of the earliest state from the chosen day on
        Map<Long, Object[]> first = new LinkedHashMap<>();
        List<Object> stamp = new ArrayList<>();
        for (String d : dates) {
            Path rdir = dir(server, d, dimKey);
            for (String n : Recording.list(rdir)) {
                Matcher m = Recording.REGION_RE.matcher(n);
                if (!m.matches()) {
                    continue;
                }
                Path p = rdir.resolve(n);
                stamp.add(p.toString());
                stamp.add(mtime(p));
                int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                Region reg = new Region(p, rx, rz);
                for (int idx : reg.chunkIndices()) {
                    first.putIfAbsent(ck(rx * 32 + (idx & 31), rz * 32 + (idx >> 5)), new Object[] {reg, idx});
                }
            }
        }
        List<Object> key = List.of(server, dimKey, date, stamp, System.currentTimeMillis() / 5000);
        synchronized (diffCache) {
            Map<String, Object> hit = diffCache.get(key);
            if (hit != null) {
                return hit;
            }
        }
        List<int[]> added = new ArrayList<>(), removed = new ArrayList<>(), changed = new ArrayList<>();
        List<Map<String, Object>> perChunk = new ArrayList<>();
        long tAdd = 0, tRem = 0, tChg = 0;
        Map<Integer, Integer> baseIds = new HashMap<>();
        Map<String, Integer> seen = new HashMap<>();
        for (Map.Entry<Long, Object[]> e : first.entrySet()) {
            int cx = (int) (e.getKey() >> 32), cz = (int) (long) e.getKey();
            Region reg = (Region) e.getValue()[0];
            Map<String, Object> rootNbt = reg.readNbt((Integer) e.getValue()[1]);
            Chunk old = rootNbt == null ? null : Chunk.fromNbt(rootNbt, cx, cz);
            Chunk now = rec.chunk(dim, cx, cz);
            if (old == null || now == null) {
                continue;
            }
            int y0 = Math.max(old.minY, now.minY);
            int y1 = Math.min(old.minY + old.height, now.minY + now.height);
            if (y1 <= y0) {
                continue;
            }
            int a = 0, r = 0, c = 0;
            List<int[]> pa = new ArrayList<>(), pr = new ArrayList<>(), pc = new ArrayList<>();
            for (int y = y0; y < y1; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int ia = old.id(y - old.minY, z, x), ib = now.id(y - now.minY, z, x);
                        if (ia == ib) {
                            continue;
                        }
                        boolean oa = air(ia), na = air(ib);
                        int[] pt = {x + cx * 16, y, z + cz * 16};
                        if (oa && !na) {
                            a++;
                            pa.add(pt);
                        } else if (!oa && na) {
                            r++;
                            pr.add(pt);
                        } else if (!oa && base(ia, baseIds, seen) != base(ib, baseIds, seen)) {
                            c++;
                            pc.add(pt);
                        }
                    }
                }
            }
            if (a == 0 && r == 0 && c == 0) {
                continue;
            }
            perChunk.add(J.map("cx", cx, "cz", cz, "added", a, "removed", r, "changed", c));
            tAdd += a;
            tRem += r;
            tChg += c;
            added.addAll(pa);
            removed.addAll(pr);
            changed.addAll(pc);
        }
        perChunk.sort((p, q) -> Integer.compare(sum(q), sum(p)));
        Map<String, Object> res = J.map("date", date, "dates", dates,
                "totals", J.map("added", tAdd, "removed", tRem, "changed", tChg),
                "chunks", perChunk.subList(0, Math.min(500, perChunk.size())),
                "points", J.map("added", sample(added, maxPoints / 3), "removed", sample(removed, maxPoints / 3),
                        "changed", sample(changed, maxPoints / 3)));
        synchronized (diffCache) {
            if (diffCache.size() > 8) {
                diffCache.clear();
            }
            diffCache.put(key, res);
        }
        return res;
    }

    private static int sum(Map<String, Object> m) {
        return (Integer) m.get("added") + (Integer) m.get("removed") + (Integer) m.get("changed");
    }

    private static boolean air(int id) {
        return AIR.contains(Chunk.baseName(Chunk.BLOCKS.name(id)));
    }

    private static int base(int id, Map<Integer, Integer> cache, Map<String, Integer> seen) {
        return cache.computeIfAbsent(id, k -> seen.computeIfAbsent(Chunk.baseName(Chunk.BLOCKS.name(k)), n -> seen.size()));
    }
}
