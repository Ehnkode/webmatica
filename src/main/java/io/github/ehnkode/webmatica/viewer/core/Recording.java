package io.github.ehnkode.webmatica.viewer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The recording folder: server -> dimensions -> regions -> chunks.
 *
 * <pre>
 * webmatica_recording/
 *   2b2t.org/
 *     region/           Overworld
 *     DIM-1/region/     Nether
 *     DIM1/region/      End
 *   region/             old recording from the first mod version (mixed)
 * </pre>
 */
public final class Recording {
    public static final Map<String, String> DIMENSIONS = new LinkedHashMap<>();
    public static final Map<String, String> DIM_LABELS = Map.of("overworld", "Overworld", "the_nether", "Nether", "the_end", "End");
    public static final String LEGACY_SERVER = "_alt";
    public static final Pattern REGION_RE = Pattern.compile("^r\\.(-?\\d+)\\.(-?\\d+)\\.mca$");

    static {
        DIMENSIONS.put("overworld", "");
        DIMENSIONS.put("the_nether", "DIM-1");
        DIMENSIONS.put("the_end", "DIM1");
    }

    public final Path root;
    public final LruCache<Object, Chunk> cache = new LruCache<>(768);
    private final Map<String, Dimension> dims = new LinkedHashMap<>();

    public Recording(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** A region/ folder: one dimension of one server. */
    public static final class Dimension {
        public final String server;
        public final String key;
        public final Path regionDir;
        private final Map<Long, Region> regions = new HashMap<>();

        Dimension(String server, String key, Path regionDir) {
            this.server = server;
            this.key = key;
            this.regionDir = regionDir;
        }

        public String id() {
            return server + "/" + key;
        }

        private static long k(int rx, int rz) {
            return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
        }

        /** Current regions; new files are detected on every call. */
        public List<Region> regions() {
            synchronized (regions) {
                Set<Long> seen = new HashSet<>();
                for (String n : list(regionDir)) {
                    Matcher m = REGION_RE.matcher(n);
                    if (!m.matches()) {
                        continue;
                    }
                    int rx = Integer.parseInt(m.group(1)), rz = Integer.parseInt(m.group(2));
                    long key = k(rx, rz);
                    seen.add(key);
                    regions.computeIfAbsent(key, x -> new Region(regionDir.resolve(n), rx, rz));
                }
                regions.keySet().retainAll(seen);
                return new ArrayList<>(regions.values());
            }
        }

        public Region region(int rx, int rz) {
            Region r;
            synchronized (regions) {
                r = regions.get(k(rx, rz));
            }
            if (r == null && Files.exists(regionDir.resolve("r." + rx + "." + rz + ".mca"))) {
                regions();
                synchronized (regions) {
                    r = regions.get(k(rx, rz));
                }
            }
            return r;
        }
    }

    /** Sorted file names in a folder, empty when it does not exist. */
    public static List<String> list(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            return new ArrayList<>(new TreeSet<>(s.map(p -> p.getFileName().toString()).toList()));
        } catch (IOException e) {
            return new ArrayList<>();
        }
    }

    /** All servers/dimensions with a region/ folder. Newly created ones are detected. */
    public List<Dimension> dimensions() {
        Map<String, Object[]> found = new LinkedHashMap<>();
        Path legacy = root.resolve("region");
        if (Files.isDirectory(legacy)) {
            found.put(LEGACY_SERVER + "/overworld", new Object[] {LEGACY_SERVER, "overworld", legacy});
        }
        for (String s : list(root)) {
            Path sdir = root.resolve(s);
            if (s.equals("region") || s.startsWith(".") || !Files.isDirectory(sdir)) {
                continue;
            }
            for (Map.Entry<String, String> e : DIMENSIONS.entrySet()) {
                Path rdir = e.getValue().isEmpty() ? sdir.resolve("region") : sdir.resolve(e.getValue()).resolve("region");
                if (Files.isDirectory(rdir)) {
                    found.put(s + "/" + e.getKey(), new Object[] {s, e.getKey(), rdir});
                }
            }
            Path custom = sdir.resolve("dimensions");
            if (Files.isDirectory(custom)) {
                for (String ns : list(custom)) {
                    for (String p : list(custom.resolve(ns))) {
                        Path rdir = custom.resolve(ns).resolve(p).resolve("region");
                        if (Files.isDirectory(rdir)) {
                            String key = ns + ":" + p;
                            found.put(s + "/" + key, new Object[] {s, key, rdir});
                        }
                    }
                }
            }
        }
        synchronized (dims) {
            for (Map.Entry<String, Object[]> e : found.entrySet()) {
                Object[] v = e.getValue();
                dims.computeIfAbsent(e.getKey(), x -> new Dimension((String) v[0], (String) v[1], (Path) v[2]));
            }
            dims.keySet().retainAll(found.keySet());
            return new ArrayList<>(dims.values());
        }
    }

    public Dimension dimension(String server, String key) {
        String did = server + "/" + key;
        Dimension d;
        synchronized (dims) {
            d = dims.get(did);
        }
        if (d == null) {
            dimensions();
            synchronized (dims) {
                d = dims.get(did);
            }
        }
        return d;
    }

    /** Uncached chunk NBT (for exporting the block entities). */
    public Map<String, Object> rawChunk(Dimension dim, int cx, int cz) {
        Region r = dim.region(cx >> 5, cz >> 5);
        return r == null ? null : r.readNbt((cx & 31) | ((cz & 31) << 5));
    }

    public Chunk chunk(Dimension dim, int cx, int cz) {
        Region region = dim.region(cx >> 5, cz >> 5);
        if (region == null) {
            return null;
        }
        Region.Header h = region.header();
        if (h == null) {
            return null;
        }
        int idx = (cx & 31) | ((cz & 31) << 5);
        if (h.offsets[idx] == 0) {
            return null;
        }
        List<Object> key = List.of(region.path.toString(), idx, h.timestamps[idx], h.offsets[idx]);
        Chunk ch = cache.get(key);
        if (ch == null) {
            Map<String, Object> root = region.readNbt(idx);
            if (root == null) {
                return null;
            }
            ch = Chunk.fromNbt(root, cx, cz);
            if (ch == null) {
                return null;
            }
            cache.put(key, ch);
        }
        return ch;
    }

    /** Nothing recorded where something was asked for. */
    public static final class NothingRecorded extends LookupError {
        public NothingRecorded(String msg) {
            super(msg);
        }
    }
}
