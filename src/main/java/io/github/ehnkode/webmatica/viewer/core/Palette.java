package io.github.ehnkode.webmatica.viewer.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Map colors: block state + biome -> RGB. The base color comes from the block model's top
 * texture; gray textures (grass, leaves, water) are tinted with the biome color as in the game.
 * The tables grow with {@link Chunk#BLOCKS} / {@link Chunk#BIOMES}.
 */
public final class Palette {
    public static final int TINT_NONE = 0, TINT_GRASS = 1, TINT_FOLIAGE = 2, TINT_WATER = 3;

    private static final Map<String, int[]> BIOME_COLORS = new HashMap<>();
    private static final Map<String, int[]> FALLBACK = Map.of(
            "chest", new int[] {160, 120, 60}, "trapped_chest", new int[] {160, 120, 60}, "ender_chest", new int[] {40, 55, 55},
            "barrel", new int[] {125, 92, 51}, "water", new int[] {128, 128, 128}, "lava", new int[] {207, 92, 23},
            "glass_pane", new int[] {200, 220, 230}, "chain", new int[] {60, 64, 75});

    private static void b(String k, int g, int f, int w) {
        BIOME_COLORS.put(k, new int[] {g, f, w});
    }

    static {
        b("plains", 0x91bd59, 0x77ab2f, 0x3f76e4);
        b("sunflower_plains", 0x91bd59, 0x77ab2f, 0x3f76e4);
        b("forest", 0x79c05a, 0x59ae30, 0x3f76e4);
        b("flower_forest", 0x79c05a, 0x59ae30, 0x3f76e4);
        b("birch_forest", 0x88bb67, 0x6ba941, 0x3f76e4);
        b("old_growth_birch_forest", 0x88bb67, 0x6ba941, 0x3f76e4);
        b("dark_forest", 0x507a32, 0x59ae30, 0x3f76e4);
        b("pale_garden", 0x778272, 0x878d76, 0x76889d);
        b("taiga", 0x86b783, 0x68a464, 0x287082);
        b("old_growth_pine_taiga", 0x86b87f, 0x6da36b, 0x2d6d77);
        b("old_growth_spruce_taiga", 0x86b87f, 0x6da36b, 0x2d6d77);
        b("snowy_taiga", 0x80b497, 0x60a17b, 0x205e83);
        b("jungle", 0x59c93c, 0x30bb0b, 0x14a2c5);
        b("sparse_jungle", 0x64c73f, 0x3eb80f, 0x0d8ae3);
        b("bamboo_jungle", 0x59c93c, 0x30bb0b, 0x14a2c5);
        b("swamp", 0x6a7039, 0x6a7039, 0x617b64);
        b("mangrove_swamp", 0x6a7039, 0x8db127, 0x3a7a6a);
        b("savanna", 0xbfb755, 0xaea42a, 0x2c8b9c);
        b("savanna_plateau", 0xbfb755, 0xaea42a, 0x2590a8);
        b("windswept_savanna", 0xbfb755, 0xaea42a, 0x2590a8);
        b("desert", 0xbfb755, 0xaea42a, 0x32a598);
        b("badlands", 0x90814d, 0x9e814d, 0x4e7f81);
        b("eroded_badlands", 0x90814d, 0x9e814d, 0x497f99);
        b("wooded_badlands", 0x90814d, 0x9e814d, 0x55809e);
        b("windswept_hills", 0x8ab689, 0x6da36b, 0x007bf7);
        b("windswept_gravelly_hills", 0x8ab689, 0x6da36b, 0x0e63ab);
        b("windswept_forest", 0x8ab689, 0x6da36b, 0x0e63ab);
        b("meadow", 0x83bb6d, 0x63a948, 0x0e4ecf);
        b("cherry_grove", 0xb6db61, 0xb6db61, 0x5db7ef);
        b("grove", 0x80b497, 0x60a17b, 0x3d57d6);
        b("snowy_plains", 0x80b497, 0x60a17b, 0x3d57d6);
        b("ice_spikes", 0x80b497, 0x60a17b, 0x3d57d6);
        b("snowy_slopes", 0x80b497, 0x60a17b, 0x3d57d6);
        b("frozen_peaks", 0x80b497, 0x60a17b, 0x3d57d6);
        b("jagged_peaks", 0x80b497, 0x60a17b, 0x3d57d6);
        b("stony_peaks", 0x9abe4b, 0x82ac1e, 0x3f76e4);
        b("stony_shore", 0x8ab689, 0x6da36b, 0x0d67bb);
        b("beach", 0x91bd59, 0x77ab2f, 0x157cab);
        b("snowy_beach", 0x80b497, 0x60a17b, 0x1463a5);
        b("river", 0x8eb971, 0x71a74d, 0x0084ff);
        b("frozen_river", 0x80b497, 0x60a17b, 0x185390);
        b("ocean", 0x8eb971, 0x71a74d, 0x1787d4);
        b("deep_ocean", 0x8eb971, 0x71a74d, 0x1787d4);
        b("warm_ocean", 0x8eb971, 0x71a74d, 0x02b0e5);
        b("lukewarm_ocean", 0x8eb971, 0x71a74d, 0x0d96db);
        b("deep_lukewarm_ocean", 0x8eb971, 0x71a74d, 0x0d96db);
        b("cold_ocean", 0x8eb971, 0x71a74d, 0x2080c9);
        b("deep_cold_ocean", 0x8eb971, 0x71a74d, 0x2080c9);
        b("frozen_ocean", 0x80b497, 0x60a17b, 0x2570b5);
        b("deep_frozen_ocean", 0x80b497, 0x60a17b, 0x2570b5);
        b("mushroom_fields", 0x55c93f, 0x2bbb0f, 0x8a8997);
        b("dripstone_caves", 0x91bd59, 0x77ab2f, 0x3f76e4);
        b("lush_caves", 0x91bd59, 0x77ab2f, 0x3f76e4);
        b("deep_dark", 0x91bd59, 0x77ab2f, 0x3f76e4);
    }

    /** Block models of the running viewer; set once at start-up. */
    public static volatile Models models;

    public static final class Tables {
        public final float[] rgb;      // id * 3
        public final float[] alpha;
        public final byte[] tint;
        public final boolean[] empty;
        public final boolean[] water;
        public final float[] biomeRgb; // biome * 9: grass rgb, foliage rgb, water rgb
        public final int blocks, biomes;

        Tables(int n, int m) {
            rgb = new float[n * 3];
            alpha = new float[n];
            tint = new byte[n];
            empty = new boolean[n];
            water = new boolean[n];
            biomeRgb = new float[m * 9];
            blocks = n;
            biomes = m;
        }
    }

    private static volatile Tables tables = new Tables(0, 0);

    private Palette() {
    }

    /** Tables covering every id known right now. */
    public static Tables sync() {
        Tables t = tables;
        int n = Chunk.BLOCKS.size(), m = Chunk.BIOMES.size();
        if (t.blocks >= n && t.biomes >= m) {
            return t;
        }
        synchronized (Palette.class) {
            t = tables;
            if (t.blocks >= n && t.biomes >= m) {
                return t;
            }
            Tables nt = new Tables(Math.max(n, t.blocks), Math.max(m, t.biomes));
            System.arraycopy(t.rgb, 0, nt.rgb, 0, t.rgb.length);
            System.arraycopy(t.alpha, 0, nt.alpha, 0, t.blocks);
            System.arraycopy(t.tint, 0, nt.tint, 0, t.blocks);
            System.arraycopy(t.empty, 0, nt.empty, 0, t.blocks);
            System.arraycopy(t.water, 0, nt.water, 0, t.blocks);
            for (int i = t.blocks; i < nt.blocks; i++) {
                describe(i, nt);
            }
            System.arraycopy(t.biomeRgb, 0, nt.biomeRgb, 0, t.biomeRgb.length);
            for (int i = t.biomes; i < nt.biomes; i++) {
                int[] c = BIOME_COLORS.getOrDefault(Chunk.shortName(Chunk.BIOMES.name(i)), BIOME_COLORS.get("plains"));
                for (int s = 0; s < 3; s++) {
                    nt.biomeRgb[i * 9 + s * 3] = (c[s] >> 16) & 255;
                    nt.biomeRgb[i * 9 + s * 3 + 1] = (c[s] >> 8) & 255;
                    nt.biomeRgb[i * 9 + s * 3 + 2] = c[s] & 255;
                }
            }
            tables = nt;
            return nt;
        }
    }

    private static void set(Tables t, int i, double r, double g, double b, double a, int tint, boolean empty) {
        t.rgb[i * 3] = (float) r;
        t.rgb[i * 3 + 1] = (float) g;
        t.rgb[i * 3 + 2] = (float) b;
        t.alpha[i] = (float) a;
        t.tint[i] = (byte) tint;
        t.empty[i] = empty;
        t.water[i] = tint == TINT_WATER;
    }

    private static void describe(int sid, Tables t) {
        Models mm = models;
        if (mm != null && mm.available) {
            Models.Compiled m = mm.get(sid);
            if (m.fluid.equals("water")) {
                set(t, sid, 255, 255, 255, 0.6, TINT_WATER, false);
                return;
            }
            if (m.fluid.equals("lava")) {
                set(t, sid, m.mapRgb[0], m.mapRgb[1], m.mapRgb[2], 1.0, TINT_NONE, false);
                return;
            }
            if (m.quads == 0) {
                set(t, sid, 0, 0, 0, 0, TINT_NONE, true);
                return;
            }
            boolean grass = false, foliage = false, anyUp = false;
            for (int q = 0; q < m.quads; q++) {
                if (m.normal[q] == 1) {
                    anyUp = true;
                    grass |= m.tint[q] == Models.TINT_GRASS;
                    foliage |= m.tint[q] == Models.TINT_FOLIAGE;
                }
            }
            if (!anyUp) {
                for (int q = 0; q < m.quads; q++) {
                    grass |= m.tint[q] == Models.TINT_GRASS;
                    foliage |= m.tint[q] == Models.TINT_FOLIAGE;
                }
            }
            int tint = grass ? TINT_GRASS : foliage ? TINT_FOLIAGE : TINT_NONE;
            set(t, sid, m.mapRgb[0], m.mapRgb[1], m.mapRgb[2], Math.max(0.35, Math.min(1.0, m.mapAlpha)), tint, false);
            return;
        }
        // Without block models (assets not built yet): rough colors.
        String name = Chunk.baseName(Chunk.BLOCKS.name(sid));
        if (Chunk.AIR_NAMES.contains(name)) {
            set(t, sid, 0, 0, 0, 0, TINT_NONE, true);
            return;
        }
        String s = Chunk.shortName(name);
        if (s.equals("water") || s.equals("bubble_column")) {
            set(t, sid, 255, 255, 255, 0.6, TINT_WATER, false);
            return;
        }
        int[] c = FALLBACK.getOrDefault(s, new int[] {110, 110, 110});
        int tint = s.equals("grass_block") || Models.GRASS.contains(s) ? TINT_GRASS : Models.FOLIAGE.contains(s) ? TINT_FOLIAGE : TINT_NONE;
        set(t, sid, c[0], c[1], c[2], 1.0, tint, false);
    }
}
