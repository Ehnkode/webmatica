package io.github.ehnkode.webmatica;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Settings of the in-game menu, kept in config/webmatica.json. */
final class IngameConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static IngameConfig instance;

    /** Job display: shown while a job runs. */
    boolean hud = true;
    /** 0 top left, 1 top right, 2 bottom left, 3 bottom right. */
    int hudCorner = 1;
    /** Start the viewer's server (without browser) when joining a world, so chests are remembered. */
    boolean viewerInBackground = true;
    /** "In chests: …" line in item tooltips. */
    boolean tooltip = true;

    static synchronized IngameConfig get() {
        if (instance == null) {
            instance = new IngameConfig();
            try {
                Path p = path();
                if (Files.isRegularFile(p)) {
                    IngameConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), IngameConfig.class);
                    if (c != null) {
                        instance = c;
                    }
                }
            } catch (Exception e) {
                WebmaticaClient.LOGGER.warn("[webmatica] config/webmatica.json not readable, using defaults: {}", e.toString());
            }
            instance.hudCorner = Math.floorMod(instance.hudCorner, 4);
        }
        return instance;
    }

    synchronized void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Files.writeString(p, GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            WebmaticaClient.LOGGER.warn("[webmatica] config/webmatica.json not saved: {}", e.toString());
        }
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("webmatica.json");
    }
}
