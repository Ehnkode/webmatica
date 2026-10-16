package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Settings of the in-game menu. Saved in config/webmatica.json, server-safe in the viewer. */
final class SettingsScreen extends WmScreen {
    private static final int W = 240;
    private static final String[] CORNERS = {"oben links", "oben rechts", "unten links", "unten rechts"};

    SettingsScreen(Screen parent) {
        super("Webmatica: Einstellungen", parent);
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        IngameConfig cfg = IngameConfig.get();
        int x = (width - W) / 2;
        int y = Math.max(30, height / 2 - 90);

        toggle(x, y, "Aufnahme", () -> RegionStore.getInstance().isEnabled(),
                () -> RegionStore.getInstance().setEnabled(!RegionStore.getInstance().isEnabled()),
                "Speichert jeden Chunk, den du siehst (wie F9)");
        toggle(x, y + 24, "Server-safe", ServerSafe::on, () -> setSafe(mc, !ServerSafe.on()),
                "Schaltet alles aus, was viele Server verbieten: Bot, Printer, X-Ray");
        toggle(x, y + 48, "Job-Anzeige (HUD)", () -> cfg.hud, () -> {
            cfg.hud = !cfg.hud;
            cfg.save();
        }, "Zeigt laufende Jobs am Bildschirmrand, nur solange einer läuft");
        Button corner = button(label("HUD-Position", t(CORNERS[cfg.hudCorner])), x, y + 72, W, b -> {
            cfg.hudCorner = (cfg.hudCorner + 1) % 4;
            cfg.save();
            b.setMessage(Component.literal(label("HUD-Position", t(CORNERS[cfg.hudCorner]))));
        });
        corner.setMessage(Component.literal(label("HUD-Position", t(CORNERS[cfg.hudCorner]))));
        toggle(x, y + 96, "Kisten-Info im Tooltip", () -> cfg.tooltip, () -> {
            cfg.tooltip = !cfg.tooltip;
            cfg.save();
        }, "Zeigt bei jedem Item, wie viel davon in deinen Kisten liegt");
        toggle(x, y + 120, "Viewer im Hintergrund starten", () -> cfg.viewerInBackground, () -> {
            cfg.viewerInBackground = !cfg.viewerInBackground;
            cfg.save();
        }, "Startet den Viewer beim Betreten einer Welt ohne Browser, damit Kisten auch ohne F10 gespeichert werden");
        button("Fertig", x, y + 156, W, b -> onClose());
    }

    private void toggle(int x, int y, String name, BooleanSupplier get, Runnable flip, String help) {
        Supplier<String> text = () -> label(name, t(get.getAsBoolean() ? "An" : "Aus"));
        Button b = addRenderableWidget(Button.builder(Component.literal(text.get()), btn -> {
            flip.run();
            btn.setMessage(Component.literal(text.get()));
        }).bounds(x, y, W, 20).build());
        tip(b, help);
    }

    private static String label(String name, String value) {
        return t(name) + ": " + value;
    }

    /** Server-safe lives in the viewer (per server); here it applies at once, the viewer confirms it. */
    private static void setSafe(Minecraft mc, boolean on) {
        String srv = RecordingTarget.serverId(mc);
        ServerSafe.set(mc, srv, on);
        JsonObject o = new JsonObject();
        o.addProperty("srv", srv);
        o.addProperty("safe", on);
        ViewerApi.post("/api/control", o, err -> {
            if (err != null && !IngameActions.NO_VIEWER.equals(err)) {
                IngameActions.say(mc, err, ChatFormatting.RED);
            }
        });
    }

    @Override
    protected void draw(Gfx g, int mouseX, int mouseY) {
        int y = Math.max(30, height / 2 - 90);
        g.centered(font, title.getString(), width / 2, y - 20, WHITE);
        g.centered(font, t("Tasten: Optionen → Steuerung → Webmatica. Sprache wie im Spiel."), width / 2, y + 182, GRAY);
    }
}
