package io.github.ehnkode.webmatica;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

/** The in-game menu (F8): search, jobs, settings and the way to the browser viewer. */
final class WebmaticaScreen extends WmScreen {
    private static final int W = 220;

    private Button pause;
    private Button stop;
    private int jobsY;

    WebmaticaScreen(Screen parent) {
        super("Webmatica", parent);
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        if (IngameConfig.get().viewerInBackground || !ViewerLauncher.running()) {
            ViewerLauncher.startInBackground(mc);
        }
        ChestIndex.refresh(mc, true);
        int x = (width - W) / 2;
        int y = Math.max(30, height / 2 - 100);
        button("Kisten durchsuchen", x, y, W, b -> Mc.setScreen(mc, new ChestSearchScreen(this, "")));
        button("Viewer im Browser öffnen", x, y + 24, W, b -> {
            Mc.setScreen(mc, null);
            ViewerLauncher.open(mc);
        });
        button("Einstellungen", x, y + 48, W, b -> Mc.setScreen(mc, new SettingsScreen(this)));
        jobsY = y + 82;
        int half = (W - 4) / 2;
        pause = button("Pause", x, jobsY + 52, half, b -> IngameActions.bot(mc, JobStatus.paused() ? "resume" : "pause"));
        stop = button("Stopp", x + half + 4, jobsY + 52, half, b -> IngameActions.bot(mc, "stop"));
        button("Fertig", x, jobsY + 84, W, b -> onClose());
    }

    @Override
    public void tick() {
        boolean busy = JobStatus.busy();
        pause.active = busy;
        stop.active = busy;
        pause.setMessage(c(JobStatus.paused() ? "Weiter" : "Pause"));
    }

    @Override
    protected void draw(Gfx g, int mouseX, int mouseY) {
        int x = (width - W) / 2;
        int top = Math.max(30, height / 2 - 100);
        g.centered(font, title.getString(), width / 2, top - 22, WHITE);
        String rec = t(RegionStore.getInstance().isEnabled() ? "Aufnahme an" : "Aufnahme aus");
        String safe = ServerSafe.on() ? " · " + t("Server-safe an") : "";
        String viewer = " · " + t(ViewerLauncher.running() ? "Viewer bereit" : "Viewer startet …");
        g.centered(font, rec + safe + viewer, width / 2, top - 10, GRAY);

        g.text(font, t("Jobs"), x, jobsY, GOLD);
        List<String> lines = JobStatus.lines();
        if (lines.isEmpty()) {
            g.text(font, t("Gerade läuft nichts."), x, jobsY + 12, GRAY);
        } else {
            for (int i = 0; i < Math.min(3, lines.size()); i++) {
                g.text(font, font.plainSubstrByWidth(lines.get(i), W), x, jobsY + 12 + i * 11, WHITE);
            }
        }
    }
}
