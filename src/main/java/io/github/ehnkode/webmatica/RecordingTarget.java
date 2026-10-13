package io.github.ehnkode.webmatica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Where a chunk is written: a separate world folder per server, containing
 * the dimensions in vanilla layout. This lets every server folder be opened
 * directly as a world in Amulet, MCA Selector or BlueMap.
 *
 * <pre>
 * webmatica_recording/
 *   2b2t.org/
 *     region/        Overworld
 *     DIM-1/region/  Nether
 *     DIM1/region/   End
 * </pre>
 */
public final class RecordingTarget {

    private RecordingTarget() {}

    public static Path root(MinecraftClient mc) {
        return mc.runDirectory.toPath().resolve("webmatica_recording");
    }

    /** Folder name for the current server (address without port, safe characters only). */
    public static String serverId(MinecraftClient mc) {
        if (mc.isInSingleplayer()) {
            return "singleplayer";
        }
        ServerInfo info = mc.getCurrentServerEntry();
        if (info == null || info.address == null || info.address.isBlank()) {
            return "unknown";
        }
        String addr = info.address.trim().toLowerCase(Locale.ROOT);
        int colon = addr.lastIndexOf(':');
        if (colon > 0 && addr.indexOf(':') == colon) {
            addr = addr.substring(0, colon);
        }
        return sanitize(addr);
    }

    /** Path relative to the server folder that contains the dimension's region/ folder. */
    public static String dimensionDir(RegistryKey<World> dim) {
        if (World.OVERWORLD.equals(dim)) {
            return "";
        }
        if (World.NETHER.equals(dim)) {
            return "DIM-1";
        }
        if (World.END.equals(dim)) {
            return "DIM1";
        }
        Identifier id = dim.getValue();
        return "dimensions/" + sanitize(id.getNamespace()) + "/" + sanitize(id.getPath());
    }

    public static Path regionDir(MinecraftClient mc, RegistryKey<World> dim) {
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
