package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.KeyError;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON helpers with the coercions the browser and the mod rely on: {@code str}, {@code num},
 * {@code bool} behave like Python's str(), int()/float() and truthiness on JSON values.
 * A failed conversion throws {@link IllegalArgumentException} (answered with 400).
 */
public final class J {
    public static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .serializeSpecialFloatingPointValues().create();
    public static final Gson PRETTY = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting()
            .serializeSpecialFloatingPointValues().create();

    private J() {
    }

    public static String dump(Object o) {
        return GSON.toJson(o);
    }

    public static JsonElement tree(Object o) {
        return o instanceof JsonElement e ? e : GSON.toJsonTree(o);
    }

    public static JsonElement parse(String s) {
        return JsonParser.parseString(s);
    }

    /** JSON file, or null when missing or broken. */
    public static JsonElement load(Path p) {
        try {
            return JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Writes JSON atomically (temp file + rename). */
    public static void save(Path p, Object o, boolean pretty) throws IOException {
        Files.createDirectories(p.getParent());
        Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
        Files.writeString(tmp, (pretty ? PRETTY : GSON).toJson(o), StandardCharsets.UTF_8);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    public static void saveQuiet(Path p, Object o) {
        try {
            save(p, o, false);
        } catch (IOException ignored) {
            // like the original: losing one save is not worth a crash
        }
    }

    // ------------------------------------------------------------------ access

    public static JsonElement get(JsonObject o, String k) {
        if (o == null) {
            return null;
        }
        JsonElement e = o.get(k);
        return e == null || e.isJsonNull() ? null : e;
    }

    /** Key present (even when null). */
    public static boolean has(JsonObject o, String k) {
        return o != null && o.has(k);
    }

    public static JsonObject obj(JsonElement e) {
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    public static JsonObject obj(JsonObject o, String k) {
        return obj(get(o, k));
    }

    public static JsonArray arr(JsonElement e) {
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    public static JsonArray arr(JsonObject o, String k) {
        return arr(get(o, k));
    }

    /** Python truthiness. */
    public static boolean bool(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return false;
        }
        if (e.isJsonArray()) {
            return !e.getAsJsonArray().isEmpty();
        }
        if (e.isJsonObject()) {
            return !e.getAsJsonObject().isEmpty();
        }
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (p.isBoolean()) {
            return p.getAsBoolean();
        }
        if (p.isNumber()) {
            return p.getAsDouble() != 0;
        }
        return !p.getAsString().isEmpty();
    }

    public static boolean bool(JsonObject o, String k) {
        return bool(get(o, k));
    }

    public static boolean bool(JsonObject o, String k, boolean def) {
        return has(o, k) ? bool(o.get(k)) : def;
    }

    /** Python str(): strings as they are, numbers as written, null as "None". */
    public static String str(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return "None";
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean() ? "True" : "False";
            }
            return p.getAsString();
        }
        return e.toString();
    }

    public static String str(JsonObject o, String k) {
        return str(o == null ? null : o.get(k));
    }

    public static String str(JsonObject o, String k, String def) {
        JsonElement e = get(o, k);
        return e == null && !has(o, k) ? def : str(o.get(k));
    }

    /** Python int(): numbers truncated, numeric strings parsed, booleans 0/1. */
    public static long lng(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("Zahl fehlt");
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean() ? 1 : 0;
            }
            if (p.isNumber()) {
                try {
                    return new BigDecimal(p.getAsString()).longValue();
                } catch (NumberFormatException ex) {
                    double d = p.getAsDouble();
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        throw new IllegalArgumentException("keine endliche Zahl");
                    }
                    return (long) d;
                }
            }
            try {
                return Long.parseLong(p.getAsString().strip().replace("_", ""));
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid literal for int(): '" + p.getAsString() + "'");
            }
        }
        throw new IllegalArgumentException("keine Zahl: " + e);
    }

    public static int i(JsonElement e) {
        return (int) lng(e);
    }

    public static int i(JsonObject o, String k) {
        if (o == null || !o.has(k)) {
            throw new KeyError(k);
        }
        return i(o.get(k));
    }

    public static int i(JsonObject o, String k, int def) {
        return o == null || !o.has(k) ? def : i(o.get(k));
    }

    /** Python float(). */
    public static double d(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            throw new IllegalArgumentException("Zahl fehlt");
        }
        if (e.isJsonPrimitive()) {
            JsonPrimitive p = e.getAsJsonPrimitive();
            if (p.isBoolean()) {
                return p.getAsBoolean() ? 1 : 0;
            }
            try {
                return Double.parseDouble(p.getAsString().strip());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("could not convert string to float: '" + p.getAsString() + "'");
            }
        }
        throw new IllegalArgumentException("keine Zahl: " + e);
    }

    public static double d(JsonObject o, String k) {
        if (o == null || !o.has(k)) {
            throw new KeyError(k);
        }
        return d(o.get(k));
    }

    public static double d(JsonObject o, String k, double def) {
        return o == null || !o.has(k) ? def : d(o.get(k));
    }

    /** Value or a default when missing / null / falsy ("x or default"). */
    public static JsonElement or(JsonObject o, String k, JsonElement def) {
        JsonElement e = get(o, k);
        return bool(e) ? e : def;
    }

    public static List<JsonElement> list(JsonElement e) {
        List<JsonElement> out = new ArrayList<>();
        JsonArray a = arr(e);
        if (a != null) {
            a.forEach(out::add);
        }
        return out;
    }

    public static boolean isInt(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) {
            return false;
        }
        String s = e.getAsString();
        return !s.contains(".") && !s.contains("e") && !s.contains("E");
    }

    /** Ordered map builder: {@code J.map("a", 1, "b", 2)}. */
    public static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static JsonElement nul() {
        return JsonNull.INSTANCE;
    }

    /** Python round(x, n) for display values (half to even is not worth emulating here). */
    public static double round(double v, int n) {
        double f = Math.pow(10, n);
        return Math.round(v * f) / f;
    }
}
