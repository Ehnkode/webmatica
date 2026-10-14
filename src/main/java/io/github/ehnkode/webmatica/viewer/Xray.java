package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.Chunk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * X-ray: a chunk with only the searched blocks, everything else becomes air. The 3D view gets
 * the textured searched blocks plus a trailer with their positions:
 * <pre>
 *  "XRY1"  uint32 m   uint32 count[m]           finds per requested name
 *          uint32 n   int16 [x, y, z, k] * n    at most MAX_POINTS, lowest first
 * </pre>
 */
final class Xray {
    static final String TRAILER = "XRY1";
    static final int MAX_NAMES = 64;
    /** per chunk; searching for stone should not send millions of boxes */
    static final int MAX_POINTS = 2048;

    private Xray() {
    }

    /** 'diamond_ore,minecraft:chest' -> sorted names without air. */
    static List<String> parseNames(String raw) {
        TreeSet<String> out = new TreeSet<>();
        for (String n : raw.split(",", -1)) {
            n = n.strip().toLowerCase(java.util.Locale.ROOT);
            if (n.isEmpty()) {
                continue;
            }
            if (!n.contains(":")) {
                n = "minecraft:" + n;
            }
            if (!Chunk.AIR_NAMES.contains(n)) {
                out.add(n);
            }
        }
        if (out.size() > MAX_NAMES) {
            throw new IllegalArgumentException("höchstens " + MAX_NAMES + " Blöcke gleichzeitig");
        }
        return new ArrayList<>(out);
    }

    /** Block state id -> index into names + 1 (0 = not searched). Grows with the registry. */
    static final class Lookup {
        final Map<String, Integer> index = new HashMap<>();
        volatile short[] lut = new short[0];

        Lookup(List<String> names) {
            for (int i = 0; i < names.size(); i++) {
                index.put(names.get(i), i + 1);
            }
        }

        int get(int id) {
            short[] l = lut;
            if (id >= l.length) {
                synchronized (this) {
                    String[] names = Chunk.BLOCKS.names();
                    l = new short[Math.max(names.length, id + 1)];
                    for (int i = 0; i < names.length; i++) {
                        l[i] = (short) (int) index.getOrDefault(Chunk.baseName(names[i]), 0);
                    }
                    lut = l;
                }
            }
            return l[id];
        }
    }

    private static final Map<List<String>, Lookup> LOOKUPS = new HashMap<>();

    static synchronized Lookup lookup(List<String> names) {
        return LOOKUPS.computeIfAbsent(List.copyOf(names), Lookup::new);
    }

    /** Copy of the chunk with all other blocks as air and without light data. */
    static Chunk only(Chunk ch, Lookup look) {
        if (ch == null) {
            return null;
        }
        char[] b = new char[ch.blocks.length];
        for (int i = 0; i < b.length; i++) {
            b[i] = look.get(ch.blocks[i]) > 0 ? ch.blocks[i] : 0;
        }
        return new Chunk(ch.cx, ch.cz, ch.minY, ch.height, b, ch.biomes, ch.blockEntities, Map.of());
    }

    static byte[] trailer(Chunk ch, Lookup look, int m) {
        long[] counts = new long[m];
        List<short[]> pts = new ArrayList<>();
        for (int i = 0; i < ch.blocks.length; i++) {
            int k = look.get(ch.blocks[i]);
            if (k == 0) {
                continue;
            }
            if (k - 1 < m) {
                counts[k - 1]++;
            }
            if (pts.size() < MAX_POINTS) {
                int y = i >> 8, z = (i >> 4) & 15, x = i & 15;
                pts.add(new short[] {(short) x, (short) (y + ch.minY), (short) z, (short) (k - 1)});
            }
        }
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + 4 * m + 4 + 8 * pts.size()).order(ByteOrder.LITTLE_ENDIAN);
        b.put(TRAILER.getBytes(StandardCharsets.US_ASCII));
        b.putInt(m);
        for (long c : counts) {
            b.putInt((int) c);
        }
        b.putInt(pts.size());
        for (short[] p : pts) {
            for (short v : p) {
                b.putShort(v);
            }
        }
        return b.array();
    }

    /** [counts per name (long[]), x,y,z,k,... in world coordinates (int[])] for the 2D map. */
    static Object[] finds(Chunk ch, Lookup look, int m) {
        long[] counts = new long[m];
        if (ch == null) {
            return new Object[] {counts, new int[0]};
        }
        int[] pts = new int[MAX_POINTS * 4];
        int n = 0;
        for (int i = 0; i < ch.blocks.length; i++) {
            int k = look.get(ch.blocks[i]);
            if (k == 0) {
                continue;
            }
            if (k - 1 < m) {
                counts[k - 1]++;
            }
            if (n < MAX_POINTS) {
                pts[n * 4] = (i & 15) + ch.cx * 16;
                pts[n * 4 + 1] = (i >> 8) + ch.minY;
                pts[n * 4 + 2] = ((i >> 4) & 15) + ch.cz * 16;
                pts[n * 4 + 3] = k - 1;
                n++;
            }
        }
        return new Object[] {counts, java.util.Arrays.copyOf(pts, n * 4)};
    }
}
