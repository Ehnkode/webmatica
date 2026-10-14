package io.github.ehnkode.webmatica;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Server-safe: turned on per server in the viewer's options. While it is on,
 * everything most public servers forbid is off: Baritone and every bot task,
 * the Litematica Printer and X-Ray. Webmatica's own printer keeps working, but
 * only places what a player could place by hand (see {@link WebPrinter}).
 *
 * The switch comes with /api/control (BuildControl). The last known value is
 * kept while the viewer doesn't answer, so a short hiccup never turns it off.
 */
public final class ServerSafe {
    private static volatile boolean on;
    private static String server;

    private ServerSafe() {
    }

    public static boolean on() {
        return on;
    }

    /** Client thread. */
    static void set(Minecraft mc, String srv, boolean value) {
        if (srv != null && !srv.equals(server)) {
            server = srv;
            on = false;
        }
        if (value == on) {
            return;
        }
        on = value;
        if (value) {
            stopBots();
        }
        Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(value ? "Server-safe AN: Bot-Funktionen aus" : "Server-safe AUS"))
                .withStyle(value ? ChatFormatting.GREEN : ChatFormatting.YELLOW));
    }

    /** Stops whatever runs right now: tasks, Baritone, its changed settings. */
    private static void stopBots() {
        AutoGuard.setPaused(false);
        FetchTask.getInstance().cancel();
        UnpackTask.getInstance().cancel();
        FarmTask.getInstance().cancel();
        Walker.stop();
        try {
            BaritoneApi.execute("stop");
        } catch (Throwable ignored) {
            // no Baritone: nothing to stop
        }
        BaritoneSafety.restore();
    }
}
