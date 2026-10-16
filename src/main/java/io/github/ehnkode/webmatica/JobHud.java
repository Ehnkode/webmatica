package io.github.ehnkode.webmatica;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.resources.Identifier;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

/** Small box at the screen edge while a job runs. Can be switched off in the menu or by key. */
final class JobHud {
    private static final int PAD = 4;
    private static final int MAX_W = 220;

    private JobHud() {
    }

    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("webmatica", "jobs"), (g, tick) -> draw(new Gfx(g)));
    }

    private static void draw(Gfx g) {
        Minecraft mc = Minecraft.getInstance();
        IngameConfig cfg = IngameConfig.get();
        if (!cfg.hud || mc.player == null || Mc.guiHidden(mc) || Mc.screen(mc) instanceof WmScreen) {
            return;
        }
        List<String> lines = JobStatus.lines();
        if (lines.isEmpty()) {
            return;
        }
        Font font = mc.font;
        String head = "Webmatica";
        int w = font.width(head);
        for (String l : lines) {
            w = Math.max(w, Math.min(MAX_W, font.width(l)));
        }
        int h = (lines.size() + 1) * 10 + PAD * 2 - 1;
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        int x = (cfg.hudCorner & 1) == 0 ? 4 : sw - w - PAD * 2 - 4;
        int y = cfg.hudCorner < 2 ? 4 : sh - h - 60;
        g.fill(x, y, x + w + PAD * 2, y + h, 0x90000000);
        g.text(font, head, x + PAD, y + PAD, WmScreen.GOLD);
        for (int i = 0; i < lines.size(); i++) {
            g.text(font, font.plainSubstrByWidth(lines.get(i), MAX_W), x + PAD, y + PAD + 10 * (i + 1), WmScreen.WHITE);
        }
    }
}
