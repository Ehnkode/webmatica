package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import java.util.zip.CRC32;

/**
 * Starts the browser viewer from inside the game. The viewer ships in the mod jar
 * (folder {@code webmatica-viewer/}); on first use, or after a mod update, it is
 * copied to {@code <game folder>/webmatica-viewer/}. Then Python runs it there and the
 * browser opens. Nothing is downloaded except numpy and Pillow through pip,
 * and only if they are missing.
 */
final class ViewerLauncher {
    static final String URL = "http://127.0.0.1:8770";
    private static final int PORT = 8770;
    private static final String PYTHON_URL = "https://www.python.org/downloads/";
    private static final String BUNDLE = "webmatica-viewer";
    private static final String STAMP = ".webmatica-version";

    private static final String OS = System.getProperty("os.name", "").toLowerCase();
    private static final boolean WINDOWS = OS.startsWith("windows");

    private static volatile boolean busy;
    private static Process process;

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

    /** Ends a viewer this game started; one started by hand keeps running. After a crash the viewer notices the game is gone and quits by itself. */
    static void shutdown() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    private static void run(Minecraft mc) throws IOException, InterruptedException {
        ModContainer mod = FabricLoader.getInstance().getModContainer("webmatica").orElseThrow();
        Path src = mod.findPath(BUNDLE).orElseThrow(() -> new IOException("viewer missing from the mod jar"));
        String version = mod.getMetadata().getVersion().getFriendlyString() + " " + fingerprint(src);
        if (listening()) {
            JsonObject running = runningStatus();
            if (running != null && version.equals(str(running, "version"))) {
                if (running.has("tabs") && running.get("tabs").getAsInt() > 0) {
                    // Each extra tab costs one of the browser's few connections to the viewer.
                    say(mc, "Viewer ist schon im Browser offen: " + URL, ChatFormatting.GREEN, URL);
                } else {
                    browse(mc);
                }
                return;
            }
            // A viewer from an older mod build is still running: ask it to quit, then start the new one.
            if (!quitRunning()) {
                say(mc, "Ein alter Viewer läuft noch. Bitte Python im Task-Manager beenden und F10 erneut drücken.",
                        ChatFormatting.RED, null);
                return;
            }
        }
        Path app = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve(BUNDLE);
        unpack(app, src, version);

        List<String> py = findPython();
        if (py == null) {
            say(mc, "Für den Viewer fehlt Python 3.10 oder neuer. Hier herunterladen, beim Installieren „Add python.exe to PATH“ anhaken:",
                    ChatFormatting.RED, null);
            say(mc, PYTHON_URL, ChatFormatting.AQUA, PYTHON_URL);
            return;
        }
        Path log = app.resolve("viewer.log");
        if (exec(app, log, py, "-c", "import numpy, PIL") != 0) {
            say(mc, "Viewer: installiere numpy und Pillow …", ChatFormatting.YELLOW, null);
            if (exec(app, log, py, "-m", "pip", "install", "--user", "-r", "requirements.txt") != 0) {
                say(mc, "Viewer: numpy und Pillow ließen sich nicht installieren, Details in " + log, ChatFormatting.RED, null);
                return;
            }
        }

        if (!Files.isRegularFile(app.resolve("viewer/assets/blocks.png"))) {
            say(mc, "Viewer: erster Start, Blockbilder werden erzeugt (etwa eine Minute) …", ChatFormatting.YELLOW, null);
        } else {
            say(mc, "Viewer startet …", ChatFormatting.YELLOW, null);
        }
        List<String> cmd = new ArrayList<>(py);
        cmd.add("-m");
        cmd.add("viewer");
        // The viewer watches the game's process and quits with it, even after a crash.
        cmd.add("--parent-pid");
        cmd.add(Long.toString(ProcessHandle.current().pid()));
        process = new ProcessBuilder(cmd).directory(app.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();

        // The first start builds the block images first, so allow a few minutes.
        long until = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(5);
        while (System.currentTimeMillis() < until) {
            if (listening()) {
                browse(mc);
                return;
            }
            if (!process.isAlive()) {
                say(mc, "Viewer wurde beendet, Details in " + log, ChatFormatting.RED, null);
                return;
            }
            Thread.sleep(1000);
        }
        say(mc, "Viewer antwortet nicht, Details in " + log, ChatFormatting.RED, null);
    }

    /** Copies the bundled viewer next to the game whenever it changed. Built block images stay. */
    private static void unpack(Path app, Path src, String version) throws IOException {
        Path stamp = app.resolve(STAMP);
        if (Files.isRegularFile(stamp) && version.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim())) {
            return;
        }
        Path code = app.resolve("viewer");
        Path assets = code.resolve("assets");
        if (Files.isDirectory(code)) {
            try (Stream<Path> old = Files.walk(code)) {
                for (Path p : old.sorted(Comparator.reverseOrder()).toList()) {
                    if (!p.startsWith(assets) && !p.equals(code)) {
                        Files.deleteIfExists(p);
                    }
                }
            }
        }
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : files.toList()) {
                Path dst = app.resolve(src.relativize(p).toString().replace(src.getFileSystem().getSeparator(), File.separator));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    try (InputStream in = Files.newInputStream(p)) {
                        Files.copy(in, dst, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
        }
        Files.createDirectories(assets);
        app.resolve("start-viewer.sh").toFile().setExecutable(true);
        Files.writeString(stamp, version, StandardCharsets.UTF_8);
    }

    /** Checksum over the bundled files, so a rebuilt jar with the same version also updates the copy. */
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

    /** The first Python 3.10+ found, as a command line. The Windows Store stub fails the check. */
    private static List<String> findPython() {
        boolean win = WINDOWS;
        List<List<String>> cands = win
                ? List.of(List.of("py", "-3"), List.of("python"), List.of("python3"))
                : List.of(List.of("python3"), List.of("python"));
        for (List<String> c : cands) {
            List<String> cmd = new ArrayList<>(c);
            cmd.add("-c");
            cmd.add("import sys; print(sys.version_info >= (3, 10))");
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
                if (p.waitFor(20, TimeUnit.SECONDS) && p.exitValue() == 0 && out.endsWith("True")) {
                    return c;
                }
            } catch (IOException | InterruptedException ignored) {
                // not installed, try the next one
            }
        }
        return null;
    }

    private static int exec(Path dir, Path log, List<String> py, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(py);
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroy();
            return -1;
        }
        return p.exitValue();
    }

    /** Status of the viewer on the port ({@code version}, {@code tabs}); null for one that predates /api/version. */
    private static JsonObject runningStatus() {
        try {
            HttpURLConnection c = (HttpURLConnection) URI.create(URL + "/api/version").toURL().openConnection();
            c.setConnectTimeout(1000);
            c.setReadTimeout(2000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                return JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement v = o.get(key);
        return v == null || v.isJsonNull() ? "" : v.getAsString();
    }

    /** Asks the running viewer to quit and waits until the port is free. */
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
        } catch (IOException e) {
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
