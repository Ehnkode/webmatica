package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;

/**
 * Every walk of the bot jobs and of "goto" goes through here. It picks the
 * engine: Baritone when it is installed, otherwise Webmatica's own
 * {@link NativeWalker}. The viewer's option "engine" can force one
 * ("native", "baritone"; default "auto").
 *
 * In auto mode it also watches Baritone: if the player hardly moves for a while
 * although Baritone is on the way, or the same goal is asked for again because
 * Baritone gave up, the walk continues with the own engine.
 *
 * Client thread.
 */
final class Walker {

    private static final long STUCK_MS = 10_000L;

    private Walker() {
    }

    private enum Kind { NEXT_TO, ONTO, XZ }

    private static Kind kind;
    private static BlockPos target;
    private static boolean baritone;
    private static Vec3 watchPos;
    private static long watchAt;

    static boolean baritoneInstalled() {
        try {
            Class.forName("baritone.api.BaritoneAPI");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** "auto", "native" or "baritone", from the viewer's options. */
    static String mode() {
        JsonObject g = BuildControl.getInstance().guard();
        if (g != null && g.has("engine") && g.get("engine").isJsonPrimitive()) {
            String m = g.get("engine").getAsString();
            if (m.equals("native") || m.equals("baritone")) {
                return m;
            }
        }
        return "auto";
    }

    /** Walk next to the block (close enough to open it). */
    static void walkTo(BlockPos pos) throws Exception {
        start(Kind.NEXT_TO, pos);
    }

    /** Walk onto the block. */
    static void walkOnto(BlockPos pos) throws Exception {
        start(Kind.ONTO, pos);
    }

    /** Walk to x/z (any height) or onto x/y/z. */
    static void gotoPos(int x, Integer y, int z) throws Exception {
        if (y != null) {
            start(Kind.ONTO, new BlockPos(x, y, z));
        } else {
            LocalPlayer p = Minecraft.getInstance().player;
            start(Kind.XZ, new BlockPos(x, p != null ? p.getBlockY() : 64, z));
        }
    }

    /**
     * Throws "Baritone nicht gefunden" only when the options insist on Baritone
     * and it is missing; other Baritone errors fall back to the own engine in
     * auto mode.
     */
    private static void start(Kind k, BlockPos pos) throws Exception {
        String mode = mode();
        boolean again = baritone && k == kind && pos.equals(target) && !BaritoneApi.goalActive();
        boolean useBaritone = mode.equals("baritone") || mode.equals("auto") && baritoneInstalled() && !again;
        kind = k;
        target = pos;
        stopAll();
        if (useBaritone) {
            try {
                switch (k) {
                    case NEXT_TO -> BaritoneApi.walkTo(pos);
                    case ONTO -> BaritoneApi.walkOnto(pos);
                    case XZ -> BaritoneApi.walkXZ(pos.getX(), pos.getZ());
                }
                baritone = true;
                watch();
                return;
            } catch (ClassNotFoundException e) {
                if (mode.equals("baritone")) {
                    throw new IllegalStateException("Baritone nicht gefunden");
                }
            } catch (Exception e) {
                if (mode.equals("baritone")) {
                    throw e;
                }
            }
        } else if (again) {
            note("Baritone kommt nicht weiter, eigene Engine läuft weiter");
        }
        startNative();
    }

    private static void startNative() {
        baritone = false;
        switch (kind) {
            case NEXT_TO -> NativeWalker.walkTo(target);
            case ONTO -> NativeWalker.walkOnto(target);
            case XZ -> NativeWalker.walkXZ(target.getX(), target.getZ(), target.getY());
        }
    }

    /** Still on the way. */
    static boolean active() {
        if (target == null) {
            return false;
        }
        if (!baritone) {
            return NativeWalker.active();
        }
        try {
            return BaritoneApi.walking();
        } catch (Throwable t) {
            return false;
        }
    }

    /** Running with Baritone (false: own engine or nothing). */
    static boolean usingBaritone() {
        return target != null && baritone;
    }

    static void stop() {
        if (target == null) {
            return;
        }
        stopAll();
        target = null;
        kind = null;
    }

    private static void stopAll() {
        if (baritone) {
            BaritoneApi.stop();
        }
        NativeWalker.stop();
    }

    private static void watch() {
        LocalPlayer p = Minecraft.getInstance().player;
        watchPos = p != null ? p.position() : null;
        watchAt = System.currentTimeMillis();
    }

    static void tick(Minecraft mc) {
        NativeWalker.tick(mc);
        if (target == null || !baritone) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || AutoGuard.paused() || AutoGuard.eating() || !mode().equals("auto")) {
            watch();
            return;
        }
        boolean walking;
        try {
            walking = BaritoneApi.walking();
        } catch (Throwable t) {
            walking = false;
        }
        if (!walking) {
            watch();
            return;
        }
        if (watchPos == null || p.position().distanceTo(watchPos) > 1.5) {
            watch();
        } else if (System.currentTimeMillis() - watchAt > STUCK_MS) {
            BaritoneApi.stop();
            note("Baritone steckt fest, eigene Engine läuft weiter");
            startNative();
        }
    }

    private static void note(String s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(s)).withStyle(ChatFormatting.YELLOW));
        }
    }
}
