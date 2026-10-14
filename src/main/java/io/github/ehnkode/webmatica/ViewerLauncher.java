package io.github.ehnkode.webmatica;

import com.google.gson.JsonParser;
import io.github.ehnkode.webmatica.viewer.AssetBuilder;
import io.github.ehnkode.webmatica.viewer.Server;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.JarURLConnection;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Starts the browser viewer from inside the game. The viewer's server runs in the mod itself
 * (package {@code viewer}); only the web page ships in the jar ({@code webmatica-viewer/static}).
 * Block and item images belong to Mojang, so they are built once from the game jar into
 * {@code <game folder>/webmatica-viewer/assets/<minecraft version>/}.
 */
final class ViewerLauncher {
    static final String URL = "http://127.0.0.1:8770";
    private static final int PORT = 8770;
    private static final String BUNDLE = "webmatica-viewer";

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.startsWith("windows");

    private static volatile boolean busy;
    private static Server server;
    private static Path serverRecording;

    private ViewerLauncher() {
    }

    /** Client thread, on the key press. */
    static void open(Minecraft mc) {
        if (busy) {
            say(mc, "Viewer startet schon …", ChatFormatting.YELLOW, null);
            return;
        }
        busy = true;
        Thread t = new Thread(() -> {
            try {
                run(mc);
            } catch (Throwable e) {
                MapperClient.LOGGER.warn("[webmatica] Viewer-Start fehlgeschlagen", e);
                say(mc, "Viewer-Start fehlgeschlagen: " + e.getMessage(), ChatFormatting.RED, null);
            } finally {
                busy = false;
            }
        }, "webmatica-viewer-start");
        t.setDaemon(true);
        t.start();
    }

    /** Stops the viewer with the game. */
    static synchronized void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    private static void run(Minecraft mc) throws IOException, InterruptedException {
        System.setProperty("java.awt.headless", "true");
        ModContainer mod = FabricLoader.getInstance().getModContainer("webmatica").orElseThrow();
        Path web = mod.findPath(BUNDLE + "/static").orElseThrow(() -> new IOException("viewer missing from the mod jar"));
        String version = mod.getMetadata().getVersion().getFriendlyString() + " " + fingerprint(web);
        Path recording = RecordingTarget.root(mc).toAbsolutePath().normalize();

        synchronized (ViewerLauncher.class) {
            if (server != null && server.running() && recording.equals(serverRecording)) {
                if (server.app.streams() > 0) {
                    // Each extra tab costs one of the browser's few connections to the viewer.
                    say(mc, "Viewer ist schon im Browser offen: " + URL, ChatFormatting.GREEN, URL);
                } else {
                    browse(mc);
                }
                return;
            }
            shutdown();                        // other recording folder (e.g. another profile): start anew
        }
        if (listening() && !quitRunning()) {
            // An older Python viewer (or something else) holds the port
            say(mc, "Port 8770 ist belegt (alter Python-Viewer?). Bitte ihn beenden und F10 erneut drücken.", ChatFormatting.RED, null);
            return;
        }

        String mcVersion = FabricLoader.getInstance().getModContainer("minecraft")
                .map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("unknown");
        Path assets = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve(BUNDLE).resolve("assets").resolve(mcVersion);
        if (!AssetBuilder.ready(assets)) {
            Path jar = gameJar(mcVersion);
            if (jar == null) {
                say(mc, "Viewer: Minecraft-Jar mit den Blockbildern nicht gefunden.", ChatFormatting.RED, null);
                return;
            }
            say(mc, "Viewer: erster Start, Blockbilder werden erzeugt …", ChatFormatting.YELLOW, null);
            AssetBuilder.build(jar, assets, msg -> MapperClient.LOGGER.info("[webmatica] {}", msg));
        }

        Files.createDirectories(recording);
        Server s = new Server(recording, recording.resolve(".viewer-cache"), assets, web, version, "127.0.0.1", PORT,
                msg -> MapperClient.LOGGER.info("[webmatica] {}", msg));
        int cpus = Runtime.getRuntime().availableProcessors();
        s.start(Math.max(1, Math.min(4, cpus / 3)));       // the game needs the rest
        synchronized (ViewerLauncher.class) {
            server = s;
            serverRecording = recording;
        }
        browse(mc);
    }

    /** The jar holding the vanilla assets: the one the game loaded them from, else the launcher's copy. */
    private static Path gameJar(String mcVersion) {
        List<Path> cands = new ArrayList<>();
        try {
            URL u = Minecraft.class.getClassLoader().getResource("assets/minecraft/blockstates/stone.json");
            if (u != null && u.getProtocol().equals("jar")) {
                cands.add(Path.of(((JarURLConnection) u.openConnection()).getJarFileURL().toURI()));
            }
        } catch (Exception ignored) {
            // try the others
        }
        try {
            cands.add(Path.of(Minecraft.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        } catch (Exception ignored) {
            // try the others
        }
        Path home = Path.of(System.getProperty("user.home"));
        Path rel = Path.of("versions", mcVersion, mcVersion + ".jar");
        String appdata = System.getenv("APPDATA");
        if (appdata != null) {
            cands.add(Path.of(appdata, ".minecraft").resolve(rel));
        }
        cands.add(home.resolve(".minecraft").resolve(rel));
        cands.add(home.resolve("Library/Application Support/minecraft").resolve(rel));
        for (Path c : cands) {
            if (Files.isRegularFile(c) && AssetBuilder.hasAssets(c)) {
                return c;
            }
        }
        return null;
    }

    /** Checksum over the web page, so a rebuilt jar with the same version still reloads the browser. */
    private static String fingerprint(Path src) throws IOException {
        CRC32 crc = new CRC32();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                crc.update(src.relativize(p).toString().getBytes(StandardCharsets.UTF_8));
                crc.update(Files.readAllBytes(p));
            }
        }
        return Long.toHexString(crc.getValue());
    }

    /** Asks a viewer on the port (an older Python one) to quit and waits until the port is free. */
    private static boolean quitRunning() throws InterruptedException {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(URL + "/api/quit").toURL().openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (var out = c.getOutputStream()) {
                out.write("{}".getBytes(StandardCharsets.UTF_8));
            }
            if (c.getResponseCode() != 200) {
                return false;
            }
            try (InputStream in = c.getInputStream()) {
                JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        } catch (IOException | RuntimeException e) {
            return !listening();
        }
        for (int i = 0; i < 50; i++) {
            if (!listening()) {
                Thread.sleep(500);
                return true;
            }
            Thread.sleep(200);
        }
        return false;
    }

    private static boolean listening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void browse(Minecraft mc) {
        openBrowser(URL);
        say(mc, "Viewer offen: " + URL, ChatFormatting.GREEN, URL);
    }

    /** Same commands the game uses for links; works on every Minecraft version. */
    private static void openBrowser(String url) {
        String[] cmd = WINDOWS ? new String[] {"rundll32", "url.dll,FileProtocolHandler", url}
                : OS.contains("mac") ? new String[] {"open", url}
                : new String[] {"xdg-open", url};
        try {
            new ProcessBuilder(cmd).start();
        } catch (IOException e) {
            MapperClient.LOGGER.warn("[webmatica] Browser nicht geöffnet: {}", e.toString());
        }
    }

    private static void say(Minecraft mc, String msg, ChatFormatting f, String link) {
        mc.execute(() -> {
            if (mc.player == null) {
                return;
            }
            Component t = Component.literal("[Webmatica] " + (link != null && msg.equals(link) ? msg : Lang.tr(msg))).withStyle(f);
            if (link != null) {
                t = t.copy().withStyle(s -> s.withClickEvent(new ClickEvent.OpenUrl(URI.create(link))).withUnderlined(true));
            }
            Mc.chat(mc, t);
        });
    }
}
