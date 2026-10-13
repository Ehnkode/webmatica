package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.BlockMirror;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridge viewer -> Litematica: schematics set to "In game" in the viewer are
 * created as real Litematica placements (ghost blocks, material list,
 * Printer, Baritone #litematica). Moving/rotating in the viewer follows along.
 *
 * Litematica is only accessed via reflection: no hard dependency, without
 * Litematica the mod runs as before. Syncing on the client thread, HTTP on its own
 * daemon thread.
 */
public final class LitematicaBridge {

    private static final String SUFFIX = " (Web)";
    private static final LitematicaBridge INSTANCE = new LitematicaBridge();

    public static LitematicaBridge getInstance() {
        return INSTANCE;
    }

    // ------------------------------------------------------------ HTTP thread

    private final AtomicReference<List<Want>> wanted = new AtomicReference<>(null);
    private volatile String serverId = null;
    private volatile String statusJson = null;
    private Thread thread;

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "webmatica-litematica");
        thread.setDaemon(true);
        thread.start();
    }

    private static String base() {
        String u = PositionReporter.url();
        int i = u.indexOf("/api/");
        return i > 0 ? u.substring(0, i) : "http://127.0.0.1:8770";
    }

    private void loop() {
        while (true) {
            try {
                String srv = serverId;
                if (srv != null && available()) {
                    wanted.set(fetch(srv));
                    String st = statusJson;
                    if (st != null) {
                        post("/api/litematica/status", st);
                    }
                }
                Thread.sleep(1000L);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                // viewer off -> keep retrying silently, placements stay as they are
                try { Thread.sleep(3000L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static List<Want> fetch(String srv) throws Exception {
        URI uri = URI.create(base() + "/api/litematica?srv=" + URLEncoder.encode(srv, StandardCharsets.UTF_8));
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(2000);
        try (InputStream in = c.getInputStream()) {
            JsonArray arr = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonArray();
            List<Want> out = new ArrayList<>();
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                JsonArray or = o.getAsJsonArray("origin");
                out.add(new Want(o.get("id").getAsString(), UUID.fromString(o.get("uuid").getAsString()),
                        o.get("name").getAsString(), o.get("path").getAsString(), o.get("dim").getAsString(),
                        or.get(0).getAsInt(), or.get(1).getAsInt(), or.get(2).getAsInt(),
                        o.get("rotation").getAsString(), o.get("mirror").getAsString(),
                        o.has("mtime") ? o.get("mtime").getAsLong() : 0L));
            }
            return out;
        } finally {
            c.disconnect();
        }
    }

    private static void post(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(1500);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        c.setFixedLengthStreamingMode(b.length);
        try (OutputStream os = c.getOutputStream()) {
            os.write(b);
        }
        c.getResponseCode();
        c.disconnect();
    }

    private record Want(String id, UUID uuid, String name, String path, String dim,
                        int x, int y, int z, String rotation, String mirror, long mtime) {
        String fileKey() {
            return path + "@" + mtime;
        }
    }

    // ------------------------------------------------------------ Client thread

    private final Map<String, Object> managed = new HashMap<>();     // viewer ID -> SchematicPlacement
    private final Map<String, String> managedFile = new HashMap<>();
    private final Map<String, String> errors = new HashMap<>();
    private ClientWorld lastWorld;
    private boolean adopted;
    private int tickCount;

    public void tick(MinecraftClient mc) {
        if (mc.world == null || mc.player == null) {
            serverId = null;
            return;
        }
        serverId = RecordingTarget.serverId(mc);
        if (!available()) {
            if (++tickCount % 20 == 0) {
                statusJson = "{\"available\":false}";
            }
            return;
        }
        if (mc.world != lastWorld) {
            // Litematica reloads its own list on world/dimension change
            lastWorld = mc.world;
            managed.clear();
            managedFile.clear();
            adopted = false;
        }
        List<Want> all = wanted.get();
        if (all == null || ++tickCount % 10 != 0) {
            return;
        }
        String dim = dimKey(mc.world);
        List<Want> here = new ArrayList<>();
        for (Want w : all) {
            if (dim.equals(w.dim())) {
                here.add(w);
            }
        }
        try {
            apply(here);
        } catch (Throwable t) {
            MapperClient.LOGGER.warn("[webmatica] Litematica-Abgleich fehlgeschlagen", t);
            errors.put("_", String.valueOf(t));
        }
        JsonObject st = new JsonObject();
        st.addProperty("available", true);
        st.addProperty("dim", dim);
        JsonArray ids = new JsonArray();
        managed.keySet().forEach(ids::add);
        st.add("ids", ids);
        JsonObject err = new JsonObject();
        errors.forEach(err::addProperty);
        st.add("errors", err);
        statusJson = st.toString();
    }

    /** Index of the placement in Litematica's list (for Baritone #litematica), else -1. Client thread. */
    public int placementIndex(String viewerId) {
        Object pl = managed.get(viewerId);
        if (pl == null || !available()) {
            return -1;
        }
        try {
            List<?> all = (List<?>) call(getManager(), "getAllSchematicsPlacements");
            return all.indexOf(pl);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String dimKey(ClientWorld w) {
        Identifier id = w.getRegistryKey().getValue();
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }

    private void apply(List<Want> here) throws Exception {
        Object mgr = getManager();
        @SuppressWarnings("unchecked")
        List<Object> existing = new ArrayList<>((List<Object>) call(mgr, "getAllSchematicsPlacements"));
        Map<String, Want> byId = new HashMap<>();
        Map<UUID, Want> byUuid = new HashMap<>();
        for (Want w : here) {
            byId.put(w.id(), w);
            byUuid.put(w.uuid(), w);
        }

        if (!adopted) {
            // Saved by Litematica from the last session: take them over again,
            // and remove our own ones that no longer exist in the viewer.
            adopted = true;
            for (Object pl : existing) {
                Want w = byUuid.get((UUID) call(pl, "getHashId"));
                String name = String.valueOf(call(pl, "getName"));
                if (w != null && !managed.containsKey(w.id())) {
                    managed.put(w.id(), pl);
                    Object f = call(pl, "getSchematicFile");
                    managedFile.put(w.id(), f != null && samePath(f.toString(), w.path()) ? w.fileKey() : "?");
                } else if (name.endsWith(SUFFIX)) {
                    call(mgr, "removeSchematicPlacement", pl);
                }
            }
            existing = new ArrayList<>((List<Object>) call(mgr, "getAllSchematicsPlacements"));
        }

        // Removed in the viewer or "In game" turned off
        for (String id : new ArrayList<>(managed.keySet())) {
            if (!byId.containsKey(id)) {
                Object pl = managed.remove(id);
                managedFile.remove(id);
                if (existing.contains(pl)) {
                    call(mgr, "removeSchematicPlacement", pl);
                }
            }
        }
        errors.keySet().removeIf(id -> !byId.containsKey(id));

        for (Want w : here) {
            Object pl = managed.get(w.id());
            boolean fileChanged = pl != null && !w.fileKey().equals(managedFile.get(w.id()));
            if (pl != null && (fileChanged || !existing.contains(pl))) {
                // file changed or deleted in Litematica: recreate (the viewer is the source)
                if (existing.contains(pl)) {
                    call(mgr, "removeSchematicPlacement", pl);
                }
                managed.remove(w.id());
                pl = null;
            }
            try {
                if (pl == null) {
                    pl = create(mgr, w, fileChanged);
                    managed.put(w.id(), pl);
                    managedFile.put(w.id(), w.fileKey());
                } else {
                    update(pl, w);
                }
                errors.remove(w.id());
            } catch (Throwable t) {
                Throwable c = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
                errors.put(w.id(), c.getClass().getSimpleName() + ": " + c.getMessage());
            }
        }
    }

    private Object create(Object mgr, Want w, boolean reload) throws Exception {
        Path p = Path.of(w.path());
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("Datei fehlt: " + w.path());
        }
        Class<?> holderC = Class.forName("fi.dy.masa.litematica.data.SchematicHolder");
        Object holder = holderC.getMethod("getInstance").invoke(null);
        if (reload) {
            // file changed in the editor: Litematica still has the old version in memory
            for (Object s : new ArrayList<>((java.util.Collection<?>) call(holder, "getAllSchematics"))) {
                Object f = call(s, "getFile");
                if (f != null && samePath(f.toString(), w.path())) {
                    call(holder, "removeSchematic", s);
                }
            }
        }
        Object schem = call(holder, "getOrLoad", p);
        if (schem == null) {
            throw new IllegalStateException("Litematica kann die Datei nicht lesen");
        }
        Class<?> plC = Class.forName("fi.dy.masa.litematica.schematic.placement.SchematicPlacement");
        Object pl = null;
        for (Method m : plC.getMethods()) {
            if (m.getName().equals("createFor") && m.getParameterCount() == 6) {
                pl = m.invoke(null, schem, new BlockPos(w.x(), w.y(), w.z()), w.name() + SUFFIX, true, true, w.uuid());
                break;
            }
        }
        if (pl == null) {
            throw new IllegalStateException("Litematica-Version passt nicht (createFor fehlt)");
        }
        call(pl, "setRotation", rotation(w.rotation()), quiet("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer"));
        call(pl, "setMirror", mirror(w.mirror()), quiet("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer"));
        call(mgr, "addSchematicPlacement", pl, false);
        return pl;
    }

    private void update(Object pl, Want w) throws Exception {
        BlockPos want = new BlockPos(w.x(), w.y(), w.z());
        if (!want.equals(call(pl, "getOrigin"))) {
            call(pl, "setOrigin", want, quiet("fi.dy.masa.malilib.interfaces.IStringConsumer"));
        }
        BlockRotation r = rotation(w.rotation());
        if (call(pl, "getRotation") != r) {
            call(pl, "setRotation", r, quiet("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer"));
        }
        BlockMirror m = mirror(w.mirror());
        if (call(pl, "getMirror") != m) {
            call(pl, "setMirror", m, quiet("fi.dy.masa.malilib.gui.interfaces.IMessageConsumer"));
        }
    }

    // reference constants directly: enum names are intermediary at runtime (valueOf doesn't work)
    private static BlockRotation rotation(String s) {
        return switch (s) {
            case "CLOCKWISE_90" -> BlockRotation.CLOCKWISE_90;
            case "CLOCKWISE_180" -> BlockRotation.CLOCKWISE_180;
            case "COUNTERCLOCKWISE_90" -> BlockRotation.COUNTERCLOCKWISE_90;
            default -> BlockRotation.NONE;
        };
    }

    private static BlockMirror mirror(String s) {
        return switch (s) {
            case "FRONT_BACK" -> BlockMirror.FRONT_BACK;
            case "LEFT_RIGHT" -> BlockMirror.LEFT_RIGHT;
            default -> BlockMirror.NONE;
        };
    }

    private static boolean samePath(String a, String b) {
        try {
            return Path.of(a).toAbsolutePath().normalize().equals(Path.of(b).toAbsolutePath().normalize());
        } catch (Exception e) {
            return Objects.equals(a, b);
        }
    }

    // ------------------------------------------------------------ Reflection

    private static Boolean avail;
    private static final Map<String, Object> QUIET = new HashMap<>();

    private static boolean available() {
        if (avail == null) {
            try {
                Class.forName("fi.dy.masa.litematica.data.DataManager");
                avail = true;
            } catch (Throwable t) {
                avail = false;
            }
        }
        return avail;
    }

    private static Object getManager() throws Exception {
        return Class.forName("fi.dy.masa.litematica.data.DataManager").getMethod("getSchematicPlacementManager").invoke(null);
    }

    private static Object quiet(String iface) throws Exception {
        Object q = QUIET.get(iface);
        if (q == null) {
            Class<?> c = Class.forName(iface);
            q = Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (proxy, m, args) -> {
                if (m.getName().equals("equals")) return proxy == args[0];
                if (m.getName().equals("hashCode")) return System.identityHashCode(proxy);
                if (m.getName().equals("toString")) return "quiet";
                return m.getReturnType() == boolean.class ? false : null;
            });
            QUIET.put(iface, q);
        }
        return q;
    }

    private static final Map<String, Method> METHODS = new HashMap<>();
    private static final Set<String> MISSING = new HashSet<>();

    private static Object call(Object target, String name, Object... args) throws Exception {
        String key = target.getClass().getName() + "#" + name + "/" + args.length;
        Method m = METHODS.get(key);
        if (m == null) {
            if (MISSING.contains(key)) {
                throw new NoSuchMethodException(key);
            }
            for (Method c : target.getClass().getMethods()) {
                if (c.getName().equals(name) && c.getParameterCount() == args.length && fits(c, args)) {
                    m = c;
                    break;
                }
            }
            if (m == null) {
                MISSING.add(key);
                throw new NoSuchMethodException(key);
            }
            METHODS.put(key, m);
        }
        return m.invoke(target, args);
    }

    private static boolean fits(Method m, Object[] args) {
        Class<?>[] ps = m.getParameterTypes();
        for (int i = 0; i < ps.length; i++) {
            if (args[i] == null) {
                continue;
            }
            Class<?> p = ps[i];
            if (p == boolean.class) {
                p = Boolean.class;
            } else if (p == int.class) {
                p = Integer.class;
            }
            if (!p.isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }
}
