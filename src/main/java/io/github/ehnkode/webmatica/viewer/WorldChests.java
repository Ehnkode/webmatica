package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.Nbt;
import io.github.ehnkode.webmatica.viewer.core.Recording;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chests from the recording: every storage block, even never opened ones (position only). */
final class WorldChests {
    /** hoppers/droppers are mostly farms */
    static final Set<String> STORAGE = Set.of("chest", "trapped_chest", "barrel", "shulker_box");
    static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    static final Map<String, String> CW = Map.of("north", "east", "east", "south", "south", "west", "west", "north");
    static final Map<String, String> CCW = Map.of("east", "north", "south", "east", "west", "south", "north", "west");
    static final Map<String, int[]> STEP = Map.of("north", new int[] {0, -1}, "south", new int[] {0, 1},
            "east", new int[] {1, 0}, "west", new int[] {-1, 0});

    private WorldChests() {
    }

    static int cmp(int[] a, int[] b) {
        return Stores.compare(a[0], a[1], a[2], b[0], b[1], b[2]);
    }

    /** All storage blocks in the recorded chunks around (cx0, cz0); double chests under the smaller half. */
    static List<Map<String, Object>> find(Recording rec, Recording.Dimension dim, int cx0, int cz0, int r) {
        Map<List<Integer>, Map<String, Object>> out = new LinkedHashMap<>();
        for (int cz = cz0 - r; cz <= cz0 + r; cz++) {
            for (int cx = cx0 - r; cx <= cx0 + r; cx++) {
                Chunk ch;
                try {
                    ch = rec.chunk(dim, cx, cz);
                } catch (RuntimeException e) {
                    continue;
                }
                if (ch == null) {
                    continue;
                }
                for (Map<String, Object> be : ch.blockEntities) {
                    String id = String.valueOf(be.getOrDefault("id", ""));
                    String kind = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
                    if (!STORAGE.contains(kind) && !kind.endsWith("shulker_box")) {
                        continue;
                    }
                    Integer x = Nbt.intOrNull(be.get("x")), y = Nbt.intOrNull(be.get("y")), z = Nbt.intOrNull(be.get("z"));
                    if (x == null || y == null || z == null) {
                        continue;
                    }
                    String state = ch.block(x, y, z);
                    String block = Chunk.baseName(state);
                    if (AIR.contains(block)) {
                        continue;
                    }
                    int[] self = {x, y, z};
                    int[] pos = self, other = self;
                    if (kind.equals("chest") || kind.equals("trapped_chest")) {
                        Map<String, String> pr = Chunk.stateProps(state);
                        String t = pr.get("type"), f = pr.get("facing");
                        if (("left".equals(t) || "right".equals(t)) && f != null && CW.containsKey(f)) {
                            String d = t.equals("left") ? CW.get(f) : CCW.get(f);
                            other = new int[] {x + STEP.get(d)[0], y, z + STEP.get(d)[1]};
                            pos = cmp(self, other) <= 0 ? self : other;
                        }
                    }
                    List<Integer> key = List.of(pos[0], pos[1], pos[2]);
                    Map<String, Object> e = out.get(key);
                    if (e == null) {
                        e = J.map("x", pos[0], "y", pos[1], "z", pos[2], "block", block);
                        out.put(key, e);
                    }
                    if (other != pos || self != pos) {
                        int[] mx = cmp(self, other) >= 0 ? self : other;
                        e.put("other", List.of(mx[0], mx[1], mx[2]));
                    }
                }
            }
        }
        return new ArrayList<>(out.values());
    }
}
