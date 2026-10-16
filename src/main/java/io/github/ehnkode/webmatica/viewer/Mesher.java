package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.Models;
import io.github.ehnkode.webmatica.viewer.core.Palette;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Tiles;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chunk -> triangle mesh for the 3D view.
 *
 * <p>For each block state, the precompiled faces ({@link Models}) are placed at all positions of
 * that state; hidden faces (cullface against an opaque neighbor) are dropped. Binary format
 * (little endian), one response per chunk:
 * <pre>
 *  "MSH3"  int32 cx, int32 cz
 *     3x pass (opaque, cutout, translucent):
 *     uint32 quads
 *        int16  pos[quads*4*3]     position in 1/16 block, x/z relative to chunk corner, y absolute
 *        uint16 uv[quads*4*2]      atlas pixels * 16
 *        uint8  rgb[quads*4*3]     brightness x biome color x corner shading
 *        uint8  light[quads*4*2]   block light, sky light (0..15 * 17), smoothed over the corner
 * </pre>
 * Corner shading (ambient occlusion) and smooth lighting as in vanilla.
 */
public final class Mesher {
    public static final String MAGIC = "MSH3";
    /** Caves below this height that get no light are not drawn (overworld, only without layer cut). */
    static final int CAVE_Y = 55;
    static final float[] AO_LEVEL = {0.5f, 0.65f, 0.82f, 1.0f};
    static final Set<String> ALWAYS_WET = Set.of("kelp", "kelp_plant", "seagrass", "tall_seagrass", "bubble_column");
    static final float FLUID_TOP = 14f / 16f;
    static final int[] ROLL = {1, 2, 3, 0};

    private Mesher() {
    }

    // ------------------------------------------------------------------ per id: opaque / water / lava

    private static volatile boolean[][] lut = {new boolean[0], new boolean[0], new boolean[0]};
    private static volatile Models lutModels;
    private static int lutDone;

    static synchronized boolean[][] lut(int n) {
        Models models = Palette.models;
        boolean[][] l = lut;
        if (lutModels != models) {
            l = new boolean[][] {new boolean[0], new boolean[0], new boolean[0]};
            lutModels = models;
            lutDone = 0;
        }
        // ids past the registry's size don't exist (yet): false, filled in once they are registered
        int have = Math.min(n, Chunk.BLOCKS.size());
        if (l[0].length >= n && lutDone >= have) {
            return l;
        }
        int len = Math.max(n, l[0].length);
        boolean[] full = Arrays.copyOf(l[0], len), water = Arrays.copyOf(l[1], len), lava = Arrays.copyOf(l[2], len);
        for (int i = lutDone; i < have; i++) {
            Models.Compiled m = models.get(i);
            full[i] = m.fullOpaque;
            String state = Chunk.BLOCKS.name(i);
            // Water also in kelp, seagrass and blocks with waterlogged=true
            water[i] = m.fluid.equals("water") || ALWAYS_WET.contains(Chunk.shortName(Chunk.baseName(state)))
                    || "true".equals(Chunk.stateProps(state).get("waterlogged"));
            lava[i] = m.fluid.equals("lava");
        }
        lutDone = Math.max(lutDone, have);
        l = new boolean[][] {full, water, lava};
        lut = l;
        return l;
    }

    // ------------------------------------------------------------------ output buffers

    /** Quads of one pass. */
    static final class Pass {
        float[] pos = new float[1200], uv = new float[800], rgb = new float[1200];
        byte[] lt = new byte[800];
        int n;

        void grow() {
            if (n * 12 + 12 > pos.length) {
                int c = pos.length * 2;
                pos = Arrays.copyOf(pos, c);
                rgb = Arrays.copyOf(rgb, c);
                uv = Arrays.copyOf(uv, c / 12 * 8);
                lt = Arrays.copyOf(lt, c / 12 * 8);
            }
        }

        void append(Pass o, float dx, float dz) {
            for (int i = 0; i < o.n; i++) {
                grow();
                System.arraycopy(o.pos, i * 12, pos, n * 12, 12);
                for (int v = 0; v < 4; v++) {
                    pos[n * 12 + v * 3] += dx;
                    pos[n * 12 + v * 3 + 2] += dz;
                }
                System.arraycopy(o.uv, i * 8, uv, n * 8, 8);
                System.arraycopy(o.rgb, i * 12, rgb, n * 12, 12);
                System.arraycopy(o.lt, i * 8, lt, n * 8, 8);
                n++;
            }
        }
    }

    // ------------------------------------------------------------------ padded fields

    interface Getter {
        /** Value array of a chunk (indexed like Chunk.blocks), or null. */
        Object get(Chunk c);
    }

    /** Copies values into the padded array P (int[] for blocks, byte[] for light). */
    static void pad(Chunk ch, Map<Long, Chunk> nb, int y0, int y1, Getter get, int below, int above, int missing, int[] outI, byte[] outB) {
        int h = y1 - y0 + 1;
        int rows = h + 2;
        if (outI != null) {
            Arrays.fill(outI, missing);
        } else {
            Arrays.fill(outB, (byte) missing);
        }
        // [chunk, z range, x range, target z offset, target x offset]
        Object[][] parts = {
                {ch, 0, 16, 0, 16, 1, 1},
                {nb.get(k(0, -1)), 15, 16, 0, 16, 0 - 15, 1},
                {nb.get(k(0, 1)), 0, 1, 0, 16, 17, 1},
                {nb.get(k(-1, 0)), 0, 16, 15, 16, 1, 0 - 15},
                {nb.get(k(1, 0)), 0, 16, 0, 1, 1, 17},
        };
        for (Object[] p : parts) {
            Chunk c = (Chunk) p[0];
            int z0 = (Integer) p[1], z1 = (Integer) p[2], x0 = (Integer) p[3], x1 = (Integer) p[4];
            int oz = (Integer) p[5], ox = (Integer) p[6];
            Object arr = c == null ? null : get.get(c);
            for (int r = 0; r < rows; r++) {
                int ly = r + y0 - 1 - (c == null ? 0 : c.minY);
                for (int z = z0; z < z1; z++) {
                    for (int x = x0; x < x1; x++) {
                        int v;
                        if (arr == null) {
                            v = missing;
                        } else if (ly < 0) {
                            v = below;
                        } else if (ly >= c.height) {
                            v = above;
                        } else {
                            int si = (ly * 16 + z) * 16 + x;
                            v = arr instanceof char[] ca ? ca[si] : ((byte[]) arr)[si];
                        }
                        int ti = (r * 18 + z + oz) * 18 + x + ox;
                        if (outI != null) {
                            outI[ti] = v;
                        } else {
                            outB[ti] = (byte) v;
                        }
                    }
                }
            }
        }
    }

    static long k(int dx, int dz) {
        return Tiles.key(dx, dz);
    }

    // ------------------------------------------------------------------ meshing

    public static byte[] meshChunk(Chunk ch, Map<Long, Chunk> nb, Integer yMin, Integer yMax, boolean openBottom, boolean caves) {
        return serialize(meshParts(ch, nb, yMin, yMax, openBottom, caves), ch.cx, ch.cz);
    }

    /** Biome tint of a quad at (x, y, z) by tint kind. */
    static void tint(Chunk ch, Palette.Tables pal, int kind, int[] fixed, int y, int z, int x, float[] out) {
        out[0] = out[1] = out[2] = 255f;
        if (kind == Models.TINT_NONE) {
            return;
        }
        if (kind == Models.TINT_FIXED) {
            out[0] = fixed[0];
            out[1] = fixed[1];
            out[2] = fixed[2];
            return;
        }
        int bi = ch.biome(y, z, x);
        int slot = kind == Models.TINT_GRASS ? 0 : kind == Models.TINT_FOLIAGE ? 1 : 2;
        int o = bi * 9 + slot * 3;
        out[0] = pal.biomeRgb[o];
        out[1] = pal.biomeRgb[o + 1];
        out[2] = pal.biomeRgb[o + 2];
    }

    /** -> 3 passes; caves=true leaves out dark caves below CAVE_Y (only with light data). */
    public static Pass[] meshParts(Chunk ch, Map<Long, Chunk> nb, Integer yMin, Integer yMax, boolean openBottom, boolean caves) {
        Models models = Palette.models;
        if (models == null || !models.available) {
            throw new IllegalStateException("Blockmodelle fehlen");
        }
        boolean[][] l = lut(Chunk.BLOCKS.size());
        boolean[] full = l[0];
        Palette.Tables pal = Palette.sync();
        Pass[] passes = {new Pass(), new Pass(), new Pass()};
        int y0 = yMin == null ? ch.minY : Math.max(ch.minY, yMin);
        int y1 = ch.minY + ch.height - 1;
        if (yMax != null) {
            y1 = Math.min(y1, yMax);
        }
        if (y1 < y0) {
            return passes;
        }
        int h = y1 - y0 + 1, rows = h + 2, size = rows * 18 * 18;
        int[] P = new int[size];
        pad(ch, nb, y0, y1, c -> c.blocks, 0, 0, 0, P, null);
        byte[] bl = new byte[size], sl = new byte[size];
        Map<Chunk, byte[][]> lit = new HashMap<>();
        java.util.function.Function<Chunk, byte[][]> light = c -> lit.computeIfAbsent(c, Chunk::light);
        boolean hasLight = light.apply(ch) != null;
        if (hasLight) {
            pad(ch, nb, y0, y1, c -> {
                byte[][] x = light.apply(c);
                return x == null ? null : x[0];
            }, 0, 0, 0, null, bl);
            pad(ch, nb, y0, y1, c -> {
                byte[][] x = light.apply(c);
                return x == null ? null : x[1];
            }, 0, 15, 15, null, sl);
        }
        // Layer cut: above (and below for "layer only") is air, so the cut faces are drawn closed.
        int layer = 18 * 18;
        if (yMax != null) {
            Arrays.fill(P, (rows - 1) * layer, rows * layer, 0);
            if (hasLight) {
                Arrays.fill(sl, (rows - 1) * layer, rows * layer, (byte) 15);
            }
        }
        if (openBottom) {
            Arrays.fill(P, 0, layer, 0);
            if (hasLight) {
                Arrays.fill(sl, 0, layer, (byte) 15);
            }
        }
        if (!hasLight) {
            Arrays.fill(bl, (byte) 0);
            Arrays.fill(sl, (byte) 15);
            caves = false;
        }
        boolean[] occ = new boolean[size];
        for (int i = 0; i < size; i++) {
            occ[i] = P[i] < full.length && full[P[i]];
        }
        int caveBelow = caves ? CAVE_Y - y0 : -1;

        // positions per block id, in (y, z, x) order
        Map<Integer, int[]> where = new java.util.TreeMap<>();
        int[] counts = new int[Chunk.BLOCKS.size() + 1];
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int v = P[((y + 1) * 18 + z + 1) * 18 + x + 1];
                    if (v >= counts.length) {
                        counts = Arrays.copyOf(counts, v + 1);
                    }
                    counts[v]++;
                }
            }
        }
        int[] fill = new int[counts.length];
        for (int v = 1; v < counts.length; v++) {
            if (counts[v] > 0) {
                where.put(v, new int[counts[v] * 3]);
            }
        }
        for (int y = 0; y < h; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int v = P[((y + 1) * 18 + z + 1) * 18 + x + 1];
                    if (v == 0) {
                        continue;
                    }
                    int[] a = where.get(v);
                    int f = fill[v]++;
                    a[f * 3] = y;
                    a[f * 3 + 1] = z;
                    a[f * 3 + 2] = x;
                }
            }
        }
        l = lut(Math.max(Chunk.BLOCKS.size(), counts.length));
        full = l[0];
        boolean[] water = l[1];
        float[] tint = new float[3];
        float[] ao = new float[4];
        float[] lt = new float[8];
        float[] rgb = new float[12];
        byte[] ltb = new byte[8];
        for (Map.Entry<Integer, int[]> e : where.entrySet()) {
            int sid = e.getKey();
            int[] pos = e.getValue();
            int n = pos.length / 3;
            Models.Compiled m = models.get(sid);
            if (!m.fluid.isEmpty()) {
                fluid(models, m.fluid, ch, P, pos, y0, passes, bl, sl, l, pal);
                continue;
            }
            if (water[sid]) {
                fluid(models, "water", ch, P, pos, y0, passes, bl, sl, l, pal);
            }
            if (m.quads == 0) {
                continue;
            }
            for (int q = 0; q < m.quads; q++) {
                int cd = m.cull[q];
                int nd = cd >= 0 ? cd : m.normal[q];
                int[] d = Models.DIR_VEC[nd];
                // corner directions along the two other axes
                int axisN = d[0] != 0 ? 0 : d[1] != 0 ? 1 : 2;
                int ta = axisN == 0 ? 1 : 0, tb = axisN == 2 ? 1 : 2;
                int[][] sg = new int[4][2];
                for (int v = 0; v < 4; v++) {
                    sg[v][0] = m.verts[q * 12 + v * 3 + ta] > 0.5f ? 1 : -1;
                    sg[v][1] = m.verts[q * 12 + v * 3 + tb] > 0.5f ? 1 : -1;
                }
                Pass out = passes[m.passes[q]];
                for (int j = 0; j < n; j++) {
                    int ys = pos[j * 3], zs = pos[j * 3 + 1], xs = pos[j * 3 + 2];
                    if (cd >= 0) {
                        int[] cv = Models.DIR_VEC[cd];
                        int nbv = P[((ys + 1 + cv[1]) * 18 + zs + 1 + cv[2]) * 18 + xs + 1 + cv[0]];
                        boolean keep = !(nbv < full.length && full[nbv]);
                        if (m.selfCull) {
                            keep &= nbv != sid;
                        }
                        if (!keep) {
                            continue;
                        }
                    }
                    int fy = ys + 1 + d[1], fz = zs + 1 + d[2], fx = xs + 1 + d[0];
                    int fi = (fy * 18 + fz) * 18 + fx;
                    int self = ((ys + 1) * 18 + zs + 1) * 18 + xs + 1;
                    if (cd < 0) {
                        int b = Math.max(bl[fi], bl[self]), s = Math.max(sl[fi], sl[self]);
                        for (int v = 0; v < 4; v++) {
                            ao[v] = 1f;
                            ltb[v * 2] = (byte) (b * 17);
                            ltb[v * 2 + 1] = (byte) (s * 17);
                        }
                    } else {
                        float b0 = bl[fi], s0 = sl[fi];
                        for (int v = 0; v < 4; v++) {
                            int[] o1 = new int[3], o2 = new int[3];
                            o1[ta] = sg[v][0];
                            o2[tb] = sg[v][1];
                            int i1 = ((fy + o1[1]) * 18 + fz + o1[2]) * 18 + fx + o1[0];
                            int i2 = ((fy + o2[1]) * 18 + fz + o2[2]) * 18 + fx + o2[0];
                            int ic = ((fy + o1[1] + o2[1]) * 18 + fz + o1[2] + o2[2]) * 18 + fx + o1[0] + o2[0];
                            boolean s1 = occ[i1], s2 = occ[i2], c = occ[ic];
                            int lvl = s1 && s2 ? 0 : 3 - (s1 ? 1 : 0) - (s2 ? 1 : 0) - (c ? 1 : 0);
                            ao[v] = AO_LEVEL[lvl];
                            float bs = b0, ss = s0, kk = 1f;
                            if (!s1) {
                                bs += bl[i1];
                                ss += sl[i1];
                                kk += 1f;
                            }
                            if (!s2) {
                                bs += bl[i2];
                                ss += sl[i2];
                                kk += 1f;
                            }
                            if (!(c || (s1 && s2))) {
                                bs += bl[ic];
                                ss += sl[ic];
                                kk += 1f;
                            }
                            ltb[v * 2] = (byte) (int) Math.rint((float) (bs / kk) * 17f);
                            ltb[v * 2 + 1] = (byte) (int) Math.rint((float) (ss / kk) * 17f);
                        }
                    }
                    if (caveBelow > 0 && ys < caveBelow) {
                        boolean any = false;
                        for (byte b : ltb) {
                            any |= b != 0;
                        }
                        if (!any) {
                            continue;
                        }
                    }
                    tint(ch, pal, m.tint[q], m.fixedTint, ys + y0, zs, xs, tint);
                    float sh = m.shade[q];
                    for (int v = 0; v < 4; v++) {
                        for (int c = 0; c < 3; c++) {
                            rgb[v * 3 + c] = (tint[c] * sh) * ao[v];
                        }
                    }
                    boolean flip = ao[0] + ao[2] < ao[1] + ao[3];
                    out.grow();
                    int qn = out.n;
                    for (int v = 0; v < 4; v++) {
                        int sv = flip ? ROLL[v] : v;
                        out.pos[qn * 12 + v * 3] = (float) xs + m.verts[q * 12 + sv * 3];
                        out.pos[qn * 12 + v * 3 + 1] = (float) (ys + y0) + m.verts[q * 12 + sv * 3 + 1];
                        out.pos[qn * 12 + v * 3 + 2] = (float) zs + m.verts[q * 12 + sv * 3 + 2];
                        out.uv[qn * 8 + v * 2] = m.uv[q * 8 + sv * 2];
                        out.uv[qn * 8 + v * 2 + 1] = m.uv[q * 8 + sv * 2 + 1];
                        out.rgb[qn * 12 + v * 3] = rgb[sv * 3];
                        out.rgb[qn * 12 + v * 3 + 1] = rgb[sv * 3 + 1];
                        out.rgb[qn * 12 + v * 3 + 2] = rgb[sv * 3 + 2];
                        out.lt[qn * 8 + v * 2] = ltb[sv * 2];
                        out.lt[qn * 8 + v * 2 + 1] = ltb[sv * 2 + 1];
                    }
                    out.n++;
                }
            }
        }
        return passes;
    }

    private static void fluid(Models models, String kind, Chunk ch, int[] P, int[] pos, int y0, Pass[] passes,
                              byte[] bl, byte[] sl, boolean[][] l, Palette.Tables pal) {
        Integer tex = models.textures.get(kind.equals("water") ? "block/water_still" : "block/lava_still");
        if (tex == null) {
            return;
        }
        boolean[] same = kind.equals("water") ? l[1] : l[2];
        boolean[] full = l[0];
        int col = tex % models.cols, row = tex / models.cols, t = models.tile;
        float u0 = col * t, v0 = row * t;
        float[] uvFace = {u0, v0, u0, v0 + t, u0 + t, v0 + t, u0 + t, v0};
        Pass out = passes[kind.equals("water") ? Models.PASS_TRANSLUCENT : Models.PASS_OPAQUE];
        int n = pos.length / 3;
        float[] tint = new float[3];
        for (int d = 0; d < 6; d++) {
            int[] dv = Models.DIR_VEC[d];
            double[][] corners = Models.faceCorners(new double[] {0, 0, 0}, new double[] {1, 1, 1}, Models.DIRS[d]);
            for (int j = 0; j < n; j++) {
                int ys = pos[j * 3], zs = pos[j * 3 + 1], xs = pos[j * 3 + 2];
                int above = P[((ys + 2) * 18 + zs + 1) * 18 + xs + 1];
                float topH = above < same.length && same[above] ? 1.0f : FLUID_TOP;
                int nbv = P[((ys + 1 + dv[1]) * 18 + zs + 1 + dv[2]) * 18 + xs + 1 + dv[0]];
                boolean nSame = nbv < same.length && same[nbv], nFull = nbv < full.length && full[nbv];
                boolean keep = d == 1 ? !nSame && !(nFull && topH >= 1.0f) : !nSame && !nFull;
                if (!keep) {
                    continue;
                }
                if (kind.equals("water")) {
                    tint(ch, pal, Models.TINT_WATER, null, ys + y0, zs, xs, tint);
                } else {
                    tint[0] = tint[1] = tint[2] = 255f;
                }
                int fi = ((ys + 1 + dv[1]) * 18 + zs + 1 + dv[2]) * 18 + xs + 1 + dv[0];
                int self = ((ys + 1) * 18 + zs + 1) * 18 + xs + 1;
                int b = kind.equals("lava") ? 15 : Math.max(bl[fi], bl[self]);
                int s = Math.max(sl[fi], sl[self]);
                out.grow();
                int qn = out.n;
                for (int v = 0; v < 4; v++) {
                    float cy = (float) corners[v][1];
                    out.pos[qn * 12 + v * 3] = (float) xs + (float) corners[v][0];
                    out.pos[qn * 12 + v * 3 + 1] = cy > 0.5f ? (float) (ys + y0) + topH : (float) (ys + y0) + cy;
                    out.pos[qn * 12 + v * 3 + 2] = (float) zs + (float) corners[v][2];
                    out.uv[qn * 8 + v * 2] = uvFace[v * 2];
                    out.uv[qn * 8 + v * 2 + 1] = uvFace[v * 2 + 1];
                    for (int c = 0; c < 3; c++) {
                        out.rgb[qn * 12 + v * 3 + c] = tint[c] * Models.SHADE[d];
                    }
                    out.lt[qn * 8 + v * 2] = (byte) (b * 17);
                    out.lt[qn * 8 + v * 2 + 1] = (byte) (s * 17);
                }
                out.n++;
            }
        }
    }

    public static byte[] serialize(Pass[] parts, int cx, int cz) {
        int total = 12 + 12;
        for (Pass p : parts) {
            total += p.n * (24 + 16 + 12 + 8);
        }
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.put(MAGIC.getBytes(StandardCharsets.US_ASCII));
        b.putInt(cx);
        b.putInt(cz);
        for (Pass p : parts) {
            b.putInt(p.n);
            for (int i = 0; i < p.n * 12; i++) {
                b.putShort((short) (int) Math.rint(p.pos[i] * 16f));
            }
            for (int i = 0; i < p.n * 8; i++) {
                b.putShort((short) (int) Math.rint(p.uv[i] * 16f));
            }
            for (int i = 0; i < p.n * 12; i++) {
                float v = p.rgb[i];
                b.put((byte) (int) (v <= 0 ? 0 : v >= 255 ? 255 : v));
            }
            b.put(p.lt, 0, p.n * 8);
        }
        return b.array();
    }

    /**
     * Standalone block cuboid (schematic), blocks [(y*L+z)*W+x]. Split into 16x16 columns and
     * meshed like chunks; positions relative to corner (0, 0, 0).
     */
    public static byte[] meshVolume(char[] blocks, int w, int h, int l) {
        return meshVolume(blocks, w, h, l, null);
    }

    /** yMax: layer cut, only blocks up to this height, with the cut face drawn closed (like /api/mesh). */
    public static byte[] meshVolume(char[] blocks, int w, int h, int l, Integer yMax) {
        int nz = (l + 15) / 16, nx = (w + 15) / 16;
        int plains = Chunk.BIOMES.id("minecraft:plains");
        Map<Long, Chunk> cols = new HashMap<>();
        List<long[]> order = new ArrayList<>();
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                char[] b = new char[h * 256];
                boolean any = false;
                for (int y = 0; y < h; y++) {
                    for (int z = 0; z < 16 && j * 16 + z < l; z++) {
                        for (int x = 0; x < 16 && i * 16 + x < w; x++) {
                            char v = blocks[(y * l + j * 16 + z) * w + i * 16 + x];
                            b[(y * 16 + z) * 16 + x] = v;
                            any |= v != 0;
                        }
                    }
                }
                if (any) {
                    char[] bio = new char[((h + 3) / 4) * 16];
                    Arrays.fill(bio, (char) plains);
                    cols.put(Tiles.key(i, j), new Chunk(i, j, 0, h, b, bio, List.of(), Map.of()));
                    order.add(new long[] {i, j});
                }
            }
        }
        Pass[] merged = {new Pass(), new Pass(), new Pass()};
        for (long[] ij : order) {
            int i = (int) ij[0], j = (int) ij[1];
            Map<Long, Chunk> nb = new HashMap<>();
            for (int[] dd : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Chunk c = cols.get(Tiles.key(i + dd[0], j + dd[1]));
                if (c != null) {
                    nb.put(k(dd[0], dd[1]), c);
                }
            }
            Pass[] parts = meshParts(cols.get(Tiles.key(i, j)), nb, 0, yMax, false, false);
            for (int p = 0; p < 3; p++) {
                merged[p].append(parts[p], i * 16, j * 16);
            }
        }
        return serialize(merged, 0, 0);
    }

    // ------------------------------------------------------------------ what /api/mesh computes

    static Map<Long, Chunk> neighbors(java.util.function.BiFunction<Integer, Integer, Chunk> get, int cx, int cz) {
        Map<Long, Chunk> nb = new HashMap<>();
        for (int[] d : new int[][] {{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            Chunk c = get.apply(cx + d[0], cz + d[1]);
            if (c != null) {
                nb.put(k(d[0], d[1]), c);
            }
        }
        return nb;
    }

    /** gzip'd mesh of a chunk (at: state at the start of that day, from the mod's daily backups). */
    static byte[] compute(Recording rec, Recording.Dimension dim, int cx, int cz, boolean full, Integer ylo, Integer yhi,
                          List<String> names, String at, History hist) {
        java.util.function.BiFunction<Integer, Integer, Chunk> get = at != null
                ? (x, z) -> hist.chunkAt(rec, dim, at, x, z) : (x, z) -> rec.chunk(dim, x, z);
        Chunk ch = get.apply(cx, cz);
        if (ch == null) {
            return null;
        }
        Map<Long, Chunk> nb = neighbors(get, cx, cz);
        if (!names.isEmpty()) {
            // X-ray: only the searched blocks, the whole height (or the layer cut), with their positions behind it
            Xray.Lookup look = Xray.lookup(names);
            Map<Long, Chunk> onb = new HashMap<>();
            nb.forEach((kk, c) -> onb.put(kk, Xray.only(c, look)));
            byte[] data = meshChunk(Xray.only(ch, look), onb, ylo, yhi, ylo != null, false);
            byte[] tr = Xray.trailer(ch, look, names.size());
            byte[] all = Arrays.copyOf(data, data.length + tr.length);
            System.arraycopy(tr, 0, all, data.length, tr.length);
            return App.gzip(all, 3);
        }
        if (yhi != null) {
            return App.gzip(meshChunk(ch, nb, ylo, yhi, ylo != null, false), 3);
        }
        Integer yMin = null;
        if (!full && !dim.key.equals("the_nether")) {
            Tiles.Rendered r = Tiles.renderChunk(ch, Tiles.Layer.parse(""));
            int lo = Integer.MAX_VALUE;
            for (int i = 0; i < 256; i++) {
                if (r.has[i]) {
                    lo = Math.min(lo, r.height[i]);
                }
            }
            if (lo != Integer.MAX_VALUE) {
                yMin = lo - 12;
            }
        }
        return App.gzip(meshChunk(ch, nb, yMin, null, false, full && dim.key.equals("overworld")), 3);
    }

    static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
