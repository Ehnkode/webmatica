package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonElement;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.ehnkode.webmatica.viewer.core.KeyError;
import io.github.ehnkode.webmatica.viewer.core.LookupError;
import io.github.ehnkode.webmatica.viewer.core.Models;
import io.github.ehnkode.webmatica.viewer.core.Palette;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Region;
import io.github.ehnkode.webmatica.viewer.core.Tiles;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * HTTP server of the viewer, running inside the mod (no Python needed).
 * Where things are: this class starts it and checks every request; {@link App} holds the state;
 * {@link Routes} has the answers. Every endpoint with its parameters: docs/protocol.md
 */
public final class Server {
    /** Bump when the tile rendering changes, so cached tiles are rendered again. */
    static final String RENDER_VERSION = "java-2";   // 2: soft relief shading + water mask

    public final App app;
    public final Router routes = new Router();
    final Path staticRoot;
    final String host;
    final int port;
    private HttpServer http;
    private ExecutorService pool;
    private volatile boolean running;
    private final Consumer<String> log;

    public Server(Path recording, Path cache, Path assets, Path staticRoot, String version, String host, int port,
                  Consumer<String> log) {
        this.log = log;
        resetCacheOnUpdate(cache, assets);
        Palette.models = new Models(assets);
        this.app = new App(recording, cache, version);
        this.app.assets = assets;
        this.staticRoot = staticRoot;
        this.host = host;
        this.port = port;
        Routes.register(this);
    }

    // ------------------------------------------------------------------ start / stop

    public synchronized void start(int workers) throws IOException {
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "webmatica-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        http = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 128);
        http.setExecutor(pool);
        http.createContext("/", this::handle);
        http.start();
        running = true;
        app.shutdown = () -> new Thread(this::stop, "webmatica-stop").start();
        if (workers > 0) {
            daemon("webmatica-prerender", () -> prerenderLoop(workers, 10.0));
        }
        daemon("webmatica-automation", () -> automationLoop(0.5));
    }

    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        app.saveTrail();
        app.radar.save();
        app.lids.save();
        if (http != null) {
            http.stop(0);
        }
        if (pool != null) {
            pool.shutdownNow();
        }
    }

    public boolean running() {
        return running;
    }

    private static void daemon(String name, Runnable r) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();
    }

    // ------------------------------------------------------------------ requests

    static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /**
     * Only "localhost" or a bare IP address may appear as Host or Origin. DNS rebinding points a
     * foreign domain at 127.0.0.1; the browser then sends that domain as Host.
     */
    static boolean localHost(String value) {
        String h = hostname(value);
        if (h == null) {
            return false;
        }
        return h.equals("localhost") || IPV4.matcher(h).matches() || h.contains(":");
    }

    /** Host part of "host:port", "[::1]:port" or "http://host:port", lower case; null when empty. */
    static String hostname(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            URI u = new URI(value.contains("//") ? value : "//" + value);
            String h = u.getHost();
            if (h == null) {
                return null;
            }
            if (h.startsWith("[") && h.endsWith("]")) {
                h = h.substring(1, h.length() - 1);
            }
            return h.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    private void handle(HttpExchange ex) {
        Http h = new Http(ex, app);
        try {
            if (!localHost(h.header("Host"))) {
                h.fail(403, "fremder Host");
                return;
            }
            String path = h.path;
            if (h.method.equals("GET") || h.method.equals("HEAD")) {
                Router.Route r = routes.find("GET", path);
                if (r == null) {
                    h.fail(404, "nicht gefunden");
                    return;
                }
                run(h, () -> r.get().handle(h));
            } else if (h.method.equals("POST")) {
                // Only local pages may write (protection against foreign web pages in the browser).
                String origin = h.header("Origin");
                if (origin != null && !origin.isEmpty()) {
                    String oh = hostname(origin);
                    if (oh == null || !(oh.equals("127.0.0.1") || oh.equals("localhost") || oh.equals(host))) {
                        h.fail(403, "fremde Herkunft");
                        return;
                    }
                }
                Router.Route r = routes.find("POST", path);
                if (r != null && r.raw()) {
                    run(h, () -> r.get().handle(h));
                    return;
                }
                JsonElement req;
                try {
                    req = h.bodyJson();
                } catch (RuntimeException e) {
                    h.fail(400, "kein gueltiges JSON: " + App.pyErr(e));
                    return;
                }
                if (r == null) {
                    h.fail(404, "nicht gefunden");
                    return;
                }
                run(h, () -> r.post().handle(h, req));
            } else {
                h.fail(405, "Methode nicht erlaubt");
            }
        } catch (Throwable e) {
            if (!h.answered()) {
                h.fail(500, "interner Fehler: " + App.pyErr(e));
            }
        } finally {
            if (!h.answered()) {
                ex.close();
            }
        }
    }

    @FunctionalInterface
    interface Body {
        void run() throws Exception;
    }

    private void run(Http h, Body fn) {
        try {
            fn.run();
        } catch (Http.Fail e) {
            if (!h.answered()) {
                h.fail(e.code, e.getMessage());
            }
        } catch (LookupError e) {
            if (!h.answered()) {
                h.fail(404, App.pyErr(e));
            }
        } catch (IllegalArgumentException | IllegalStateException | com.google.gson.JsonParseException
                 | ClassCastException | IndexOutOfBoundsException | UnsupportedOperationException e) {
            if (!h.answered()) {
                h.fail(400, "Parameter fehlen oder sind ungueltig: " + App.pyErr(e));
            }
        } catch (IOException e) {
            if (!h.answered()) {
                h.fail(500, "interner Fehler: " + App.pyErr(e));
            }
        } catch (Exception e) {   // never drop the connection without a word
            StringBuilder where = new StringBuilder();
            StackTraceElement[] st = e.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                where.append("\n    at ").append(st[i]);
            }
            log.accept("Fehler bei " + h.path + ": " + e + where);
            if (!h.answered()) {
                h.fail(500, "interner Fehler: " + App.pyErr(e));
            }
        }
    }

    // ------------------------------------------------------------------ cache version

    /** Fingerprint of what the cached tiles depend on: the render code and the block colors. */
    static String renderVersion(Path assets) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update(RENDER_VERSION.getBytes(StandardCharsets.UTF_8));
            for (String name : new String[] {"blocks.json", "blocks.png"}) {
                try {
                    md.update((name + ":" + Files.size(assets.resolve(name))).getBytes(StandardCharsets.UTF_8));
                } catch (IOException ignored) {
                    // missing assets are part of the fingerprint too
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            return RENDER_VERSION;
        }
    }

    /** Tiles rendered by other render code may be wrong: start over. */
    static void resetCacheOnUpdate(Path cache, Path assets) {
        try {
            Files.createDirectories(cache);
            Path stamp = cache.resolve(".viewer-version");
            String version = renderVersion(assets);
            try {
                if (Files.readString(stamp, StandardCharsets.UTF_8).strip().equals(version)) {
                    return;
                }
            } catch (IOException ignored) {
                // no stamp yet
            }
            // Only the default cache folder or one we stamped earlier is ours to empty.
            if (cache.getFileName().toString().equals(".viewer-cache") || Files.isRegularFile(stamp)) {
                deleteTree(cache);
            }
            Files.createDirectories(cache);
            Files.writeString(stamp, version, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            // the tiles report problems when they are written
        }
    }

    static void deleteTree(Path p) {
        if (!Files.exists(p)) {
            return;
        }
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(q -> {
                try {
                    Files.delete(q);
                } catch (IOException ignored) {
                    // a file still open (Windows): left behind
                }
            });
        } catch (IOException ignored) {
            // nothing to do
        }
    }

    // ------------------------------------------------------------------ background work

    /** Keeps all region tiles up to date in the background (newest regions first). */
    private void prerenderLoop(int workers, double interval) {
        ExecutorService exec = Executors.newFixedThreadPool(workers, r -> {
            Thread t = new Thread(r, "webmatica-render");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        Map<List<Object>, Long> done = new HashMap<>();
        try {
            while (running) {
                List<Object[]> jobs = new ArrayList<>();
                for (Recording.Dimension dim : app.rec.dimensions()) {
                    for (Map<String, Object> layer : App.layersFor(dim.key)) {
                        for (Region r : dim.regions()) {
                            long m;
                            try {
                                m = Files.getLastModifiedTime(r.path).toMillis();
                            } catch (IOException e) {
                                continue;
                            }
                            List<Object> k = List.of(dim.id(), layer.get("id"), r.rx, r.rz);
                            Long d = done.get(k);
                            if (d == null || d != m) {
                                jobs.add(new Object[] {m, k, dim, Tiles.Layer.parse((String) layer.get("id")), r});
                            }
                        }
                    }
                }
                jobs.sort((a, b) -> Long.compare((Long) b[0], (Long) a[0]));
                List<Future<?>> futs = new ArrayList<>();
                for (Object[] j : jobs) {
                    futs.add(exec.submit(() -> {
                        Region r = (Region) j[4];
                        try {
                            app.tiles.regionTile((Recording.Dimension) j[2], (Tiles.Layer) j[3], r.rx, r.rz);
                        } catch (Exception e) {
                            log.accept("Vorrendern fehlgeschlagen " + j[1] + ": " + e);
                        }
                    }));
                }
                for (int i = 0; i < futs.size(); i++) {
                    try {
                        futs.get(i).get();
                    } catch (Exception ignored) {
                        // logged above
                    }
                    @SuppressWarnings("unchecked")
                    List<Object> k = (List<Object>) jobs.get(i)[1];
                    done.put(k, (Long) jobs.get(i)[0]);
                }
                Thread.sleep((long) (interval * 1000));
            }
        } catch (InterruptedException ignored) {
            // shutting down
        } finally {
            exec.shutdownNow();
        }
    }

    /** Auto-build and layer automation for every server whose mod polls right now. */
    private void automationLoop(double every) {
        while (running) {
            double now = Stores.mono();
            for (Map.Entry<String, Double> e : app.polling.entrySet()) {
                if (now - e.getValue() > 10.0) {
                    continue;
                }
                String srv = e.getKey();
                try {
                    app.control.autoStep(srv, pid -> app.placements.verify(pid, app.rec, null));
                    app.auto.step(srv);
                } catch (Exception ex) {   // keep the loop alive
                    log.accept("automation " + srv + ": " + ex);
                }
            }
            try {
                Thread.sleep((long) (every * 1000));
            } catch (InterruptedException ex) {
                return;
            }
        }
    }

    // ------------------------------------------------------------------ standalone (tests)

    /** java ... Server --recording R [--cache C] --assets A --static S [--port P] */
    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            a.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        Path rec = Path.of(a.get("recording"));
        Path cache = a.containsKey("cache") ? Path.of(a.get("cache")) : rec.resolve(".viewer-cache");
        Server s = new Server(rec, cache, Path.of(a.get("assets")), Path.of(a.get("static")), a.getOrDefault("version", "dev"),
                a.getOrDefault("host", "127.0.0.1"), Integer.parseInt(a.getOrDefault("port", "8770")), System.out::println);
        s.start(Integer.parseInt(a.getOrDefault("workers", "2")));
        System.out.println("Webmatica: http://" + s.host + ":" + s.port);
        Thread.currentThread().join();
    }
}
