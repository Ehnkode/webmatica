package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;

/** What to do with the in-game selection: save as schematic, flatten, fill, break, show in the viewer. */
final class SelectionScreen extends WmScreen {
    private static final int W = 240;

    private EditBox name;
    private Button mode;
    private Button save;
    private Button flat;
    private Button fill;
    private Button clear;
    private Button viewer;
    private Button drop;
    private int top;

    SelectionScreen(Screen parent) {
        super("Auswahl", parent);
    }

    @Override
    protected void init() {
        Minecraft mc = Minecraft.getInstance();
        int x = (width - W) / 2;
        top = Math.max(40, height / 2 - 90);
        int y = top;
        mode = button("", x, y, W, b -> {
            SelectionTool.setActive(mc, !SelectionTool.active());
            if (SelectionTool.active()) {
                Mc.setScreen(mc, null);       // straight into the world to click the corners
            }
        });
        int half = (W - 4) / 2;
        name = new EditBox(font, x, y + 30, half, 20, c("Name"));
        name.setMaxLength(60);
        name.setValue("auswahl");
        addRenderableWidget(name);
        save = button("Als Schematic speichern", x + half + 4, y + 30, half, b -> saveSchematic(mc));
        tip(save, "Speichert die Auswahl aus der Aufnahme in den Ordner schematics (Litematica und Viewer finden sie dort)");
        int third = (W - 8) / 3;
        flat = button("Ebnen", x, y + 54, third, b -> confirm(mc, "flat"));
        fill = button("Füllen", x + third + 4, y + 54, third, b -> confirm(mc, "fill"));
        clear = button("Abbauen", x + 2 * (third + 4), y + 54, third, b -> confirm(mc, "clear"));
        tip(flat, "Alles über der untersten Schicht abtragen, Löcher bis dahin mit dem Block in der Hand füllen");
        tip(fill, "Jede leere Stelle in der Auswahl mit dem Block in der Hand füllen");
        tip(clear, "Alles in der Auswahl abbauen und einsammeln");
        viewer = button("Im Viewer öffnen", x, y + 78, half, b -> {
            Mc.setScreen(mc, null);
            ViewerLauncher.open(mc);
        });
        tip(viewer, "Der Viewer zeigt dieselbe Auswahl");
        drop = button("Auswahl löschen", x + half + 4, y + 78, half, b -> SelectionTool.clear());
        button("Zurück", x, y + 112, W, b -> onClose());
        tick();
    }

    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean has = SelectionTool.box(mc) != null;
        boolean safe = ServerSafe.on();
        String block = handBlock(mc);
        mode.setMessage(c(SelectionTool.active() ? "Auswahlmodus beenden" : "Auswahlmodus starten"));
        save.active = has;
        viewer.active = has;
        drop.active = has;
        clear.active = has && !safe;
        flat.active = has && !safe;
        fill.active = has && !safe && block != null;
        if (safe) {
            for (Button b : new Button[] {flat, fill, clear}) {
                tip(b, "Server-safe ist an: der Bot baut nichts ab und nichts auf");
            }
        } else if (block == null) {
            tip(fill, "Nimm den Block zum Füllen in die Hand");
        } else {
            tip(fill, "Jede leere Stelle in der Auswahl mit dem Block in der Hand füllen");
        }
    }

    /** The block in the main hand, or null if it is not a block. */
    private static String handBlock(Minecraft mc) {
        if (mc.player == null) {
            return null;
        }
        ItemStack s = mc.player.getMainHandItem();
        if (!(s.getItem() instanceof BlockItem bi)) {
            return null;
        }
        return BuiltInRegistries.BLOCK.getKey(bi.getBlock()).toString();
    }

    private void confirm(Minecraft mc, String kind) {
        int[] b = SelectionTool.box(mc);
        if (b == null) {
            return;
        }
        String block = handBlock(mc);
        String bname = block == null ? "" : ChestIndex.displayName(block);
        String what = t(switch (kind) {
            case "clear" -> "Alles in der Auswahl abbauen und einsammeln.";
            case "fill" -> "Jede leere Stelle mit " + bname + " füllen.";
            default -> block == null ? "Alles über Y " + b[1] + " abtragen."
                    : "Alles über Y " + b[1] + " abtragen und Löcher mit " + bname + " füllen.";
        });
        String title = t(switch (kind) {
            case "clear" -> "Abbauen starten?";
            case "fill" -> "Füllen starten?";
            default -> "Ebnen starten?";
        });
        String full = kind.equals("fill") ? "" : " " + t("Volles Inventar: Kiste aus dem Inventar aufstellen und einpacken.");
        Mc.setScreen(mc, new ConfirmScreen(yes -> {
            if (yes) {
                start(mc, kind, b, block);
                Mc.setScreen(mc, null);
            } else {
                Mc.setScreen(mc, this);
            }
        }, Component.literal(title), Component.literal(SelectionTool.size(b) + " · " + what + full)));
    }

    private static void start(Minecraft mc, String kind, int[] b, String block) {
        JsonObject area = new JsonObject();
        area.addProperty("x0", b[0]);
        area.addProperty("z0", b[2]);
        area.addProperty("x1", b[3]);
        area.addProperty("z1", b[5]);
        area.addProperty("y0", b[1]);
        area.addProperty("y1", b[4]);
        JsonObject fl = new JsonObject();
        fl.addProperty("kind", kind);
        if (kind.equals("flat")) {
            fl.addProperty("level", b[1]);
            fl.addProperty("clear", true);
            fl.addProperty("fill", block != null);
        }
        if (block != null) {
            fl.addProperty("block", block);
        }
        JsonObject farm = new JsonObject();
        farm.add("area", area);
        farm.addProperty("dim", SelectionTool.viewerDim(mc));
        farm.addProperty("placeChest", true);
        farm.add("flatten", fl);
        JsonObject req = new JsonObject();
        req.addProperty("srv", RecordingTarget.serverId(mc));
        req.add("farm", farm);
        ViewerApi.post("/api/auto", req, err -> IngameActions.say(mc, err == null ? "Gestartet, Stopp im Menü (F8)" : "Fehler: " + err,
                err == null ? ChatFormatting.GREEN : ChatFormatting.RED));
    }

    private void saveSchematic(Minecraft mc) {
        int[] b = SelectionTool.box(mc);
        if (b == null) {
            return;
        }
        String n = name.getValue().replaceAll("[^A-Za-z0-9_. -]", "_").strip();
        String file = n.isEmpty() ? "auswahl" : n;
        String q = "/api/schem?srv=" + ViewerApi.q(RecordingTarget.serverId(mc)) + "&dim=" + ViewerApi.q(SelectionTool.viewerDim(mc))
                + "&name=" + ViewerApi.q(file) + "&x0=" + b[0] + "&y0=" + b[1] + "&z0=" + b[2]
                + "&x1=" + b[3] + "&y1=" + b[4] + "&z1=" + b[5];
        save.active = false;
        ViewerApi.background(() -> {
            String msg;
            boolean ok = false;
            try {
                byte[] data = ViewerApi.getBytesNow(q);
                Path dir = FabricLoader.getInstance().getGameDir().resolve("schematics");
                Files.createDirectories(dir);
                Path out = dir.resolve(file + ".schem");
                for (int i = 2; Files.exists(out); i++) {
                    out = dir.resolve(file + "-" + i + ".schem");
                }
                Files.write(out, data);
                msg = "Gespeichert: schematics/" + out.getFileName();
                ok = true;
            } catch (IOException e) {
                msg = "Speichern fehlgeschlagen: " + e.getMessage();
            }
            String m = msg;
            boolean good = ok;
            mc.execute(() -> IngameActions.say(mc, m, good ? ChatFormatting.GREEN : ChatFormatting.RED));
        });
    }

    @Override
    protected void draw(Gfx g, int mouseX, int mouseY) {
        Minecraft mc = Minecraft.getInstance();
        g.centered(font, title.getString(), width / 2, top - 30, WHITE);
        int[] b = SelectionTool.box(mc);
        String line = b == null ? t("Noch keine Auswahl: Auswahlmodus starten, dann Linksklick und Rechtsklick auf die Ecken.")
                : SelectionTool.size(b) + " · " + b[0] + " " + b[1] + " " + b[2] + "  " + t("bis") + "  " + b[3] + " " + b[4] + " " + b[5];
        g.centered(font, font.plainSubstrByWidth(line, width - 20), width / 2, top - 16, b == null ? GRAY : GREEN);
        if (ServerSafe.on()) {
            g.centered(font, t("Server-safe ist an: Ebnen, Füllen und Abbauen sind aus."), width / 2, top + 102 - 2, RED);
        }
    }
}
