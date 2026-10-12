package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Sagt dem Viewer, wo er suchen muss: schreibt beim Start
 * {@code <Benutzerordner>/.webmatica/client.json} mit dem Aufnahme-Ordner und der
 * Spiel-Jar (aus der der Viewer Blocktexturen und -modelle liest). So braucht der
 * Viewer keine Pfade, egal welcher Launcher oder welches Profil benutzt wird.
 * Die Datei bleibt auf dem eigenen Rechner, nichts davon geht ins Netz.
 */
final class ClientInfo {

    private ClientInfo() {
    }

    private static boolean done;

    /** Client-Thread, einmal nach dem Start. */
    static void write(MinecraftClient mc) {
        if (done) {
            return;
        }
        done = true;
        try {
            JsonObject o = new JsonObject();
            Path game = mc.runDirectory.toPath().toAbsolutePath().normalize();
            o.addProperty("recording", RecordingTarget.root(mc).toAbsolutePath().normalize().toString());
            o.addProperty("gameDir", game.toString());
            try {
                Path jar = Path.of(MinecraftClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                o.addProperty("gameJar", jar.toAbsolutePath().toString());
            } catch (Throwable ignored) {
                // ohne Jar-Pfad fragt der Viewer beim ersten Start danach
            }
            o.addProperty("minecraft", "1.21.11");
            o.addProperty("updated", System.currentTimeMillis());
            Path dir = Path.of(System.getProperty("user.home"), ".webmatica");
            Files.createDirectories(dir);
            Path tmp = dir.resolve("client.json.tmp");
            Files.writeString(tmp, o.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, dir.resolve("client.json"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            MapperClient.LOGGER.warn("[webmatica] client.json nicht geschrieben: {}", t.toString());
        }
    }
}
