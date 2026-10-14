package io.github.ehnkode.webmatica;

import java.nio.file.Path;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Where a chunk is written: a separate world folder per server, containing
 * the dimensions in vanilla layout. This lets every server folder be opened
 * directly as a world in Amulet, MCA Selector or BlueMap.
 *
 * <pre>
 * webmatica_recording/
 *   2b2t.org/
 *   localhost_25566/       a port other than 25565 gets its own folder
 *     region/        Overworld
 *     DIM-1/region/  Nether
 *     DIM1/region/   End
 *   singleplayer-My World/   one folder per singleplayer save
 * </pre>
 */
public final class RecordingTarget {

    private RecordingTarget() {}

    public static Path root(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("webmatica_recording");
    }

    /** Folder name for the current server (address, plus "_port" for a port other than 25565; safe characters only),
     *  in singleplayer "singleplayer-" + the save folder, so every world keeps its own map,
     *  history, chests and waypoints. */
    public static String serverId(Minecraft mc) {
        if (mc.isLocalServer()) {
            IntegratedServer local = mc.getSingleplayerServer();
            Path save = local == null ? null : local.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize().getFileName();
            return save == null ? "singleplayer" : "singleplayer-" + sanitize(save.toString());
        }
        ServerData info = mc.getCurrentServer();
        if (info == null || info.ip == null || info.ip.isBlank()) {
            return "unknown";
        }
        String addr = info.ip.trim().toLowerCase(Locale.ROOT);
        int colon = addr.lastIndexOf(':');
        if (colon > 0 && addr.indexOf(':') == colon) {
            // Default port: just the address (as before). Another port gets its own folder,
            // so several servers on one host (e.g. localhost:25566) don't share a map.
            String port = addr.substring(colon + 1);
            addr = addr.substring(0, colon);
            if (!port.isEmpty() && !port.equals("25565")) {
                return sanitize(addr) + "_" + sanitize(port);
            }
        }
        return sanitize(addr);
    }

    /** Path relative to the server folder that contains the dimension's region/ folder. */
    public static String dimensionDir(ResourceKey<Level> dim) {
        if (Level.OVERWORLD.equals(dim)) {
            return "";
        }
        if (Level.NETHER.equals(dim)) {
            return "DIM-1";
        }
        if (Level.END.equals(dim)) {
            return "DIM1";
        }
        Identifier id = dim.identifier();
        return "dimensions/" + sanitize(id.getNamespace()) + "/" + sanitize(id.getPath());
    }

    public static Path regionDir(Minecraft mc, ResourceKey<Level> dim) {
        Path serverDir = root(mc).resolve(serverId(mc));
        String sub = dimensionDir(dim);
        return (sub.isEmpty() ? serverDir : serverDir.resolve(sub)).resolve("region");
    }

    private static String sanitize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            sb.append(Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_' ? c : '_');
        }
        return sb.length() == 0 ? "unknown" : sb.toString();
    }
}
