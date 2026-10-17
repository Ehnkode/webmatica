package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Tells the viewer where to look: on startup writes
 * {@code <user folder>/.webmatica/client.json} with the recording folder and the
 * game jar (from which the viewer reads block textures and models). So the
 * viewer needs no paths, regardless of launcher or profile.
 * The file stays on the local machine, nothing goes to the network.
 */
final class ClientInfo {

    private ClientInfo() {
    }

    private static boolean done;

    /** Client thread, once after startup. */
    static void write(Minecraft mc) {
        if (done) {
            return;
        }
        done = true;
        try {
            JsonObject o = new JsonObject();
            Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
            o.addProperty("recording", RecordingTarget.root(mc).toAbsolutePath().normalize().toString());
            o.addProperty("gameDir", game.toString());
            try {
                Path jar = Path.of(Minecraft.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                o.addProperty("gameJar", jar.toAbsolutePath().toString());
            } catch (Throwable ignored) {
                // without a jar path the viewer asks for it on first start
            }
            o.addProperty("minecraft", FabricLoader.getInstance().getModContainer("minecraft")
                    .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse(""));
            if (mc.options != null) {
                o.addProperty("language", mc.options.languageCode);
            }
            o.addProperty("updated", System.currentTimeMillis());
            Path dir = Path.of(System.getProperty("user.home"), ".webmatica");
            Files.createDirectories(dir);
            Path tmp = dir.resolve("client.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("client.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            WebmaticaClient.LOGGER.warn("[webmatica] client.json not written: {}", t.toString());
        }
    }
}
