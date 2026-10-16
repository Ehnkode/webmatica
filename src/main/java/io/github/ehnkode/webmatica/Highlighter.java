package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.world.phys.Vec3;

/**
 * Highlights chests in game ("Show in game" button on the web page): a
 * box around each chest and a line above it, both seen through walls, for 60 seconds or until the
 * chest is opened. Only visible in your own client, the server knows nothing about it.
 */
public final class Highlighter {

    private static final Highlighter INSTANCE = new Highlighter();
    private static final long DURATION_MS = 60_000L;
    private static final int COLOR = 0xFFFFD23F;
    private static final int FILL = 0x50FFD23F;

    public static Highlighter getInstance() {
        return INSTANCE;
    }

    private final List<int[]> points = new ArrayList<>();
    private long until;

    /** Client thread. */
    public int set(JsonArray pts, String label) {
        points.clear();
        if (pts != null) {
            for (JsonElement e : pts) {
                JsonArray a = e.getAsJsonArray();
                points.add(new int[]{a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt()});
                if (points.size() >= 64) {
                    break;
                }
            }
        }
        until = System.currentTimeMillis() + DURATION_MS;
        return points.size();
    }

    /** Client thread: a chest was opened, its mark (both halves of a double chest) goes away. */
    public void opened(BlockPos pos, BlockPos other) {
        points.removeIf(q -> is(q, pos) || is(q, other));
    }

    private static boolean is(int[] q, BlockPos p) {
        return p != null && q[0] == p.getX() && q[1] == p.getY() && q[2] == p.getZ();
    }

    public void clear() {
        points.clear();
    }

    public int count() {
        return System.currentTimeMillis() > until ? 0 : points.size();
    }

    /** End of the client tick (inside the game's per-tick gizmo collection). */
    public void tick(Minecraft mc) {
        if (points.isEmpty() || mc.level == null || mc.player == null) {
            return;
        }
        if (System.currentTimeMillis() > until) {
            points.clear();
            return;
        }
        LocalPlayer p = mc.player;
        for (int[] q : points) {
            double dx = q[0] + 0.5 - p.getX(), dz = q[2] + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 256 * 256) {
                continue;
            }
            // the chest as a box through walls, like Baritone's goal, and a line up to find it from afar
            Gizmos.cuboid(new BlockPos(q[0], q[1], q[2]), 0.04f, GizmoStyle.strokeAndFill(COLOR, 2.5f, FILL)).setAlwaysOnTop();
            Gizmos.line(new Vec3(q[0] + 0.5, q[1] + 1.04, q[2] + 0.5), new Vec3(q[0] + 0.5, q[1] + 40, q[2] + 0.5), COLOR, 2.0f)
                    .setAlwaysOnTop();
        }
    }
}
