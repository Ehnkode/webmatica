package io.github.ehnkode.webmatica;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
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

    /** Ends a viewer this game started; one started by hand keeps running. */
    static void shutdown() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }

    private static void run(Minecraft mc) throws IOException, InterruptedException {
        if (listening()) {
            browse(mc);
            return;
        }
        Path app = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve(BUNDLE);
        unpack(app);

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

    /** Copies the bundled viewer next to the game, once per mod version. Built block images stay. */
    private static void unpack(Path app) throws IOException {
        ModContainer mod = FabricLoader.getInstance().getModContainer("webmatica").orElseThrow();
        String version = mod.getMetadata().getVersion().getFriendlyString();
        Path stamp = app.resolve(STAMP);
        if (Files.isRegularFile(stamp) && version.equals(Files.readString(stamp, StandardCharsets.UTF_8).trim())
                && !version.contains("SNAPSHOT")) {
            return;
        }
        Path src = mod.findPath(BUNDLE).orElseThrow(() -> new IOException("viewer missing from the mod jar"));
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

    /** The first Python 3.10+ found, as a command line. The Windows Store stub fails the check. */
    private static List<String> findPython() {
        boolean win = Util.getPlatform() == Util.OS.WINDOWS;
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

    private static boolean listening() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    private static void browse(Minecraft mc) {
        Util.getPlatform().openUri(URI.create(URL));
        say(mc, "Viewer offen: " + URL, ChatFormatting.GREEN, URL);
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
            mc.player.displayClientMessage(t, false);
        });
    }
}
