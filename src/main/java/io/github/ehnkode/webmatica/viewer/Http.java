package io.github.ehnkode.webmatica.viewer;

import io.github.ehnkode.webmatica.viewer.core.KeyError;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One request: path, query, body and the ways to answer it. */
public final class Http {
    public final HttpExchange ex;
    public final App app;
    public final String method;
    /** Raw (still percent-encoded) path. */
    public final String path;
    private final Map<String, List<String>> query;
    private boolean answered;

    Http(HttpExchange ex, App app) {
        this.ex = ex;
        this.app = app;
        this.method = ex.getRequestMethod();
        this.path = ex.getRequestURI().getRawPath();
        this.query = parseQuery(ex.getRequestURI().getRawQuery());
    }

    /** Thrown for "not found" answers inside a route (Python's LookupError). */
    public static final class NotFound extends io.github.ehnkode.webmatica.viewer.core.LookupError {
        public NotFound(String msg) {
            super(msg);
        }
    }

    /** Answers with a status and message, from anywhere inside a route. */
    public static final class Fail extends RuntimeException {
        public final int code;

        public Fail(int code, String msg) {
            super(msg);
            this.code = code;
        }
    }

    // ------------------------------------------------------------------ request

    /** Python's unquote: percent escapes only, '+' stays. */
    public static String unquote(String s) {
        if (s.indexOf('%') < 0) {
            return s;
        }
        byte[] in = s.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(in.length);
        for (int i = 0; i < in.length; i++) {
            if (in[i] == '%' && i + 2 < in.length && hex((char) in[i + 1]) >= 0 && hex((char) in[i + 2]) >= 0) {
                out.write(hex((char) in[i + 1]) * 16 + hex((char) in[i + 2]));
                i += 2;
            } else {
                out.write(in[i]);
            }
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static int hex(char c) {
        return Character.digit(c, 16);
    }

    /** Like parse_qs: blank values are dropped, '+' is a space. */
    static Map<String, List<String>> parseQuery(String raw) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String part : raw.split("[&;]")) {
            if (part.isEmpty()) {
                continue;
            }
            int i = part.indexOf('=');
            if (i < 0) {
                continue;
            }
            String k = unquote(part.substring(0, i).replace('+', ' '));
            String v = unquote(part.substring(i + 1).replace('+', ' '));
            if (v.isEmpty()) {
                continue;
            }
            out.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
        return out;
    }

    public boolean has(String k) {
        return query.containsKey(k);
    }

    /** First value of a query parameter, or null. */
    public String q(String k) {
        List<String> v = query.get(k);
        return v == null ? null : v.get(0);
    }

    public String q(String k, String def) {
        String v = q(k);
        return v == null ? def : v;
    }

    /** Required parameter (KeyError -> 400). */
    public String need(String k) {
        String v = q(k);
        if (v == null) {
            throw new KeyError(k);
        }
        return v;
    }

    public int qi(String k) {
        return pyInt(need(k));
    }

    public int qi(String k, int def) {
        String v = q(k);
        return v == null ? def : pyInt(v);
    }

    /** int() of a string the way Python accepts it. */
    public static int pyInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid literal for int() with base 10: '" + s + "'");
        }
    }

    public static double pyFloat(String s) {
        try {
            return Double.parseDouble(s.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("could not convert string to float: '" + s + "'");
        }
    }

    /** /api/<what>/<a>/<b>/... -> [a, b, ...] (unquoted) */
    public List<String> parts() {
        String[] s = path.split("/", -1);
        List<String> out = new ArrayList<>();
        for (int i = 3; i < s.length; i++) {
            out.add(unquote(s[i]));
        }
        return out;
    }

    public String header(String k) {
        return ex.getRequestHeaders().getFirst(k);
    }

    public byte[] bodyBytes(int max) throws IOException {
        String cl = header("Content-Length");
        int n = cl == null ? 0 : Integer.parseInt(cl.strip());
        if (n > max) {
            throw new IllegalArgumentException("zu gross");
        }
        return ex.getRequestBody().readNBytes(n);
    }

    JsonElement bodyJson() throws IOException {
        byte[] b = bodyBytes(4 * 1024 * 1024);
        if (b.length == 0) {
            return new JsonObject();
        }
        return J.parse(new String(b, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------------ answers

    public boolean answered() {
        return answered;
    }

    public void send(int code, byte[] body, String ctype, String cache, Map<String, String> extra) {
        answered = true;
        try {
            var h = ex.getResponseHeaders();
            h.set("Content-Type", ctype);
            h.set("Cache-Control", cache);
            if (extra != null) {
                extra.forEach(h::set);
            }
            boolean noBody = code == 204 || code == 304 || method.equals("HEAD");
            if (noBody) {
                if (code != 204 && code != 304) {
                    h.set("Content-Length", Integer.toString(body.length));
                }
                ex.sendResponseHeaders(code, -1);
            } else {
                ex.sendResponseHeaders(code, body.length == 0 ? -1 : body.length);
                if (body.length > 0) {
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                }
            }
        } catch (IOException e) {
            // the browser cancelled the request
        } finally {
            ex.close();
        }
    }

    public void send(int code, byte[] body, String ctype, String cache) {
        send(code, body, ctype, cache, null);
    }

    public void send(int code, byte[] body, String ctype) {
        send(code, body, ctype, "no-store", null);
    }

    public void json(Object obj, int code) {
        send(code, J.dump(obj).getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    public void json(Object obj) {
        json(obj, 200);
    }

    public void fail(int code, String msg) {
        json(J.map("error", msg), code);
    }

    /** Answers 304 when the browser already has this version. */
    public boolean notModified(String etag, String ctype) {
        if (!etag.equals(header("If-None-Match"))) {
            return false;
        }
        send(304, new byte[0], ctype, "no-cache", Map.of("ETag", etag));
        return true;
    }

    public boolean notModified(String etag) {
        return notModified(etag, "application/octet-stream");
    }

    /** Server and dimension from ?srv=&dim=, or null. */
    public io.github.ehnkode.webmatica.viewer.core.Recording.Dimension dimFrom() {
        return app.rec.dimension(q("srv", ""), q("dim", ""));
    }
}
