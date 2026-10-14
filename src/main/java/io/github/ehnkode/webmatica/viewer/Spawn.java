package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.Models;
import io.github.ehnkode.webmatica.viewer.core.Palette;
import io.github.ehnkode.webmatica.viewer.core.Png;
import io.github.ehnkode.webmatica.viewer.core.Recording;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Where can mobs spawn? From blocks and the light the mod records. Rules as in vanilla for normal
 * monsters: floor with a full solid top, two free blocks above, block light 0 (Nether: at most 7).
 * Result per spot: 2 = any time, 1 = only at night (sky light > 7).
 */
final class Spawn {
    static final Pattern NO_FLOOR = Pattern.compile("(^|_)(glass|leaves|ice)$|^bedrock$|^barrier$|^magma_block$|^glowstone$|^sea_lantern$|"
            + "^shroomlight$|^jack_o_lantern$|froglight$|^beacon$|^spawner$|^light$|^redstone_lamp$|"
            + "^redstone_block$|^observer$|^target$|^tnt$|stained_glass$|^mangrove_roots$");
    static final Pattern PASSABLE = Pattern.compile("^(short_grass|tall_grass|fern|large_fern|dead_bush|bush|firefly_bush|short_dry_grass|tall_dry_grass|"
            + "dandelion|poppy|blue_orchid|allium|azure_bluet|.*_tulip|oxeye_daisy|cornflower|lily_of_the_valley|"
            + "torchflower|sunflower|lilac|rose_bush|peony|pitcher_plant|pink_petals|wildflowers|leaf_litter|"
            + ".*_sapling|sugar_cane|vine|glow_lichen|sculk_vein|hanging_roots|cave_vines(_plant)?|"
            + "brown_mushroom|red_mushroom|crimson_fungus|warped_fungus|crimson_roots|warped_roots|nether_sprouts|"
            + "twisting_vines(_plant)?|weeping_vines(_plant)?|.*_button|lever|.*_sign|.*_wall_sign|.*_hanging_sign|"
            + ".*_banner|.*_wall_banner|tripwire|tripwire_hook|string|cobweb|ladder|wheat|carrots|potatoes|beetroots|"
            + "nether_wart|attached_.*_stem|.*_stem|seagrass|structure_void|cave_air|void_air|air)$");
    /** Torches light up to 13 blocks (Manhattan); a bit less because walls swallow light. */
    static final int TORCH_REACH = 11;
    static final Pattern LIGHTS = Pattern.compile("^minecraft:(.*torch|.*lantern)$");

    private Spawn() {
    }

    // ------------------------------------------------------------------ per block state: 1 floor, 2 free, 4 fluid

    private static byte[] cls = new byte[0];
    private static Models clsModels;

    static synchronized byte[] classes(int n) {
        if (clsModels != Palette.models) {
            cls = new byte[0];
            clsModels = Palette.models;
        }
        if (cls.length < n) {
            byte[] v = java.util.Arrays.copyOf(cls, n);
            for (int i = cls.length; i < n; i++) {
                v[i] = (byte) classify(Chunk.BLOCKS.name(i), i);
            }
            cls = v;
        }
        return cls;
    }

    static int classify(String state, int sid) {
        String name = Chunk.baseName(state);
        String sh = Chunk.shortName(name);
        if (Chunk.AIR_NAMES.contains(name) && !sh.equals("light") && !sh.equals("barrier")) {
            return 2;
        }
        Map<String, String> props = Chunk.stateProps(state);
        if (sh.equals("water") || sh.equals("lava") || sh.equals("bubble_column") || "true".equals(props.get("waterlogged"))) {
            return 4;
        }
        if (sh.equals("snow")) {
            String layers = props.getOrDefault("layers", "1");
            return layers.equals("1") ? 2 : "8".equals(props.get("layers")) ? 1 : 0;
        }
        if (PASSABLE.matcher(sh).find()) {
            return 2;
        }
        if (NO_FLOOR.matcher(sh).find()) {
            return 0;
        }
        if (sh.endsWith("_slab")) {
            String t = props.get("type");
            return "top".equals(t) || "double".equals(t) ? 1 : 0;
        }
        if (sh.endsWith("_stairs")) {
            return "top".equals(props.get("half")) ? 1 : 0;
        }
        try {
            Models m = Palette.models;
            return m != null && m.available && topFull(m.get(sid)) ? 1 : 0;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static boolean close(float a, float b) {
        return Math.abs(a - b) <= 1e-8 + 1e-5 * Math.abs(b);
    }

    /** Does the model have a full face on top (whole 16 x 16 at height 1)? */
    static boolean topFull(Models.Compiled m) {
        for (int q = 0; q < m.quads; q++) {
            if (m.cull[q] != 1) {
                continue;
            }
            float minX = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, minZ = Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            boolean top = true;
            for (int v = 0; v < 4; v++) {
                float x = m.verts[q * 12 + v * 3], y = m.verts[q * 12 + v * 3 + 1], z = m.verts[q * 12 + v * 3 + 2];
                top &= close(y, 1);
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
            if (top && close(minX, 0) && close(maxX, 1) && close(minZ, 0) && close(maxZ, 1)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ spots

    /** {"light": bool, "pts": [x, y, z, kind, ...]}; y is the block the mob stands in. */
    static Map<String, Object> points(Chunk ch, String dimKey) {
        byte[][] lit = ch.light();
        if (lit == null) {
            return J.map("light", false, "pts", List.of());
        }
        byte[] bl = lit[0], sl = lit[1];
        byte[] c = classes(Chunk.BLOCKS.size());
        int limit = dimKey.equals("the_nether") ? 7 : 0;
        boolean noSky = dimKey.equals("the_nether") || dimKey.equals("the_end");
        List<Integer> out = new ArrayList<>();
        for (int y = 0; y + 2 < ch.height; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int i = (y * 16 + z) * 16 + x;
                    if (c[ch.blocks[i]] == 1 && c[ch.blocks[i + 256]] == 2 && c[ch.blocks[i + 512]] == 2 && bl[i + 256] <= limit) {
                        int sky = sl[i + 256];
                        out.add(x + ch.cx * 16);
                        out.add(y + 1 + ch.minY);
                        out.add(z + ch.cz * 16);
                        out.add(sky <= 7 || noSky ? 2 : 1);
                    }
                }
            }
        }
        return J.map("light", true, "pts", out);
    }

    /**
     * For the 2D map: per column [z*16+x] the kind of the topmost spawn spot that can be seen from
     * above, 0 = none; null without light data. ymax: layer cut.
     */
    static byte[] columns(Chunk ch, String dimKey, Integer ymax) {
        byte[][] lit = ch.light();
        if (lit == null) {
            return null;
        }
        byte[] bl = lit[0], sl = lit[1];
        byte[] c = classes(Chunk.BLOCKS.size());
        int top = ch.height;
        int cut = ymax == null ? top : Math.max(0, Math.min(top, ymax - ch.minY + 1));
        int okLimit = ymax == null ? Integer.MAX_VALUE : Math.max(0, ymax - ch.minY);
        int limit = dimKey.equals("the_nether") ? 7 : 0;
        boolean noSky = dimKey.equals("the_nether") || dimKey.equals("the_end");
        byte[] out = new byte[256];
        boolean[] free = new boolean[top], open = new boolean[top];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                boolean o = true;
                for (int y = top - 1; y >= 0; y--) {
                    free[y] = y >= cut || c[ch.blocks[(y * 16 + z) * 16 + x]] == 2;
                    o &= free[y];
                    open[y] = o;
                }
                // topmost spot: floor at s, mob in s+1 and s+2, nothing solid above
                for (int s = Math.min(top - 3, okLimit - 1); s >= 0; s--) {
                    int i1 = ((s + 1) * 16 + z) * 16 + x;
                    if (c[ch.blocks[(s * 16 + z) * 16 + x]] == 1 && free[s + 1] && free[s + 2] && bl[i1] <= limit && open[s + 1]) {
                        out[z * 16 + x] = (byte) (sl[i1] > 7 && !noSky ? 1 : 2);
                        break;
                    }
                }
            }
        }
        return out;
    }

    /** 512 x 512 overlay (1 px per block): red = any time, orange = only at night. */
    static byte[] regionPng(Recording rec, Recording.Dimension dim, int rx, int rz, Integer ymax) {
        byte[] rgba = new byte[512 * 512 * 4];
        boolean found = false;
        for (int lz = 0; lz < 32; lz++) {
            for (int lx = 0; lx < 32; lx++) {
                Chunk ch = rec.chunk(dim, rx * 32 + lx, rz * 32 + lz);
                if (ch == null) {
                    continue;
                }
                byte[] k = columns(ch, dim.key, ymax);
                if (k == null) {
                    continue;
                }
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int v = k[z * 16 + x];
                        if (v == 0) {
                            continue;
                        }
                        found = true;
                        int o = ((lz * 16 + z) * 512 + lx * 16 + x) * 4;
                        if (v == 2) {
                            rgba[o] = (byte) 229;
                            rgba[o + 1] = 72;
                            rgba[o + 2] = 77;
                            rgba[o + 3] = (byte) 200;
                        } else {
                            rgba[o] = (byte) 245;
                            rgba[o + 1] = (byte) 165;
                            rgba[o + 2] = 36;
                            rgba[o + 3] = 110;
                        }
                    }
                }
            }
        }
        return found ? Png.encode(512, 512, rgba, 3) : null;
    }

    // ------------------------------------------------------------------ torches

    record TorchPlan(int spots, List<int[]> torches, Map<Long, Integer> chunks) {
    }

    private static boolean[] lightLut = new boolean[0];

    static synchronized boolean[] lightLut() {
        String[] names = Chunk.BLOCKS.names();
        if (lightLut.length < names.length) {
            boolean[] l = new boolean[names.length + 1];
            for (int i = 0; i < names.length; i++) {
                l[i] = LIGHTS.matcher(Chunk.baseName(names[i])).matches();
            }
            lightLut = l;
        }
        return lightLut;
    }

    /**
     * Where torches would have to go so that no monster spawns in the area any more. Greedy: from
     * the lowest uncovered spawn spot, one torch on it, everything within reach counts as lit.
     */
    static TorchPlan torchPlan(Recording rec, Recording.Dimension dim, Farm.Area a, double[] near) {
        List<int[]> p = new ArrayList<>();
        List<int[]> lights = new ArrayList<>();
        for (int cz = a.z0() >> 4; cz <= a.z1() >> 4; cz++) {
            for (int cx = a.x0() >> 4; cx <= a.x1() >> 4; cx++) {
                Chunk ch = rec.chunk(dim, cx, cz);
                if (ch == null) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                List<Integer> pts = (List<Integer>) points(ch, dim.key).get("pts");
                for (int i = 0; i + 4 <= pts.size(); i += 4) {
                    int x = pts.get(i), y = pts.get(i + 1), z = pts.get(i + 2);
                    if (x >= a.x0() && x <= a.x1() && z >= a.z0() && z <= a.z1() && y >= a.y0() && y <= a.y1()) {
                        p.add(new int[] {x, y, z});
                    }
                }
                boolean[] lut = lightLut();
                for (int i = 0; i < ch.blocks.length; i++) {
                    int id = Math.min(ch.blocks[i], lut.length - 1);
                    if (lut[id]) {
                        lights.add(new int[] {(i & 15) + cx * 16, (i >> 8) + ch.minY, ((i >> 4) & 15) + cz * 16});
                    }
                }
            }
        }
        Map<Long, Integer> chunks = new LinkedHashMap<>();
        if (p.isEmpty()) {
            return new TorchPlan(0, List.of(), chunks);
        }
        boolean[] lit = new boolean[p.size()];
        for (int j = 0; j < Math.min(4096, lights.size()); j++) {
            cover(p, lit, lights.get(j));
        }
        Integer[] order = new Integer[p.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, Comparator.<Integer>comparingInt(i -> p.get(i)[1]).thenComparingInt(i -> p.get(i)[0])
                .thenComparingInt(i -> p.get(i)[2]));
        List<int[]> torches = new ArrayList<>();
        for (int i : order) {
            if (lit[i]) {
                continue;
            }
            cover(p, lit, p.get(i));
            torches.add(p.get(i).clone());
        }
        if (near != null) {
            torches.sort(Comparator.comparingDouble(t -> Farm.sq(t[0] - near[0]) + Farm.sq(t[2] - near[2]) + 4 * Farm.sq(t[1] - near[1])));
        }
        for (int[] t : torches) {
            chunks.merge(History.ck(t[0] >> 4, t[2] >> 4), 1, Integer::sum);
        }
        return new TorchPlan(p.size(), torches, chunks);
    }

    static TorchPlan torchPlan(Recording rec, Recording.Dimension dim, Farm.Area a) {
        return torchPlan(rec, dim, a, null);
    }

    private static void cover(List<int[]> p, boolean[] lit, int[] q) {
        for (int i = 0; i < lit.length; i++) {
            int[] v = p.get(i);
            if (Math.abs(v[0] - q[0]) + Math.abs(v[1] - q[1]) + Math.abs(v[2] - q[2]) <= TORCH_REACH) {
                lit[i] = true;
            }
        }
    }
}
