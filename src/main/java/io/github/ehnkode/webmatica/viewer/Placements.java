package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.KeyError;
import io.github.ehnkode.webmatica.viewer.core.LookupError;
import io.github.ehnkode.webmatica.viewer.core.Palette;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Schematic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Schematic library and placed schematics. Library: all .schem/.litematic/.nbt in the schematic
 * folder next to the recording (Litematica: schematics/) plus uploads in
 * {@code <recording>/.viewer/schematics/}. Placements are stored in .viewer/placements.json.
 */
final class Placements {
    static final String[] EXTS = {".schem", ".litematic", ".nbt", ".schematic"};
    static final Map<Integer, String> LM_ROT = Map.of(0, "NONE", 90, "CLOCKWISE_90", 180, "CLOCKWISE_180", 270, "COUNTERCLOCKWISE_90");

    final Path root, dataDir, uploadDir, path;
    final Map<String, Path> dirs = new LinkedHashMap<>();
    final Object lock = new Object();
    private final Map<List<Object>, Schematic> schems = new HashMap<>();
    private final Map<List<Object>, Schematic> trans = new HashMap<>();
    private final Map<List<Object>, byte[]> meshes = new HashMap<>();

    Placements(Path recRoot) {
        root = recRoot;
        dataDir = recRoot.resolve(".viewer");
        uploadDir = dataDir.resolve("schematics");
        try {
            Files.createDirectories(uploadDir);
        } catch (IOException ignored) {
            // uploads report it
        }
        Path parent = recRoot.toAbsolutePath().getParent();
        List<Path> cands = new ArrayList<>();
        if (parent != null) {
            cands.add(parent.resolve("schematics"));
        }
        cands.add(uploadDir);
        for (Path d : cands) {
            if (Files.isDirectory(d)) {
                String label = d.equals(uploadDir) ? "hochgeladen" : d.getFileName().toString();
                dirs.putIfAbsent(label, d.toAbsolutePath().normalize());
            }
        }
        path = dataDir.resolve("placements.json");
    }

    List<String> dirs() {
        return new ArrayList<>(dirs.keySet());
    }

    static boolean hasExt(String fn) {
        String l = fn.toLowerCase(Locale.ROOT);
        for (String e : EXTS) {
            if (l.endsWith(e)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ library

    List<Map<String, Object>> library() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, Path> e : dirs.entrySet()) {
            Path d = e.getValue();
            List<Path> files;
            try (Stream<Path> s = Files.walk(d)) {
                files = s.filter(Files::isRegularFile).filter(p -> hasExt(p.getFileName().toString())).toList();
            } catch (IOException ex) {
                continue;
            }
            for (Path p : files) {
                String fn = p.getFileName().toString();
                String rel = d.relativize(p).toString().replace("\\", "/");
                long size, mtime;
                try {
                    size = Files.size(p);
                    mtime = Files.getLastModifiedTime(p).toMillis() / 1000;
                } catch (IOException ex) {
                    continue;
                }
                int dot = fn.lastIndexOf('.');
                out.add(J.map("file", e.getKey() + "/" + rel, "name", dot < 0 ? fn : fn.substring(0, dot), "folder", e.getKey(),
                        "bytes", size, "mtime", mtime));
                if (out.size() >= 3000) {
                    return out;
                }
            }
        }
        out.sort((a, b) -> Long.compare((Long) b.get("mtime"), (Long) a.get("mtime")));
        return out;
    }

    Path resolve(String fileId) {
        int i = fileId.indexOf('/');
        String label = i < 0 ? fileId : fileId.substring(0, i), rel = i < 0 ? "" : fileId.substring(i + 1);
        Path d = dirs.get(label);
        if (d == null || rel.isEmpty()) {
            throw new LookupError("Schematic-Ordner unbekannt");
        }
        Path p = d.resolve(rel).toAbsolutePath().normalize();
        if (!p.startsWith(d) || p.equals(d) || !Files.isRegularFile(p)) {
            throw new LookupError("Schematic-Datei nicht gefunden");
        }
        return p;
    }

    Map<String, Object> upload(String filename, byte[] data) throws IOException {
        String base = filename.substring(Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\')) + 1);
        String name = Routes.stripChars(base.replaceAll("[^A-Za-z0-9_. ()-]", "_"), " .");
        if (name.isEmpty()) {
            name = "upload.schem";
        }
        if (!hasExt(name)) {
            throw new IllegalArgumentException("nur .schem, .litematic oder .nbt");
        }
        Schematic.load(data, name);       // check up front so only readable files end up there
        Files.write(uploadDir.resolve(name), data);
        return J.map("file", "hochgeladen/" + name, "name", name.substring(0, name.lastIndexOf('.')));
    }

    // ------------------------------------------------------------------ loading with cache

    static long mtime(Path p) {
        try {
            return Files.getLastModifiedTime(p).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    Schematic schem(String fileId, int rot, boolean mirror) throws IOException {
        Path p = resolve(fileId);
        long mt = mtime(p);
        List<Object> key = List.of(p.toString(), mt);
        Schematic s;
        synchronized (schems) {
            s = schems.get(key);
        }
        if (s == null) {
            s = Schematic.load(Files.readAllBytes(p), p.getFileName().toString());
            synchronized (schems) {
                if (schems.size() > 16) {
                    schems.clear();
                    trans.clear();
                }
                schems.put(key, s);
            }
        }
        List<Object> tkey = List.of(p.toString(), mt, Math.floorMod(rot, 360), mirror);
        Schematic t;
        synchronized (schems) {
            t = trans.get(tkey);
        }
        if (t == null) {
            t = Schematic.transform(s, rot, mirror);
            synchronized (schems) {
                trans.put(tkey, t);
            }
        }
        return t;
    }

    Schematic schem(JsonObject pl) throws IOException {
        return schem(J.str(pl, "file"), J.i(pl, "rot", 0), J.bool(pl, "mirror"));
    }

    Object[] mesh(JsonObject pl) throws IOException {
        if (!pl.has("file")) {
            throw new KeyError("file");
        }
        Path p = resolve(J.str(pl, "file"));
        long mt = mtime(p);
        int rot = Math.floorMod(J.i(pl, "rot", 0), 360);
        boolean mirror = J.bool(pl, "mirror");
        List<Object> key = List.of(p.toString(), mt, rot, mirror);
        String etag = "\"" + Palette.models.version + "-" + Mesher.MAGIC + "-" + Integer.toUnsignedString(key.hashCode()) + "\"";
        byte[] data;
        synchronized (meshes) {
            data = meshes.get(key);
        }
        if (data == null) {
            Schematic s = schem(pl);
            if (Math.max(s.w, Math.max(s.h, s.l)) > 2047) {
                throw new IllegalArgumentException("Schematic zu gross fuer die 3D-Ansicht (max. 2047 Bloecke Kantenlaenge)");
            }
            data = App.gzip(Mesher.meshVolume(s.blocks, s.w, s.h, s.l), 5);
            synchronized (meshes) {
                if (meshes.size() > 32) {
                    meshes.clear();
                }
                meshes.put(key, data);
            }
        }
        return new Object[] {data, etag};
    }

    // ------------------------------------------------------------------ placements

    JsonArray read() {
        JsonElement e = J.load(path);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : new JsonArray();
    }

    void write(JsonArray pls) throws IOException {
        J.save(path, pls, true);
    }

    JsonObject withSize(JsonObject pl) {
        JsonObject out = pl.deepCopy();
        try {
            Schematic s = schem(pl);
            out.add("size", J.tree(List.of(s.w, s.h, s.l)));
            out.add("error", J.nul());
            out.addProperty("mtime", mtime(resolve(J.str(pl, "file"))));
        } catch (Exception e) {    // file gone/broken: show it anyway so it can be removed
            out.add("size", J.tree(List.of(1, 1, 1)));
            out.addProperty("error", App.pyErr(e));
        }
        return out;
    }

    JsonArray list(String server, String dim) {
        JsonArray out = new JsonArray();
        for (JsonElement e : read()) {
            JsonObject pl = e.getAsJsonObject();
            if (server != null && !server.isEmpty() && !server.equals(J.str(pl, "server", "None"))) {
                continue;
            }
            if (dim != null && !dim.isEmpty() && !dim.equals(J.str(pl, "dim", "None"))) {
                continue;
            }
            out.add(withSize(pl));
        }
        return out;
    }

    JsonObject get(String pid) {
        for (JsonElement e : read()) {
            JsonObject pl = e.getAsJsonObject();
            if (pid.equals(J.str(pl, "id", null))) {
                return pl;
            }
        }
        throw new LookupError("Platzierung unbekannt");
    }

    static String need(JsonObject req, String k) {
        if (!req.has(k)) {
            throw new KeyError(k);
        }
        return J.str(req, k);
    }

    JsonObject place(JsonObject req) throws IOException {
        Schematic s = schem(need(req, "file"), 0, false);
        JsonObject pl = new JsonObject();
        pl.addProperty("id", Stores.Commands.newId());
        pl.addProperty("file", need(req, "file"));
        String name = J.bool(req, "name") ? J.str(req, "name") : s.name;
        pl.addProperty("name", name.substring(0, Math.min(80, name.length())));
        pl.addProperty("server", need(req, "server"));
        pl.addProperty("dim", need(req, "dim"));
        pl.addProperty("x", J.i(req, "x"));
        pl.addProperty("y", J.i(req, "y"));
        pl.addProperty("z", J.i(req, "z"));
        pl.addProperty("rot", 0);
        pl.addProperty("mirror", false);
        pl.addProperty("visible", true);
        pl.addProperty("opacity", 1.0);
        synchronized (lock) {
            JsonArray pls = read();
            pls.add(pl);
            write(pls);
        }
        return withSize(pl);
    }

    JsonObject update(JsonObject req) throws IOException {
        synchronized (lock) {
            JsonArray pls = read();
            for (JsonElement e : pls) {
                JsonObject pl = e.getAsJsonObject();
                if (!java.util.Objects.equals(J.get(pl, "id"), J.get(req, "id"))) {
                    continue;
                }
                for (String k : new String[] {"x", "y", "z", "rot"}) {
                    if (req.has(k)) {
                        pl.addProperty(k, J.i(req, k));
                    }
                }
                for (String k : new String[] {"mirror", "visible", "ingame"}) {
                    if (req.has(k)) {
                        pl.addProperty(k, J.bool(req, k));
                    }
                }
                if (req.has("opacity")) {
                    pl.addProperty("opacity", J.d(req, "opacity"));
                }
                if (req.has("name")) {
                    pl.addProperty("name", J.str(req, "name"));
                }
                pl.addProperty("rot", Math.floorMod(J.i(pl, "rot"), 360));
                pl.addProperty("opacity", Math.min(1.0, Math.max(0.1, J.d(pl, "opacity", 1.0))));
                String name = J.str(pl, "name");
                pl.addProperty("name", name.substring(0, Math.min(80, name.length())));
                write(pls);
                return withSize(pl);
            }
        }
        throw new LookupError("Platzierung unbekannt");
    }

    void delete(String pid) throws IOException {
        synchronized (lock) {
            JsonArray keep = new JsonArray();
            for (JsonElement e : read()) {
                if (!pid.equals(J.str(e.getAsJsonObject(), "id", "None"))) {
                    keep.add(e);
                }
            }
            write(keep);
        }
    }

    /**
     * Removes a file from the library: it goes to .viewer/papierkorb (not deleted for good),
     * placements that use it are unloaded.
     */
    Map<String, Object> trash(String fileId) throws IOException {
        Path p = resolve(fileId);
        Path bin = dataDir.resolve("papierkorb");
        Files.createDirectories(bin);
        String fn = p.getFileName().toString();
        Path to = bin.resolve(fn);
        for (int i = 2; Files.exists(to); i++) {
            int dot = fn.lastIndexOf('.');
            to = bin.resolve((dot < 0 ? fn : fn.substring(0, dot)) + " (" + i + ")" + (dot < 0 ? "" : fn.substring(dot)));
        }
        Files.move(p, to);
        int dropped = 0;
        synchronized (lock) {
            JsonArray keep = new JsonArray();
            for (JsonElement e : read()) {
                if (fileId.equals(J.str(e.getAsJsonObject(), "file", "None"))) {
                    dropped++;
                } else {
                    keep.add(e);
                }
            }
            if (dropped > 0) {
                write(keep);
            }
        }
        return J.map("ok", true, "trash", to.toString(), "unloaded", dropped);
    }

    // ------------------------------------------------------------------ Litematica bridge

    static String uuid5(String name) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            UUID ns = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");
            java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(16);
            b.putLong(ns.getMostSignificantBits());
            b.putLong(ns.getLeastSignificantBits());
            md.update(b.array());
            md.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] h = md.digest();
            h[6] = (byte) ((h[6] & 0x0f) | 0x50);
            h[8] = (byte) ((h[8] & 0x3f) | 0x80);
            java.nio.ByteBuffer r = java.nio.ByteBuffer.wrap(h, 0, 16);
            return new UUID(r.getLong(), r.getLong()).toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Placements with "In game" for the mod, in Litematica's terms. Litematica wants the point
     * where the file origin lands: origin = corner - min(transform(file box)).
     */
    List<Map<String, Object>> litematica(String server) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonElement e : read()) {
            JsonObject pl = e.getAsJsonObject();
            if (!J.bool(pl, "ingame") || (server != null && !server.isEmpty() && !server.equals(J.str(pl, "server", "None")))) {
                continue;
            }
            Path p;
            Schematic s;
            try {
                p = resolve(J.str(pl, "file"));
                s = schem(J.str(pl, "file"), 0, false);
            } catch (Exception ex) {
                continue;
            }
            int rot = Math.floorMod(J.i(pl, "rot", 0), 360);
            boolean mirror = J.bool(pl, "mirror");
            int ox = s.offset[0], oy = s.offset[1], oz = s.offset[2];
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (int cx : new int[] {ox, ox + s.w - 1}) {
                for (int cz : new int[] {oz, oz + s.l - 1}) {
                    int x = mirror ? -cx : cx, z = cz;
                    for (int k = 0; k < rot / 90; k++) {
                        int t = x;
                        x = -z;
                        z = t;
                    }
                    minX = Math.min(minX, x);
                    minZ = Math.min(minZ, z);
                }
            }
            String id = J.str(pl, "id");
            out.add(J.map("id", pl.get("id"), "uuid", uuid5("webmatica:" + id),
                    "name", J.bool(pl, "name") ? J.str(pl, "name") : s.name, "path", p.toString(),
                    "dim", J.str(pl, "dim", "overworld"),
                    "origin", List.of(J.i(pl, "x") - minX, J.i(pl, "y") - oy, J.i(pl, "z") - minZ),
                    "rotation", LM_ROT.get(rot), "mirror", mirror ? "FRONT_BACK" : "NONE", "mtime", mtime(p)));
        }
        return out;
    }

    // ------------------------------------------------------------------ Webmatica printer

    /** Target blocks of the "In game" placements in a cube around the player, for the mod's printer. */
    Map<String, Object> printerBlocks(String server, String dim, int x, int y, int z, int r) {
        r = Math.max(1, Math.min(12, r));
        List<String> palette = new ArrayList<>();
        Map<Integer, Integer> index = new HashMap<>();
        Map<List<Integer>, Integer> found = new LinkedHashMap<>();
        for (JsonElement e : read()) {
            JsonObject pl = e.getAsJsonObject();
            if (!J.bool(pl, "ingame") || !server.equals(J.str(pl, "server", "None")) || !dim.equals(J.str(pl, "dim", "overworld"))) {
                continue;
            }
            Schematic s;
            try {
                s = schem(pl);
            } catch (Exception ex) {
                continue;
            }
            int x0 = J.i(pl, "x"), y0 = J.i(pl, "y"), z0 = J.i(pl, "z");
            int ax = Math.max(x - r, x0), bx = Math.min(x + r, x0 + s.w - 1);
            int ay = Math.max(y - r, y0), by = Math.min(y + r, y0 + s.h - 1);
            int az = Math.max(z - r, z0), bz = Math.min(z + r, z0 + s.l - 1);
            if (ax > bx || ay > by || az > bz) {
                continue;
            }
            TreeSet<Integer> ids = new TreeSet<>();
            for (int yy = ay; yy <= by; yy++) {
                for (int zz = az; zz <= bz; zz++) {
                    for (int xx = ax; xx <= bx; xx++) {
                        ids.add(s.at(xx - x0, yy - y0, zz - z0));
                    }
                }
            }
            for (int sid : ids) {
                if (!index.containsKey(sid) && !Schematic.isAir(Chunk.BLOCKS.name(sid))) {
                    index.put(sid, palette.size());
                    palette.add(Chunk.BLOCKS.name(sid));
                }
            }
            for (int yy = ay; yy <= by; yy++) {
                for (int zz = az; zz <= bz; zz++) {
                    for (int xx = ax; xx <= bx; xx++) {
                        int v = s.at(xx - x0, yy - y0, zz - z0);
                        if (v == 0) {
                            continue;
                        }
                        Integer i = index.get(v);
                        if (i != null) {
                            found.put(List.of(xx, yy, zz), i);
                        }
                    }
                }
            }
        }
        List<Integer> flat = new ArrayList<>();
        for (Map.Entry<List<Integer>, Integer> e : found.entrySet()) {
            flat.addAll(e.getKey());
            flat.add(e.getValue());
        }
        return J.map("palette", palette, "blocks", flat);
    }

    // ------------------------------------------------------------------ evaluation

    Map<String, Object> materials(String pid) throws IOException {
        Schematic s = schem(get(pid));
        LinkedHashMap<String, Long> items = Schematic.materials(s.blocks);
        long total = 0;
        for (long v : items.values()) {
            total += v;
        }
        return J.map("size", List.of(s.w, s.h, s.l), "items", Schematic.sortedItems(items), "blocks", total);
    }

    JsonObject verify(String pid, Recording rec, double[] near) {
        JsonObject pl = get(pid);
        Recording.Dimension dim = rec.dimension(J.str(pl, "server"), J.str(pl, "dim"));
        if (dim == null) {
            throw new LookupError("fuer diese Dimension gibt es keine Aufnahme");
        }
        try {
            Schematic s = schem(pl);
            return J.tree(Schematic.verify(s, rec, dim, J.i(pl, "x"), J.i(pl, "y"), J.i(pl, "z"), near)).getAsJsonObject();
        } catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }
}
