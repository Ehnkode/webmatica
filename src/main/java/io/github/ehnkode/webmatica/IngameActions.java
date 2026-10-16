package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

/** What the in-game menu and the /wm command can do. Client thread. */
final class IngameActions {
    static final String NO_VIEWER = "Viewer läuft nicht";

    private IngameActions() {
    }

    /** "overworld", "the_nether", … like the chests are stored. */
    static String dim(Minecraft mc) {
        if (mc.level == null) {
            return "";
        }
        Identifier d = mc.level.dimension().identifier();
        return "minecraft".equals(d.getNamespace()) ? d.getPath() : d.toString();
    }

    /** Stop, pause or resume the bot. Works without the viewer too, then only for the mod's own jobs. */
    static void bot(Minecraft mc, String kind) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("kind", kind);
        ViewerApi.command(mc, cmd, err -> {
            if (err == null) {
                return;
            }
            if (NO_VIEWER.equals(err)) {
                JsonObject local = new JsonObject();
                local.addProperty("id", "");
                local.addProperty("kind", kind);
                BaritoneBridge.getInstance().runLocal(local);
            } else {
                say(mc, err, ChatFormatting.RED);
            }
        });
        if ("stop".equals(kind)) {
            say(mc, "Stopp gesendet", ChatFormatting.YELLOW);
        }
    }

    static void highlight(Minecraft mc, ChestIndex.Hit h) {
        JsonArray pts = new JsonArray();
        JsonArray p = new JsonArray();
        p.add(h.x);
        p.add(h.y);
        p.add(h.z);
        pts.add(p);
        Highlighter.getInstance().set(pts, ChestIndex.displayName(h.id));
        say(mc, "Kiste markiert: " + h.x + " " + h.y + " " + h.z, ChatFormatting.GOLD);
    }

    static void walkTo(Minecraft mc, ChestIndex.Hit h) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("kind", "goto");
        cmd.addProperty("x", h.x);
        cmd.addProperty("y", h.y);
        cmd.addProperty("z", h.z);
        send(mc, cmd);
    }

    /** Takes this item from the chest; for items inside a shulker the whole shulker. */
    static void fetch(Minecraft mc, ChestIndex.Hit h) {
        JsonObject cmd = new JsonObject();
        cmd.addProperty("kind", "fetch");
        cmd.addProperty("x", h.x);
        cmd.addProperty("y", h.y);
        cmd.addProperty("z", h.z);
        JsonArray slots = new JsonArray();
        JsonArray items = new JsonArray();
        if (h.shulkerSlot && h.slot != null) {
            slots.add(h.slot);
        } else {
            JsonObject it = new JsonObject();
            it.addProperty("id", h.id);
            it.addProperty("count", h.count);
            items.add(it);
        }
        cmd.add("slots", slots);
        cmd.add("items", items);
        send(mc, cmd);
    }

    private static void send(Minecraft mc, JsonObject cmd) {
        if (!ViewerLauncher.running()) {
            ViewerLauncher.startInBackground(mc);
        }
        ViewerApi.command(mc, cmd, err -> {
            if (err != null) {
                say(mc, NO_VIEWER.equals(err) ? "Viewer startet noch, gleich nochmal versuchen" : err, ChatFormatting.RED);
            }
        });
    }

    static void say(Minecraft mc, String german, ChatFormatting f) {
        Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(german)).withStyle(f));
    }
}
