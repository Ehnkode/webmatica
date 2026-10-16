package io.github.ehnkode.webmatica;

import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/**
 * /wm on the client only: never sent to the server, so it shows up nowhere else.
 * /wm, /wm search <item>, /wm stop, /wm pause, /wm resume, /wm hud
 */
final class WmCommand {
    private static Screen pending;

    private WmCommand() {
    }

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, ctx) -> dispatcher.register(
                ClientCommandManager.literal("wm")
                        .executes(c -> open(new WebmaticaScreen(null)))
                        .then(ClientCommandManager.literal("search")
                                .executes(c -> open(new ChestSearchScreen(null, "")))
                                .then(ClientCommandManager.argument("item", StringArgumentType.greedyString())
                                        .executes(c -> open(new ChestSearchScreen(null, StringArgumentType.getString(c, "item"))))))
                        .then(ClientCommandManager.literal("stop").executes(c -> bot("stop")))
                        .then(ClientCommandManager.literal("pause").executes(c -> bot("pause")))
                        .then(ClientCommandManager.literal("resume").executes(c -> bot("resume")))
                        .then(ClientCommandManager.literal("hud").executes(c -> {
                            toggleHud(Minecraft.getInstance());
                            return 1;
                        }))));
    }

    private static int open(Screen s) {
        pending = s;            // the chat closes after the command; open on the next tick
        return 1;
    }

    private static int bot(String kind) {
        IngameActions.bot(Minecraft.getInstance(), kind);
        return 1;
    }

    static void toggleHud(Minecraft mc) {
        IngameConfig cfg = IngameConfig.get();
        cfg.hud = !cfg.hud;
        cfg.save();
        IngameActions.say(mc, cfg.hud ? "Job-Anzeige an" : "Job-Anzeige aus", net.minecraft.ChatFormatting.AQUA);
    }

    static void tick(Minecraft mc) {
        if (pending != null && Mc.screen(mc) == null) {
            Mc.setScreen(mc, pending);
            pending = null;
        }
    }
}
