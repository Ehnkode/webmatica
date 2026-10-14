package io.github.ehnkode.webmatica.viewer.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Schematics: loading (Sponge .schem v1-v3, Litematica .litematic, vanilla structure .nbt),
 * rotating/mirroring, material list, comparison with the world, and writing Sponge v3.
 * Internally a schematic is a cuboid of global block ids {@code blocks[(y * L + z) * W + x]}.
 */
public final class Schematic {
    public static final long MAX_VOLUME = 64L * 1024 * 1024;

    public final String name;
    public final int w, h, l;
    public final char[] blocks;
    /** {"pos": int[3], "id": String, "data": Map} */
    public final List<Map<String, Object>> blockEntities;
    /** Position of corner blocks[0] in the file's coordinates (Litematica regions can be negative). */
    public final int[] offset;

    public Schematic(String name, int w, int h, int l, char[] blocks, List<Map<String, Object>> bes, int[] offset) {
        this.name = name;
        this.w = w;
        this.h = h;
        this.l = l;
        this.blocks = blocks;
        this.blockEntities = bes;
        this.offset = offset;
    }

    public Schematic(String name, int w, int h, int l, char[] blocks) {
        this(name, w, h, l, blocks, new ArrayList<>(), new int[3]);
    }

    public int[] size() {
        return new int[] {w, h, l};
    }

    public int at(int x, int y, int z) {
        return blocks[(y * l + z) * w + x];
    }

    // ------------------------------------------------------------------ loading

    static byte[] decompress(byte[] data) {
        try {
            if (data.length >= 2 && (data[0] & 255) == 0x1f && (data[1] & 255) == 0x8b) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
                    return in.readAllBytes();
                }
            }
            try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
                return in.readAllBytes();
            } catch (IOException e) {
                return data;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("nicht entpackbar: " + e.getMessage());
        }
    }

    /** 'stone' / 'minecraft:oak_stairs[half=bottom,facing=north]' -> canonical key. */
    public static String normState(String s) {
        s = s.strip();
        int i = s.indexOf('[');
        String name = i < 0 ? s : s.substring(0, i);
        String rest = i < 0 ? "" : s.substring(i + 1);
        if (!name.contains(":")) {
            name = "minecraft:" + name;
        }
        if (rest.isEmpty()) {
            return name;
        }
        while (rest.endsWith("]")) {
            rest = rest.substring(0, rest.length() - 1);
        }
        Map<String, String> props = new LinkedHashMap<>();
        for (String kv : rest.split(",", -1)) {
            if (kv.isEmpty()) {
                continue;
            }
            int e = kv.indexOf('=');
            props.put(e < 0 ? kv : kv.substring(0, e), e < 0 ? "" : kv.substring(e + 1));
        }
        return Chunk.stateKey(name, props);
    }

    static int[] varints(byte[] b, int count) {
        int[] out = new int[count];
        int k = 0, v = 0, shift = 0;
        for (byte x : b) {
            if (k >= count) {
                break;
            }
            v |= (x & 0x7F) << shift;
            if ((x & 0x80) == 0) {
                out[k++] = v;
                v = 0;
                shift = 0;
            } else {
                shift += 7;
            }
        }
        return out;
    }

    static void checkVolume(long w, long h, long l) {
        if (w <= 0 || h <= 0 || l <= 0) {
            throw new IllegalArgumentException("leere Schematic");
        }
        if (w * h * l > MAX_VOLUME) {
            throw new IllegalArgumentException("Schematic zu gross (" + w + "x" + h + "x" + l + ")");
        }
    }

    private static Map<String, Object> without(Map<String, Object> m, Set<String> skip) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (!skip.contains(k)) {
                out.put(k, v);
            }
        });
        return out;
    }

    private static Map<String, Object> be(int x, int y, int z, String id, Map<String, Object> data) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("pos", new int[] {x, y, z});
        e.put("id", id);
        e.put("data", data);
        return e;
    }

    static Schematic sponge(Map<String, Object> root, String name) {
        if (Nbt.map(root.get("Schematic")) != null) {
            root = Nbt.map(root.get("Schematic"));
        }
        int w = (int) Nbt.num(root.get("Width"), 0) & 0xFFFF, h = (int) Nbt.num(root.get("Height"), 0) & 0xFFFF,
                l = (int) Nbt.num(root.get("Length"), 0) & 0xFFFF;
        checkVolume(w, h, l);
        Map<String, Object> pal;
        Object data;
        List<Object> bes;
        Map<String, Object> blocksTag = Nbt.map(root.get("Blocks"));
        if (blocksTag != null) {                                   // v3
            pal = Nbt.map(blocksTag.get("Palette"));
            data = blocksTag.get("Data");
            bes = Nbt.list(blocksTag.get("BlockEntities"));
        } else {                                                    // v1/v2
            pal = Nbt.map(root.get("Palette"));
            data = root.get("BlockData");
            bes = root.containsKey("BlockEntities") ? Nbt.list(root.get("BlockEntities")) : Nbt.list(root.get("TileEntities"));
        }
        if (pal == null) {
            pal = new HashMap<>();
        }
        if (!(data instanceof byte[] raw)) {
            throw new IllegalArgumentException("MCEdit-.schematic (alte Block-IDs) wird nicht unterstuetzt");
        }
        int max = 0;
        for (Object v : pal.values()) {
            max = Math.max(max, (int) Nbt.num(v, 0));
        }
        char[] lut = new char[max + 1];
        for (Map.Entry<String, Object> e : pal.entrySet()) {
            lut[(int) Nbt.num(e.getValue(), 0)] = (char) Chunk.BLOCKS.id(normState(e.getKey()));
        }
        int n = w * h * l;
        int[] idx = varints(raw, n);
        char[] blocks = new char[n];
        for (int i = 0; i < n; i++) {
            blocks[i] = lut[Math.max(0, Math.min(lut.length - 1, idx[i]))];
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object o : bes) {
            Map<String, Object> b = Nbt.map(o);
            if (b == null || !(b.get("Pos") instanceof int[] pos) || pos.length < 3) {
                continue;
            }
            Map<String, Object> d = Nbt.map(b.get("Data"));
            if (d == null) {
                d = without(b, Set.of("Pos", "Id"));
            }
            out.add(be(pos[0], pos[1], pos[2], String.valueOf(b.getOrDefault("Id", "")), d));
        }
        return new Schematic(name, w, h, l, blocks, out, new int[3]);
    }

    /** Litematica bit packing: values may cross long boundaries. */
    static int[] unpackSpanning(long[] longs, int bits, int count) {
        int[] out = new int[count];
        if (longs == null) {
            return out;
        }
        long mask = (1L << bits) - 1;
        for (int i = 0; i < count; i++) {
            long start = (long) i * bits;
            int li = (int) (start >> 6), off = (int) (start & 63);
            long lo = li < longs.length ? longs[li] >>> off : 0;
            long hi = 0;
            if (off + bits > 64 && li + 1 < longs.length) {
                hi = longs[li + 1] << (64 - off);
            }
            out[i] = (int) ((lo | hi) & mask);
        }
        return out;
    }

    static Schematic litematic(Map<String, Object> root, String name) {
        Map<String, Object> regions = Nbt.map(root.get("Regions"));
        if (regions == null || regions.isEmpty()) {
            throw new IllegalArgumentException("Litematic ohne Regionen");
        }
        List<Object[]> boxes = new ArrayList<>();
        for (Object ro : regions.values()) {
            Map<String, Object> r = Nbt.map(ro);
            Map<String, Object> p = Nbt.map(r.get("Position")), s = Nbt.map(r.get("Size"));
            int sx = (int) Nbt.num(s.get("x"), 0), sy = (int) Nbt.num(s.get("y"), 0), sz = (int) Nbt.num(s.get("z"), 0);
            int px = (int) Nbt.num(p.get("x"), 0), py = (int) Nbt.num(p.get("y"), 0), pz = (int) Nbt.num(p.get("z"), 0);
            int[] lo = {px + Math.min(sx + 1, 0), py + Math.min(sy + 1, 0), pz + Math.min(sz + 1, 0)};
            boxes.add(new Object[] {r, lo, new int[] {Math.abs(sx), Math.abs(sy), Math.abs(sz)}});
        }
        int[] mn = new int[3], mx = new int[3];
        for (int i = 0; i < 3; i++) {
            mn[i] = Integer.MAX_VALUE;
            mx[i] = Integer.MIN_VALUE;
            for (Object[] b : boxes) {
                mn[i] = Math.min(mn[i], ((int[]) b[1])[i]);
                mx[i] = Math.max(mx[i], ((int[]) b[1])[i] + ((int[]) b[2])[i]);
            }
        }
        int w = mx[0] - mn[0], h = mx[1] - mn[1], l = mx[2] - mn[2];
        checkVolume(w, h, l);
        char[] blocks = new char[w * h * l];
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] b : boxes) {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = (Map<String, Object>) b[0];
            int[] lo = (int[]) b[1], sz = (int[]) b[2];
            int bw = sz[0], bh = sz[1], bl = sz[2];
            List<Object> pal = Nbt.list(r.get("BlockStatePalette"));
            char[] lut = new char[Math.max(1, pal.size())];
            for (int i = 0; i < pal.size(); i++) {
                lut[i] = (char) Chunk.BLOCKS.id(Chunk.stateKey(pal.get(i)));
            }
            int bits = Math.max(2, Chunk.bitLength(pal.size() - 1));
            int[] idx = unpackSpanning(r.get("BlockStates") instanceof long[] la ? la : null, bits, bw * bh * bl);
            int ox = lo[0] - mn[0], oy = lo[1] - mn[1], oz = lo[2] - mn[2];
            for (int y = 0; y < bh; y++) {
                for (int z = 0; z < bl; z++) {
                    for (int x = 0; x < bw; x++) {
                        char v = lut[Math.min(idx[(y * bl + z) * bw + x], lut.length - 1)];
                        if (v != 0) {
                            blocks[((oy + y) * l + oz + z) * w + ox + x] = v;
                        }
                    }
                }
            }
            for (Object to : Nbt.list(r.get("TileEntities"))) {
                Map<String, Object> te = Nbt.map(to);
                if (te == null) {
                    continue;
                }
                Integer x = Nbt.intOrNull(te.get("x")), y = Nbt.intOrNull(te.get("y")), z = Nbt.intOrNull(te.get("z"));
                if (x == null || y == null || z == null) {
                    continue;
                }
                out.add(be(x + ox, y + oy, z + oz, String.valueOf(te.getOrDefault("id", "")), without(te, Set.of("x", "y", "z", "id"))));
            }
        }
        Map<String, Object> meta = Nbt.map(root.get("Metadata"));
        Object mname = meta == null ? null : meta.get("Name");
        return new Schematic(mname != null && !"".equals(mname) ? mname.toString() : name, w, h, l, blocks, out, mn);
    }

    static Schematic structure(Map<String, Object> root, String name) {
        Object sizeO = root.get("size");
        int w, h, l;
        if (sizeO instanceof int[] s) {
            w = s[0];
            h = s[1];
            l = s[2];
        } else {
            List<Object> s = Nbt.list(sizeO);
            w = (int) Nbt.num(s.get(0), 0);
            h = (int) Nbt.num(s.get(1), 0);
            l = (int) Nbt.num(s.get(2), 0);
        }
        checkVolume(w, h, l);
        List<Object> pal = Nbt.list(root.get("palette"));
        if (pal.isEmpty()) {
            List<Object> pals = Nbt.list(root.get("palettes"));
            pal = pals.isEmpty() ? List.of() : Nbt.list(pals.get(0));
        }
        char[] lut = new char[Math.max(1, pal.size())];
        for (int i = 0; i < pal.size(); i++) {
            lut[i] = (char) Chunk.BLOCKS.id(Chunk.stateKey(pal.get(i)));
        }
        char[] blocks = new char[w * h * l];
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object bo : Nbt.list(root.get("blocks"))) {
            Map<String, Object> b = Nbt.map(bo);
            int[] pos;
            if (b.get("pos") instanceof int[] p) {
                pos = p;
            } else {
                List<Object> p = Nbt.list(b.get("pos"));
                pos = new int[] {(int) Nbt.num(p.get(0), 0), (int) Nbt.num(p.get(1), 0), (int) Nbt.num(p.get(2), 0)};
            }
            int x = pos[0], y = pos[1], z = pos[2];
            int sid = (int) Nbt.num(b.get("state"), 0);
            if (sid >= 0 && sid < lut.length && x >= 0 && x < w && y >= 0 && y < h && z >= 0 && z < l) {
                blocks[(y * l + z) * w + x] = lut[sid];
                Map<String, Object> d = Nbt.map(b.get("nbt"));
                if (d != null) {
                    out.add(be(x, y, z, String.valueOf(d.getOrDefault("id", "")), without(d, Set.of("x", "y", "z", "id"))));
                }
            }
        }
        return new Schematic(name, w, h, l, blocks, out, new int[3]);
    }

    public static Schematic load(byte[] data, String filename) {
        byte[] raw = decompress(data);
        Map<String, Object> root = Nbt.read(raw);
        String n = filename;
        n = n.substring(n.lastIndexOf('/') + 1);
        n = n.substring(n.lastIndexOf('\\') + 1);
        int dot = n.lastIndexOf('.');
        String name = dot < 0 ? n : n.substring(0, dot);
        if (root.containsKey("Regions")) {
            return litematic(root, name);
        }
        if (root.containsKey("palette") || root.containsKey("palettes")) {
            return structure(root, name);
        }
        return sponge(root, name);
    }

    // ------------------------------------------------------------------ rotate / mirror

    static final List<String> H = List.of("north", "east", "south", "west");
    static final Map<String, String> RAIL = Map.of(
            "north_south", "east_west", "east_west", "north_south",
            "ascending_north", "ascending_east", "ascending_east", "ascending_south",
            "ascending_south", "ascending_west", "ascending_west", "ascending_north",
            "north_east", "south_east", "south_east", "south_west", "south_west", "north_west",
            "north_west", "north_east");
    static final Map<String, String> MIRROR_X = Map.of("east", "west", "west", "east");

    static String rotDir(String d, int steps) {
        int i = H.indexOf(d);
        return i < 0 ? d : H.get((i + steps) % 4);
    }

    /** Rotate a block state (rot in 90-degree steps clockwise) and mirror on X. */
    public static String transformState(String state, int rot, boolean mirror) {
        Map<String, String> props = Chunk.stateProps(state);
        if (props.isEmpty()) {
            return state;
        }
        Map<String, String> p = new HashMap<>(props);
        if (mirror) {
            for (String k : new String[] {"facing", "horizontal_facing"}) {
                if (p.containsKey(k)) {
                    p.put(k, MIRROR_X.getOrDefault(p.get(k), p.get(k)));
                }
            }
            if (p.containsKey("rotation")) {
                p.put("rotation", Integer.toString(Math.floorMod(16 - Integer.parseInt(p.get("rotation")), 16)));
            }
            if (props.containsKey("east") && props.containsKey("west")) {
                p.put("east", props.get("west"));
                p.put("west", props.get("east"));
            }
            if (p.containsKey("hinge")) {
                p.put("hinge", p.get("hinge").equals("left") ? "right" : "left");
            }
            String type = p.get("type");
            if (("left".equals(type) || "right".equals(type)) && state.contains("chest")) {
                p.put("type", type.equals("left") ? "right" : "left");
            }
            String sh = p.get("shape");
            if (sh != null && Set.of("inner_left", "inner_right", "outer_left", "outer_right").contains(sh)) {
                p.put("shape", sh.replace("left", "X").replace("right", "left").replace("X", "right"));
            }
            sh = p.get("shape");
            if (sh != null && (sh.startsWith("north_") || sh.startsWith("south_")) && !sh.equals("north_south")) {
                int u = sh.indexOf('_');
                String a = sh.substring(0, u), b = sh.substring(u + 1);
                p.put("shape", a + "_" + MIRROR_X.getOrDefault(b, b));
            }
            sh = p.get("shape");
            if ("ascending_east".equals(sh) || "ascending_west".equals(sh)) {
                p.put("shape", "ascending_" + MIRROR_X.get(sh.split("_")[1]));
            }
        }
        int steps = Math.floorMod(Math.floorDiv(rot, 90), 4);
        if (steps != 0) {
            for (String k : new String[] {"facing", "horizontal_facing"}) {
                if (p.containsKey(k)) {
                    p.put(k, rotDir(p.get(k), steps));
                }
            }
            if (p.containsKey("rotation")) {
                p.put("rotation", Integer.toString(Math.floorMod(Integer.parseInt(p.get("rotation")) + 4 * steps, 16)));
            }
            if (p.containsKey("axis") && steps % 2 == 1) {
                String a = p.get("axis");
                p.put("axis", a.equals("x") ? "z" : a.equals("z") ? "x" : a);
            }
            if (p.keySet().containsAll(H)) {
                Map<String, String> old = new HashMap<>();
                for (String d : H) {
                    old.put(d, p.get(d));
                }
                for (String d : H) {
                    p.put(rotDir(d, steps), old.get(d));
                }
            }
            if (p.containsKey("shape") && RAIL.containsKey(p.get("shape"))) {
                for (int i = 0; i < steps; i++) {
                    p.put("shape", RAIL.getOrDefault(p.get("shape"), p.get("shape")));
                }
            }
        }
        return Chunk.stateKey(Chunk.baseName(state), p);
    }

    public static Schematic transform(Schematic s, int rot, boolean mirror) {
        rot = Math.floorMod(rot, 360);
        if (rot == 0 && !mirror) {
            return s;
        }
        Map<Integer, Character> lut = new HashMap<>();
        char[] b = new char[s.blocks.length];
        for (int i = 0; i < b.length; i++) {
            int sid = s.blocks[i];
            Character t = lut.get(sid);
            if (t == null) {
                t = (char) Chunk.BLOCKS.id(transformState(Chunk.BLOCKS.name(sid), rot, mirror));
                lut.put(sid, t);
            }
            b[i] = t;
        }
        int w = s.w, h = s.h, l = s.l;
        List<int[]> pos = new ArrayList<>();
        for (Map<String, Object> e : s.blockEntities) {
            pos.add(((int[]) e.get("pos")).clone());
        }
        if (mirror) {
            char[] m = new char[b.length];
            for (int y = 0; y < h; y++) {
                for (int z = 0; z < l; z++) {
                    for (int x = 0; x < w; x++) {
                        m[(y * l + z) * w + x] = b[(y * l + z) * w + (w - 1 - x)];
                    }
                }
            }
            b = m;
            for (int[] p : pos) {
                p[0] = w - 1 - p[0];
            }
        }
        for (int k = 0; k < rot / 90; k++) {
            // (H, L, W) -> (H, W, L): new[y][z'][x'] = old[y][L-1-x'][z']
            int nw = l, nl = w;
            char[] r = new char[b.length];
            for (int y = 0; y < h; y++) {
                for (int zz = 0; zz < nl; zz++) {
                    for (int xx = 0; xx < nw; xx++) {
                        r[(y * nl + zz) * nw + xx] = b[(y * l + (l - 1 - xx)) * w + zz];
                    }
                }
            }
            for (int[] p : pos) {
                int x = p[0], z = p[2];
                p[0] = l - 1 - z;
                p[2] = x;
            }
            b = r;
            w = nw;
            l = nl;
        }
        List<Map<String, Object>> bes = new ArrayList<>();
        for (int i = 0; i < s.blockEntities.size(); i++) {
            Map<String, Object> e = new LinkedHashMap<>(s.blockEntities.get(i));
            e.put("pos", pos.get(i));
            bes.add(e);
        }
        return new Schematic(s.name, w, h, l, b, bes, new int[3]);
    }

    // ------------------------------------------------------------------ material list

    static final Set<String> SKIP = Set.of("air", "cave_air", "void_air", "structure_void", "light", "barrier", "piston_head",
            "moving_piston", "fire", "soul_fire", "nether_portal", "end_portal", "end_gateway", "frosted_ice", "bubble_column");
    static final Map<String, String> ITEM = new HashMap<>();

    static {
        String[] kv = {"wall_torch", "torch", "soul_wall_torch", "soul_torch", "redstone_wall_torch", "redstone_torch",
                "copper_wall_torch", "copper_torch", "redstone_wire", "redstone", "tripwire", "string",
                "cave_vines", "glow_berries", "cave_vines_plant", "glow_berries", "kelp_plant", "kelp",
                "twisting_vines_plant", "twisting_vines", "weeping_vines_plant", "weeping_vines",
                "bamboo_sapling", "bamboo", "carrots", "carrot", "potatoes", "potato", "beetroots", "beetroot_seeds",
                "wheat", "wheat_seeds", "melon_stem", "melon_seeds", "attached_melon_stem", "melon_seeds",
                "pumpkin_stem", "pumpkin_seeds", "attached_pumpkin_stem", "pumpkin_seeds", "cocoa", "cocoa_beans",
                "sweet_berry_bush", "sweet_berries", "water", "water_bucket", "lava", "lava_bucket",
                "powder_snow", "powder_snow_bucket", "big_dripleaf_stem", "big_dripleaf", "tall_seagrass", "seagrass",
                "dirt_path", "dirt", "farmland", "dirt", "torchflower_crop", "torchflower_seeds",
                "pitcher_crop", "pitcher_pod", "player_wall_head", "player_head"};
        for (int i = 0; i < kv.length; i += 2) {
            ITEM.put(kv[i], kv[i + 1]);
        }
    }

    /** Block state -> (item, count) for the material list; item null = don't count. */
    public static Object[] itemFor(String state) {
        String name = Chunk.shortName(Chunk.baseName(state));
        if (SKIP.contains(name)) {
            return new Object[] {null, 0};
        }
        Map<String, String> props = Chunk.stateProps(state);
        if ("upper".equals(props.get("half")) || "head".equals(props.get("part"))) {
            return new Object[] {null, 0};
        }
        if ((name.equals("water") || name.equals("lava")) && !props.getOrDefault("level", "0").equals("0")) {
            return new Object[] {null, 0};
        }
        if (name.startsWith("potted_")) {
            return new Object[] {"flower_pot", 1};
        }
        String item = ITEM.get(name);
        if (item == null) {
            if (name.endsWith("_wall_sign")) {
                item = name.replace("_wall_sign", "_sign");
            } else if (name.endsWith("_wall_hanging_sign")) {
                item = name.replace("_wall_hanging_sign", "_hanging_sign");
            } else if (name.endsWith("_wall_banner")) {
                item = name.replace("_wall_banner", "_banner");
            } else if (name.endsWith("_wall_skull") || name.endsWith("_wall_head")) {
                item = name.replace("_wall_", "_");
            } else if (name.endsWith("_wall_fan")) {
                item = name.replace("_wall_fan", "_fan");
            } else if (name.endsWith("candle_cake")) {
                item = "cake";
            } else {
                item = name;
            }
        }
        int n = 1;
        if ("double".equals(props.get("type")) && name.endsWith("_slab")) {
            n = 2;
        }
        for (String k : new String[] {"layers", "pickles", "candles", "eggs", "flower_amount", "segment_amount"}) {
            if (props.containsKey(k)) {
                n = Integer.parseInt(props.get(k));
            }
        }
        return new Object[] {item, n};
    }

    /** Sorted ids -> counts. */
    static TreeMap<Integer, Long> counts(char[] blocks) {
        long[] c = new long[Chunk.BLOCKS.size() + 1];
        for (char b : blocks) {
            if (b >= c.length) {
                c = Arrays.copyOf(c, b + 1);
            }
            c[b]++;
        }
        TreeMap<Integer, Long> out = new TreeMap<>();
        for (int i = 0; i < c.length; i++) {
            if (c[i] > 0) {
                out.put(i, c[i]);
            }
        }
        return out;
    }

    /** item -> count, in order of the block ids. */
    public static LinkedHashMap<String, Long> materials(char[] blocks) {
        LinkedHashMap<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : counts(blocks).entrySet()) {
            Object[] it = itemFor(Chunk.BLOCKS.name(e.getKey()));
            if (it[0] != null) {
                out.merge((String) it[0], e.getValue() * (Integer) it[1], Long::sum);
            }
        }
        return out;
    }

    /** [[item, n], ...] most first (stable). */
    public static List<List<Object>> sortedItems(Map<String, Long> m) {
        List<Map.Entry<String, Long>> es = new ArrayList<>(m.entrySet());
        es.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        List<List<Object>> out = new ArrayList<>();
        for (Map.Entry<String, Long> e : es) {
            out.add(List.of(e.getKey(), e.getValue()));
        }
        return out;
    }

    public static boolean isAir(String state) {
        return Chunk.AIR_NAMES.contains(Chunk.baseName(state));
    }

    // ------------------------------------------------------------------ comparison with the world

    /** Copies the recorded world into a schematic-sized array; known marks recorded cells. */
    static char[] world(Recording rec, Recording.Dimension dim, int x0, int y0, int z0, int w, int h, int l, boolean[] known) {
        char[] world = new char[w * h * l];
        for (int cz = z0 >> 4; cz <= (z0 + l - 1) >> 4; cz++) {
            for (int cx = x0 >> 4; cx <= (x0 + w - 1) >> 4; cx++) {
                Chunk ch = rec.chunk(dim, cx, cz);
                if (ch == null) {
                    continue;
                }
                int ax = Math.max(x0, cx * 16), bx = Math.min(x0 + w - 1, cx * 16 + 15);
                int az = Math.max(z0, cz * 16), bz = Math.min(z0 + l - 1, cz * 16 + 15);
                int ay = Math.max(y0, ch.minY), by = Math.min(y0 + h - 1, ch.minY + ch.height - 1);
                if (ay > by) {
                    continue;
                }
                for (int y = ay; y <= by; y++) {
                    for (int z = az; z <= bz; z++) {
                        for (int x = ax; x <= bx; x++) {
                            int i = ((y - y0) * l + (z - z0)) * w + (x - x0);
                            world[i] = (char) ch.id(y - ch.minY, z & 15, x & 15);
                            known[i] = true;
                        }
                    }
                }
            }
        }
        return world;
    }

    /** Compares the placed schematic with the recorded world. */
    public static Map<String, Object> verify(Schematic s, Recording rec, Recording.Dimension dim, int x0, int y0, int z0,
                                             double[] near) {
        int maxPoints = 60000, maxTodo = 400;
        int w = s.w, h = s.h, l = s.l, n = s.blocks.length;
        boolean[] known = new boolean[n];
        char[] world = world(rec, dim, x0, y0, z0, w, h, l, known);
        // Compare by block name (orientation counts as "wrongly rotated")
        String[] names = Chunk.BLOCKS.names();
        int[] baseId = new int[names.length];
        boolean[] air = new boolean[names.length], soft = new boolean[names.length];
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < names.length; i++) {
            String bn = Chunk.baseName(names[i]);
            baseId[i] = seen.computeIfAbsent(bn, k -> seen.size());
            air[i] = Chunk.AIR_NAMES.contains(bn);
            soft[i] = Chunk.REPLACEABLE.matcher(bn).matches();
        }
        byte[] kind = new byte[n];   // 0 none, 1 ok, 2 missing, 3 wrong, 4 rotated, 5 extra, 6 unknown
        long total = 0, ok = 0, rotated = 0, missing = 0, wrong = 0, extra = 0, unknown = 0;
        int[] perY = new int[h];
        for (int i = 0; i < n; i++) {
            int want = s.blocks[i], have = world[i];
            boolean need = !air[want];
            if (need) {
                total++;
            }
            if (!known[i]) {
                if (need) {
                    unknown++;
                    kind[i] = 6;
                }
                continue;
            }
            boolean same = baseId[want] == baseId[have];
            if (need) {
                if (same) {
                    ok++;
                    if (want != have) {
                        rotated++;
                        kind[i] = 4;
                    } else {
                        kind[i] = 1;
                    }
                } else if (air[have]) {
                    missing++;
                    kind[i] = 2;
                    perY[i / (w * l)]++;
                } else {
                    wrong++;
                    kind[i] = 3;
                }
            } else if (!air[have] && !soft[have]) {
                extra++;
                kind[i] = 5;
            }
        }
        LinkedHashMap<String, Long> missItems = new LinkedHashMap<>();
        TreeMap<Integer, Long> mc = new TreeMap<>();
        for (int i = 0; i < n; i++) {
            if (kind[i] == 2 || kind[i] == 3) {
                mc.merge((int) s.blocks[i], 1L, Long::sum);
            }
        }
        for (Map.Entry<Integer, Long> e : mc.entrySet()) {
            Object[] it = itemFor(names[e.getKey()]);
            if (it[0] != null) {
                missItems.merge((String) it[0], e.getValue() * (Integer) it[1], Long::sum);
            }
        }
        // Build list: what is missing or wrong, in world coordinates. Bottom to top, within a
        // layer nearest first (that's how you build by hand too).
        List<int[]> cand = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (kind[i] == 2 || kind[i] == 3 || kind[i] == 4) {
                int y = i / (w * l), z = (i / w) % l, x = i % w;
                cand.add(new int[] {x, y, z, i});
            }
        }
        int todoTotal = cand.size();
        if (near != null) {
            double nx = near[0], nz = near[2];
            List<double[]> keyed = new ArrayList<>();
            for (int k = 0; k < cand.size(); k++) {
                int[] c = cand.get(k);
                double dx = c[0] + x0 - nx, dz = c[2] + z0 - nz;
                keyed.add(new double[] {c[1], dx * dx + dz * dz, k});
            }
            keyed.sort((a, b) -> a[0] != b[0] ? Double.compare(a[0], b[0]) : a[1] != b[1] ? Double.compare(a[1], b[1]) : Double.compare(a[2], b[2]));
            List<int[]> sorted = new ArrayList<>();
            for (double[] kk : keyed) {
                sorted.add(cand.get((int) kk[2]));
            }
            cand = sorted;
        }
        List<List<Object>> todo = new ArrayList<>();
        for (int k = 0; k < Math.min(maxTodo, cand.size()); k++) {
            int[] c = cand.get(k);
            int i = c[3];
            String st = names[s.blocks[i]];
            String kd = kind[i] == 2 ? "missing" : kind[i] == 3 ? "wrong" : "rotated";
            Object item = itemFor(st)[0];
            List<Object> entry = new ArrayList<>(List.of(c[0] + x0, c[1] + y0, c[2] + z0, item != null ? item : Chunk.baseName(st), kd));
            if (!kd.equals("missing")) {
                entry.add(Chunk.baseName(names[world[i]]));
            }
            todo.add(entry);
        }
        Object lowest = null;
        for (int y = 0; y < h; y++) {
            if (perY[y] > 0) {
                lowest = linked("y", y + y0, "count", perY[y]);
                break;
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("todo", todo);
        out.put("todoTotal", todoTotal);
        out.put("lowestMissing", lowest);
        out.put("stats", linked("total", total, "ok", ok, "rotated", rotated, "missing", missing, "wrong", wrong, "extra", extra, "unknown", unknown));
        out.put("missingItems", sortedItems(missItems));
        out.put("points", linked("missing", pts(kind, (byte) 2, w, l, maxPoints), "wrong", pts(kind, (byte) 3, w, l, maxPoints),
                "extra", pts(kind, (byte) 5, w, l, maxPoints), "rotated", pts(kind, (byte) 4, w, l, maxPoints)));
        return out;
    }

    static List<Integer> pts(byte[] kind, byte want, int w, int l, int max) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < kind.length; i++) {
            if (kind[i] == want) {
                idx.add(i);
            }
        }
        List<Integer> sel = idx;
        if (idx.size() > max) {
            sel = new ArrayList<>();
            double step = (double) (idx.size() - 1) / (max - 1);
            for (int k = 0; k < max; k++) {
                sel.add(idx.get(k == max - 1 ? idx.size() - 1 : (int) (k * step)));
            }
        }
        List<Integer> out = new ArrayList<>(sel.size() * 3);
        for (int i : sel) {
            out.add(i % w);
            out.add(i / (w * l));
            out.add((i / w) % l);
        }
        return out;
    }

    static Map<String, Object> linked(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    // ------------------------------------------------------------------ writing (Sponge v3)

    static byte[] varintBytes(int[] values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(values.length + 16);
        for (int v : values) {
            while ((v & ~0x7F) != 0) {
                out.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            out.write(v);
        }
        return out.toByteArray();
    }

    /**
     * Block ids (W x H x L) as Sponge Schematic v3 (gzip bytes). Block entities in Sponge form:
     * {"Pos": int[3], "Id": String, "Data": Map}.
     */
    public static byte[] write(char[] ids, int w, int h, int l, String name, List<Map<String, Object>> blockEntities,
                               Integer dataVersion, int[] origin) {
        TreeMap<Integer, Integer> used = new TreeMap<>();
        for (char c : ids) {
            used.put((int) c, 0);
        }
        used.put(0, 0);                       // air is always index 0
        Map<String, Object> palette = new LinkedHashMap<>();
        int k = 0;
        for (Map.Entry<Integer, Integer> e : used.entrySet()) {
            e.setValue(k);
            palette.put(Chunk.BLOCKS.name(e.getKey()), k);
            k++;
        }
        int[] lut = new int[used.lastKey() + 1];
        for (Map.Entry<Integer, Integer> e : used.entrySet()) {
            lut[e.getKey()] = e.getValue();
        }
        int[] inv = new int[ids.length];
        for (int i = 0; i < ids.length; i++) {
            inv[i] = lut[ids[i]];
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("Name", name);
        meta.put("Author", "Webmatica");
        meta.put("Date", System.currentTimeMillis());
        if (origin != null) {
            meta.put("WorldOrigin", origin.clone());
        }
        Map<String, Object> blocks = new LinkedHashMap<>();
        blocks.put("Palette", palette);
        blocks.put("Data", varintBytes(inv));
        blocks.put("BlockEntities", new ArrayList<Object>(blockEntities == null ? List.of() : blockEntities));
        Map<String, Object> schem = new LinkedHashMap<>();
        schem.put("Version", 3);
        schem.put("DataVersion", dataVersion != null && dataVersion != 0 ? dataVersion : 4671);
        schem.put("Metadata", meta);
        schem.put("Width", (short) w);
        schem.put("Height", (short) h);
        schem.put("Length", (short) l);
        schem.put("Offset", new int[] {0, 0, 0});
        schem.put("Blocks", blocks);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("Schematic", schem);
        return gzip(Nbt.write(root, ""), 6);
    }

    static byte[] gzip(byte[] data, int level) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length / 4 + 64);
        try (GZIPOutputStream gz = new GZIPOutputStream(out, 65536) {
            {
                def.setLevel(level);
            }
        }) {
            gz.write(data);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    public static byte[] gzipBytes(byte[] data, int level) {
        return gzip(data, level);
    }

    /** Sponge block entity list of a loaded schematic (for writing it back). */
    public static List<Map<String, Object>> spongeEntities(Schematic s) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> be : s.blockEntities) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("Pos", ((int[]) be.get("pos")).clone());
            e.put("Id", String.valueOf(be.getOrDefault("id", "")));
            Object d = be.get("data");
            e.put("Data", d != null ? d : new LinkedHashMap<>());
            out.add(e);
        }
        return out;
    }

    static final long EXPORT_MAX = 64L * 1024 * 1024;
    static final Set<String> BE_SKIP = Set.of("x", "y", "z", "id", "keepPacked");

    /** Recording area -> Sponge v3 bytes and info. Corners inclusive, any order. */
    public static Object[] export(Recording rec, Recording.Dimension dim, int x0, int y0, int z0, int x1, int y1, int z1, String name) {
        int ax = Math.min(x0, x1), bx = Math.max(x0, x1), ay = Math.min(y0, y1), by = Math.max(y0, y1),
                az = Math.min(z0, z1), bz = Math.max(z0, z1);
        long W = bx - ax + 1, Hh = by - ay + 1, L = bz - az + 1;
        if (W * Hh * L > EXPORT_MAX) {
            throw new IllegalArgumentException("Bereich zu gross (" + W + "x" + Hh + "x" + L + "), hoechstens "
                    + String.format(java.util.Locale.ROOT, "%,d", EXPORT_MAX) + " Bloecke");
        }
        if (Math.max(W, Math.max(Hh, L)) > 32767) {
            throw new IllegalArgumentException("eine Kante ist laenger als 32767 Bloecke");
        }
        int w = (int) W, h = (int) Hh, l = (int) L;
        boolean[] known2 = new boolean[l * w];
        char[] ids = new char[w * h * l];
        List<Map<String, Object>> bes = new ArrayList<>();
        Integer dataVersion = null;
        boolean dvKnown = false;
        for (int cz = az >> 4; cz <= bz >> 4; cz++) {
            for (int cx = ax >> 4; cx <= bx >> 4; cx++) {
                Chunk ch = rec.chunk(dim, cx, cz);
                if (ch == null) {
                    continue;
                }
                int sx = Math.max(ax, cx * 16), ex = Math.min(bx, cx * 16 + 15);
                int sz = Math.max(az, cz * 16), ez = Math.min(bz, cz * 16 + 15);
                int sy = Math.max(ay, ch.minY), ey = Math.min(by, ch.minY + ch.height - 1);
                for (int z = sz; z <= ez; z++) {
                    for (int x = sx; x <= ex; x++) {
                        known2[(z - az) * w + (x - ax)] = true;
                    }
                }
                for (int y = sy; y <= ey; y++) {
                    for (int z = sz; z <= ez; z++) {
                        for (int x = sx; x <= ex; x++) {
                            ids[((y - ay) * l + (z - az)) * w + (x - ax)] = (char) ch.id(y - ch.minY, z & 15, x & 15);
                        }
                    }
                }
                if (!ch.blockEntities.isEmpty() || !dvKnown) {
                    Map<String, Object> raw = rec.rawChunk(dim, cx, cz);
                    if (raw == null) {
                        raw = new HashMap<>();
                    }
                    if (dataVersion == null && raw.get("DataVersion") instanceof Number dv) {
                        dataVersion = dv.intValue();
                    }
                    dvKnown = dataVersion != null;
                    for (Object o : Nbt.list(raw.get("block_entities"))) {
                        Map<String, Object> be = Nbt.map(o);
                        if (be == null) {
                            continue;
                        }
                        Integer bxx = Nbt.intOrNull(be.get("x")), byy = Nbt.intOrNull(be.get("y")), bzz = Nbt.intOrNull(be.get("z"));
                        if (bxx == null || byy == null || bzz == null) {
                            continue;
                        }
                        if (ax <= bxx && bxx <= bx && ay <= byy && byy <= by && az <= bzz && bzz <= bz) {
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("Pos", new int[] {bxx - ax, byy - ay, bzz - az});
                            e.put("Id", String.valueOf(be.getOrDefault("id", "")));
                            e.put("Data", without(be, BE_SKIP));
                            bes.add(e);
                        }
                    }
                }
            }
        }
        boolean any = false;
        int knownCount = 0;
        for (boolean b : known2) {
            any |= b;
            knownCount += b ? 1 : 0;
        }
        if (!any) {
            throw new Recording.NothingRecorded("in diesem Bereich wurde nichts aufgezeichnet");
        }
        byte[] data = write(ids, w, h, l, name, bes, dataVersion, new int[] {ax, ay, az});
        long nonAir = 0;
        java.util.BitSet distinct = new java.util.BitSet();
        for (char c : ids) {
            if (c != 0) {
                nonAir++;
            }
            distinct.set(c);
        }
        Map<String, Object> info = linked("size", List.of(w, h, l), "blocks", nonAir, "palette", distinct.cardinality(),
                "blockEntities", bes.size(), "coverage", Math.round((double) knownCount / known2.length * 1000) / 1000.0);
        return new Object[] {data, info, ids, w, h, l, bes};
    }
}
