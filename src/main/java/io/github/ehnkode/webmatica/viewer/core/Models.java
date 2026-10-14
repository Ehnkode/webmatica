package io.github.ehnkode.webmatica.viewer.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Block state -> list of faces (quads), the way Minecraft renders them.
 *
 * <p>Based on {@code blocks.json} (see {@link AssetBuilder}): blockstate variants or multipart
 * rules select models whose elements (cuboids with faces) are converted here to block
 * coordinates 0..1, including element rotation and variant rotation (x/y). The result is
 * cached per global block id.
 */
public final class Models {
    public static final String[] DIRS = {"down", "up", "north", "south", "west", "east"};
    public static final int[][] DIR_VEC = {{0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    public static final float[] SHADE = {0.5f, 1.0f, 0.8f, 0.8f, 0.6f, 0.6f};

    public static final int PASS_OPAQUE = 0, PASS_CUTOUT = 1, PASS_TRANSLUCENT = 2;
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3, TINT_FIXED = 4;

    static final Set<String> GRASS = Set.of("grass_block", "short_grass", "tall_grass", "fern", "large_fern", "potted_fern",
            "sugar_cane", "pink_petals", "wildflowers", "bush");
    static final Set<String> FOLIAGE = Set.of("oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves",
            "mangrove_leaves", "vine", "leaf_litter");
    public static final Map<String, int[]> FIXED = Map.of(
            "spruce_leaves", new int[] {0x61, 0x99, 0x61}, "birch_leaves", new int[] {0x80, 0xa7, 0x55},
            "lily_pad", new int[] {0x20, 0x80, 0x30}, "redstone_wire", new int[] {0xb0, 0x10, 0x10},
            "attached_melon_stem", new int[] {0xe0, 0xc7, 0x1c}, "attached_pumpkin_stem", new int[] {0xe0, 0xc7, 0x1c},
            "melon_stem", new int[] {0x80, 0xb0, 0x30}, "pumpkin_stem", new int[] {0x80, 0xb0, 0x30});

    /** Block entities without model elements: rough substitute cuboids (in 1/16). Order matters for the suffix search. */
    static final Map<String, int[][]> ENTITY_BOXES = new LinkedHashMap<>();

    static {
        ENTITY_BOXES.put("chest", new int[][] {{1, 0, 1, 15, 14, 15}});
        ENTITY_BOXES.put("trapped_chest", new int[][] {{1, 0, 1, 15, 14, 15}});
        ENTITY_BOXES.put("ender_chest", new int[][] {{1, 0, 1, 15, 14, 15}});
        ENTITY_BOXES.put("shulker_box", new int[][] {{0, 0, 0, 16, 16, 16}});
        ENTITY_BOXES.put("bed", new int[][] {{0, 0, 0, 16, 9, 16}});
        ENTITY_BOXES.put("decorated_pot", new int[][] {{1, 0, 1, 15, 16, 15}});
        ENTITY_BOXES.put("sign", new int[][] {{7, 0, 7, 9, 10, 9}, {0, 8, 7, 16, 16, 9}});
        ENTITY_BOXES.put("wall_sign", new int[][] {{0, 4, 14, 16, 12, 16}});
        ENTITY_BOXES.put("hanging_sign", new int[][] {{1, 0, 7, 15, 10, 9}});
        ENTITY_BOXES.put("wall_hanging_sign", new int[][] {{1, 0, 7, 15, 10, 9}});
        ENTITY_BOXES.put("banner", new int[][] {{7, 0, 7, 9, 16, 9}, {1, 2, 7, 15, 16, 8}});
        ENTITY_BOXES.put("wall_banner", new int[][] {{1, 0, 14, 15, 14, 16}});
        ENTITY_BOXES.put("skull", new int[][] {{4, 0, 4, 12, 8, 12}});
        ENTITY_BOXES.put("head", new int[][] {{4, 0, 4, 12, 8, 12}});
        ENTITY_BOXES.put("wall_skull", new int[][] {{4, 4, 8, 12, 12, 16}});
        ENTITY_BOXES.put("wall_head", new int[][] {{4, 4, 8, 12, 12, 16}});
        ENTITY_BOXES.put("conduit", new int[][] {{5, 5, 5, 11, 11, 11}});
        ENTITY_BOXES.put("copper_golem_statue", new int[][] {{4, 0, 4, 12, 14, 12}});
    }

    /** Faces of one block state, in block coordinates 0..1. */
    public static final class Compiled {
        public final int quads;
        public final float[] verts;   // quads * 4 * 3
        public final float[] uv;      // quads * 4 * 2, atlas pixels
        public final byte[] normal;   // direction 0..5 (for shading)
        public final byte[] cull;     // direction or -1
        public final byte[] tint;
        public final float[] shade;
        public final byte[] passes;
        public final boolean fullOpaque;
        public final boolean selfCull;
        public final int[] fixedTint;
        public final String fluid;
        public final double[] mapRgb;
        public final double mapAlpha;

        Compiled(int quads, float[] verts, float[] uv, byte[] normal, byte[] cull, byte[] tint, float[] shade, byte[] passes,
                 boolean fullOpaque, boolean selfCull, int[] fixedTint, String fluid, double[] mapRgb, double mapAlpha) {
            this.quads = quads;
            this.verts = verts;
            this.uv = uv;
            this.normal = normal;
            this.cull = cull;
            this.tint = tint;
            this.shade = shade;
            this.passes = passes;
            this.fullOpaque = fullOpaque;
            this.selfCull = selfCull;
            this.fixedTint = fixedTint;
            this.fluid = fluid;
            this.mapRgb = mapRgb;
            this.mapAlpha = mapAlpha;
        }

        static Compiled empty(String fluid, double[] rgb, double alpha) {
            return new Compiled(0, new float[0], new float[0], new byte[0], new byte[0], new byte[0], new float[0], new byte[0],
                    false, false, new int[] {255, 255, 255}, fluid, rgb, alpha);
        }

        static Compiled empty() {
            return empty("", new double[] {110, 110, 110}, 1.0);
        }
    }

    public final boolean available;
    public final String version;
    public int tile, cols, rows, width, height;
    public final Map<String, Integer> textures = new HashMap<>();
    public List<String> textureNames = new ArrayList<>();
    public int[] kinds = new int[0];
    public double[][] colors = new double[0][];
    public JsonObject blockstates = new JsonObject();
    public JsonObject models = new JsonObject();
    private volatile Compiled[] cache = new Compiled[0];

    public Models(Path assets) {
        Path path = assets.resolve("blocks.json");
        boolean ok = Files.isRegularFile(path);
        String ver = "0";
        if (ok) {
            try (Reader r = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                JsonObject meta = JsonParser.parseReader(r).getAsJsonObject();
                ver = (Files.getLastModifiedTime(path).toMillis() / 1000) + "-" + Files.size(path);
                load(meta);
            } catch (IOException | RuntimeException e) {
                ok = false;
            }
        }
        available = ok;
        version = ver;
    }

    /** From an in-memory blocks.json (used by the item builder and tests). */
    public Models(JsonObject meta) {
        load(meta);
        available = true;
        version = "mem";
    }

    private void load(JsonObject meta) {
        tile = meta.get("tile").getAsInt();
        cols = meta.get("cols").getAsInt();
        rows = meta.get("rows").getAsInt();
        width = cols * tile;
        height = rows * tile;
        JsonArray tex = meta.getAsJsonArray("textures");
        for (int i = 0; i < tex.size(); i++) {
            textures.put(tex.get(i).getAsString(), i);
            textureNames.add(tex.get(i).getAsString());
        }
        JsonArray k = meta.getAsJsonArray("kinds");
        kinds = new int[k.size()];
        for (int i = 0; i < kinds.length; i++) {
            kinds[i] = k.get(i).getAsInt();
        }
        JsonArray c = meta.getAsJsonArray("colors");
        colors = new double[c.size()][];
        for (int i = 0; i < colors.length; i++) {
            JsonArray a = c.get(i).getAsJsonArray();
            colors[i] = new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble(), a.get(3).getAsDouble()};
        }
        blockstates = meta.getAsJsonObject("blockstates");
        models = meta.getAsJsonObject("models");
    }

    // ------------------------------------------------------------------ selection

    List<JsonObject> select(String name, Map<String, String> props) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement bse = blockstates.get(name);
        if (bse == null || !bse.isJsonObject()) {
            return out;
        }
        JsonObject bs = bse.getAsJsonObject();
        if (bs.has("variants")) {
            for (Map.Entry<String, JsonElement> e : bs.getAsJsonObject("variants").entrySet()) {
                if (e.getKey().isEmpty() || matches(e.getKey(), props)) {
                    out.add(first(e.getValue()));
                    break;
                }
            }
        }
        if (bs.has("multipart")) {
            for (JsonElement pe : bs.getAsJsonArray("multipart")) {
                JsonObject part = pe.getAsJsonObject();
                if (when(part.get("when"), props)) {
                    out.add(first(part.get("apply")));
                }
            }
        }
        return out;
    }

    private static JsonObject first(JsonElement v) {
        return v.isJsonArray() ? v.getAsJsonArray().get(0).getAsJsonObject() : v.getAsJsonObject();
    }

    private static boolean matches(String key, Map<String, String> props) {
        for (String kv : key.split(",", -1)) {
            int i = kv.indexOf('=');
            String k = i < 0 ? kv : kv.substring(0, i);
            String v = i < 0 ? "" : kv.substring(i + 1);
            if (!v.equals(props.get(k))) {
                return false;
            }
        }
        return true;
    }

    private static boolean when(JsonElement ce, Map<String, String> props) {
        if (ce == null || ce.isJsonNull()) {
            return true;
        }
        JsonObject cond = ce.getAsJsonObject();
        if (cond.has("OR")) {
            for (JsonElement c : cond.getAsJsonArray("OR")) {
                if (when(c, props)) {
                    return true;
                }
            }
            return false;
        }
        if (cond.has("AND")) {
            for (JsonElement c : cond.getAsJsonArray("AND")) {
                if (!when(c, props)) {
                    return false;
                }
            }
            return true;
        }
        for (Map.Entry<String, JsonElement> e : cond.entrySet()) {
            String have = props.get(e.getKey());
            if (have == null) {
                return false;
            }
            boolean any = false;
            for (String alt : e.getValue().getAsString().split("\\|", -1)) {
                if (alt.equals(have)) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ compiling

    public Compiled get(int sid) {
        Compiled[] c = cache;
        if (sid < c.length && c[sid] != null) {
            return c[sid];
        }
        if (sid >= Chunk.BLOCKS.size()) {
            return Compiled.empty();         // not registered (yet): nothing to draw, not cached
        }
        String state = Chunk.BLOCKS.name(sid);
        Compiled made = compile(state);
        synchronized (this) {
            c = cache;
            if (sid >= c.length) {
                c = java.util.Arrays.copyOf(c, Math.max(sid + 1, c.length * 2));
            }
            if (c[sid] == null) {
                c[sid] = made;
            }
            cache = c;
            return c[sid];
        }
    }

    private float[] tileUv(int t, double[] uv, int rot) {
        int col = t % cols, row = t / cols;
        double u1 = uv[0], v1 = uv[1], u2 = uv[2], v2 = uv[3];
        double[][] corners = {{u1, v1}, {u1, v2}, {u2, v2}, {u2, v1}};
        int k = Math.floorMod(Math.floorDiv(rot, 90), 4);
        float[] out = new float[8];
        for (int i = 0; i < 4; i++) {
            double[] c = corners[(i + k) % 4];
            out[2 * i] = (float) (col * tile + c[0] * tile / 16.0);
            out[2 * i + 1] = (float) (row * tile + c[1] * tile / 16.0);
        }
        return out;
    }

    public static double[][] faceCorners(double[] f, double[] t, String d) {
        double fx = f[0], fy = f[1], fz = f[2], tx = t[0], ty = t[1], tz = t[2];
        return switch (d) {
            case "down" -> new double[][] {{fx, fy, tz}, {fx, fy, fz}, {tx, fy, fz}, {tx, fy, tz}};
            case "up" -> new double[][] {{fx, ty, fz}, {fx, ty, tz}, {tx, ty, tz}, {tx, ty, fz}};
            case "north" -> new double[][] {{tx, ty, fz}, {tx, fy, fz}, {fx, fy, fz}, {fx, ty, fz}};
            case "south" -> new double[][] {{fx, ty, tz}, {fx, fy, tz}, {tx, fy, tz}, {tx, ty, tz}};
            case "west" -> new double[][] {{fx, ty, fz}, {fx, fy, fz}, {fx, fy, tz}, {fx, ty, tz}};
            default -> new double[][] {{tx, ty, tz}, {tx, fy, tz}, {tx, fy, fz}, {tx, ty, fz}};
        };
    }

    static double[] defaultUv(double[] f, double[] t, String d) {
        double fx = f[0], fy = f[1], fz = f[2], tx = t[0], ty = t[1], tz = t[2];
        return switch (d) {
            case "down" -> new double[] {fx, 16 - tz, tx, 16 - fz};
            case "up" -> new double[] {fx, fz, tx, tz};
            case "north" -> new double[] {16 - tx, 16 - ty, 16 - fx, 16 - fy};
            case "south" -> new double[] {fx, 16 - ty, tx, 16 - fy};
            case "west" -> new double[] {fz, 16 - ty, tz, 16 - fy};
            default -> new double[] {16 - tz, 16 - ty, 16 - fz, 16 - fy};
        };
    }

    static int dirIndex(float[] v) {
        long x = Math.round(Math.rint(v[0])), y = Math.round(Math.rint(v[1])), z = Math.round(Math.rint(v[2]));
        for (int i = 0; i < 6; i++) {
            if (DIR_VEC[i][0] == x && DIR_VEC[i][1] == y && DIR_VEC[i][2] == z) {
                return i;
            }
        }
        return -1;
    }

    static int dirIndex(String d) {
        for (int i = 0; i < 6; i++) {
            if (DIRS[i].equals(d)) {
                return i;
            }
        }
        return -1;
    }

    /** Rotations of a vector around the block's axes, in place (float32 like the original). */
    static void rotX(float[] c, int deg) {
        float x = c[0], y = c[1], z = c[2];
        switch (deg) {
            case 90 -> { c[1] = z; c[2] = -y; }
            case 180 -> { c[1] = -y; c[2] = -z; }
            case 270 -> { c[1] = -z; c[2] = y; }
            default -> { }
        }
        c[0] = x;
    }

    static void rotY(float[] c, int deg) {
        float x = c[0], z = c[2];
        switch (deg) {
            case 90 -> { c[0] = -z; c[2] = x; }
            case 180 -> { c[0] = -x; c[2] = -z; }
            case 270 -> { c[0] = z; c[2] = -x; }
            default -> { }
        }
    }

    private static double[] vec3(JsonElement e) {
        JsonArray a = e.getAsJsonArray();
        return new double[] {a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()};
    }

    /** Compile with an explicit model list instead of the blockstate (item images). */
    public Compiled compile(String state) {
        return compile(state, null);
    }

    public Compiled compile(String state, List<JsonObject> forced) {
        String name = Chunk.baseName(state);
        String shortName = Chunk.shortName(name);
        Map<String, String> props = Chunk.stateProps(state);
        switch (shortName) {
            case "air", "cave_air", "void_air", "light", "structure_void", "barrier":
                return Compiled.empty();
            case "water", "bubble_column":
                return Compiled.empty("water", new double[] {63, 118, 228}, 0.6);
            case "lava":
                return Compiled.empty("lava", new double[] {207, 92, 23}, 1.0);
            default:
                break;
        }
        if (!available) {
            return Compiled.empty();
        }
        List<float[]> V = new ArrayList<>();
        List<float[]> UV = new ArrayList<>();
        List<Integer> N = new ArrayList<>(), C = new ArrayList<>(), T = new ArrayList<>(), P = new ArrayList<>();
        List<Float> S = new ArrayList<>();
        int tintKind = GRASS.contains(shortName) ? TINT_GRASS : FOLIAGE.contains(shortName) ? TINT_FOLIAGE
                : FIXED.containsKey(shortName) ? TINT_FIXED : TINT_GRASS;
        List<JsonObject> entries = forced != null ? forced : select(name, props);
        for (JsonObject entry : entries) {
            String mid = entry.has("model") ? Chunk.shortName(entry.get("model").getAsString()) : "";
            JsonElement me = models.get(mid);
            if (me == null || !me.isJsonObject()) {
                continue;
            }
            JsonObject model = me.getAsJsonObject();
            int xr = Math.floorMod(entry.has("x") ? entry.get("x").getAsInt() : 0, 360);
            int yr = Math.floorMod(entry.has("y") ? entry.get("y").getAsInt() : 0, 360);
            JsonArray els = model.has("elements") && model.get("elements").isJsonArray() ? model.getAsJsonArray("elements") : new JsonArray();
            if (els.isEmpty()) {
                // Block entity (chest, sign, ...): substitute cuboid with the particle texture.
                int[][] boxes = ENTITY_BOXES.get(shortName);
                if (boxes == null) {
                    for (Map.Entry<String, int[][]> e : ENTITY_BOXES.entrySet()) {
                        if (shortName.endsWith("_" + e.getKey())) {
                            boxes = e.getValue();
                            break;
                        }
                    }
                }
                JsonElement particle = model.get("particle");
                if (boxes == null || particle == null || particle.isJsonNull()) {
                    continue;
                }
                els = new JsonArray();
                for (int[] b : boxes) {
                    JsonObject el = new JsonObject();
                    JsonArray fr = new JsonArray(), to = new JsonArray();
                    for (int i = 0; i < 3; i++) {
                        fr.add(b[i]);
                        to.add(b[3 + i]);
                    }
                    el.add("from", fr);
                    el.add("to", to);
                    JsonObject faces = new JsonObject();
                    for (String d : DIRS) {
                        JsonObject f = new JsonObject();
                        f.add("t", particle);
                        faces.add(d, f);
                    }
                    el.add("faces", faces);
                    els.add(el);
                }
                String facing = props.get("facing");
                if (facing != null) {
                    switch (facing) {
                        case "north" -> yr = 180;
                        case "east" -> yr = 270;
                        case "south" -> yr = 0;
                        case "west" -> yr = 90;
                        default -> { }
                    }
                }
            }
            for (JsonElement ee : els) {
                JsonObject el = ee.getAsJsonObject();
                double[] f = vec3(el.get("from")), t = vec3(el.get("to"));
                JsonObject erot = el.has("rotation") && el.get("rotation").isJsonObject() ? el.getAsJsonObject("rotation") : null;
                boolean noShade = el.has("shade") && el.get("shade").isJsonPrimitive() && el.get("shade").getAsJsonPrimitive().isBoolean()
                        && !el.get("shade").getAsBoolean();
                for (Map.Entry<String, JsonElement> fe : el.getAsJsonObject("faces").entrySet()) {
                    String d = fe.getKey();
                    int di = dirIndex(d);
                    if (di < 0) {
                        continue;
                    }
                    JsonObject face = fe.getValue().getAsJsonObject();
                    double[][] fc = faceCorners(f, t, d);
                    float[][] corners = new float[4][3];
                    for (int i = 0; i < 4; i++) {
                        for (int a = 0; a < 3; a++) {
                            corners[i][a] = (float) fc[i][a] / 16.0f;
                        }
                    }
                    float[] normal = {DIR_VEC[di][0], DIR_VEC[di][1], DIR_VEC[di][2]};
                    double angle = erot != null && erot.has("angle") ? erot.get("angle").getAsDouble() : 0;
                    if (angle != 0) {
                        double ang = Math.toRadians(angle);
                        float[] o = {8 / 16f, 8 / 16f, 8 / 16f};
                        if (erot.has("origin")) {
                            double[] oo = vec3(erot.get("origin"));
                            for (int a = 0; a < 3; a++) {
                                o[a] = (float) oo[a] / 16.0f;
                            }
                        }
                        String ax = erot.has("axis") ? erot.get("axis").getAsString() : "y";
                        float ca = (float) Math.cos(ang), sa = (float) Math.sin(ang);
                        float[][] R;
                        if (ax.equals("y")) {
                            R = new float[][] {{ca, 0, sa}, {0, 1, 0}, {-sa, 0, ca}};
                        } else if (ax.equals("x")) {
                            R = new float[][] {{1, 0, 0}, {0, ca, -sa}, {0, sa, ca}};
                        } else {
                            R = new float[][] {{ca, -sa, 0}, {sa, ca, 0}, {0, 0, 1}};
                        }
                        for (float[] c : corners) {
                            float px = c[0] - o[0], py = c[1] - o[1], pz = c[2] - o[2];
                            for (int a = 0; a < 3; a++) {
                                c[a] = px * R[a][0] + py * R[a][1] + pz * R[a][2] + o[a];
                            }
                        }
                        float nx = normal[0], ny = normal[1], nz = normal[2];
                        for (int a = 0; a < 3; a++) {
                            normal[a] = nx * R[a][0] + ny * R[a][1] + nz * R[a][2];
                        }
                    }
                    String cullName = face.has("cullface") ? face.get("cullface").getAsString() : null;
                    int ci = cullName == null ? -1 : dirIndex(cullName);
                    float[] cullVec = ci >= 0 ? new float[] {DIR_VEC[ci][0], DIR_VEC[ci][1], DIR_VEC[ci][2]} : null;
                    float[] vq = new float[12];
                    for (int i = 0; i < 4; i++) {
                        float[] c = {corners[i][0] - 0.5f, corners[i][1] - 0.5f, corners[i][2] - 0.5f};
                        rotX(c, xr);
                        rotY(c, yr);
                        vq[3 * i] = c[0] + 0.5f;
                        vq[3 * i + 1] = c[1] + 0.5f;
                        vq[3 * i + 2] = c[2] + 0.5f;
                    }
                    rotX(normal, xr);
                    rotY(normal, yr);
                    if (cullVec != null) {
                        rotX(cullVec, xr);
                        rotY(cullVec, yr);
                    }
                    double[] uv;
                    if (face.has("uv") && face.get("uv").isJsonArray()) {
                        JsonArray ua = face.getAsJsonArray("uv");
                        uv = new double[] {ua.get(0).getAsDouble(), ua.get(1).getAsDouble(), ua.get(2).getAsDouble(), ua.get(3).getAsDouble()};
                    } else {
                        uv = defaultUv(f, t, d);
                    }
                    int texIdx = face.get("t").getAsInt();
                    V.add(vq);
                    UV.add(tileUv(texIdx, uv, face.has("rotation") ? face.get("rotation").getAsInt() : 0));
                    int ni = dirIndex(normal);
                    N.add(Math.max(ni, 0));
                    C.add(cullVec != null ? dirIndex(cullVec) : -1);
                    T.add(face.has("tintindex") ? tintKind : TINT_NONE);
                    S.add(noShade ? 1.0f : ni >= 0 ? SHADE[ni] : 0.85f);
                    int k = kinds[texIdx];
                    P.add(k == 2 ? PASS_TRANSLUCENT : k == 1 ? PASS_CUTOUT : PASS_OPAQUE);
                }
            }
        }
        if (V.isEmpty()) {
            return Compiled.empty();
        }
        int q = V.size();
        float[] verts = new float[q * 12];
        float[] uvs = new float[q * 8];
        for (int i = 0; i < q; i++) {
            System.arraycopy(V.get(i), 0, verts, i * 12, 12);
            System.arraycopy(UV.get(i), 0, uvs, i * 8, 8);
        }
        boolean full = false;
        boolean allOpaque = true;
        for (int p : P) {
            allOpaque &= p == PASS_OPAQUE;
        }
        if (q >= 6 && allOpaque) {
            float[] lo = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
            float[] hi = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
            for (int i = 0; i < q * 4; i++) {
                for (int a = 0; a < 3; a++) {
                    lo[a] = Math.min(lo[a], verts[i * 3 + a]);
                    hi[a] = Math.max(hi[a], verts[i * 3 + a]);
                }
            }
            boolean[] covered = new boolean[6];
            for (int i = 0; i < q; i++) {
                int cd = C.get(i);
                if (cd < 0) {
                    continue;
                }
                int axis = DIR_VEC[cd][0] != 0 ? 0 : DIR_VEC[cd][1] != 0 ? 1 : 2;
                boolean all = true;
                for (int a = 0; a < 3; a++) {
                    if (a == axis) {
                        continue;
                    }
                    float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
                    for (int v = 0; v < 4; v++) {
                        mn = Math.min(mn, verts[i * 12 + v * 3 + a]);
                        mx = Math.max(mx, verts[i * 12 + v * 3 + a]);
                    }
                    all &= isClose(mn, 0) && isClose(mx, 1);
                }
                if (all) {
                    covered[cd] = true;
                }
            }
            boolean six = true;
            for (boolean b : covered) {
                six &= b;
            }
            full = six && isClose(lo[0], 0) && isClose(lo[1], 0) && isClose(lo[2], 0)
                    && isClose(hi[0], 1) && isClose(hi[1], 1) && isClose(hi[2], 1);
        }
        boolean selfCull = !full && (shortName.endsWith("glass") || shortName.endsWith("leaves")
                || shortName.equals("ice") || shortName.equals("slime_block") || shortName.equals("honey_block"));

        // Map color: texture of the topmost face pointing up.
        int pick = 0;
        float best = -Float.MAX_VALUE;
        boolean anyUp = false;
        for (int i = 0; i < q; i++) {
            if (N.get(i) == 1) {
                float m = (verts[i * 12 + 1] + verts[i * 12 + 4] + verts[i * 12 + 7] + verts[i * 12 + 10]) / 4f;
                if (!anyUp || m > best) {
                    best = m;
                    pick = i;
                    anyUp = true;
                }
            }
        }
        double u0 = Double.MAX_VALUE, v0 = Double.MAX_VALUE;
        for (int v = 0; v < 4; v++) {
            u0 = Math.min(u0, uvs[pick * 8 + 2 * v]);
            v0 = Math.min(v0, uvs[pick * 8 + 2 * v + 1]);
        }
        int tidx = (int) Math.floor(v0 / tile) * cols + (int) Math.floor(u0 / tile);
        double[] col = tidx >= 0 && tidx < colors.length ? colors[tidx] : new double[] {110, 110, 110, 1.0};
        double[] rgb = {col[0], col[1], col[2]};
        int[] fixed = FIXED.get(shortName);
        if (fixed != null) {
            for (int a = 0; a < 3; a++) {
                rgb[a] = rgb[a] * fixed[a] / 255.0;
            }
        }
        double alpha = col[3];
        if (!full) {
            // Small things (torches, flowers) only half opaque on the map.
            alpha = Math.min(alpha, q >= 5 ? 0.999 : 0.6);
        }
        byte[] nb = new byte[q], cb = new byte[q], tb = new byte[q], pb = new byte[q];
        float[] sb = new float[q];
        for (int i = 0; i < q; i++) {
            nb[i] = (byte) (int) N.get(i);
            cb[i] = (byte) (int) C.get(i);
            tb[i] = (byte) (int) T.get(i);
            pb[i] = (byte) (int) P.get(i);
            sb[i] = S.get(i);
        }
        return new Compiled(q, verts, uvs, nb, cb, tb, sb, pb, full, selfCull,
                fixed != null ? fixed : new int[] {255, 255, 255}, "", rgb, alpha);
    }

    private static boolean isClose(float a, float b) {
        return Math.abs(a - b) <= 1e-8 + 1e-5 * Math.abs(b);
    }
}
