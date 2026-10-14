package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.ehnkode.webmatica.viewer.core.Models;
import io.github.ehnkode.webmatica.viewer.core.Png;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Builds the viewer's block and item images from the game jar (they belong to Mojang and are
 * not shipped): blocks.png/json (atlas, blockstates, resolved models) and items.png/json
 * (inventory icons). Port of tools/build_assets.py and tools/build_items.py.
 */
public final class AssetBuilder {
    static final int TILE = 16, SIZE = 32, ITEM_COLS = 48;
    static final List<String> NEEDED = List.of("blocks.json", "blocks.png", "items.json", "items.png");
    static final String PREFIX = "assets/minecraft/";

    private AssetBuilder() {
    }

    public static boolean ready(Path dir) {
        for (String n : NEEDED) {
            if (!Files.isRegularFile(dir.resolve(n))) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasAssets(Path jar) {
        try (ZipFile z = new ZipFile(jar.toFile())) {
            return z.stream().anyMatch(e -> e.getName().startsWith(PREFIX + "blockstates/"));
        } catch (IOException e) {
            return false;
        }
    }

    /** Builds all four files into out (written as .tmp first, so a crash leaves nothing half-done). */
    public static void build(Path jar, Path out, Consumer<String> log) throws IOException {
        Files.createDirectories(out);
        try (Jar src = new Jar(jar)) {
            JsonObject meta = buildBlocks(src, out, log);
            buildItems(src, out, meta, log);
        }
    }

    // ------------------------------------------------------------------ images

    /** Simple RGBA image: argb ints, row-major. */
    static final class Img {
        final int w, h;
        final int[] px;

        Img(int w, int h) {
            this(w, h, new int[w * h]);
        }

        Img(int w, int h, int[] px) {
            this.w = w;
            this.h = h;
            this.px = px;
        }

        static Img of(BufferedImage b) {
            return new Img(b.getWidth(), b.getHeight(), b.getRGB(0, 0, b.getWidth(), b.getHeight(), null, 0, b.getWidth()));
        }

        int get(int x, int y) {
            return x < 0 || y < 0 || x >= w || y >= h ? 0 : px[y * w + x];
        }

        void set(int x, int y, int v) {
            px[y * w + x] = v;
        }

        static int alpha(int v) {
            return v >>> 24;
        }

        /** PIL crop: outside the image is transparent. */
        Img crop(int x0, int y0, int x1, int y1) {
            Img o = new Img(x1 - x0, y1 - y0);
            for (int y = 0; y < o.h; y++) {
                for (int x = 0; x < o.w; x++) {
                    o.set(x, y, get(x0 + x, y0 + y));
                }
            }
            return o;
        }

        /** [::-1] (rows reversed) */
        Img flipV() {
            Img o = new Img(w, h);
            for (int y = 0; y < h; y++) {
                System.arraycopy(px, (h - 1 - y) * w, o.px, y * w, w);
            }
            return o;
        }

        /** [:, ::-1] (columns reversed) */
        Img flipH() {
            Img o = new Img(w, h);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    o.set(x, y, px[y * w + w - 1 - x]);
                }
            }
            return o;
        }

        Img cols(int c0, int c1) {
            return crop(c0, 0, c1, h);
        }

        static Img vstack(Img a, Img b) {
            Img o = new Img(a.w, a.h + b.h);
            System.arraycopy(a.px, 0, o.px, 0, a.px.length);
            System.arraycopy(b.px, 0, o.px, a.px.length, b.px.length);
            return o;
        }

        /** PIL NEAREST resize. */
        Img resize(int nw, int nh) {
            if (nw == w && nh == h) {
                return this;
            }
            Img o = new Img(nw, nh);
            for (int y = 0; y < nh; y++) {
                int sy = Math.min(h - 1, (int) ((y + 0.5) * h / nh));
                for (int x = 0; x < nw; x++) {
                    int sx = Math.min(w - 1, (int) ((x + 0.5) * w / nw));
                    o.set(x, y, px[sy * w + sx]);
                }
            }
            return o;
        }

        void paste(Img src, int ox, int oy) {
            for (int y = 0; y < src.h; y++) {
                for (int x = 0; x < src.w; x++) {
                    int tx = ox + x, ty = oy + y;
                    if (tx >= 0 && ty >= 0 && tx < w && ty < h) {
                        set(tx, ty, src.px[y * src.w + x]);
                    }
                }
            }
        }

        /** PIL alpha_composite of src over this (same size). */
        void over(Img src) {
            for (int i = 0; i < px.length; i++) {
                int s = src.px[i], d = px[i];
                double sa = alpha(s) / 255.0, da = alpha(d) / 255.0;
                double oa = sa + da * (1 - sa);
                if (oa <= 0) {
                    px[i] = 0;
                    continue;
                }
                int r = 0;
                for (int sh = 0; sh <= 16; sh += 8) {
                    double c = (((s >> sh) & 255) * sa + ((d >> sh) & 255) * da * (1 - sa)) / oa;
                    r |= ((int) Math.round(c) & 255) << sh;
                }
                px[i] = r | ((int) Math.round(oa * 255) << 24);
            }
        }

        byte[] png() {
            byte[] rgba = new byte[w * h * 4];
            for (int i = 0; i < px.length; i++) {
                int v = px[i];
                rgba[4 * i] = (byte) (v >> 16);
                rgba[4 * i + 1] = (byte) (v >> 8);
                rgba[4 * i + 2] = (byte) v;
                rgba[4 * i + 3] = (byte) (v >>> 24);
            }
            return Png.encode(w, h, rgba, 6);
        }
    }

    /** The part with alpha > 0 of each piece onto a 16x16 tile. */
    static Img tile(Object... parts) {
        Img t = new Img(16, 16);
        for (int i = 0; i < parts.length; i += 3) {
            Img a = (Img) parts[i];
            int c = (Integer) parts[i + 1], r = (Integer) parts[i + 2];
            for (int y = 0; y < a.h && r + y < 16; y++) {
                for (int x = 0; x < a.w && c + x < 16; x++) {
                    int v = a.get(x, y);
                    if (Img.alpha(v) > 0) {
                        t.set(c + x, r + y, v);
                    }
                }
            }
        }
        return t;
    }

    // ------------------------------------------------------------------ jar

    static final class Jar implements AutoCloseable {
        final ZipFile zip;
        final TreeSet<String> names = new TreeSet<>();

        Jar(Path path) throws IOException {
            zip = new ZipFile(path.toFile());
            zip.stream().forEach(e -> names.add(e.getName()));
        }

        byte[] read(String rel) {
            ZipEntry e = zip.getEntry(PREFIX + rel);
            if (e == null) {
                return null;
            }
            try {
                return zip.getInputStream(e).readAllBytes();
            } catch (IOException ex) {
                return null;
            }
        }

        JsonElement json(String rel) {
            byte[] raw = read(rel);
            if (raw == null || raw.length == 0) {
                return null;
            }
            try {
                return JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                return null;
            }
        }

        JsonObject obj(String rel) {
            JsonElement e = json(rel);
            return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
        }

        /** textures/<name>.png as RGBA, or null. */
        Img texture(String name) {
            byte[] raw = read("textures/" + normId(name) + ".png");
            if (raw == null) {
                return null;
            }
            BufferedImage b = Png.read(raw);
            return b == null ? null : Img.of(b);
        }

        List<String> list(String dir, String ext) {
            List<String> out = new ArrayList<>();
            String p = PREFIX + dir;
            for (String n : names.subSet(p, p + Character.MAX_VALUE)) {
                if (n.endsWith(ext) && n.indexOf('/', p.length()) < 0) {
                    out.add(n.substring(p.length(), n.length() - ext.length()));
                }
            }
            return out;
        }

        @Override
        public void close() throws IOException {
            zip.close();
        }
    }

    static String normId(String ref) {
        int i = ref.indexOf(':');
        return i < 0 ? ref : ref.substring(i + 1);
    }

    /** A texture reference as a string; newer versions also allow {"sprite": ...}. */
    static String sprite(JsonElement ref) {
        if (ref == null || ref.isJsonNull()) {
            return null;
        }
        if (ref.isJsonObject()) {
            JsonElement s = ref.getAsJsonObject().get("sprite");
            return s == null || !s.isJsonPrimitive() ? null : s.getAsString();
        }
        return ref.isJsonPrimitive() ? ref.getAsString() : null;
    }

    // ------------------------------------------------------------------ block models

    static final class Resolver {
        final Jar src;
        final Map<String, Img> synthetic = new HashMap<>();
        final Map<String, JsonObject> raw = new HashMap<>();
        final Map<String, Integer> textures = new HashMap<>();
        final List<String> texList = new ArrayList<>();

        Resolver(Jar src) {
            this.src = src;
        }

        JsonObject load(String mid) {
            mid = normId(mid);
            if (!raw.containsKey(mid)) {
                raw.put(mid, src.obj("models/" + mid + ".json"));
            }
            return raw.get(mid);
        }

        int texIndex(String name) {
            name = normId(name);
            Integer i = textures.get(name);
            if (i == null) {
                i = texList.size();
                textures.put(name, i);
                texList.add(name);
            }
            return i;
        }

        int synth(String key, Img im) {
            synthetic.put(key, im);
            return texIndex(key);
        }

        JsonObject resolve(String mid) {
            List<JsonObject> chain = new ArrayList<>();
            String cur = normId(mid);
            while (cur != null && chain.size() < 16) {
                JsonObject m = load(cur);
                if (m == null) {
                    break;
                }
                chain.add(m);
                JsonElement pe = m.get("parent");
                String parent = pe != null && pe.isJsonPrimitive() ? pe.getAsString() : null;
                cur = parent != null && !parent.isEmpty() && !parent.startsWith("builtin/") ? normId(parent) : null;
            }
            Map<String, String> tex = new HashMap<>();
            JsonArray elements = null;
            JsonElement ao = new com.google.gson.JsonPrimitive(true);
            for (int i = chain.size() - 1; i >= 0; i--) {
                JsonObject m = chain.get(i);
                JsonElement t = m.get("textures");
                if (t != null && t.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : t.getAsJsonObject().entrySet()) {
                        tex.put(e.getKey(), sprite(e.getValue()));
                    }
                }
                if (m.has("ambientocclusion")) {
                    ao = m.get("ambientocclusion");
                }
            }
            for (JsonObject m : chain) {
                if (m.has("elements")) {
                    JsonElement e = m.get("elements");
                    elements = e.isJsonArray() ? e.getAsJsonArray() : null;
                    break;
                }
            }
            JsonArray outEl = new JsonArray();
            if (elements != null) {
                for (JsonElement ee : elements) {
                    JsonObject e = ee.getAsJsonObject();
                    JsonObject faces = new JsonObject();
                    JsonElement fs = e.get("faces");
                    if (fs != null && fs.isJsonObject()) {
                        for (Map.Entry<String, JsonElement> fe : fs.getAsJsonObject().entrySet()) {
                            JsonObject f = fe.getValue().getAsJsonObject();
                            Integer t = ref(tex, f.has("texture") ? sprite(f.get("texture")) : "");
                            if (t == null) {
                                continue;
                            }
                            JsonObject face = new JsonObject();
                            face.addProperty("t", t);
                            for (String k : new String[] {"uv", "cullface", "rotation", "tintindex"}) {
                                if (f.has(k)) {
                                    face.add(k, f.get(k));
                                }
                            }
                            faces.add(fe.getKey(), face);
                        }
                    }
                    if (faces.isEmpty()) {
                        continue;
                    }
                    JsonObject el = new JsonObject();
                    el.add("from", e.get("from"));
                    el.add("to", e.get("to"));
                    el.add("faces", faces);
                    if (e.has("rotation")) {
                        el.add("rotation", e.get("rotation"));
                    }
                    JsonElement sh = e.get("shade");
                    if (sh != null && sh.isJsonPrimitive() && sh.getAsJsonPrimitive().isBoolean() && !sh.getAsBoolean()) {
                        el.addProperty("shade", false);
                    }
                    outEl.add(el);
                }
            }
            JsonObject out = new JsonObject();
            out.add("elements", outEl);
            String p = tex.get("particle");
            Integer particle = p != null && !p.isEmpty() ? ref(tex, p) : null;
            out.add("particle", particle == null ? com.google.gson.JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(particle));
            out.add("ao", ao);
            return out;
        }

        private Integer ref(Map<String, String> tex, String r) {
            int seen = 0;
            while (r != null && r.startsWith("#") && seen < 10) {
                r = tex.get(r.substring(1));
                seen++;
            }
            if (r == null || r.isEmpty() || r.startsWith("#")) {
                return null;
            }
            return texIndex(r);
        }
    }

    static JsonObject buildBlocks(Jar src, Path out, Consumer<String> log) throws IOException {
        Resolver res = new Resolver(src);
        JsonObject blockstates = new JsonObject();
        Map<String, JsonObject> models = new LinkedHashMap<>();
        for (String name : src.list("blockstates/", ".json")) {
            JsonObject bs = src.obj("blockstates/" + name + ".json");
            if (bs == null || bs.isEmpty()) {
                continue;
            }
            blockstates.add("minecraft:" + name, bs);
            List<JsonElement> vs = new ArrayList<>();
            JsonElement variants = bs.get("variants");
            if (variants != null && variants.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : variants.getAsJsonObject().entrySet()) {
                    vs.add(e.getValue());
                }
            }
            JsonElement mp = bs.get("multipart");
            if (mp != null && mp.isJsonArray()) {
                for (JsonElement p : mp.getAsJsonArray()) {
                    vs.add(p.isJsonObject() ? p.getAsJsonObject().get("apply") : null);
                }
            }
            for (JsonElement v : vs) {
                List<JsonElement> entries = new ArrayList<>();
                if (v != null && v.isJsonArray()) {
                    v.getAsJsonArray().forEach(entries::add);
                } else {
                    entries.add(v);
                }
                for (JsonElement en : entries) {
                    if (en != null && en.isJsonObject() && en.getAsJsonObject().has("model")) {
                        String mid = normId(en.getAsJsonObject().get("model").getAsString());
                        if (!models.containsKey(mid)) {
                            models.put(mid, res.resolve(mid));
                        }
                    }
                }
            }
        }
        int made = EntityBlocks.add(src, res, blockstates, models);
        log.accept(made + " Block-Entities mit eigenem Modell (Truhen, Betten, Shulkerkisten)");
        res.texIndex("block/water_still");
        res.texIndex("block/lava_still");

        int n = res.texList.size();
        int cols = (int) Math.ceil(Math.sqrt(n));
        int rows = (int) Math.ceil(n / (double) cols);
        Img atlas = new Img(cols * TILE, rows * TILE);
        JsonArray kinds = new JsonArray(), colors = new JsonArray(), texNames = new JsonArray();
        for (int i = 0; i < n; i++) {
            String name = res.texList.get(i);
            texNames.add(name);
            Img img = res.synthetic.get(name);
            if (img == null) {
                img = src.texture(name);
            }
            if (img == null) {
                img = new Img(TILE, TILE);
                java.util.Arrays.fill(img.px, 0xFFFF00FF);
            }
            img = img.crop(0, 0, img.w, img.w).resize(TILE, TILE);     // first animation frame
            int amin = 255;
            boolean binary = true;
            double ws = 0, r = 0, g = 0, b = 0;
            for (int v : img.px) {
                int a = Img.alpha(v);
                amin = Math.min(amin, a);
                binary &= a == 0 || a == 255;
                float w8 = a / 255.0f;
                ws += w8;
                r += ((v >> 16) & 255) * w8;
                g += ((v >> 8) & 255) * w8;
                b += (v & 255) * w8;
            }
            kinds.add(amin == 255 ? 0 : binary ? 1 : 2);
            double div = Math.max(ws, 1e-6);
            JsonArray c = new JsonArray();
            c.add((long) Math.rint(r / div));
            c.add((long) Math.rint(g / div));
            c.add((long) Math.rint(b / div));
            c.add(Math.rint(ws / img.px.length * 1000) / 1000.0);
            colors.add(c);
            atlas.paste(img, (i % cols) * TILE, (i / cols) * TILE);
        }
        JsonObject ms = new JsonObject();
        models.forEach(ms::add);
        JsonObject meta = new JsonObject();
        meta.addProperty("version", 1);
        meta.addProperty("tile", TILE);
        meta.addProperty("cols", cols);
        meta.addProperty("rows", rows);
        meta.add("textures", texNames);
        meta.add("kinds", kinds);
        meta.add("colors", colors);
        meta.add("blockstates", blockstates);
        meta.add("models", ms);
        Png.writeAtomic(out.resolve("blocks.png"), atlas.png());
        Png.writeAtomic(out.resolve("blocks.json"), J.GSON.toJson(meta).getBytes(StandardCharsets.UTF_8));
        log.accept(blockstates.size() + " Blockstates, " + models.size() + " Modelle, " + n + " Texturen");
        return meta;
    }

    // ------------------------------------------------------------------ block entities

    /**
     * Chests, beds and shulker boxes have no block model in the game; they are drawn as
     * entities. Simple block models are built from the entity textures instead.
     */
    static final class EntityBlocks {
        static final Map<String, Integer> FACING_Y = new LinkedHashMap<>();
        static final String[] COLORS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray",
                "cyan", "purple", "blue", "brown", "green", "red", "black"};
        static final Map<String, Map<String, Integer>> SHULKER_ROT = new LinkedHashMap<>();

        static {
            FACING_Y.put("north", 0);
            FACING_Y.put("east", 90);
            FACING_Y.put("south", 180);
            FACING_Y.put("west", 270);
            SHULKER_ROT.put("up", Map.of());
            SHULKER_ROT.put("down", Map.of("x", 180));
            SHULKER_ROT.put("north", Map.of("x", 90));
            SHULKER_ROT.put("south", orderedXY(90, 180));
            SHULKER_ROT.put("east", orderedXY(90, 90));
            SHULKER_ROT.put("west", orderedXY(90, 270));
        }

        private static Map<String, Integer> orderedXY(int x, int y) {
            Map<String, Integer> m = new LinkedHashMap<>();
            m.put("x", x);
            m.put("y", y);
            return m;
        }

        static JsonArray vec(int... v) {
            JsonArray a = new JsonArray();
            for (int x : v) {
                a.add(x);
            }
            return a;
        }

        static JsonObject box(int[] fr, int[] to, Map<String, Integer> faces, boolean cull) {
            JsonObject el = new JsonObject();
            el.add("from", vec(fr));
            el.add("to", vec(to));
            JsonObject fs = new JsonObject();
            for (Map.Entry<String, Integer> e : faces.entrySet()) {
                JsonObject f = new JsonObject();
                f.addProperty("t", e.getValue());
                if (cull) {
                    f.addProperty("cullface", e.getKey());
                }
                fs.add(e.getKey(), f);
            }
            el.add("faces", fs);
            return el;
        }

        static JsonObject model(List<JsonObject> els, int particle) {
            JsonObject m = new JsonObject();
            JsonArray a = new JsonArray();
            els.forEach(a::add);
            m.add("elements", a);
            m.addProperty("particle", particle);
            m.addProperty("ao", true);
            return m;
        }

        /** kind: single | left | right; the model for facing=north. */
        static JsonObject chest(Resolver res, Img img, String name, String kind) {
            String key = "synthetic/" + name + "_" + kind + "_";
            Img lock = img.crop(1, 1, 3, 5).flipV().flipH();       // 2x4, rotated like the front
            int x0, x1, tw;
            Img top, bottom;
            Map<String, Integer> strips = new HashMap<>(), widths = new HashMap<>();
            if (kind.equals("single")) {
                x0 = 1;
                x1 = 15;
                top = img.crop(28, 0, 42, 14);
                bottom = img.crop(14, 19, 28, 33);
                strips.putAll(Map.of("front", 42, "back", 14, "side_a", 0, "side_b", 28));
                widths.putAll(Map.of("front", 14, "back", 14, "side_a", 14, "side_b", 14));
            } else {
                x0 = kind.equals("left") ? 1 : 0;
                x1 = kind.equals("left") ? 16 : 15;
                top = img.crop(29, 0, 44, 14);
                bottom = img.crop(14, 19, 29, 33);
                strips.putAll(kind.equals("left") ? Map.of("front", 43, "back", 14, "side", 29) : Map.of("front", 44, "back", 14, "side", 0));
                widths.putAll(Map.of("front", 15, "back", 15, "side", 14));
            }
            Map<String, Integer> t = new LinkedHashMap<>();
            t.put("up", res.synth(key + "top", tile(top.flipH(), x0, 1)));
            t.put("down", res.synth(key + "bottom", tile(bottom, x0, 1)));
            Img front = side(img, strips.get("front"), widths.get("front"));
            int fc = 16 - x1;                                         // north side: u = 16 - x
            Img lockPart = kind.equals("single") ? lock : kind.equals("left") ? lock.cols(1, lock.w) : lock.cols(0, 1);
            int lockX = kind.equals("single") ? 7 : kind.equals("left") ? 0 : 15;
            t.put("north", res.synth(key + "front", tile(front, fc, 2, lockPart, lockX, 5)));
            t.put("south", res.synth(key + "back", tile(side(img, strips.get("back"), widths.get("back")), x0, 2)));
            if (kind.equals("single")) {
                int s = res.synth(key + "side", tile(side(img, strips.get("side_a"), 14), 1, 2));
                t.put("west", s);
                t.put("east", s);
            } else {
                int s = res.synth(key + "side", tile(side(img, strips.get("side"), 14), 1, 2));
                t.put(kind.equals("left") ? "west" : "east", s);
            }
            return model(List.of(box(new int[] {x0, 0, 1}, new int[] {x1, 14, 15}, t, false)), t.get("up"));
        }

        /** The strips are upside down in the texture: each flipped on its own, lid on top. */
        static Img side(Img img, int u, int wd) {
            Img lid = img.crop(u, 14, u + wd, 19), base = img.crop(u, 33, u + wd, 42);
            return Img.vstack(lid.flipV(), base.flipV()).flipH();
        }

        static JsonObject bed(Resolver res, Img a, String color, String part) {
            String key = "synthetic/bed_" + color + "_" + part + "_";
            int oy = part.equals("head") ? 0 : 22;
            Img top = a.crop(6, oy + 6, 22, oy + 22);
            Img west = new Img(16, 6), east = new Img(16, 6);
            for (int r = 0; r < 16; r++) {
                for (int c = 0; c < 6; c++) {
                    west.set(r, 5 - c, a.get(c, oy + 6 + r));
                    east.set(15 - r, c, a.get(22 + c, oy + 6 + r));
                }
            }
            Img end = part.equals("head") ? a.crop(6, 0, 22, 6).flipV() : a.crop(22, 22, 38, 28).flipV();
            Img bottom = a.crop(28, oy + 6, 44, oy + 22);
            Img leg = new Img(16, 16);
            for (int y = 0; y < 16; y++) {
                for (int x = 0; x < 16; x++) {
                    leg.set(x, y, a.get(50 + x % 3, y % 3));
                }
            }
            Map<String, Integer> t = new LinkedHashMap<>();
            t.put("up", res.synth(key + "top", tile(top, 0, 0)));
            t.put("west", res.synth(key + "west", tile(west, 0, 7)));
            t.put("east", res.synth(key + "east", tile(east, 0, 7)));
            t.put("down", res.synth(key + "bottom", tile(bottom, 0, 0)));
            t.put(part.equals("head") ? "north" : "south", res.synth(key + "end", tile(end, 0, 7)));
            int legt = res.synth(key + "leg", tile(leg, 0, 0));
            Map<String, Integer> lf = new LinkedHashMap<>();
            for (String d : new String[] {"north", "south", "west", "east", "down"}) {
                lf.put(d, legt);
            }
            int z0 = part.equals("head") ? 0 : 13, z1 = part.equals("head") ? 3 : 16;
            return model(List.of(box(new int[] {0, 3, 0}, new int[] {16, 9, 16}, t, false),
                    box(new int[] {0, 0, z0}, new int[] {3, 3, z1}, lf, false),
                    box(new int[] {13, 0, z0}, new int[] {16, 3, z1}, lf, false)), t.get("up"));
        }

        static JsonObject shulker(Resolver res, Img a, String name) {
            String key = "synthetic/" + name + "_";
            Map<String, Integer> t = new LinkedHashMap<>();
            t.put("up", res.synth(key + "top", tile(a.crop(16, 0, 32, 16), 0, 0)));
            t.put("down", res.synth(key + "bottom", tile(a.crop(32, 28, 48, 44), 0, 0)));
            String[] ds = {"north", "east", "south", "west"};
            for (int i = 0; i < 4; i++) {
                int u = i * 16;
                t.put(ds[i], res.synth(key + ds[i], tile(a.crop(u, 44, u + 16, 52), 0, 8, a.crop(u, 16, u + 16, 28), 0, 0)));
            }
            return model(List.of(box(new int[] {0, 0, 0}, new int[] {16, 16, 16}, t, true)), t.get("north"));
        }

        static JsonObject variant(String mid, Map<String, Integer> extra) {
            JsonObject v = new JsonObject();
            v.addProperty("model", mid);
            extra.forEach(v::addProperty);
            return v;
        }

        static int add(Jar src, Resolver res, JsonObject blockstates, Map<String, JsonObject> models) {
            Map<String, String> chests = new LinkedHashMap<>();
            chests.put("chest", "normal");
            chests.put("trapped_chest", "trapped");
            chests.put("ender_chest", "ender");
            for (String ox : new String[] {"", "exposed_", "weathered_", "oxidized_"}) {
                for (String wx : new String[] {"", "waxed_"}) {
                    chests.put(wx + ox + "copper_chest", "copper" + (ox.isEmpty() ? "" : "_" + ox.substring(0, ox.length() - 1)));
                }
            }
            int made = 0;
            for (Map.Entry<String, String> e : chests.entrySet()) {
                String block = e.getKey(), texname = e.getValue();
                if (!blockstates.has("minecraft:" + block)) {
                    continue;
                }
                boolean ender = block.equals("ender_chest");
                JsonObject variants = new JsonObject();
                for (String kind : new String[] {"single", "left", "right"}) {
                    Img img = src.texture("entity/chest/" + texname + (kind.equals("single") ? "" : "_" + kind));
                    if (img == null || img.w != 64 || img.h != 64) {
                        continue;
                    }
                    String mid = "synthetic/" + block + "_" + kind;
                    models.put(mid, chest(res, img, texname, kind));
                    for (Map.Entry<String, Integer> f : FACING_Y.entrySet()) {
                        String k = ender ? "facing=" + f.getKey() : "facing=" + f.getKey() + ",type=" + kind;
                        variants.add(k, variant(mid, Map.of("y", f.getValue())));
                    }
                    if (ender) {
                        break;
                    }
                }
                if (!variants.isEmpty()) {
                    JsonObject bs = new JsonObject();
                    bs.add("variants", variants);
                    blockstates.add("minecraft:" + block, bs);
                    made++;
                }
            }
            for (String color : COLORS) {
                Img img = src.texture("entity/bed/" + color);
                if (img == null || img.w != 64 || img.h != 64 || !blockstates.has("minecraft:" + color + "_bed")) {
                    continue;
                }
                JsonObject variants = new JsonObject();
                for (String part : new String[] {"head", "foot"}) {
                    String mid = "synthetic/" + color + "_bed_" + part;
                    models.put(mid, bed(res, img, color, part));
                    for (Map.Entry<String, Integer> f : FACING_Y.entrySet()) {
                        variants.add("facing=" + f.getKey() + ",part=" + part, variant(mid, Map.of("y", f.getValue())));
                    }
                }
                JsonObject bs = new JsonObject();
                bs.add("variants", variants);
                blockstates.add("minecraft:" + color + "_bed", bs);
                made++;
            }
            List<String> sc = new ArrayList<>();
            sc.add(null);
            sc.addAll(List.of(COLORS));
            for (String color : sc) {
                String block = color != null ? color + "_shulker_box" : "shulker_box";
                Img img = src.texture(color != null ? "entity/shulker/shulker_" + color : "entity/shulker/shulker");
                if (img == null || img.w != 64 || img.h != 64 || !blockstates.has("minecraft:" + block)) {
                    continue;
                }
                String mid = "synthetic/" + block;
                models.put(mid, shulker(res, img, block));
                JsonObject variants = new JsonObject();
                for (Map.Entry<String, Map<String, Integer>> r : SHULKER_ROT.entrySet()) {
                    variants.add("facing=" + r.getKey(), variant(mid, r.getValue()));
                }
                JsonObject bs = new JsonObject();
                bs.add("variants", variants);
                blockstates.add("minecraft:" + block, bs);
                made++;
            }
            return made;
        }
    }

    // ------------------------------------------------------------------ items

    static final int[] GRASS = {0x7c, 0xbd, 0x6b}, FOLIAGE = {0x59, 0xae, 0x30};
    static final double[][] VIEW;

    static {
        double a = Math.toRadians(225), b = Math.toRadians(30);
        double[][] ry = {{Math.cos(a), 0, Math.sin(a)}, {0, 1, 0}, {-Math.sin(a), 0, Math.cos(a)}};
        double[][] rx = {{1, 0, 0}, {0, Math.cos(b), -Math.sin(b)}, {0, Math.sin(b), Math.cos(b)}};
        VIEW = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                for (int k = 0; k < 3; k++) {
                    VIEW[i][j] += rx[i][k] * ry[k][j];
                }
            }
        }
    }

    /** items/<id>.json -> [model name, tints (JsonArray), special?] for the inventory view. */
    static Object[] pickModel(JsonElement ne) {
        if (ne == null || !ne.isJsonObject()) {
            return null;
        }
        JsonObject node = ne.getAsJsonObject();
        String t = normId(J.str(node, "type", ""));
        switch (t) {
            case "model":
                return new Object[] {strOrNull(node.get("model")), node.has("tints") && node.get("tints").isJsonArray()
                        ? node.getAsJsonArray("tints") : new JsonArray(), false};
            case "special":
                return new Object[] {strOrNull(node.get("base")), new JsonArray(), true};
            case "select": {
                List<JsonElement> cases = J.list(node.get("cases"));
                for (JsonElement ce : cases) {
                    JsonObject c = ce.getAsJsonObject();
                    JsonElement w = c.get("when");
                    boolean gui = w != null && (w.isJsonPrimitive() && w.getAsString().equals("gui")
                            || w.isJsonArray() && w.getAsJsonArray().contains(new com.google.gson.JsonPrimitive("gui")));
                    if (gui) {
                        return pickModel(c.get("model"));
                    }
                }
                Object[] f = pickModel(node.get("fallback"));
                return f != null ? f : pickModel(cases.isEmpty() ? null : cases.get(0).getAsJsonObject().get("model"));
            }
            case "condition": {
                Object[] f = pickModel(node.get("on_false"));
                return f != null ? f : pickModel(node.get("on_true"));
            }
            case "range_dispatch": {
                Object[] f = pickModel(node.get("fallback"));
                List<JsonElement> en = J.list(node.get("entries"));
                return f != null ? f : pickModel(en.isEmpty() ? null : en.get(0).getAsJsonObject().get("model"));
            }
            case "composite": {
                List<JsonElement> ms = J.list(node.get("models"));
                return pickModel(ms.isEmpty() ? null : ms.get(0));
            }
            default:
                return node.has("model") ? pickModel(node.get("model")) : null;
        }
    }

    static String strOrNull(JsonElement e) {
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }

    /** [textures (first definition wins), parent chain] of models/<name>.json. */
    static Object[] modelChain(Jar jar, String name) {
        Map<String, String> textures = new HashMap<>();
        List<String> chain = new ArrayList<>();
        String n = normId(name);
        for (int i = 0; i < 12; i++) {
            chain.add(n);
            JsonObject m = jar.obj("models/" + n + ".json");
            if (m == null) {
                break;
            }
            JsonElement t = m.get("textures");
            if (t != null && t.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : t.getAsJsonObject().entrySet()) {
                    textures.putIfAbsent(e.getKey(), sprite(e.getValue()));
                }
            }
            String par = strOrNull(m.get("parent"));
            if (par == null || par.isEmpty()) {
                break;
            }
            n = normId(par);
        }
        return new Object[] {textures, chain};
    }

    static int[] tintRgb(JsonElement te) {
        if (te == null || !te.isJsonObject()) {
            return null;
        }
        JsonObject t = te.getAsJsonObject();
        JsonElement v = t.has("value") ? t.get("value") : t.get("default");
        if (v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() && v.getAsString().matches("-?\\d+")) {
            long x = v.getAsLong() & 0xFFFFFF;
            return new int[] {(int) (x >> 16) & 255, (int) (x >> 8) & 255, (int) x & 255};
        }
        if (J.str(t, "type", "").endsWith("grass")) {
            return GRASS;
        }
        return null;
    }

    static Img flatIcon(Jar jar, Map<String, String> textures, JsonArray tints) {
        Img out = new Img(16, 16);
        boolean found = false;
        for (int i = 0; i < 8; i++) {
            String ref = textures.get("layer" + i);
            if (ref == null || ref.isEmpty()) {
                continue;
            }
            Img im = jar.texture(ref);
            if (im == null) {
                continue;
            }
            if (im.h > im.w) {
                im = im.crop(0, 0, im.w, im.w);
            }
            im = im.resize(16, 16);
            if (i < tints.size()) {
                int[] rgb = tintRgb(tints.get(i));
                if (rgb != null) {
                    Img t = new Img(16, 16);
                    for (int p = 0; p < im.px.length; p++) {
                        int v = im.px[p];
                        int r = (int) Math.min(255, ((v >> 16) & 255) * (rgb[0] / 255.0f));
                        int g = (int) Math.min(255, ((v >> 8) & 255) * (rgb[1] / 255.0f));
                        int b = (int) Math.min(255, (v & 255) * (rgb[2] / 255.0f));
                        t.px[p] = (v & 0xFF000000) | r << 16 | g << 8 | b;
                    }
                    im = t;
                }
            }
            out.over(im);
            found = true;
        }
        return found ? out.resize(SIZE, SIZE) : null;
    }

    /** Draws a block at an angle from above as in the inventory (small software rasterizer). */
    static Img renderBlock(Img atlas, Models.Compiled comp) {
        if (comp == null || comp.quads == 0) {
            return null;
        }
        float[][] img = new float[SIZE * SIZE][4];
        float[] depth = new float[SIZE * SIZE];
        java.util.Arrays.fill(depth, -1e9f);
        int q4 = comp.quads * 4;
        float[] sx = new float[q4], sy = new float[q4], sz = new float[q4];
        float scale = SIZE * 0.62f;
        for (int i = 0; i < q4; i++) {
            float x = comp.verts[3 * i] - 0.5f, y = comp.verts[3 * i + 1] - 0.5f, z = comp.verts[3 * i + 2] - 0.5f;
            float vx = (float) (x * VIEW[0][0] + y * VIEW[0][1] + z * VIEW[0][2]);
            float vy = (float) (x * VIEW[1][0] + y * VIEW[1][1] + z * VIEW[1][2]);
            float vz = (float) (x * VIEW[2][0] + y * VIEW[2][1] + z * VIEW[2][2]);
            sx[i] = SIZE / 2f + vx * scale;
            sy[i] = SIZE / 2f - vy * scale;
            sz[i] = vz;
        }
        int W = atlas.w, H = atlas.h;
        int[][] tris = {{0, 1, 2}, {0, 2, 3}};
        boolean any = false;
        for (int q = 0; q < comp.quads; q++) {
            int tint = comp.tint[q];
            int[] colI = tint == Models.TINT_GRASS ? GRASS : tint == Models.TINT_FOLIAGE ? FOLIAGE
                    : tint == Models.TINT_FIXED ? comp.fixedTint : new int[] {255, 255, 255};
            float[] col = {colI[0] / 255f, colI[1] / 255f, colI[2] / 255f};
            float shade = switch (comp.normal[q]) {
                case 1 -> 1.0f;
                case 2, 3 -> 0.8f;
                case 4, 5 -> 0.6f;
                default -> 0.5f;
            };
            for (int[] tri : tris) {
                float[] ax = new float[3], ay = new float[3], az = new float[3], tu = new float[3], tv = new float[3];
                for (int k = 0; k < 3; k++) {
                    int vi = q * 4 + tri[k];
                    ax[k] = sx[vi];
                    ay[k] = sy[vi];
                    az[k] = sz[vi];
                    tu[k] = comp.uv[2 * vi];
                    tv[k] = comp.uv[2 * vi + 1];
                }
                float den = (ay[1] - ay[2]) * (ax[0] - ax[2]) + (ax[2] - ax[1]) * (ay[0] - ay[2]);
                if (Math.abs(den) < 1e-6) {
                    continue;
                }
                int x0 = (int) Math.max(0, Math.min(ax[0], Math.min(ax[1], ax[2])));
                int x1 = (int) Math.min(SIZE - 1, Math.ceil(Math.max(ax[0], Math.max(ax[1], ax[2]))));
                int y0 = (int) Math.max(0, Math.min(ay[0], Math.min(ay[1], ay[2])));
                int y1 = (int) Math.min(SIZE - 1, Math.ceil(Math.max(ay[0], Math.max(ay[1], ay[2]))));
                for (int py = y0; py <= y1; py++) {
                    for (int px = x0; px <= x1; px++) {
                        float bx = px + 0.5f, by = py + 0.5f;
                        float w0 = ((ay[1] - ay[2]) * (bx - ax[2]) + (ax[2] - ax[1]) * (by - ay[2])) / den;
                        float w1 = ((ay[2] - ay[0]) * (bx - ax[2]) + (ax[0] - ax[2]) * (by - ay[2])) / den;
                        float w2 = 1 - w0 - w1;
                        if (w0 < -1e-4f || w1 < -1e-4f || w2 < -1e-4f) {
                            continue;
                        }
                        float u = w0 * tu[0] + w1 * tu[1] + w2 * tu[2];
                        float v = w0 * tv[0] + w1 * tv[1] + w2 * tv[2];
                        float z = w0 * az[0] + w1 * az[1] + w2 * az[2];
                        int ui = Math.max(0, Math.min(W - 1, (int) u)), vi = Math.max(0, Math.min(H - 1, (int) v));
                        int tx = atlas.px[vi * W + ui];
                        float ta = Img.alpha(tx) / 255f;
                        int p = py * SIZE + px;
                        if (ta <= 0.1f || z <= depth[p]) {
                            continue;
                        }
                        float[] o = img[p];
                        o[0] = ((tx >> 16) & 255) / 255f * col[0] * shade * ta + o[0] * (1 - ta);
                        o[1] = ((tx >> 8) & 255) / 255f * col[1] * shade * ta + o[1] * (1 - ta);
                        o[2] = (tx & 255) / 255f * col[2] * shade * ta + o[2] * (1 - ta);
                        o[3] = Math.max(o[3], ta);
                        depth[p] = z;
                        any = true;
                    }
                }
            }
        }
        if (!any) {
            return null;
        }
        Img out = new Img(SIZE, SIZE);
        for (int p = 0; p < out.px.length; p++) {
            int[] c = new int[4];
            for (int k = 0; k < 4; k++) {
                c[k] = (int) (Math.max(0f, Math.min(1f, img[p][k])) * 255);
            }
            out.px[p] = c[3] << 24 | c[0] << 16 | c[1] << 8 | c[2];
        }
        return out;
    }

    /** Block model the viewer doesn't know (e.g. *_inventory), taken over from the jar if all its textures are in the atlas. */
    @SuppressWarnings("unchecked")
    static boolean injectJarModel(Jar jar, Models models, String name) {
        Object[] mc = modelChain(jar, name);
        Map<String, String> textures = (Map<String, String>) mc[0];
        JsonArray elements = null;
        for (String n : (List<String>) mc[1]) {
            JsonObject m = jar.obj("models/" + n + ".json");
            if (m != null && m.has("elements") && m.get("elements").isJsonArray() && !m.getAsJsonArray("elements").isEmpty()) {
                elements = m.getAsJsonArray("elements");
                break;
            }
        }
        if (elements == null) {
            return false;
        }
        JsonArray els = new JsonArray();
        for (JsonElement ee : elements) {
            JsonObject el = ee.getAsJsonObject();
            JsonObject faces = new JsonObject();
            JsonElement fs = el.get("faces");
            if (fs != null && fs.isJsonObject()) {
                for (Map.Entry<String, JsonElement> fe : fs.getAsJsonObject().entrySet()) {
                    JsonObject f = fe.getValue().getAsJsonObject();
                    String ref = f.has("texture") ? sprite(f.get("texture")) : "";
                    if (ref == null) {
                        ref = "";
                    }
                    for (int d = 0; ref.startsWith("#") && d < 8; d++) {
                        ref = textures.getOrDefault(ref.substring(1), "");
                        if (ref == null) {
                            ref = "";
                        }
                    }
                    Integer t = models.textures.get(normId(ref));
                    if (t == null) {
                        return false;
                    }
                    JsonObject face = new JsonObject();
                    face.addProperty("t", t);
                    for (String k : new String[] {"uv", "rotation", "tintindex", "cullface"}) {
                        if (f.has(k)) {
                            face.add(k, f.get(k));
                        }
                    }
                    faces.add(fe.getKey(), face);
                }
            }
            JsonObject e = new JsonObject();
            e.add("from", el.get("from"));
            e.add("to", el.get("to"));
            e.add("faces", faces);
            JsonElement rot = el.get("rotation");
            if (rot != null && !rot.isJsonNull()) {
                e.add("rotation", rot);
            }
            els.add(e);
        }
        JsonObject m = new JsonObject();
        m.add("elements", els);
        models.models.add(name, m);
        return true;
    }

    static final Map<String, String> PREF = new LinkedHashMap<>();

    static {
        PREF.put("facing", "north");
        PREF.put("half", "bottom");
        PREF.put("type", "single");
        PREF.put("part", "foot");
        PREF.put("shape", "straight");
        PREF.put("axis", "y");
        PREF.put("face", "floor");
        PREF.put("attachment", "floor");
    }

    static String defaultState(Models models, String block) {
        JsonObject bs = J.obj(models.blockstates.get(block));
        JsonObject vs = bs == null ? null : J.obj(bs.get("variants"));
        if (vs == null || vs.isEmpty() || vs.has("")) {
            return block;
        }
        String best = null;
        int bestScore = -1;
        for (String k : vs.keySet()) {
            Map<String, String> props = new HashMap<>();
            for (String kv : k.split(",")) {
                int i = kv.indexOf('=');
                props.put(i < 0 ? kv : kv.substring(0, i), i < 0 ? "" : kv.substring(i + 1));
            }
            int score = 0;
            for (Map.Entry<String, String> p : PREF.entrySet()) {
                score += p.getValue().equals(props.get(p.getKey())) ? 1 : 0;
            }
            if (score > bestScore) {
                best = k;
                bestScore = score;
            }
        }
        return block + "[" + best + "]";
    }

    static final List<String> FLAT_PARENTS = List.of("item/generated", "builtin/generated", "item/handheld", "item/handheld_rod",
            "item/handheld_mace");

    @SuppressWarnings("unchecked")
    static void buildItems(Jar jar, Path out, JsonObject meta, Consumer<String> log) throws IOException {
        Models models = new Models(meta);
        Img atlas = Img.of(Png.read(Files.readAllBytes(out.resolve("blocks.png"))));
        Map<String, Img> icons = new TreeMap<>();
        Map<String, Integer> stats = new LinkedHashMap<>(Map.of("block", 0, "flat", 0, "fallback", 0, "missing", 0));
        for (String item : jar.list("items/", ".json")) {
            String full = "minecraft:" + item;
            JsonObject def = jar.obj("items/" + item + ".json");
            Object[] res = pickModel(def == null ? null : def.get("model"));
            Img icon = null;
            if (res != null && res[0] != null) {
                String mname = normId((String) res[0]);
                JsonArray tints = (JsonArray) res[1];
                boolean special = (Boolean) res[2];
                Object[] mc = modelChain(jar, mname);
                Map<String, String> textures = (Map<String, String>) mc[0];
                List<String> chain = (List<String>) mc[1];
                boolean flat = chain.stream().anyMatch(FLAT_PARENTS::contains) || textures.containsKey("layer0") && !mname.startsWith("block/");
                if (flat) {
                    icon = flatIcon(jar, textures, tints);
                    if (icon != null) {
                        stats.merge("flat", 1, Integer::sum);
                    }
                }
                if (icon == null && (special || mname.startsWith("block/"))) {
                    Models.Compiled comp = null;
                    if (mname.startsWith("block/") && !models.models.has(mname)) {
                        injectJarModel(jar, models, mname);
                    }
                    JsonObject m = J.obj(models.models.get(mname));
                    if (mname.startsWith("block/") && m != null && !J.list(m.get("elements")).isEmpty()) {
                        JsonObject forced = new JsonObject();
                        forced.addProperty("model", mname);
                        comp = models.compile(full, List.of(forced));
                    }
                    if ((comp == null || comp.quads == 0) && models.blockstates.has(full)) {
                        comp = models.compile(defaultState(models, full));
                    }
                    icon = renderBlock(atlas, comp);
                    if (icon != null) {
                        stats.merge("block", 1, Integer::sum);
                    }
                }
                if (icon == null) {
                    for (String k : new String[] {"particle", "layer0", "all", "side", "texture"}) {
                        String ref = textures.get(k);
                        if (ref != null && !ref.startsWith("#")) {
                            Img im = jar.texture(ref);
                            if (im != null) {
                                if (im.h > im.w) {
                                    im = im.crop(0, 0, im.w, im.w);
                                }
                                icon = im.resize(SIZE, SIZE);
                                stats.merge("fallback", 1, Integer::sum);
                                break;
                            }
                        }
                    }
                }
            }
            if (icon == null) {
                stats.merge("missing", 1, Integer::sum);
                continue;
            }
            icons.put(full, icon);
        }
        List<String> names = new ArrayList<>(icons.keySet());
        int rows = (names.size() + ITEM_COLS - 1) / ITEM_COLS;
        Img sheet = new Img(ITEM_COLS * SIZE, Math.max(1, rows) * SIZE);
        JsonObject index = new JsonObject();
        for (int i = 0; i < names.size(); i++) {
            sheet.paste(icons.get(names.get(i)), (i % ITEM_COLS) * SIZE, (i / ITEM_COLS) * SIZE);
            index.addProperty(names.get(i), i);
        }
        JsonObject info = new JsonObject();
        info.addProperty("size", SIZE);
        info.addProperty("cols", ITEM_COLS);
        info.addProperty("rows", rows);
        info.add("index", index);
        Png.writeAtomic(out.resolve("items.png"), sheet.png());
        Png.writeAtomic(out.resolve("items.json"), J.GSON.toJson(info).getBytes(StandardCharsets.UTF_8));
        log.accept(names.size() + " Item-Bilder " + stats);
    }

    public static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        build(Path.of(args[0]), Path.of(args[1]), System.out::println);
    }
}
