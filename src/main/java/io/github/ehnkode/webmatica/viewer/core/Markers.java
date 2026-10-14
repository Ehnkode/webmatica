package io.github.ehnkode.webmatica.viewer.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Interesting block entities per chunk: sign texts and containers (stash finder). The server
 * sends every block entity of a chunk with type and position (not chest contents), so chests,
 * shulkers etc. can be counted without opening them.
 */
public final class Markers {
    static final Map<String, String> CONTAINERS = new LinkedHashMap<>();
    static final Set<String> SIGNS = Set.of("sign", "hanging_sign");

    static {
        String[][] c = {{"chest", "Truhe"}, {"trapped_chest", "Truhe"}, {"barrel", "Fass"}, {"shulker_box", "Shulker"},
                {"ender_chest", "Endertruhe"}, {"hopper", "Trichter"}, {"dispenser", "Werfer"}, {"dropper", "Spender"},
                {"furnace", "Ofen"}, {"blast_furnace", "Ofen"}, {"smoker", "Ofen"}, {"brewing_stand", "Braustand"},
                {"crafter", "Crafter"}, {"chiseled_bookshelf", "Buecherregal"}};
        for (String[] e : c) {
            CONTAINERS.put(e[0], e[1]);
        }
    }

    private Markers() {
    }

    /** Text component (JSON string or NBT compound) -> plain text. */
    static String text(Object v) {
        if (v instanceof String str) {
            String s = str.strip();
            if (!s.isEmpty() && (s.charAt(0) == '"' || s.charAt(0) == '{' || s.charAt(0) == '[')) {
                try {
                    return text(JsonParser.parseString(s));
                } catch (RuntimeException e) {
                    return s;
                }
            }
            return s;
        }
        if (v instanceof JsonElement je) {
            if (je.isJsonPrimitive()) {
                return je.getAsJsonPrimitive().isString() ? text(je.getAsString()) : "";
            }
            if (je.isJsonArray()) {
                StringBuilder sb = new StringBuilder();
                for (JsonElement x : je.getAsJsonArray()) {
                    sb.append(text(x));
                }
                return sb.toString();
            }
            if (je.isJsonObject()) {
                var o = je.getAsJsonObject();
                String out = o.has("text") ? text(o.get("text")) : "";
                if (o.has("translate") && out.isEmpty()) {
                    out = o.has("fallback") ? o.get("fallback").getAsString() : o.get("translate").getAsString();
                }
                if (o.has("extra") && o.get("extra").isJsonArray()) {
                    StringBuilder sb = new StringBuilder(out);
                    for (JsonElement x : o.getAsJsonArray("extra")) {
                        sb.append(text(x));
                    }
                    out = sb.toString();
                }
                return out;
            }
            return "";
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object x : l) {
                sb.append(text(x));
            }
            return sb.toString();
        }
        if (v instanceof Map<?, ?> m) {
            String out = m.containsKey("text") ? text(m.get("text")) : "";
            if (m.containsKey("translate") && out.isEmpty()) {
                out = String.valueOf(m.containsKey("fallback") ? m.get("fallback") : m.get("translate"));
            }
            if (m.get("extra") instanceof List<?> extra) {
                StringBuilder sb = new StringBuilder(out);
                for (Object x : extra) {
                    sb.append(text(x));
                }
                out = sb.toString();
            }
            return out;
        }
        return "";
    }

    /** {"signs": [{x, y, z, lines}], "containers": {label: count}} */
    public static Map<String, Object> of(Chunk ch) {
        List<Object> signs = new ArrayList<>();
        Map<String, Integer> containers = new LinkedHashMap<>();
        for (Map<String, Object> be : ch.blockEntities) {
            String kind = Chunk.shortName(String.valueOf(be.getOrDefault("id", "")));
            if (SIGNS.contains(kind)) {
                List<String> lines = new ArrayList<>();
                for (String side : new String[] {"front_text", "back_text"}) {
                    Map<String, Object> st = Nbt.map(be.get(side));
                    if (st != null) {
                        for (Object m : Nbt.list(st.get("messages"))) {
                            lines.add(text(m));
                        }
                    }
                }
                if (lines.isEmpty() && be.containsKey("Text1")) {   // old format
                    for (int i = 1; i <= 4; i++) {
                        lines.add(text(be.getOrDefault("Text" + i, "")));
                    }
                }
                lines.removeIf(l -> l.strip().isEmpty());
                if (!lines.isEmpty()) {
                    Map<String, Object> s = new LinkedHashMap<>();
                    s.put("x", be.get("x"));
                    s.put("y", be.get("y"));
                    s.put("z", be.get("z"));
                    s.put("lines", lines);
                    signs.add(s);
                }
            } else {
                String label = CONTAINERS.get(kind);
                if (label == null && kind.endsWith("shulker_box")) {
                    label = "Shulker";
                }
                if (label != null) {
                    containers.merge(label, 1, Integer::sum);
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("signs", signs);
        out.put("containers", containers);
        return out;
    }
}
