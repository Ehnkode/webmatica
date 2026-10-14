package io.github.ehnkode.webmatica.viewer.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * A decoded chunk: block states and biomes as process-wide unique ids ({@link #BLOCKS},
 * {@link #BIOMES}), so colors, shapes etc. are lookup tables indexed by id.
 *
 * <p>Layout: {@code blocks[(y * 16 + z) * 16 + x]} with y counted from {@link #minY},
 * {@code biomes[(y4 * 4 + z4) * 4 + x4]}.
 */
public final class Chunk {
    public static final Set<String> AIR_NAMES = Set.of(
            "minecraft:air", "minecraft:cave_air", "minecraft:void_air",
            "minecraft:structure_void", "minecraft:light", "minecraft:barrier");
    /** Liquids and plants a placed block simply replaces: never "in the way". */
    public static final Pattern REPLACEABLE = Pattern.compile("^minecraft:(water|lava|bubble_column|short_grass|tall_grass|fern|large_fern|dead_bush|snow|"
            + "seagrass|tall_seagrass|kelp|kelp_plant|vine|glow_lichen)$");

    /** Block states as "minecraft:oak_stairs[facing=north,half=bottom]". Id 0 = air. */
    public static final Registry BLOCKS = new Registry("minecraft:air");
    /** Biomes as "minecraft:plains". Id 0 = plains (vanilla default). */
    public static final Registry BIOMES = new Registry("minecraft:plains");

    public final int cx, cz;
    public final int minY;
    public final int height;
    public final char[] blocks;
    public final char[] biomes;
    public final List<Map<String, Object>> blockEntities;
    /** Light per section index from the bottom: {block light, sky light}, each raw 2048 bytes or null. */
    public final Map<Integer, byte[][]> lightRaw;

    public Chunk(int cx, int cz, int minY, int height, char[] blocks, char[] biomes,
                 List<Map<String, Object>> blockEntities, Map<Integer, byte[][]> lightRaw) {
        this.cx = cx;
        this.cz = cz;
        this.minY = minY;
        this.height = height;
        this.blocks = blocks;
        this.biomes = biomes;
        this.blockEntities = blockEntities;
        this.lightRaw = lightRaw;
    }

    public int biomeLayers() {
        return biomes.length / 16;
    }

    public int id(int ly, int z, int x) {
        return blocks[(ly * 16 + z) * 16 + x];
    }

    public int biome(int y, int z, int x) {
        int by = Math.max(0, Math.min(biomeLayers() - 1, (y - minY) >> 2));
        return biomes[(by * 4 + (z >> 2)) * 4 + (x >> 2)];
    }

    public String block(int x, int y, int z) {
        int ly = y - minY;
        if (ly < 0 || ly >= height) {
            return "minecraft:air";
        }
        return BLOCKS.name(blocks[(ly * 16 + (z & 15)) * 16 + (x & 15)]);
    }

    /**
     * {block light, sky light} per block like {@link #blocks}, or null without light data.
     * Sections without sky light count as fully lit, those without block light as dark.
     */
    public byte[][] light() {
        if (lightRaw.isEmpty()) {
            return null;
        }
        byte[] bl = new byte[blocks.length];
        byte[] sl = new byte[blocks.length];
        java.util.Arrays.fill(sl, (byte) 15);
        for (Map.Entry<Integer, byte[][]> e : lightRaw.entrySet()) {
            int base = e.getKey() * 4096;
            if (base < 0 || base + 4096 > blocks.length) {
                continue;
            }
            if (e.getValue()[0] != null) {
                nibbles(e.getValue()[0], bl, base);
            }
            if (e.getValue()[1] != null) {
                nibbles(e.getValue()[1], sl, base);
            }
        }
        return new byte[][] {bl, sl};
    }

    private static void nibbles(byte[] raw, byte[] out, int base) {
        for (int i = 0; i < 2048; i++) {
            out[base + 2 * i] = (byte) (raw[i] & 15);
            out[base + 2 * i + 1] = (byte) ((raw[i] >> 4) & 15);
        }
    }

    // ------------------------------------------------------------------ state strings

    /** Classic {Name, Properties}; since 26.1 also a bare name, {id, properties} and {"": name}. */
    public static String stateKey(Object entry) {
        if (entry instanceof String s) {
            return s;
        }
        Map<String, Object> m = Nbt.map(entry);
        if (m == null) {
            return "minecraft:air";
        }
        if (m.containsKey("")) {
            return stateKey(m.get(""));
        }
        Object name = m.get("Name");
        if (name == null || "".equals(name)) {
            name = m.get("id");
        }
        if (name == null || "".equals(name)) {
            name = "minecraft:air";
        }
        Map<String, Object> props = Nbt.map(m.get("Properties"));
        if (props == null || props.isEmpty()) {
            props = Nbt.map(m.get("properties"));
        }
        if (props == null || props.isEmpty()) {
            return name.toString();
        }
        return stateKey(name.toString(), props);
    }

    public static String stateKey(String name, Map<String, ?> props) {
        if (props.isEmpty()) {
            return name;
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, ?> e : new TreeMap<>(props).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.append(']').toString();
    }

    public static String baseName(String state) {
        int i = state.indexOf('[');
        return i < 0 ? state : state.substring(0, i);
    }

    /** "minecraft:oak_stairs" -> "oak_stairs" */
    public static String shortName(String name) {
        int i = name.indexOf(':');
        return i < 0 ? name : name.substring(i + 1);
    }

    public static Map<String, String> stateProps(String state) {
        int i = state.indexOf('[');
        if (i < 0) {
            return new HashMap<>();
        }
        Map<String, String> out = new HashMap<>();
        String body = state.substring(i + 1, state.endsWith("]") ? state.length() - 1 : state.length());
        for (String kv : body.split(",", -1)) {
            int e = kv.indexOf('=');
            if (e < 0) {
                out.put(kv, "");
            } else {
                out.put(kv.substring(0, e), kv.substring(e + 1));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ decoding

    /** Vanilla bit packing (since 1.16 values don't span longs). */
    static int[] unpack(long[] data, int bits, int count) {
        int[] out = new int[count];
        if (bits == 0 || data == null || data.length == 0) {
            return out;
        }
        int perLong = 64 / bits;
        long mask = (1L << bits) - 1;
        int i = 0;
        for (long l : data) {
            for (int k = 0; k < perLong && i < count; k++) {
                out[i++] = (int) ((l >>> (k * bits)) & mask);
            }
            if (i >= count) {
                break;
            }
        }
        return out;
    }

    static int bitLength(int v) {
        return 32 - Integer.numberOfLeadingZeros(v);
    }

    public static Chunk fromNbt(Map<String, Object> root, Integer cx, Integer cz) {
        List<Object> sections = Nbt.list(root.get("sections"));
        int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
        for (Object o : sections) {
            Map<String, Object> s = Nbt.map(o);
            if (s != null && s.get("Y") instanceof Number y) {
                lo = Math.min(lo, y.intValue());
                hi = Math.max(hi, y.intValue());
            }
        }
        if (lo > hi) {
            return null;
        }
        int n = hi - lo + 1;
        char[] blocks = new char[n * 4096];
        char[] biomes = new char[n * 64];
        Map<Integer, byte[][]> light = new HashMap<>();
        for (Object o : sections) {
            Map<String, Object> s = Nbt.map(o);
            if (s == null || !(s.get("Y") instanceof Number y)) {
                continue;
            }
            int i = y.intValue() - lo;
            Map<String, Object> bs = Nbt.map(s.get("block_states"));
            if (bs != null && !bs.isEmpty()) {
                List<Object> pal = Nbt.list(bs.get("palette"));
                if (!pal.isEmpty()) {
                    int[] lut = new int[pal.size()];
                    for (int k = 0; k < lut.length; k++) {
                        lut[k] = BLOCKS.id(stateKey(pal.get(k)));
                    }
                    int bits = pal.size() == 1 ? 0 : Math.max(4, bitLength(pal.size() - 1));
                    int[] idx = unpack(bs.get("data") instanceof long[] l ? l : null, bits, 4096);
                    int base = i * 4096;
                    for (int k = 0; k < 4096; k++) {
                        blocks[base + k] = (char) lut[Math.min(idx[k], lut.length - 1)];
                    }
                }
            }
            byte[] bl = raw(s.get("BlockLight")), sl = raw(s.get("SkyLight"));
            if (bl != null || sl != null) {
                light.put(i, new byte[][] {bl, sl});
            }
            Map<String, Object> bm = Nbt.map(s.get("biomes"));
            if (bm != null && !bm.isEmpty()) {
                List<Object> pal = Nbt.list(bm.get("palette"));
                if (!pal.isEmpty()) {
                    int[] lut = new int[pal.size()];
                    for (int k = 0; k < lut.length; k++) {
                        Object p = pal.get(k);
                        String key = p instanceof String ps ? ps : String.valueOf(Nbt.map(p) != null ? Nbt.map(p).getOrDefault("", "") : "");
                        lut[k] = BIOMES.id(key);
                    }
                    int bits = pal.size() == 1 ? 0 : bitLength(pal.size() - 1);
                    int[] idx = unpack(bm.get("data") instanceof long[] l ? l : null, bits, 64);
                    int base = i * 64;
                    for (int k = 0; k < 64; k++) {
                        biomes[base + k] = (char) lut[Math.min(idx[k], lut.length - 1)];
                    }
                }
            }
        }
        List<Map<String, Object>> bes = new ArrayList<>();
        for (Object o : Nbt.list(root.get("block_entities"))) {
            Map<String, Object> m = Nbt.map(o);
            if (m != null) {
                bes.add(m);
            }
        }
        int x = cx != null ? cx : (int) Nbt.num(root.get("xPos"), 0);
        int z = cz != null ? cz : (int) Nbt.num(root.get("zPos"), 0);
        return new Chunk(x, z, lo * 16, n * 16, blocks, biomes, bes, light);
    }

    private static byte[] raw(Object arr) {
        return arr instanceof byte[] b && b.length == 2048 ? b : null;
    }
}
