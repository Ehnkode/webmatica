package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.KeyError;
import io.github.ehnkode.webmatica.viewer.core.LookupError;
import io.github.ehnkode.webmatica.viewer.core.Models;
import io.github.ehnkode.webmatica.viewer.core.Palette;
import io.github.ehnkode.webmatica.viewer.core.Png;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Region;
import io.github.ehnkode.webmatica.viewer.core.Schematic;
import io.github.ehnkode.webmatica.viewer.core.Tiles;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Every endpoint of the viewer (docs/protocol.md), grouped like the Python modules were. */
final class Routes {
    static final Pattern DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    static final String XRAY_SAFE = "Server-safe ist an: X-Ray ist aus (Optionen)";
    static final String SAFE_MSG = "Server-safe ist an: Bot-Funktionen sind aus (Optionen)";
    /** Commands that are fine on any server: they only stop the bot or mark chests. */
    static final java.util.Set<String> SAFE_COMMANDS = java.util.Set.of("highlight", "stop", "pause", "resume");
    static final String[] AREA = {"x0", "z0", "x1", "z1", "y0", "y1"};
    static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Routes() {
    }

    static void register(Server s) {
        Router r = s.routes;
        App app = s.app;
        pages(r, s.staticRoot);
        maps(r, app);
        game(r, app);
        schematics(r, app);
    }

    static Recording.Dimension dimOr404(App app, String srv, String dim, String msg) {
        Recording.Dimension d = app.rec.dimension(srv, dim);
        if (d == null) {
            throw new Http.Fail(404, msg);
        }
        return d;
    }

    static String date(Http h) {
        String at = h.q("at", "");
        if (!at.isEmpty() && !DATE.matcher(at).matches()) {
            throw new Http.Fail(400, "Datum: JJJJ-MM-TT");
        }
        return at;
    }

    // ------------------------------------------------------------------ pages

    static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html"), Map.entry("js", "text/javascript"), Map.entry("mjs", "text/javascript"),
            Map.entry("css", "text/css"), Map.entry("json", "application/json"), Map.entry("png", "image/png"),
            Map.entry("svg", "image/svg+xml"), Map.entry("ico", "image/vnd.microsoft.icon"), Map.entry("txt", "text/plain"),
            Map.entry("woff2", "font/woff2"), Map.entry("woff", "font/woff"), Map.entry("map", "application/json"),
            Map.entry("jpg", "image/jpeg"), Map.entry("gif", "image/gif"), Map.entry("wasm", "application/wasm"));

    static void serveStatic(Http h, Path root, String rel) throws IOException {
        rel = Http.unquote(rel);
        if (rel.contains("\\") || rel.contains("\0")) {
            h.fail(403, "verboten");
            return;
        }
        List<String> parts = new ArrayList<>();
        for (String seg : rel.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) {
                continue;
            }
            if (seg.equals("..")) {
                if (parts.isEmpty()) {
                    h.fail(403, "verboten");
                    return;
                }
                parts.remove(parts.size() - 1);
                continue;
            }
            parts.add(seg);
        }
        if (parts.isEmpty()) {
            h.fail(403, "verboten");
            return;
        }
        Path p = root;
        for (String seg : parts) {
            p = p.resolve(seg);
        }
        if (!Files.isRegularFile(p)) {
            h.fail(404, "nicht gefunden");
            return;
        }
        String name = parts.get(parts.size() - 1);
        int dot = name.lastIndexOf('.');
        String ctype = dot < 0 ? "application/octet-stream" : MIME.getOrDefault(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT), "application/octet-stream");
        if (ctype.startsWith("text/") || ctype.endsWith("javascript")) {
            ctype += "; charset=utf-8";
        }
        h.send(200, Files.readAllBytes(p), ctype, "no-cache");
    }

    static void pages(Router r, Path root) {
        r.get("/", h -> serveStatic(h, root, "index.html"));
        r.getPrefix("/static/", h -> serveStatic(h, root, h.path.substring("/static/".length())));
        r.get("/editor", h -> serveStatic(h, root, "editor.html"));
    }

    // ------------------------------------------------------------------ maps

    static void maps(Router r, App app) {
        r.get("/api/worlds", h -> h.json(J.map("worlds", app.worlds(), "now", Stores.now())));

        r.getPrefix("/api/tile/", h -> {
            List<String> parts = h.parts();
            if (parts.size() != 6 || !parts.get(5).endsWith(".png")) {
                h.fail(400, "Kachelpfad: /api/tile/<srv>/<dim>/<layer>/<z>/<x>/<y>.png");
                return;
            }
            String srv = parts.get(0), dimKey = parts.get(1);
            Recording.Dimension dim = dimOr404(app, srv, dimKey, "Dimension unbekannt");
            int zoom = Http.pyInt(parts.get(3)), tx = Http.pyInt(parts.get(4)), tz = Http.pyInt(parts.get(5).substring(0, parts.get(5).length() - 4));
            if (zoom < -8 || zoom > 0) {
                h.fail(400, "Zoom ausserhalb");
                return;
            }
            String at = date(h);
            Tiles store = at.isEmpty() ? app.tiles : app.past(srv, dimKey, at);
            Path path = store.tile(dim, Tiles.Layer.parse(parts.get(2)), zoom, tx, tz,
                            "1".equals(h.q("raw")) && at.isEmpty() ? "raw" : "classic".equals(h.q("shade")) ? "classic" : "");
            if (path == null) {
                h.send(200, Png.EMPTY, "image/png", "no-cache");
                return;
            }
            String etag = "\"" + Files.getLastModifiedTime(path).toMillis() + "\"";
            if (h.notModified(etag, "image/png")) {
                return;
            }
            h.send(200, Files.readAllBytes(path), "image/png", "no-cache", Map.of("ETag", etag));
        });

        r.get("/api/changes", h -> {
            Recording.Dimension dim = h.dimFrom();
            double since = Http.pyFloat(h.q("since", "0"));
            double now = Stores.now();
            List<List<Integer>> regs = dim != null ? app.changedRegions(dim, since) : List.of();
            List<List<Integer>> chunks = dim != null && !regs.isEmpty() ? app.changedChunks(dim, regs, since) : List.of();
            h.json(J.map("now", now, "regions", regs, "chunks", chunks));
        });

        r.get("/api/column", h -> {
            Recording.Dimension dim = h.dimFrom();
            if (dim == null) {
                h.fail(404, "Dimension unbekannt");
                return;
            }
            Tiles.Layer layer = Tiles.Layer.parse(h.q("layer", "surface320"));
            h.json(app.column(dim, h.qi("x"), h.qi("z"), layer));
        });

        r.get("/api/markers", h -> {
            Recording.Dimension dim = h.dimFrom();
            if (dim == null) {
                h.fail(404, "Dimension unbekannt");
                return;
            }
            h.json(app.tiles.markers(dim, Tiles.Layer.parse(h.q("layer", "surface320"))));
        });

        r.get("/api/waypoints", h -> h.json(app.waypoints()));
        r.post("/api/waypoints", (h, req) -> {
            if (!req.isJsonArray()) {
                h.fail(400, "Liste erwartet");
                return;
            }
            h.json(app.saveWaypoints(req.getAsJsonArray()));
        });

        r.get("/api/schem", h -> {
            Recording.Dimension dim = h.dimFrom();
            if (dim == null) {
                h.fail(404, "Dimension unbekannt");
                return;
            }
            int[] c = new int[6];
            String[] keys = {"x0", "y0", "z0", "x1", "y1", "z1"};
            for (int i = 0; i < 6; i++) {
                c[i] = h.qi(keys[i]);
            }
            String name = h.q("name", "auswahl").replaceAll("[^A-Za-z0-9_. -]", "_");
            name = stripChars(name, " .");
            name = name.substring(0, Math.min(60, name.length()));
            name = name.isEmpty() ? "auswahl" : name;
            Object[] res;
            try {
                res = Schematic.export(app.rec, dim, c[0], c[1], c[2], c[3], c[4], c[5], name);
            } catch (LookupError e) {
                h.fail(404, App.pyErr(e));
                return;
            }
            h.send(200, (byte[]) res[0], "application/octet-stream", "no-store", Map.of(
                    "Content-Disposition", "attachment; filename=\"" + name + ".schem\"",
                    "X-Schem-Info", pyJson(res[1])));
        });

        r.getPrefix("/api/mesh/", h -> {
            List<String> parts = h.parts();
            if (parts.size() != 4) {
                h.fail(400, "Pfad: /api/mesh/<srv>/<dim>/<cx>/<cz>");
                return;
            }
            Recording.Dimension dim = dimOr404(app, parts.get(0), parts.get(1), "Dimension unbekannt");
            Models models = Palette.models;
            if (models == null || !models.available) {
                h.fail(503, "Blockmodelle fehlen (Spiel-Jar nicht gefunden)");
                return;
            }
            int cx = Http.pyInt(parts.get(2)), cz = Http.pyInt(parts.get(3));
            List<String> names = Xray.parseNames(h.q("xray", ""));
            if (!names.isEmpty() && app.control.safe(parts.get(0))) {
                h.fail(403, XRAY_SAFE);
                return;
            }
            String at = date(h);
            Integer ylo = h.has("ylo") ? h.qi("ylo") : null, yhi = h.has("yhi") ? h.qi("yhi") : null;
            Object[] res = app.mesh(dim, cx, cz, "1".equals(h.q("full", "0")), ylo, yhi, names, at.isEmpty() ? null : at);
            if (res == null) {
                h.send(204, new byte[0], "application/octet-stream");
                return;
            }
            // Atlas version is part of it: new assets = new UVs, old meshes in the browser cache no longer fit
            String etag = "\"" + models.version + "-" + Mesher.MAGIC + "-" + res[1] + "\"";
            if (h.notModified(etag)) {
                return;
            }
            h.send(200, (byte[]) res[0], "application/octet-stream", "no-cache", Map.of("ETag", etag, "Content-Encoding", "gzip"));
        });

        r.get("/api/blocks.png", h -> {
            Path path = app.assets.resolve("blocks.png");
            if (!Files.exists(path)) {
                h.fail(404, "blocks.png fehlt (Spiel-Jar nicht gefunden)");
                return;
            }
            h.send(200, Files.readAllBytes(path), "image/png", "no-cache", Map.of("ETag", "\"" + Palette.models.version + "\""));
        });

        r.get("/api/water.png", h -> {
            Path path = app.assets.resolve("water.png");
            if (!Files.exists(path)) {
                h.fail(404, "water.png fehlt (Spiel-Jar nicht gefunden)");
                return;
            }
            h.send(200, Files.readAllBytes(path), "image/png", "max-age=3600", Map.of());
        });

        Router.Get items = h -> {
            String file = h.path.substring(h.path.lastIndexOf('/') + 1);
            Path path = app.assets.resolve(file);
            if (!Files.exists(path)) {
                h.fail(404, "Item-Bilder fehlen (Spiel-Jar nicht gefunden)");
                return;
            }
            h.send(200, Files.readAllBytes(path), file.endsWith(".png") ? "image/png" : "application/json", "no-cache",
                    Map.of("ETag", "\"" + Files.getLastModifiedTime(path).toMillis() / 1000 + "\""));
        };
        r.get("/api/items.png", items);
        r.get("/api/items.json", items);

        r.get("/api/blocks-info", h -> {
            Models m = Palette.models;
            h.json(J.map("available", m != null && m.available, "version", m == null ? "0" : m.version,
                    "width", m == null ? 0 : m.width, "height", m == null ? 0 : m.height,
                    "water", m == null ? -1 : m.textures.getOrDefault("block/water_still", -1)));
        });

        r.get("/api/xray/find", h -> {
            Recording.Dimension dim = app.rec.dimension(h.need("srv"), h.need("dim"));
            if (dim == null) {
                h.fail(404, "keine Aufnahme");
                return;
            }
            List<String> names;
            int rr, cx, cz;
            try {
                names = Xray.parseNames(h.q("b", ""));
                rr = Math.max(0, Math.min(24, Http.pyInt(h.q("r", "12"))));
                cx = ((int) Http.pyFloat(h.need("x"))) >> 4;
                cz = ((int) Http.pyFloat(h.need("z"))) >> 4;
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
                return;
            }
            if (names.isEmpty()) {
                h.json(J.map("names", List.of(), "counts", List.of(), "pts", List.of(), "chunks", 0));
                return;
            }
            if (app.control.safe(h.need("srv"))) {
                h.fail(403, XRAY_SAFE);
                return;
            }
            h.json(app.xrayFind(dim, cx, cz, rr, names));
        });

        r.get("/api/xray/names", h -> {
            // Every block the assets know (for the search suggestions), otherwise what the recording has seen so far
            Models m = Palette.models;
            TreeSet<String> names = new TreeSet<>();
            if (m != null && m.available) {
                names.addAll(m.blockstates.keySet());
            } else {
                for (String n : Chunk.BLOCKS.names()) {
                    names.add(Chunk.baseName(n));
                }
            }
            names.removeAll(Chunk.AIR_NAMES);
            h.json(new ArrayList<>(names));
        });

        r.getPrefix("/api/lod/", h -> {
            List<String> parts = h.parts();
            Recording.Dimension dim = dimOr404(app, parts.get(0), parts.get(1), "Dimension unbekannt");
            Object[] res = app.lod(dim, Http.pyInt(parts.get(2)), Http.pyInt(parts.get(3)),
                    Math.max(0, Math.min(3, Http.pyInt(h.q("level", "0")))));
            if (res == null) {
                h.send(204, new byte[0], "application/json");
                return;
            }
            String etag = "\"" + res[1] + "\"";
            if (h.notModified(etag)) {
                return;
            }
            h.send(200, (byte[]) res[0], "application/octet-stream", "no-cache", Map.of("Content-Encoding", "gzip", "ETag", etag));
        });

        r.get("/api/trail", h -> {
            String key = h.q("srv", "") + "/" + h.q("dim", "");
            int n = Http.pyInt(h.q("n", "5000"));
            JsonArray out = new JsonArray();
            synchronized (app) {
                JsonArray pts = J.arr(app.trail, key);
                if (pts != null) {
                    // Python's pts[-n:]
                    int from = n > 0 ? Math.max(0, pts.size() - n) : n == 0 ? 0 : Math.min(pts.size(), -n);
                    int to = pts.size();
                    for (int i = from; i < to; i++) {
                        out.add(pts.get(i));
                    }
                }
            }
            h.json(out);
        });

        r.getPrefix("/api/spawn-tile/", h -> {
            List<String> parts = h.parts();
            Recording.Dimension dim = parts.size() == 4 ? app.rec.dimension(parts.get(0), parts.get(1)) : null;
            if (dim == null) {
                h.fail(404, "keine Aufnahme");
                return;
            }
            String last = parts.get(3);
            int rx = Http.pyInt(parts.get(2)), rz = Http.pyInt(last.endsWith(".png") ? last.substring(0, last.length() - 4) : last);
            Integer ymax = h.has("ymax") ? h.qi("ymax") : null;
            Region reg = dim.region(rx, rz);
            Region.Header head = reg == null ? null : reg.header();
            if (head == null) {
                h.send(200, Png.EMPTY, "image/png", "no-cache");
                return;
            }
            String etag = "\"spawn-" + head.mtimeNs + "-" + (ymax == null ? "None" : ymax) + "\"";
            if (h.notModified(etag, "image/png")) {
                return;
            }
            List<Object> key = java.util.Arrays.asList("spawn", dim.id(), rx, rz, head.mtimeNs, ymax);
            byte[] png = (byte[]) app.meshes.get(key);
            if (png == null) {
                png = Spawn.regionPng(app.rec, dim, rx, rz, ymax);
                if (png == null) {
                    png = Png.EMPTY;
                }
                app.meshes.put(key, png);
            }
            h.send(200, png, "image/png", "no-cache", Map.of("ETag", etag));
        });

        r.getPrefix("/api/spawn/", h -> {
            List<String> parts = h.parts();
            Recording.Dimension dim = parts.size() == 4 ? app.rec.dimension(parts.get(0), parts.get(1)) : null;
            if (dim == null) {
                h.fail(404, "keine Aufnahme");
                return;
            }
            Chunk ch = app.rec.chunk(dim, Http.pyInt(parts.get(2)), Http.pyInt(parts.get(3)));
            h.json(ch != null ? Spawn.points(ch, dim.key) : J.map("light", false, "pts", List.of()));
        });

        r.get("/api/history/dates", h -> h.json(app.history.dates(h.need("srv"), h.need("dim"))));
        r.get("/api/history/diff", h -> {
            Recording.Dimension dim = app.rec.dimension(h.need("srv"), h.need("dim"));
            if (dim == null) {
                h.fail(404, "keine Aufnahme fuer diese Dimension");
                return;
            }
            h.json(app.history.diff(app.rec, dim, h.need("srv"), h.need("dim"), h.need("date")));
        });
    }

    static String stripChars(String s, String chars) {
        int a = 0, b = s.length();
        while (a < b && chars.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && chars.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    /** json.dumps with its default separators (", " and ": "), ASCII only. */
    static String pyJson(Object o) {
        return pyJson(J.tree(o));
    }

    static String pyJson(JsonElement e) {
        StringBuilder sb = new StringBuilder();
        pyJson(e, sb);
        return sb.toString();
    }

    private static void pyJson(JsonElement e, StringBuilder sb) {
        if (e == null || e.isJsonNull()) {
            sb.append("null");
        } else if (e.isJsonObject()) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonElement> en : e.getAsJsonObject().entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                pyJson(new JsonPrimitive(en.getKey()), sb);
                sb.append(": ");
                pyJson(en.getValue(), sb);
            }
            sb.append('}');
        } else if (e.isJsonArray()) {
            sb.append('[');
            boolean first = true;
            for (JsonElement x : e.getAsJsonArray()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                pyJson(x, sb);
            }
            sb.append(']');
        } else if (e.getAsJsonPrimitive().isString()) {
            String s = e.getAsString();
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"' || c == '\\') {
                    sb.append('\\').append(c);
                } else if (c < 0x20 || c > 0x7e) {
                    sb.append(String.format("\\u%04x", (int) c));
                } else {
                    sb.append(c);
                }
            }
            sb.append('"');
        } else {
            sb.append(e.toString());
        }
    }

    // ------------------------------------------------------------------ game

    static Map<String, Object> playerPayload(App app) {
        JsonObject pl = app.player;
        boolean active = pl != null && Stores.mono() - app.playerTs < App.PLAYER_STALE;
        JsonObject out = pl == null ? new JsonObject() : pl.deepCopy();
        out.addProperty("active", active);
        out.addProperty("skinVersion", app.skinVersion);
        out.addProperty("hasSkin", app.skinFull != null || app.skinFace != null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("_", out);
        return m;
    }

    static JsonObject playerJson(App app) {
        return (JsonObject) playerPayload(app).get("_");
    }

    static boolean isPng(byte[] b) {
        if (b == null || b.length < 8) {
            return false;
        }
        for (int i = 0; i < 8; i++) {
            if (b[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
    }

    static byte[] skinByName(App app) {
        JsonObject pl = app.player;
        String name = pl == null ? "" : J.str(pl, "name", "").replaceAll("[^A-Za-z0-9_]", "");
        if (name.isEmpty() || name.equals(app.skinFetchName)) {
            return null;
        }
        app.skinFetchName = name;
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL).build();
            HttpResponse<byte[]> res = c.send(HttpRequest.newBuilder(URI.create("https://mc-heads.net/skin/" + name))
                    .header("User-Agent", "webmatica").timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofByteArray());
            byte[] data = res.body();
            if (isPng(data)) {
                app.skinFull = data;
                app.skinVersion++;
                return data;
            }
        } catch (Exception e) {
            // offline: no skin
        }
        return null;
    }

    static Path clientInfo() {
        return Path.of(System.getProperty("user.home"), ".webmatica", "client.json");
    }

    static void game(Router r, App app) {
        r.get("/api/version", h -> h.json(J.map("version", app.version, "tabs", app.streams)));
        r.get("/api/lang", h -> {
            JsonObject info = J.obj(J.load(clientInfo()));
            JsonElement lang = info == null ? null : J.get(info, "language");
            h.json(J.map("game", J.bool(lang) ? lang : null));
        });
        r.post("/api/quit", (h, req) -> {
            h.json(J.map("ok", true));
            app.shutdown.run();
        });

        r.get("/api/player", h -> h.json(playerJson(app)));
        r.get("/api/player/stream", h -> playerStream(h, app));

        Router.Get skin = h -> {
            boolean face = h.path.endsWith("face.png");
            byte[] data = face ? app.skinFace : app.skinFull;
            if ((data == null || data.length == 0) && !face) {
                data = skinByName(app);
            }
            if (data == null || data.length == 0) {
                h.fail(404, "kein Skin");
                return;
            }
            h.send(200, data, "image/png", "no-cache");
        };
        r.get("/api/skin/face.png", skin);
        r.get("/api/skin/full.png", skin);

        r.post("/api/position", (h, reqE) -> {
            JsonObject req = J.obj(reqE);
            JsonObject pl = new JsonObject();
            try {
                if (req == null) {
                    throw new KeyError("x");
                }
                pl.addProperty("x", J.d(req, "x"));
                pl.addProperty("y", J.d(req, "y"));
                pl.addProperty("z", J.d(req, "z"));
                pl.addProperty("yaw", J.d(req, "yaw", 0));
                pl.addProperty("pitch", J.d(req, "pitch", 0));
                String dim = J.str(req, "dim", "");
                pl.addProperty("dim", dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim);
                pl.addProperty("server", J.str(req, "server", ""));
                pl.addProperty("name", J.str(req, "name", ""));
                pl.addProperty("skinModel", J.str(req, "skinModel", ""));
                pl.addProperty("recording", J.bool(req, "recording", true));
                pl.addProperty("written", J.bool(req, "written") ? J.lng(req.get("written")) : 0);
                pl.addProperty("t", J.bool(req, "t") ? J.d(req.get("t")) : 0.0);
                JsonObject mods = J.obj(req, "mods");
                if (mods != null) {
                    JsonObject m = new JsonObject();
                    for (Map.Entry<String, JsonElement> e : mods.entrySet()) {
                        m.addProperty(e.getKey(), J.bool(e.getValue()));
                    }
                    pl.add("mods", m);
                } else {
                    pl.add("mods", J.nul());
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                h.fail(400, "x/y/z fehlen");
                return;
            }
            for (String field : new String[] {"skinFace", "skinFull"}) {
                if (J.bool(req, field)) {
                    try {
                        byte[] data = Base64.getMimeDecoder().decode(J.str(req, field));
                        byte[] old = field.equals("skinFace") ? app.skinFace : app.skinFull;
                        if (isPng(data) && !java.util.Arrays.equals(old, data)) {
                            if (field.equals("skinFace")) {
                                app.skinFace = data;
                            } else {
                                app.skinFull = data;
                            }
                            app.skinVersion++;
                        }
                    } catch (IllegalArgumentException ignored) {
                        // broken base64
                    }
                }
            }
            JsonObject sel = J.obj(req, "sel");
            if (sel != null) {
                // the selection made in game, shown in the viewer too
                JsonObject o = new JsonObject();
                try {
                    o.addProperty("dim", J.str(sel, "dim", ""));
                    for (String k : new String[] {"x0", "y0", "z0", "x1", "y1", "z1"}) {
                        o.addProperty(k, J.i(sel, k));
                    }
                    pl.add("sel", o);
                } catch (RuntimeException ignored) {
                    // incomplete box: leave it out
                }
            }
            JsonArray others = J.arr(req, "others");
            if (others != null) {
                pl.add("others", app.radar.update(pl, others));
            }
            JsonArray inv = J.arr(req, "inv");
            if (inv != null) {
                JsonObject o = new JsonObject();
                o.add("items", Stores.Containers.cleanItems(inv));
                o.addProperty("t", Stores.now() * 1000);
                o.add("server", pl.get("server"));
                o.add("name", pl.get("name"));
                app.inventory = o;
            }
            synchronized (app.playerCond) {
                app.player = pl;
                app.playerTs = Stores.mono();
                app.playerSeq++;
                app.playerCond.notifyAll();
            }
            if (!J.str(pl, "server").isEmpty()) {
                app.addTrail(pl);
            }
            h.json(J.map("ok", true));
        });

        r.get("/api/inventory", h -> h.json(app.inventory == null ? J.nul() : app.inventory));
        r.get("/api/radar", h -> h.json(app.radar.list(h.q("srv"))));

        // commands for the mod
        r.get("/api/commands", h -> h.json(app.commands.pop(h.need("srv"))));
        r.post("/api/commands", (h, reqE) -> {
            JsonObject req = J.obj(reqE);
            if (req == null) {
                throw new IllegalArgumentException("Objekt erwartet");
            }
            String kind = J.str(req, "kind", "None");
            if (!SAFE_COMMANDS.contains(kind) && app.control.safe(J.str(req, "srv", "None"))) {
                h.fail(403, SAFE_MSG);
                return;
            }
            try {
                if (kind.equals("stop")) {
                    app.auto.stop(J.str(req, "srv", "None"), "gestoppt (Stopp-Knopf)");
                }
                h.json(app.commands.push(req));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });
        r.get("/api/commands/last", h -> {
            if (h.has("id")) {
                JsonObject res = app.commands.results.get(h.q("id"));
                h.json(res == null ? J.nul() : res);
                return;
            }
            JsonObject last = app.commands.last;
            h.json(last == null ? J.nul() : last);
        });
        r.post("/api/commands/result", (h, req) -> {
            app.commands.result(J.obj(req) != null ? req.getAsJsonObject() : new JsonObject());
            h.json(J.map("ok", true));
        });

        // printer, server-safe, layer control
        r.get("/api/control", h -> {
            // Only read here: the mod waits at most 2 s for this answer.
            String srv = h.need("srv");
            app.polling.put(srv, Stores.mono());
            JsonObject out = app.control.forMod(srv).deepCopy();
            out.add("guard", J.tree(app.auto.guard(srv)));
            h.json(out);
        });
        r.post("/api/control", (h, reqE) -> {
            JsonObject req = J.obj(reqE);
            try {
                if (req == null || !req.has("srv")) {
                    throw new KeyError("srv");
                }
                String srv = J.str(req, "srv");
                JsonObject res = app.control.push(req);
                if (J.bool(req, "safe")) {
                    app.auto.stop(srv, "gestoppt (Server-safe eingeschaltet)");
                }
                h.json(res);
            } catch (IllegalArgumentException | IllegalStateException e) {
                h.fail(400, App.pyErr(e));
            }
        });
        r.get("/api/printer/blocks", h -> {
            try {
                h.json(app.placements.printerBlocks(h.need("srv"), h.need("dim"), h.qi("x"), h.qi("y"), h.qi("z"),
                        Http.pyInt(h.q("r", "8"))));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });
        r.post("/api/control/status", (h, req) -> {
            app.control.setStatus(J.obj(req) != null ? req.getAsJsonObject() : new JsonObject());
            h.json(J.map("ok", true));
        });
        r.get("/api/control/state", h -> h.json(app.control.view(h.need("srv"))));

        // auto-build, farming, flattening
        r.get("/api/auto/settings", h -> h.json(app.auto.getSettings()));
        r.post("/api/auto/settings", (h, req) -> h.json(app.auto.setSettings(J.obj(req) != null ? req.getAsJsonObject() : new JsonObject())));
        r.get("/api/auto", h -> h.json(app.auto.view(h.need("srv"))));
        r.get("/api/auto/storeplan", h -> {
            try {
                h.json(app.auto.storePlan(h.need("srv"), "1".equals(h.q("hotbar", "0"))));
            } catch (KeyError e) {
                throw e;
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });
        r.post("/api/auto", (h, reqE) -> {
            JsonObject req = J.obj(reqE);
            AutoBuild auto = app.auto;
            try {
                if (req == null || !req.has("srv")) {
                    throw new KeyError("srv");
                }
                String srv = J.str(req, "srv");
                boolean starts = J.bool(req, "store") || J.bool(req, "startQueue") || J.bool(req, "farm") || J.bool(req, "pid");
                if (starts && app.control.safe(srv)) {
                    h.fail(403, SAFE_MSG);
                    return;
                }
                if (J.bool(req, "store")) {
                    h.json(auto.startStore(srv, J.bool(req, "hotbar")));
                } else if (J.bool(req, "startQueue")) {
                    auto.nextInQueue(srv);
                    h.json(auto.view(srv));
                } else if (req.has("queue")) {
                    List<String> q = new ArrayList<>();
                    for (JsonElement e : J.list(req.get("queue"))) {
                        q.add(J.str(e));
                    }
                    h.json(auto.setQueue(srv, q));
                } else if (J.bool(req, "farm") && J.bool(J.obj(req, "farm"), "flatten")) {
                    h.json(auto.startFlatten(srv, J.obj(req, "farm").deepCopy()));
                } else if (J.bool(req, "farm")) {
                    h.json(auto.startFarm(srv, J.obj(req, "farm").deepCopy()));
                } else if (J.bool(req, "pid")) {
                    h.json(auto.start(srv, J.str(req, "pid")));
                } else {
                    h.json(auto.stop(srv, null));
                }
            } catch (LookupError | KeyError e) {
                h.fail(404, "unbekannt: " + App.pyErr(e));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });

        r.post("/api/fix", (h, reqE) -> {
            JsonObject req = J.obj(reqE);
            if (req == null) {
                req = new JsonObject();
            }
            if (app.control.safe(J.str(req, "srv", "None"))) {
                h.fail(403, SAFE_MSG);
                return;
            }
            try {
                JsonObject res = app.placements.verify(J.str(J.get(req, "pid") == null && !req.has("pid") ? keyErr("pid") : req.get("pid")), app.rec, null);
                List<int[]> pts = AutoBuild.fixPoints(res, J.bool(req, "extra"));
                if (pts.isEmpty()) {
                    h.json(J.map("n", 0));
                    return;
                }
                if (!req.has("srv")) {
                    throw new KeyError("srv");
                }
                JsonObject cmd = new JsonObject();
                cmd.addProperty("srv", J.str(req, "srv"));
                cmd.addProperty("kind", "breakboxes");
                cmd.add("boxes", J.tree(Farm.boxes(pts)));
                cmd.addProperty("label", "Falsche Blöcke");
                cmd.addProperty("pickup", true);
                JsonObject done = app.commands.push(cmd);
                h.json(J.map("n", pts.size(), "id", done.get("id")));
            } catch (LookupError e) {
                h.fail(404, App.pyErr(e));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });

        r.get("/api/farm/scan", h -> {
            Recording.Dimension dim = app.rec.dimension(h.need("srv"), h.need("dim"));
            if (dim == null) {
                h.fail(404, "keine Aufnahme");
                return;
            }
            try {
                h.json(Farm.scan(app.rec, dim, area(h)));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });

        r.get("/api/farm/flatscan", h -> {
            Recording.Dimension dim = app.rec.dimension(h.need("srv"), h.need("dim"));
            if (dim == null) {
                h.fail(404, "keine Aufnahme");
                return;
            }
            try {
                Farm.Area a = Farm.normArea(area(h));
                String kind = h.q("kind", "flat");
                if (kind.equals("light")) {
                    Spawn.TorchPlan plan = Spawn.torchPlan(app.rec, dim, a);
                    List<List<Integer>> chunks = new ArrayList<>();
                    List<long[]> keys = new ArrayList<>();
                    for (Map.Entry<Long, Integer> e : plan.chunks().entrySet()) {
                        keys.add(new long[] {(int) (e.getKey() >> 32), (int) (long) e.getKey(), e.getValue()});
                    }
                    keys.sort((p, q) -> p[0] != q[0] ? Long.compare(p[0], q[0]) : Long.compare(p[1], q[1]));
                    for (long[] k : keys) {
                        chunks.add(List.of((int) k[0], (int) k[1], 0, (int) k[2]));
                    }
                    h.json(J.map("clear", 0, "fill", plan.torches().size(), "spots", plan.spots(), "mined", List.of(),
                            "chunks", chunks));
                    return;
                }
                int level = kind.equals("flat") ? h.qi("level") : 0;
                h.json(Farm.flatPreview(app.rec, dim, a, kind, level));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });
    }

    private static JsonElement keyErr(String k) {
        throw new KeyError(k);
    }

    static Map<String, String> area(Http h) {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : AREA) {
            m.put(k, h.need(k));
        }
        return m;
    }

    /** Server-sent events: every new position to the browser immediately (instead of polling). */
    static void playerStream(Http h, App app) {
        var ex = h.ex;
        var hd = ex.getResponseHeaders();
        hd.set("Content-Type", "text/event-stream");
        hd.set("Cache-Control", "no-cache");
        hd.set("X-Accel-Buffering", "no");
        hd.set("Connection", "close");
        long seq = -1;
        synchronized (app.playerCond) {
            app.streams++;
        }
        try {
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            while (true) {
                synchronized (app.playerCond) {
                    long deadline = System.currentTimeMillis() + 2000;
                    while (app.playerSeq == seq) {
                        long left = deadline - System.currentTimeMillis();
                        if (left <= 0) {
                            break;
                        }
                        app.playerCond.wait(left);
                    }
                    seq = app.playerSeq;
                }
                os.write(("data: " + J.dump(playerJson(app)) + "\n\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException | InterruptedException e) {
            // tab closed
        } finally {
            synchronized (app.playerCond) {
                app.streams--;
            }
            ex.close();
        }
    }

    // ------------------------------------------------------------------ schematics, editor, chests

    @FunctionalInterface
    interface JsonCall {
        Object call(JsonObject req) throws Exception;
    }

    /** POST route whose lookups answer 404 "unbekannt", everything else 400. */
    static void lookupRoute(Router r, String path, JsonCall fn, boolean anyErrorIs400) {
        r.post(path, (h, reqE) -> {
            JsonObject req = J.obj(reqE) != null ? reqE.getAsJsonObject() : new JsonObject();
            try {
                Object res = fn.call(req);
                h.json(res);
            } catch (LookupError | KeyError e) {
                h.fail(404, "unbekannt: " + App.pyErr(e));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            } catch (Exception e) {
                if (!anyErrorIs400) {
                    throw e;
                }
                h.fail(400, App.pyErr(e));
            }
        });
    }

    static void schematics(Router r, App app) {
        r.get("/api/schematics", h -> h.json(app.withIngame(app.placements.list(h.q("srv"), h.q("dim")))));
        r.get("/api/schematics/library", h -> h.json(J.map("folders", app.placements.dirs(), "files", app.placements.library())));
        r.getPrefix("/api/schematics/", h -> {
            String[] parts = h.path.split("/", -1);
            if (parts.length != 5) {
                h.fail(404, "nicht gefunden");
                return;
            }
            String pid = Http.unquote(parts[3]), what = parts[4];
            try {
                switch (what) {
                    case "mesh" -> {
                        Object[] res = app.placements.mesh(app.placements.get(pid));
                        String etag = (String) res[1];
                        if (h.notModified(etag)) {
                            return;
                        }
                        h.send(200, (byte[]) res[0], "application/octet-stream", "no-cache", Map.of("Content-Encoding", "gzip", "ETag", etag));
                    }
                    case "materials" -> h.json(app.placements.materials(pid));
                    case "verify" -> {
                        JsonObject pl = app.placements.get(pid);
                        JsonObject me = app.player;
                        double[] near = me != null && J.str(me, "dim").equals(J.str(pl, "dim", "None"))
                                ? new double[] {J.d(me, "x"), J.d(me, "y"), J.d(me, "z")} : null;
                        h.json(app.placements.verify(pid, app.rec, near));
                    }
                    default -> h.fail(404, "nicht gefunden");
                }
            } catch (LookupError | KeyError e) {
                h.fail(404, App.pyErr(e));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });

        r.postRaw("/api/schematics/upload", h -> {
            String cl = h.header("Content-Length");
            long n = cl == null ? 0 : Long.parseLong(cl.strip());
            if (n <= 0 || n > 64L * 1024 * 1024) {
                h.fail(400, "Datei fehlt oder ist groesser als 64 MB");
                return;
            }
            byte[] data = h.ex.getRequestBody().readNBytes((int) n);
            try {
                h.json(app.placements.upload(h.q("name", "upload.schem"), data));
            } catch (Exception e) {
                h.fail(400, "Schematic nicht lesbar: " + App.pyErr(e));
            }
        });

        lookupRoute(r, "/api/schematics/place", app.placements::place, true);
        lookupRoute(r, "/api/schematics/update", app.placements::update, true);
        lookupRoute(r, "/api/schematics/trash", req -> app.placements.trash(J.str(req, "file", "None")), true);
        lookupRoute(r, "/api/schematics/delete", req -> {
            app.placements.delete(J.str(req, "id", "None"));
            return J.map("ok", true);
        }, true);

        r.get("/api/litematica", h -> h.json(app.placements.litematica(h.q("srv"))));
        r.post("/api/litematica/status", (h, req) -> {
            app.lmStatus = J.obj(req) != null ? req.getAsJsonObject() : new JsonObject();
            app.lmStatusTs = Stores.mono();
            h.json(J.map("ok", true));
        });

        // editor
        r.getPrefix("/api/editor/", h -> {
            try {
                switch (h.path) {
                    case "/api/editor/blocks" -> h.json(app.editor.blocks());
                    case "/api/editor/voxels" -> h.json(app.editor.voxels(h.need("file")));
                    case "/api/editor/file" -> {
                        Path path = app.placements.resolve(h.need("file"));
                        byte[] data;
                        try {
                            data = Files.readAllBytes(path);
                        } catch (java.nio.file.NoSuchFileException e) {
                            throw new LookupError("[Errno 2] No such file or directory: '" + path + "'");
                        }
                        String name = path.getFileName().toString().replace("\"", "");
                        h.send(200, data, "application/octet-stream", "no-cache", Map.of("Content-Disposition", "attachment; filename=\"" + name + "\""));
                    }
                    case "/api/editor/info" -> h.json(app.editor.info(h.need("file")));
                    case "/api/editor/blockmesh" -> h.send(200, app.editor.blockMesh(h.need("state")), "application/octet-stream",
                            "max-age=3600", Map.of("Content-Encoding", "gzip"));
                    case "/api/editor/mesh" -> {
                        Object[] res = app.editor.mesh(h.need("file"), h.has("yhi") ? h.qi("yhi") : null);
                        String etag = (String) res[1];
                        if (h.notModified(etag)) {
                            return;
                        }
                        h.send(200, (byte[]) res[0], "application/octet-stream", "no-cache", Map.of("Content-Encoding", "gzip", "ETag", etag));
                    }
                    default -> h.fail(404, "nicht gefunden");
                }
            } catch (LookupError | KeyError e) {
                h.fail(404, "unbekannt: " + App.pyErr(e));
            } catch (IllegalArgumentException e) {
                h.fail(400, App.pyErr(e));
            }
        });
        Editor ed = app.editor;
        lookupRoute(r, "/api/editor/open", ed::open, false);
        lookupRoute(r, "/api/editor/new", ed::create, false);
        lookupRoute(r, "/api/editor/cut", ed::cut, false);
        lookupRoute(r, "/api/editor/rename", ed::rename, false);
        lookupRoute(r, "/api/editor/edit", ed::edit, false);
        lookupRoute(r, "/api/editor/undo", req -> ed.undo(req, false), false);
        lookupRoute(r, "/api/editor/redo", req -> ed.undo(req, true), false);
        lookupRoute(r, "/api/editor/save", ed::save, false);
        lookupRoute(r, "/api/editor/discard", ed::discard, false);

        // chests
        r.get("/api/containers", h -> h.json(app.containers(h.q("srv"))));
        r.post("/api/containers", (h, req) -> {
            try {
                JsonObject o = J.obj(req);
                if (o == null) {
                    throw new IllegalArgumentException("Objekt erwartet");
                }
                h.json(app.containers.put(o));
            } catch (IllegalArgumentException | IllegalStateException e) {
                h.fail(400, "Kiste unvollstaendig: " + App.pyErr(e));
            }
        });
        r.post("/api/containers/gone", (h, req) -> {
            JsonObject o = J.obj(req);
            h.json(J.map("removed", o == null ? 0 : app.containers.gone(o)));
        });
        r.post("/api/containers/delete", (h, req) -> {
            app.containers.delete(J.str(J.obj(req), "key", "None"));
            h.json(J.map("ok", true));
        });
        r.get("/api/containers/lid", h -> h.json(app.lids.list(h.q("srv"), h.q("dim"))));
        r.post("/api/containers/lid", (h, req) -> {
            try {
                JsonObject o = J.obj(req);
                if (o == null) {
                    throw new IllegalArgumentException("Objekt erwartet");
                }
                h.json(app.lids.put(o));
            } catch (IllegalArgumentException | IllegalStateException e) {
                h.fail(400, App.pyErr(e));
            }
        });
        r.get("/api/containers/world", h -> {
            Recording.Dimension dim = app.rec.dimension(h.need("srv"), h.need("dim"));
            if (dim == null) {
                h.json(List.of());
                return;
            }
            int rr = Math.max(1, Math.min(32, Http.pyInt(h.q("r", "10"))));
            int cx = ((int) Http.pyFloat(h.need("x"))) >> 4, cz = ((int) Http.pyFloat(h.need("z"))) >> 4;
            String key = dim.id() + "|" + (cx >> 2) + "|" + (cz >> 2) + "|" + rr;
            Object[] hit = app.worldChests.get(key);
            if (hit == null || Stores.mono() - (Double) hit[0] > 30) {
                hit = new Object[] {Stores.mono(), WorldChests.find(app.rec, dim, cx, cz, rr)};
                if (app.worldChests.size() > 16) {
                    app.worldChests.clear();
                }
                app.worldChests.put(key, hit);
            }
            h.json(hit[1]);
        });
    }
}
