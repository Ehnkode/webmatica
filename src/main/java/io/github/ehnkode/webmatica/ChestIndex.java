package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * The remembered chests of the current server, for the in-game search and the item tooltip.
 * Asks the viewer (which also drops chests that were broken); if it is not running yet, reads
 * containers.json directly.
 */
final class ChestIndex {
    private static final long MAX_AGE_MS = 15_000L;

    /** One item kind in one chest. */
    static final class Hit {
        final String id;
        final String name;          // custom name, or null
        long count;
        final String dim;
        final int x;
        final int y;
        final int z;
        final String block;
        boolean inShulker;
        Integer slot;               // chest slot to fetch, if known
        boolean shulkerSlot;        // slot holds the shulker, not the item itself

        Hit(String id, String name, String dim, int x, int y, int z, String block) {
            this.id = id;
            this.name = name;
            this.dim = dim;
            this.x = x;
            this.y = y;
            this.z = z;
            this.block = block;
        }

        boolean ender() {
            return "ender".equals(dim);
        }
    }

    private static volatile List<Hit> hits = List.of();
    private static volatile Map<String, long[]> totals = Map.of();   // id -> {count, chests}
    private static volatile String server;
    private static volatile long loadedAt;
    private static volatile boolean loading;
    private static final Map<String, String> NAMES = new HashMap<>();

    private ChestIndex() {
    }

    /** Reloads in the background if the data is old or from another server. */
    static void refresh(Minecraft mc, boolean force) {
        if (mc.level == null || loading) {
            return;
        }
        String srv = RecordingTarget.serverId(mc);
        if (!force && srv.equals(server) && System.currentTimeMillis() - loadedAt < MAX_AGE_MS) {
            return;
        }
        Path file = RecordingTarget.root(mc).resolve(".viewer").resolve("containers.json");
        loading = true;
        ViewerApi.background(() -> {
            try {
                List<JsonElement> chests = load(srv, file);
                build(srv, chests);
            } finally {
                loading = false;
            }
        });
    }

    static boolean loaded(Minecraft mc) {
        return mc.level != null && RecordingTarget.serverId(mc).equals(server);
    }

    private static List<JsonElement> load(String srv, Path file) {
        List<JsonElement> out = new ArrayList<>();
        try {
            JsonElement e = ViewerApi.getNow("/api/containers?srv=" + ViewerApi.q(srv));
            if (e.isJsonArray()) {
                e.getAsJsonArray().forEach(out::add);
                return out;
            }
        } catch (Exception ignored) {
            // viewer not running: read the file it keeps
        }
        try {
            if (Files.isRegularFile(file)) {
                JsonObject all = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
                for (Map.Entry<String, JsonElement> en : all.entrySet()) {
                    JsonObject c = en.getValue().getAsJsonObject();
                    if (c.has("server") && srv.equals(c.get("server").getAsString())) {
                        out.add(c);
                    }
                }
            }
        } catch (Exception ex) {
            MapperClient.LOGGER.warn("[webmatica] containers.json nicht lesbar: {}", ex.toString());
        }
        return out;
    }

    private static void build(String srv, List<JsonElement> chests) {
        List<Hit> list = new ArrayList<>();
        Map<String, long[]> sum = new HashMap<>();
        for (JsonElement el : chests) {
            JsonObject c = el.getAsJsonObject();
            String dim = str(c, "dim");
            int x = c.get("x").getAsInt(), y = c.get("y").getAsInt(), z = c.get("z").getAsInt();
            String block = str(c, "block");
            Map<String, Hit> here = new LinkedHashMap<>();
            JsonArray items = c.has("items") && c.get("items").isJsonArray() ? c.getAsJsonArray("items") : new JsonArray();
            for (JsonElement ie : items) {
                JsonObject it = ie.getAsJsonObject();
                Integer slot = it.has("slot") ? it.get("slot").getAsInt() : null;
                add(here, it, dim, x, y, z, block, false, slot);
                if (it.has("contents") && it.get("contents").isJsonArray()) {
                    for (JsonElement inner : it.getAsJsonArray("contents")) {
                        add(here, inner.getAsJsonObject(), dim, x, y, z, block, true, slot);
                    }
                }
            }
            for (Hit h : here.values()) {
                list.add(h);
                long[] t = sum.computeIfAbsent(h.id, k -> new long[2]);
                t[0] += h.count;
                t[1]++;
            }
        }
        hits = Collections.unmodifiableList(list);
        totals = sum;
        server = srv;
        loadedAt = System.currentTimeMillis();
    }

    private static void add(Map<String, Hit> here, JsonObject it, String dim, int x, int y, int z, String block,
                            boolean inShulker, Integer slot) {
        if (!it.has("id")) {
            return;
        }
        String id = it.get("id").getAsString();
        String name = it.has("name") ? it.get("name").getAsString() : null;
        long n = it.has("count") ? it.get("count").getAsLong() : 1;
        Hit h = here.get(id);
        if (h == null) {
            h = new Hit(id, name, dim, x, y, z, block);
            here.put(id, h);
        }
        h.count += n;
        if (inShulker) {
            h.inShulker = true;
        }
        if (h.slot == null && slot != null) {
            h.slot = slot;
            h.shulkerSlot = inShulker;
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }

    /** {count, chests} of an item over all remembered chests of this server, or null. */
    static long[] total(String id) {
        return totals.get(id);
    }

    /** Matches by item id and by the name in the game language. Client thread. */
    static List<Hit> search(String query, int limit) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Hit> out = new ArrayList<>();
        if (q.isEmpty()) {
            return out;
        }
        for (Hit h : hits) {
            if (h.id.toLowerCase(Locale.ROOT).contains(q.replace(' ', '_'))
                    || displayName(h.id).toLowerCase(Locale.ROOT).contains(q)
                    || (h.name != null && h.name.toLowerCase(Locale.ROOT).contains(q))) {
                out.add(h);
            }
        }
        out.sort((a, b) -> Long.compare(b.count, a.count));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    static int chestCount() {
        int n = 0;
        String last = null;
        for (Hit h : hits) {
            String k = h.dim + h.x + "," + h.y + "," + h.z;
            if (!k.equals(last)) {
                n++;
                last = k;
            }
        }
        return n;
    }

    static Item item(String id) {
        Identifier rid = Identifier.tryParse(id);
        return rid == null ? null : BuiltInRegistries.ITEM.getValue(rid);
    }

    static ItemStack stack(String id) {
        Item it = item(id);
        return it == null ? ItemStack.EMPTY : new ItemStack(it);
    }

    /** Name in the game language. Client thread. */
    static String displayName(String id) {
        synchronized (NAMES) {
            return NAMES.computeIfAbsent(id, k -> {
                ItemStack s = stack(k);
                return s.isEmpty() ? k : s.getHoverName().getString();
            });
        }
    }

    /** The game language changed: names have to be looked up again. */
    static void clearNames() {
        synchronized (NAMES) {
            NAMES.clear();
        }
    }
}
