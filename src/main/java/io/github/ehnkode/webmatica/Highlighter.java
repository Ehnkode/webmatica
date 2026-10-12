package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.particle.ParticleTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * Markiert Kisten im Spiel (Knopf "Im Spiel zeigen" auf der Webseite): eine
 * Lichtsaeule aus Partikeln ueber jeder Kiste, 60 Sekunden lang. Nur im eigenen
 * Client sichtbar, der Server bekommt davon nichts mit.
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

    /** Client-Thread. */
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

    public void tick(MinecraftClient mc) {
        if (points.isEmpty() || mc.world == null || mc.player == null) {
            return;
        }
        if (System.currentTimeMillis() > until) {
            points.clear();
            return;
        }
        if (++tick % 3 != 0) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        for (int[] q : points) {
            double dx = q[0] + 0.5 - p.getX(), dz = q[2] + 0.5 - p.getZ();
            if (dx * dx + dz * dz > 160 * 160) {
                continue;
            }
            // Saeule nach oben, damit man sie auch hinter Waenden von weitem sieht
            for (int k = 0; k < 6; k++) {
                double y = q[1] + 1.0 + k * 1.5 + (tick % 9) / 6.0;
                mc.world.addParticleClient(ParticleTypes.END_ROD, true, true, q[0] + 0.5, y, q[2] + 0.5, 0.0, 0.02, 0.0);
            }
            mc.world.addParticleClient(ParticleTypes.HAPPY_VILLAGER, true, true,
                    q[0] + 0.5 + (Math.random() - 0.5), q[1] + 0.6 + Math.random() * 0.6, q[2] + 0.5 + (Math.random() - 0.5), 0, 0, 0);
        }
    }
}
