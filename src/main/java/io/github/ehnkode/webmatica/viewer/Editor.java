package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.ehnkode.webmatica.viewer.core.Chunk;
import io.github.ehnkode.webmatica.viewer.core.KeyError;
import io.github.ehnkode.webmatica.viewer.core.LookupError;
import io.github.ehnkode.webmatica.viewer.core.Models;
import io.github.ehnkode.webmatica.viewer.core.Palette;
import io.github.ehnkode.webmatica.viewer.core.Recording;
import io.github.ehnkode.webmatica.viewer.core.Schematic;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Supplier;

/**
 * Schematic editor (server side). The browser sends edits (set block, fill area, replace); they
 * are applied to the block array here, kept in memory until "save" writes a Sponge .schem. Each
 * file has undo/redo. Only files in hochgeladen/ are edited; everything else is copied there first.
 */
final class Editor {
    static final int MAX_EDGE = 512;
    static final int MAX_VOLUME = 16 * 1024 * 1024;
    static final int UNDO = 40;

    /** Preferred values when the browser sets a block without properties. */
    static final Map<String, String> PREF = new HashMap<>();

    static {
        String[] kv = {"facing", "north", "half", "bottom", "type", "bottom", "shape", "straight", "axis", "y",
                "face", "floor", "hinge", "left", "open", "false", "powered", "false", "lit", "false",
                "attachment", "floor", "part", "foot", "occupied", "false", "rotation", "0", "east", "false",
                "west", "false", "north", "false", "south", "false", "up", "false", "down", "false",
                "in_wall", "false", "snowy", "false", "level", "0", "age", "0", "waterlogged", "false",
                "hanging", "false", "triggered", "false", "inverted", "false", "extended", "false",
                "delay", "1", "mode", "compare", "locked", "false", "bottom", "false", "distance", "7",
                "persistent", "true", "candles", "1", "pickles", "1", "layers", "1", "eggs", "1"};
        for (int i = 0; i < kv.length; i += 2) {
            PREF.put(kv[i], kv[i + 1]);
        }
    }

    /** A block cuboid [(y*L+z)*W+x]. */
    static final class Vol {
        char[] b;
        int w, h, l;

        Vol(char[] b, int w, int h, int l) {
            this.b = b;
            this.w = w;
            this.h = h;
            this.l = l;
        }

        Vol copy() {
            return new Vol(b.clone(), w, h, l);
        }

        int i(int x, int y, int z) {
            return (y * l + z) * w + x;
        }
    }

    record Step(Vol blocks, List<Map<String, Object>> bes, int[] shift) {
    }

    static final class Session {
        Vol blocks;
        List<Map<String, Object>> bes;
        final List<Step> undo = new ArrayList<>(), redo = new ArrayList<>();
        Path path;
        long mtime;
        String name;
        boolean dirty;
        int[] pshift = new int[3];
        int rev;
        List<Object> meshKey;
        byte[] mesh;
        String meshEtag;
    }

    final Placements pl;
    final Recording rec;
    final Supplier<Path> assets;
    private final Object lock = new Object();
    private final Map<String, Session> sessions = new HashMap<>();
    private volatile List<Map<String, Object>> blocks;
    private final Map<String, Map<String, List<String>>> props = new HashMap<>();
    private final Map<String, String> defaults = new HashMap<>();

    Editor(Placements pl, Recording rec, Supplier<Path> assets) {
        this.pl = pl;
        this.rec = rec;
        this.assets = assets;
    }

    // ------------------------------------------------------------------ blocks

    /** Properties of a block with all possible values (from the blockstate files). */
    static Map<String, List<String>> blockProps(String name) {
        Models m = Palette.models;
        Map<String, List<String>> props = new LinkedHashMap<>();
        JsonObject bs = m != null && m.available ? J.obj(m.blockstates, name) : null;
        if (bs == null || bs.isEmpty()) {
            return props;
        }
        JsonObject variants = J.obj(bs, "variants");
        if (variants != null) {
            for (String key : variants.keySet()) {
                for (String kv : key.split(",", -1)) {
                    int e = kv.indexOf('=');
                    String k = e < 0 ? kv : kv.substring(0, e), v = e < 0 ? "" : kv.substring(e + 1);
                    if (!k.isEmpty()) {
                        add(props, k, v);
                    }
                }
            }
        }
        for (JsonElement part : J.list(J.get(bs, "multipart"))) {
            walk(props, J.get(J.obj(part), "when"));
        }
        // Multipart often names only the "on" values (fence: north=true, wall: low|tall)
        for (List<String> vals : props.values()) {
            Set<String> set = new HashSet<>(vals);
            if (Set.of("true").containsAll(set)) {
                vals.add("false");
            } else if ((set.contains("low") || set.contains("tall") || set.contains("side") || set.contains("up")) && !set.contains("none")) {
                vals.add("none");
            }
        }
        return props;
    }

    private static void add(Map<String, List<String>> props, String k, String v) {
        List<String> lst = props.computeIfAbsent(k, x -> new ArrayList<>());
        for (String x : v.split("\\|", -1)) {
            if (!lst.contains(x)) {
                lst.add(x);
            }
        }
    }

    private static void walk(Map<String, List<String>> props, JsonElement cond) {
        JsonObject o = J.obj(cond);
        if (o == null) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : o.entrySet()) {
            if (e.getKey().equals("OR") || e.getKey().equals("AND")) {
                for (JsonElement c : J.list(e.getValue())) {
                    walk(props, c);
                }
            } else {
                add(props, e.getKey(), J.str(e.getValue()));
            }
        }
    }

    synchronized Map<String, List<String>> props(String name) {
        return props.computeIfAbsent(name, Editor::blockProps);
    }

    static String withProps(String name, Map<String, String> p) {
        if (p.isEmpty()) {
            return Schematic.normState(name);
        }
        StringBuilder sb = new StringBuilder(name).append('[');
        boolean first = true;
        for (Map.Entry<String, String> e : new TreeMap<>(p).entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return Schematic.normState(sb.append(']').toString());
    }

    synchronized String defaultState(String name) {
        String st = defaults.get(name);
        if (st != null) {
            return st;
        }
        Models m = Palette.models;
        JsonObject bs = m != null && m.available ? J.obj(m.blockstates, name) : null;
        Map<String, String> chosen = new LinkedHashMap<>();
        JsonObject variants = bs == null ? null : J.obj(bs, "variants");
        if (variants != null && !variants.isEmpty()) {
            Map<String, String> best = null;
            int score = -1;
            for (String key : variants.keySet()) {
                Map<String, String> kv = new LinkedHashMap<>();
                for (String x : key.split(",", -1)) {
                    if (x.isEmpty()) {
                        continue;
                    }
                    int e = x.indexOf('=');
                    kv.put(e < 0 ? x : x.substring(0, e), e < 0 ? "" : x.substring(e + 1));
                }
                int sc = 0;
                for (Map.Entry<String, String> e : kv.entrySet()) {
                    if (e.getValue().equals(PREF.get(e.getKey()))) {
                        sc++;
                    }
                }
                if (sc > score) {
                    best = kv;
                    score = sc;
                }
            }
            if (best != null) {
                chosen.putAll(best);
            }
        }
        for (Map.Entry<String, List<String>> e : props(name).entrySet()) {
            String k = e.getKey();
            List<String> vals = e.getValue();
            if (!chosen.containsKey(k)) {
                String pref = PREF.get(k);
                chosen.put(k, pref != null && vals.contains(pref) ? pref : vals.contains("false") ? "false" : vals.contains("none") ? "none" : vals.get(0));
            }
        }
        st = withProps(name, chosen);
        defaults.put(name, st);
        return st;
    }

    /** Fill missing properties with default values. */
    String complete(String state) {
        state = Schematic.normState(state == null || state.isEmpty() ? "minecraft:air" : state);
        String name = Chunk.baseName(state);
        if (Chunk.AIR_NAMES.contains(name)) {
            return "minecraft:air";
        }
        Map<String, String> given = Chunk.stateProps(state);
        Map<String, String> full = new LinkedHashMap<>(Chunk.stateProps(defaultState(name)));
        Map<String, List<String>> known = props(name);
        for (Map.Entry<String, String> e : given.entrySet()) {
            if (known.containsKey(e.getKey()) || known.isEmpty()) {
                full.put(e.getKey(), e.getValue());
            }
        }
        return withProps(name, full);
    }

    /** All placeable blocks (with item image), including properties. */
    List<Map<String, Object>> blocks() {
        List<Map<String, Object>> b = blocks;
        if (b != null) {
            return b;
        }
        JsonObject items = null;
        Path a = assets.get();
        if (a != null) {
            items = J.obj(J.obj(J.load(a.resolve("items.json"))), "index");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        Models m = Palette.models;
        if (m != null && m.available) {
            for (String name : new TreeSet<>(m.blockstates.keySet())) {
                if (items != null && !items.isEmpty() && !items.has(name)) {
                    continue;
                }
                out.add(J.map("id", name, "props", props(name), "default", defaultState(name)));
            }
        }
        blocks = out;
        return out;
    }

    // ------------------------------------------------------------------ files

    static boolean editable(String fileId) {
        return fileId.startsWith("hochgeladen/") && fileId.toLowerCase(java.util.Locale.ROOT).endsWith(".schem");
    }

    String newName(String base) {
        base = Routes.stripChars(base.replaceAll("[^A-Za-z0-9_. ()-]", "_"), " .");
        base = base.substring(0, Math.min(60, base.length()));
        if (base.isEmpty()) {
            base = "neu";
        }
        String n = base;
        int i = 2;
        while (Files.exists(pl.uploadDir.resolve(n + ".schem"))) {
            n = base + " (" + i + ")";
            i++;
        }
        return n;
    }

    String saveNew(String name, Vol ids, List<Map<String, Object>> bes, int[] origin) throws IOException {
        String n = newName(name);
        byte[] data = Schematic.write(ids.b, ids.w, ids.h, ids.l, n, bes == null ? List.of() : bes, null, origin);
        Files.write(pl.uploadDir.resolve(n + ".schem"), data);
        return "hochgeladen/" + n + ".schem";
    }

    static List<Map<String, Object>> besOut(Schematic s) {
        return Schematic.spongeEntities(s);
    }

    static String need(JsonObject req, String k) {
        if (req == null || !req.has(k)) {
            throw new KeyError(k);
        }
        return J.str(req, k);
    }

    /** Open a file or placement for editing; returns the (possibly new) file. */
    Map<String, Object> open(JsonObject req) throws IOException {
        if (J.bool(req, "pid")) {
            JsonObject p = pl.get(J.str(req, "pid"));
            String fileId = J.str(p, "file");
            int rot = Math.floorMod(J.i(p, "rot", 0), 360);
            boolean mirror = J.bool(p, "mirror");
            if (!editable(fileId) || rot != 0 || mirror) {
                Schematic s = pl.schem(fileId, rot, mirror);
                String fresh = saveNew(s.name + " bearbeitet", new Vol(s.blocks, s.w, s.h, s.l), besOut(s), null);
                JsonObject up = new JsonObject();
                up.add("id", p.get("id"));
                up.addProperty("rot", 0);
                up.addProperty("mirror", false);
                pl.update(up);
                setFile(J.str(p, "id"), fresh);
                fileId = fresh;
            }
            return info(fileId, J.map("pid", p.get("id")));
        }
        String fileId = need(req, "file");
        if (!editable(fileId)) {
            Schematic s = pl.schem(fileId, 0, false);
            fileId = saveNew(s.name + " bearbeitet", new Vol(s.blocks, s.w, s.h, s.l), besOut(s), null);
        }
        return info(fileId, Map.of());
    }

    private void setFile(String pid, String fileId) throws IOException {
        synchronized (plLock()) {
            JsonArray pls = pl.read();
            for (JsonElement e : pls) {
                JsonObject p = e.getAsJsonObject();
                if (pid.equals(J.str(p, "id", null))) {
                    p.addProperty("file", fileId);
                }
            }
            pl.write(pls);
        }
    }

    private Object plLock() {
        return pl.lock;
    }

    /** Rename a file; placements then point to the new name. */
    Map<String, Object> rename(JsonObject req) throws IOException {
        String fileId = need(req, "file");
        if (!editable(fileId)) {
            throw new IllegalArgumentException("nur eigene Schematics lassen sich umbenennen");
        }
        String last = fileId.substring(fileId.lastIndexOf('/') + 1);
        String old = last.contains(".") ? last.substring(0, last.lastIndexOf('.')) : last;
        String want = Routes.stripChars((J.bool(req, "name") ? J.str(req, "name") : "").replaceAll("[^A-Za-z0-9_. ()-]", "_"), " .");
        want = want.substring(0, Math.min(60, want.length()));
        if (want.isEmpty()) {
            throw new IllegalArgumentException("Name fehlt");
        }
        if (want.equals(old)) {
            return info(fileId, Map.of());
        }
        String n, newId;
        Session s;
        synchronized (lock) {
            s = session(fileId);
            n = !want.equalsIgnoreCase(old) ? newName(want) : want;
            newId = "hochgeladen/" + n + ".schem";
            Path newPath = pl.uploadDir.resolve(n + ".schem");
            s.name = n;
            Path src = pl.resolve(fileId);
            s.path = newPath;
            Files.move(src, newPath, StandardCopyOption.REPLACE_EXISTING);
            // Name in the file too, but only write the saved state (unsaved stays unsaved)
            Schematic disk = Schematic.load(Files.readAllBytes(newPath), n + ".schem");
            Path tmp = newPath.resolveSibling(newPath.getFileName() + ".tmp");
            Files.write(tmp, Schematic.write(disk.blocks, disk.w, disk.h, disk.l, n, besOut(disk), null, null));
            Files.move(tmp, newPath, StandardCopyOption.REPLACE_EXISTING);
            s.mtime = Placements.mtime(newPath);
            sessions.remove(fileId);
            sessions.put(newId, s);
        }
        synchronized (plLock()) {
            JsonArray pls = pl.read();
            for (JsonElement e : pls) {
                JsonObject p = e.getAsJsonObject();
                if (fileId.equals(J.str(p, "file", null))) {
                    p.addProperty("file", newId);
                    JsonElement nm = J.get(p, "name");
                    String pn = nm == null ? null : J.str(nm);
                    if (pn == null || pn.isEmpty() || pn.equals(old) || pn.equals(s.name) || pn.startsWith(old)) {
                        p.addProperty("name", n);
                    }
                }
            }
            pl.write(pls);
        }
        return info(newId, Map.of());
    }

    Map<String, Object> create(JsonObject req) throws IOException {
        List<JsonElement> size = J.bool(req, "size") ? J.list(req.get("size")) : null;
        int[] v = {16, 16, 16};
        if (size != null) {
            if (size.size() != 3) {
                throw new IllegalArgumentException("Groesse: [w, h, l]");
            }
            for (int i = 0; i < 3; i++) {
                v[i] = J.i(size.get(i));
            }
        }
        int w = Math.max(1, Math.min(MAX_EDGE, v[0])), h = Math.max(1, Math.min(MAX_EDGE, v[1])), l = Math.max(1, Math.min(MAX_EDGE, v[2]));
        String name = J.bool(req, "name") ? J.str(req, "name") : "neu";
        return info(saveNew(name, new Vol(new char[w * h * l], w, h, l), null, null), Map.of());
    }

    Map<String, Object> cut(JsonObject req) throws IOException {
        Recording.Dimension dim = rec.dimension(need(req, "srv"), need(req, "dim"));
        if (dim == null) {
            throw new LookupError("Dimension unbekannt");
        }
        String[] keys = {"x0", "y0", "z0", "x1", "y1", "z1"};
        int[] c = new int[6];
        for (int i = 0; i < 6; i++) {
            c[i] = J.i(req, keys[i]);
        }
        String name = newName(J.bool(req, "name") ? J.str(req, "name") : "ausschnitt");
        byte[] data = (byte[]) Schematic.export(rec, dim, c[0], c[1], c[2], c[3], c[4], c[5], name)[0];
        Schematic s = Schematic.load(data, name + ".schem");
        // Trim air at the top, otherwise the cutout reaches up to the build height
        int top = 0;
        for (int y = s.h - 1; y >= 0 && top == 0; y--) {
            for (int i = y * s.l * s.w; i < (y + 1) * s.l * s.w; i++) {
                if (s.blocks[i] != 0) {
                    top = y + 1;
                    break;
                }
            }
        }
        if (top == 0) {
            top = 1;
        }
        long vol = (long) top * s.l * s.w;
        if (vol > MAX_VOLUME || Math.max(top, Math.max(s.l, s.w)) > MAX_EDGE) {
            throw new IllegalArgumentException("Ausschnitt zu groß für den Editor (" + s.w + "×" + top + "×" + s.l + "), "
                    + "höchstens " + MAX_EDGE + " pro Kante: Y-Bereich kleiner wählen");
        }
        char[] b = Arrays.copyOf(s.blocks, (int) vol);
        List<Map<String, Object>> bes = new ArrayList<>();
        for (Map<String, Object> be : besOut(s)) {
            if (((int[]) be.get("Pos"))[1] < top) {
                bes.add(be);
            }
        }
        int[] origin = {Math.min(c[0], c[3]), Math.min(c[1], c[4]), Math.min(c[2], c[5])};
        String fileId = saveNew(name, new Vol(b, s.w, top, s.l), bes, origin);
        return info(fileId, J.map("origin", List.of(origin[0], origin[1], origin[2])));
    }

    Map<String, Object> info(String fileId, Map<String, Object> extra) throws IOException {
        Session s;
        synchronized (lock) {
            s = session(fileId);
        }
        String last = fileId.substring(fileId.lastIndexOf('/') + 1);
        Map<String, Object> out = J.map("file", fileId, "name", last.contains(".") ? last.substring(0, last.lastIndexOf('.')) : last,
                "size", List.of(s.blocks.w, s.blocks.h, s.blocks.l), "undo", s.undo.size(), "redo", s.redo.size(), "dirty", s.dirty);
        out.putAll(extra);
        return out;
    }

    Map<String, Object> info(String fileId) throws IOException {
        return info(fileId, Map.of());
    }

    private Session session(String fileId) throws IOException {
        Path p = pl.resolve(fileId);
        long mt = Placements.mtime(p);
        Session s = sessions.get(fileId);
        if (s == null || (s.mtime != mt && !s.dirty)) {
            Schematic sc = pl.schem(fileId, 0, false);
            s = new Session();
            s.blocks = new Vol(sc.blocks.clone(), sc.w, sc.h, sc.l);
            s.bes = besOut(sc);
            s.path = p;
            s.mtime = mt;
            s.name = sc.name;
            sessions.put(fileId, s);
        }
        return s;
    }

    Map<String, Object> voxels(String fileId) throws IOException {
        Vol b;
        synchronized (lock) {
            b = session(fileId).blocks;
        }
        TreeSet<Integer> used = new TreeSet<>();
        for (char c : b.b) {
            used.add((int) c);
        }
        Map<Integer, Integer> idx = new HashMap<>();
        List<String> palette = new ArrayList<>();
        for (int id : used) {
            idx.put(id, palette.size());
            palette.add(Chunk.BLOCKS.name(id));
        }
        ByteBuffer bb = ByteBuffer.allocate(b.b.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (char c : b.b) {
            bb.putShort((short) (int) idx.get((int) c));
        }
        return J.map("size", List.of(b.w, b.h, b.l), "palette", palette, "data", Base64.getEncoder().encodeToString(bb.array()));
    }

    /** Mesh from the edit state (including unsaved), not from the file. yhi: layer cut with closed top. */
    Object[] mesh(String fileId, Integer yhi) throws IOException {
        List<Object> key;
        Vol blocks;
        Session s;
        synchronized (lock) {
            s = session(fileId);
            if (yhi != null && yhi >= s.blocks.h - 1) {
                yhi = null;
            }
            key = List.of(s.path.toString(), s.mtime, s.rev, yhi == null ? "all" : yhi);
            if (key.equals(s.meshKey)) {
                return new Object[] {s.mesh, s.meshEtag};
            }
            blocks = s.blocks.copy();
        }
        byte[] data = App.gzip(Mesher.meshVolume(blocks.b, blocks.w, blocks.h, blocks.l, yhi), 5);
        String etag = "\"" + Palette.models.version + "-" + Mesher.MAGIC + "-ed-" + Integer.toUnsignedString(key.hashCode()) + "\"";
        synchronized (lock) {
            s.meshKey = key;
            s.mesh = data;
            s.meshEtag = etag;
        }
        return new Object[] {data, etag};
    }

    private final Map<String, byte[]> blockMeshes = new HashMap<>();

    /** Mesh of a single block state (preview at the mouse pointer); air gives an empty mesh. */
    byte[] blockMesh(String state) {
        String st = complete(state);
        String key = Palette.models.version + "|" + st;
        synchronized (blockMeshes) {
            byte[] m = blockMeshes.get(key);
            if (m != null) {
                return m;
            }
        }
        char sid = (char) (st.equals("minecraft:air") ? 0 : Chunk.BLOCKS.id(st));
        byte[] data = App.gzip(Mesher.meshVolume(new char[] {sid}, 1, 1, 1), 5);
        synchronized (blockMeshes) {
            if (blockMeshes.size() > 512) {
                blockMeshes.clear();
            }
            blockMeshes.put(key, data);
        }
        return data;
    }

    // ------------------------------------------------------------------ editing

    /** Grow the array so that [lo, hi] (x, y, z) fits. Returns the shift. */
    private int[] grow(Session s, int[] lo, int[] hi) {
        Vol b = s.blocks;
        int[] nlo = {Math.min(0, lo[0]), Math.min(0, lo[1]), Math.min(0, lo[2])};
        int[] nhi = {Math.max(b.w - 1, hi[0]), Math.max(b.h - 1, hi[1]), Math.max(b.l - 1, hi[2])};
        if (nlo[0] == 0 && nlo[1] == 0 && nlo[2] == 0 && nhi[0] == b.w - 1 && nhi[1] == b.h - 1 && nhi[2] == b.l - 1) {
            return new int[3];
        }
        int nw = nhi[0] - nlo[0] + 1, nh = nhi[1] - nlo[1] + 1, nl = nhi[2] - nlo[2] + 1;
        if (Math.max(nw, Math.max(nh, nl)) > MAX_EDGE || (long) nw * nh * nl > MAX_VOLUME) {
            throw new IllegalArgumentException("Schematic würde zu groß (" + nw + "×" + nh + "×" + nl + "), höchstens " + MAX_EDGE + " pro Kante");
        }
        int dx = -nlo[0], dy = -nlo[1], dz = -nlo[2];
        Vol nb = new Vol(new char[nw * nh * nl], nw, nh, nl);
        for (int y = 0; y < b.h; y++) {
            for (int z = 0; z < b.l; z++) {
                System.arraycopy(b.b, b.i(0, y, z), nb.b, nb.i(dx, y + dy, z + dz), b.w);
            }
        }
        s.blocks = nb;
        List<Map<String, Object>> moved = new ArrayList<>();
        for (Map<String, Object> be : s.bes) {
            Map<String, Object> e = new LinkedHashMap<>(be);
            int[] p = (int[]) be.get("Pos");
            e.put("Pos", new int[] {p[0] + dx, p[1] + dy, p[2] + dz});
            moved.add(e);
        }
        s.bes = moved;
        return new int[] {dx, dy, dz};
    }

    static List<Map<String, Object>> copyBes(List<Map<String, Object>> bes) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> be : bes) {
            out.add(new LinkedHashMap<>(be));
        }
        return out;
    }

    Map<String, Object> edit(JsonObject req) throws IOException {
        String fileId = need(req, "file");
        List<JsonElement> ops = J.bool(req, "ops") ? J.list(req.get("ops")) : List.of();
        int[] shift = new int[3];
        int changed = 0;
        synchronized (lock) {
            Session s = session(fileId);
            Vol beforeB = s.blocks.copy();
            List<Map<String, Object>> beforeBes = copyBes(s.bes);
            try {
                for (JsonElement oe : ops) {
                    JsonObject op = J.obj(oe);
                    if (op == null) {
                        throw new IllegalArgumentException("Bearbeitung ist kein Objekt");
                    }
                    Object[] r = op(s, op, shift);
                    int[] d = (int[]) r[0];
                    changed += (Integer) r[1];
                    for (int i = 0; i < 3; i++) {
                        shift[i] += d[i];
                    }
                }
            } catch (RuntimeException e) {
                s.blocks = beforeB;
                s.bes = beforeBes;
                throw e;
            }
            if (changed != 0 || shift[0] != 0 || shift[1] != 0 || shift[2] != 0) {
                s.undo.add(new Step(beforeB, beforeBes, shift.clone()));
                while (s.undo.size() > UNDO) {
                    s.undo.remove(0);
                }
                s.redo.clear();
                touch(s, shift);
            }
        }
        Map<String, Object> out = info(fileId);
        out.put("shift", List.of(shift[0], shift[1], shift[2]));
        out.put("changed", changed);
        return out;
    }

    static int[] ints(JsonElement e, int n) {
        List<JsonElement> l = J.list(e);
        if (l.size() < n) {
            throw new IllegalArgumentException("zu wenige Koordinaten");
        }
        int[] out = new int[n];
        for (int i = 0; i < n; i++) {
            out[i] = J.i(l.get(i));
        }
        return out;
    }

    static JsonElement needEl(JsonObject o, String k) {
        if (!o.has(k)) {
            throw new KeyError(k);
        }
        return o.get(k);
    }

    /** Copy/move an area, rotating/mirroring it. "to" is the new minimum corner. */
    private Object[] cloneOp(Session s, JsonObject op, int[] shift) {
        int[] bx = ints(needEl(op, "box"), 6);
        Vol b = s.blocks;
        int[] lo = new int[3], hi = new int[3], max = {b.w - 1, b.h - 1, b.l - 1};
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.max(0, Math.min(bx[i], bx[i + 3]) + shift[i]);
            hi[i] = Math.min(max[i], Math.max(bx[i], bx[i + 3]) + shift[i]);
            if (hi[i] < lo[i]) {
                return new Object[] {new int[3], 0};
            }
        }
        int sw = hi[0] - lo[0] + 1, sh = hi[1] - lo[1] + 1, sl = hi[2] - lo[2] + 1;
        char[] sub = new char[sw * sh * sl];
        for (int y = 0; y < sh; y++) {
            for (int z = 0; z < sl; z++) {
                System.arraycopy(b.b, b.i(lo[0], lo[1] + y, lo[2] + z), sub, (y * sl + z) * sw, sw);
            }
        }
        Schematic t = Schematic.transform(new Schematic("ausschnitt", sw, sh, sl, sub), J.bool(op, "rot") ? J.i(op, "rot") : 0, J.bool(op, "mirror"));
        if (J.bool(op, "move")) {
            for (int y = 0; y < sh; y++) {
                for (int z = 0; z < sl; z++) {
                    Arrays.fill(b.b, b.i(lo[0], lo[1] + y, lo[2] + z), b.i(lo[0], lo[1] + y, lo[2] + z) + sw, (char) 0);
                }
            }
        }
        int[] to = ints(needEl(op, "to"), 3);
        for (int i = 0; i < 3; i++) {
            to[i] += shift[i];
        }
        int[] d = grow(s, to, new int[] {to[0] + t.w - 1, to[1] + t.h - 1, to[2] + t.l - 1});
        for (int i = 0; i < 3; i++) {
            to[i] += d[i];
        }
        b = s.blocks;
        boolean air = J.bool(op, "air");
        Set<List<Integer>> pts = new HashSet<>();
        int n = 0;
        for (int y = 0; y < t.h; y++) {
            for (int z = 0; z < t.l; z++) {
                for (int x = 0; x < t.w; x++) {
                    char v = t.blocks[(y * t.l + z) * t.w + x];
                    if (air || v != 0) {
                        b.b[b.i(to[0] + x, to[1] + y, to[2] + z)] = v;
                        pts.add(List.of(to[0] + x, to[1] + y, to[2] + z));
                        n++;
                    }
                }
            }
        }
        dropBes(s, pts);
        return new Object[] {d, n};
    }

    private Object[] op(Session s, JsonObject op, int[] shift) {
        String kind = J.str(op, "op", "None");
        if (kind.equals("clone")) {
            return cloneOp(s, op, shift);
        }
        String state = complete(J.bool(op, "block") ? J.str(op, "block") : "minecraft:air");
        int sid = Chunk.BLOCKS.id(state);
        if (kind.equals("set")) {
            List<int[]> pts = new ArrayList<>();
            for (JsonElement p : J.bool(op, "pts") ? J.list(op.get("pts")) : List.<JsonElement>of()) {
                int[] q = ints(p, 3);
                pts.add(new int[] {q[0] + shift[0], q[1] + shift[1], q[2] + shift[2]});
            }
            if (pts.isEmpty()) {
                return new Object[] {new int[3], 0};
            }
            int[] d = new int[3];
            if (sid != 0) {
                int[] lo = pts.get(0).clone(), hi = pts.get(0).clone();
                for (int[] p : pts) {
                    for (int i = 0; i < 3; i++) {
                        lo[i] = Math.min(lo[i], p[i]);
                        hi[i] = Math.max(hi[i], p[i]);
                    }
                }
                d = grow(s, lo, hi);
            }
            Vol b = s.blocks;
            Set<List<Integer>> done = new HashSet<>();
            int n = 0;
            for (int[] p : pts) {
                int x = p[0] + d[0], y = p[1] + d[1], z = p[2] + d[2];
                if (x >= 0 && x < b.w && y >= 0 && y < b.h && z >= 0 && z < b.l) {
                    b.b[b.i(x, y, z)] = (char) sid;
                    done.add(List.of(x, y, z));
                    n++;
                }
            }
            dropBes(s, done);
            return new Object[] {d, n};
        }
        if (Set.of("fill", "replace", "walls", "hollow").contains(kind)) {
            int[] bx = ints(needEl(op, "box"), 6);
            int[] lo = new int[3], hi = new int[3];
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.min(bx[i], bx[i + 3]) + shift[i];
                hi[i] = Math.max(bx[i], bx[i + 3]) + shift[i];
            }
            int[] d = sid != 0 && !kind.equals("replace") ? grow(s, lo, hi) : new int[3];
            Vol b = s.blocks;
            int[] max = {b.w - 1, b.h - 1, b.l - 1};
            for (int i = 0; i < 3; i++) {
                lo[i] = Math.max(0, lo[i] + d[i]);
                hi[i] = Math.min(max[i], hi[i] + d[i]);
                if (hi[i] < lo[i]) {
                    return new Object[] {d, 0};
                }
            }
            int vw = hi[0] - lo[0] + 1, vh = hi[1] - lo[1] + 1, vl = hi[2] - lo[2] + 1;
            boolean[] mask = new boolean[vw * vh * vl];
            if (kind.equals("fill")) {
                Arrays.fill(mask, true);
            } else if (kind.equals("replace")) {
                String frm = J.bool(op, "from") ? J.str(op, "from") : "";
                if (frm.isEmpty() || frm.equals("*")) {
                    forView(b, lo, vw, vh, vl, (k, i) -> mask[k] = b.b[i] != 0);
                } else if (frm.contains("[")) {
                    int want = Chunk.BLOCKS.id(complete(frm));
                    forView(b, lo, vw, vh, vl, (k, i) -> mask[k] = b.b[i] == want);
                } else {
                    String f = Schematic.normState(frm);
                    boolean airName = Chunk.AIR_NAMES.contains(f);
                    Map<Integer, Boolean> hit = new HashMap<>();
                    forView(b, lo, vw, vh, vl, (k, i) -> {
                        int id = b.b[i];
                        mask[k] = hit.computeIfAbsent(id, x -> Chunk.baseName(Chunk.BLOCKS.name(x)).equals(f) || (airName && x == 0));
                    });
                }
            } else {
                for (int y = 0; y < vh; y++) {
                    for (int z = 0; z < vl; z++) {
                        for (int x = 0; x < vw; x++) {
                            boolean edge = x == 0 || x == vw - 1 || z == 0 || z == vl - 1;
                            if (kind.equals("hollow")) {
                                edge |= y == 0 || y == vh - 1;
                                if (x > 0 && x < vw - 1 && y > 0 && y < vh - 1 && z > 0 && z < vl - 1) {
                                    b.b[b.i(lo[0] + x, lo[1] + y, lo[2] + z)] = 0;
                                }
                            }
                            mask[(y * vl + z) * vw + x] = edge;
                        }
                    }
                }
            }
            int n = 0;
            for (boolean m : mask) {
                n += m ? 1 : 0;
            }
            if (kind.equals("replace") && J.bool(op, "keep") && sid != 0) {
                // Keep orientation: stairs stay stairs facing the same direction
                String tgt = Chunk.baseName(Chunk.BLOCKS.name(sid));
                Map<Integer, Integer> to = new HashMap<>();
                forView(b, lo, vw, vh, vl, (k, i) -> {
                    if (mask[k]) {
                        int orig = b.b[i];
                        int nid = to.computeIfAbsent(orig, o -> Chunk.BLOCKS.id(complete(withProps(tgt, Chunk.stateProps(Chunk.BLOCKS.name(o))))));
                        b.b[i] = (char) nid;
                    }
                });
            } else {
                forView(b, lo, vw, vh, vl, (k, i) -> {
                    if (mask[k]) {
                        b.b[i] = (char) sid;
                    }
                });
            }
            Set<List<Integer>> pts = new HashSet<>();
            for (int y = 0; y < vh; y++) {
                for (int z = 0; z < vl; z++) {
                    for (int x = 0; x < vw; x++) {
                        if (mask[(y * vl + z) * vw + x]) {
                            pts.add(List.of(x + lo[0], y + lo[1], z + lo[2]));
                        }
                    }
                }
            }
            dropBes(s, pts);
            return new Object[] {d, n};
        }
        throw new IllegalArgumentException("unbekannte Bearbeitung '" + kind + "'");
    }

    interface Cell {
        void at(int k, int i);
    }

    /** Calls fn(index in view, index in volume) for every cell of the view box. */
    static void forView(Vol b, int[] lo, int vw, int vh, int vl, Cell fn) {
        for (int y = 0; y < vh; y++) {
            for (int z = 0; z < vl; z++) {
                for (int x = 0; x < vw; x++) {
                    fn.at((y * vl + z) * vw + x, b.i(lo[0] + x, lo[1] + y, lo[2] + z));
                }
            }
        }
    }

    private static void dropBes(Session s, Set<List<Integer>> pts) {
        if (pts.isEmpty() || s.bes.isEmpty()) {
            return;
        }
        List<Map<String, Object>> keep = new ArrayList<>();
        for (Map<String, Object> be : s.bes) {
            int[] p = (int[]) be.get("Pos");
            if (!pts.contains(List.of(p[0], p[1], p[2]))) {
                keep.add(be);
            }
        }
        s.bes = keep;
    }

    private void saveFile(String fileId, Session s, int[] shift) throws IOException {
        byte[] data = Schematic.write(s.blocks.b, s.blocks.w, s.blocks.h, s.blocks.l, s.name, s.bes, null, null);
        Path tmp = s.path.resolveSibling(s.path.getFileName() + ".tmp");
        Files.write(tmp, data);
        Files.move(tmp, s.path, StandardCopyOption.REPLACE_EXISTING);
        s.mtime = Placements.mtime(s.path);
        if (shift[0] != 0 || shift[1] != 0 || shift[2] != 0) {
            // Shift this file's placements so the blocks stay put in the world
            synchronized (plLock()) {
                JsonArray pls = pl.read();
                for (JsonElement e : pls) {
                    JsonObject p = e.getAsJsonObject();
                    if (fileId.equals(J.str(p, "file", null)) && !J.bool(p, "rot") && !J.bool(p, "mirror")) {
                        p.addProperty("x", J.i(p, "x") - shift[0]);
                        p.addProperty("y", J.i(p, "y") - shift[1]);
                        p.addProperty("z", J.i(p, "z") - shift[2]);
                    }
                }
                pl.write(pls);
            }
        }
    }

    /** Keep the edit in memory only; save writes the file. */
    private static void touch(Session s, int[] shift) {
        s.dirty = true;
        s.rev++;
        for (int i = 0; i < 3; i++) {
            s.pshift[i] += shift[i];
        }
    }

    Map<String, Object> save(JsonObject req) throws IOException {
        String fileId = need(req, "file");
        synchronized (lock) {
            Session s = session(fileId);
            if (s.dirty) {
                saveFile(fileId, s, s.pshift);
                s.dirty = false;
                s.pshift = new int[3];
            }
        }
        Map<String, Object> out = info(fileId);
        out.put("saved", true);
        return out;
    }

    Map<String, Object> discard(JsonObject req) throws IOException {
        String fileId = need(req, "file");
        synchronized (lock) {
            sessions.remove(fileId);
        }
        Map<String, Object> out = info(fileId);
        out.put("shift", List.of(0, 0, 0));
        return out;
    }

    Map<String, Object> undo(JsonObject req, boolean redo) throws IOException {
        String fileId = need(req, "file");
        int[] move;
        synchronized (lock) {
            Session s = session(fileId);
            List<Step> src = redo ? s.redo : s.undo, dst = redo ? s.undo : s.redo;
            if (src.isEmpty()) {
                Map<String, Object> out = info(fileId);
                out.put("shift", List.of(0, 0, 0));
                out.put("changed", 0);
                return out;
            }
            Step st = src.remove(src.size() - 1);
            dst.add(new Step(s.blocks.copy(), copyBes(s.bes), st.shift()));
            s.blocks = st.blocks();
            s.bes = st.bes();
            // Undo or redo the shift from growing as well
            int[] sh = st.shift();
            move = redo ? sh.clone() : new int[] {-sh[0], -sh[1], -sh[2]};
            touch(s, move);
        }
        Map<String, Object> out = info(fileId);
        out.put("shift", List.of(move[0], move[1], move[2]));
        out.put("changed", 1);
        return out;
    }
}
