package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;

/**
 * Highlights chests in game ("Show in game" button on the web page): a
 * light pillar of particles above each chest, for 60 seconds or until the
 * chest is opened. Only visible in your own client, the server knows nothing about it.
 */
public final class Highlighter {

    private static final Highlighter INSTANCE = new Highlighter();
    private static final long DURATION_MS = 60_000L;

    public static Highlighter getInstance() {
        return INSTANCE;
    }

    private final List<int[]> points = new ArrayList<>();
    private long until;
    private int tick;

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

    public void tick(Minecraft mc) {
        if (points.isEmpty() || mc.level == null || mc.player == null) {
            return;
        }
        if (System.currentTimeMillis() > until) {
            points.clear();
            return;
        }
        if (++tick % 3 != 0) {
            return;
        }
        LocalPlayer p = mc.player;
        for (int[] q : points) {
            double dx = q[0] + 0.5 - p.getX(), dz = q[2] + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 160 * 160) {
                continue;
            }
            // pillar upwards so it can be seen from afar, even behind walls
            for (int k = 0; k < 6; k++) {
                double y = q[1] + 1.0 + k * 1.5 + (tick % 9) / 6.0;
                mc.level.addParticle(ParticleTypes.END_ROD, true, true, q[0] + 0.5, y, q[2] + 0.5, 0.0, 0.02, 0.0);
            }
            mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, true, true,
                    q[0] + 0.5 + (Math.random() - 0.5), q[1] + 0.6 + Math.random() * 0.6, q[2] + 0.5 + (Math.random() - 0.5), 0, 0, 0);
        }
    }
}
