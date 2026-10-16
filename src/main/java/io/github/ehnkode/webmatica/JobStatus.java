package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;

/**
 * What the bot is doing right now, for the HUD and the menu: the mod's own jobs (fetch, unpack,
 * farm, walk, printer) plus auto-build/farm/flatten, which runs in the viewer. Client thread,
 * except the viewer poll.
 */
final class JobStatus {
    private static final Set<String> ACTIVE = Set.of("fetch", "build", "approach", "unpack", "store", "return",
            "fix", "mine", "deposit");
    private static final Map<String, String> MODES = Map.of("build", "Bauen", "farm", "Farmen",
            "flatten", "Ebnen", "store", "Einlagern");

    private static volatile JsonObject auto;
    private static volatile long autoAt;
    private static boolean polling;
    private static int tick;

    private JobStatus() {
    }

    static void tick(Minecraft mc) {
        if (mc.level == null || mc.player == null) {
            auto = null;
            return;
        }
        if (++tick % 20 != 0 || polling || !ViewerLauncher.running()) {
            return;
        }
        polling = true;
        String srv = RecordingTarget.serverId(mc);
        ViewerApi.background(() -> {
            try {
                JsonElement e = ViewerApi.getNow("/api/auto?srv=" + ViewerApi.q(srv));
                auto = e.isJsonObject() ? e.getAsJsonObject() : null;
                autoAt = System.currentTimeMillis();
            } catch (Exception ignored) {
                auto = null;
            } finally {
                polling = false;
            }
        });
    }

    /** Auto job of the viewer, if one is running. */
    private static JsonObject runningAuto() {
        JsonObject a = auto;
        if (a == null || System.currentTimeMillis() - autoAt > 5000L) {
            return null;
        }
        String phase = a.has("phase") && !a.get("phase").isJsonNull() ? a.get("phase").getAsString() : "off";
        boolean paused = a.has("paused") && a.get("paused").isJsonObject();
        return ACTIVE.contains(phase) || paused ? a : null;
    }

    static boolean busy() {
        return runningAuto() != null || FetchTask.getInstance().busy() || UnpackTask.getInstance().busy()
                || FarmTask.getInstance().busy() || Walker.active() || WebPrinter.getInstance().isOn();
    }

    static boolean paused() {
        return AutoGuard.paused();
    }

    /** One line per running job, already in the game language. Empty if nothing runs. */
    static List<String> lines() {
        List<String> out = new ArrayList<>();
        JsonObject a = runningAuto();
        if (a != null) {
            String mode = a.has("mode") ? a.get("mode").getAsString() : "build";
            String info = a.has("info") && !a.get("info").isJsonNull() ? a.get("info").getAsString() : "";
            String head = Lang.tr(MODES.getOrDefault(mode, mode));
            out.add(info.isEmpty() ? head : head + ": " + Lang.tr(info));
            if (a.has("paused") && a.get("paused").isJsonObject()) {
                JsonObject p = a.getAsJsonObject("paused");
                String who = p.has("name") ? p.get("name").getAsString() : "?";
                out.add(Lang.tr("Pause: Spieler " + who + " in der Nähe"));
            }
            int queued = a.has("queue") && a.get("queue").isJsonArray() ? a.getAsJsonArray("queue").size() : 0;
            if (queued > 0) {
                out.add(Lang.tr("Warteschlange: " + queued));
            }
        }
        if (FetchTask.getInstance().busy()) {
            out.add(Lang.tr("Holen aus Kiste"));
        }
        if (UnpackTask.getInstance().busy()) {
            out.add(Lang.tr("Shulker auspacken"));
        }
        if (FarmTask.getInstance().busy()) {
            out.add(Lang.tr("Abbauen"));
        }
        if (a == null && Walker.active() && !FetchTask.getInstance().walking()) {
            out.add(Lang.tr("Läuft zum Ziel"));
        }
        WebPrinter wp = WebPrinter.getInstance();
        if (wp.isOn()) {
            JsonObject s = wp.status();
            out.add(Lang.tr("Printer: " + s.get("placed").getAsLong() + " gesetzt, " + s.get("todo").getAsLong() + " offen"));
            if (s.has("missing") && s.getAsJsonArray("missing").size() > 0) {
                List<String> names = new ArrayList<>();
                s.getAsJsonArray("missing").forEach(m -> names.add(ChestIndex.displayName(m.getAsString())));
                out.add(Lang.tr("Fehlt: " + String.join(", ", names)));
            }
        }
        if (!out.isEmpty() && AutoGuard.paused()) {
            out.add(Lang.tr("Pausiert"));
        }
        return out;
    }
}
