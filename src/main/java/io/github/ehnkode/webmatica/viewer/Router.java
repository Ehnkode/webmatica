package io.github.ehnkode.webmatica.viewer;

import com.google.gson.JsonElement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps a request path to the function that answers it. Exact paths win over prefixes;
 * prefixes are tried in the order they were added.
 */
public final class Router {
    @FunctionalInterface
    public interface Get {
        void handle(Http h) throws Exception;
    }

    @FunctionalInterface
    public interface Post {
        void handle(Http h, JsonElement req) throws Exception;
    }

    /** A route: GET/raw-POST handlers get only the request, JSON POST handlers also the parsed body. */
    public record Route(Get get, Post post, boolean raw) {
    }

    private final Map<String, Route> exact = new HashMap<>();
    private final List<Object[]> prefix = new ArrayList<>();

    private void add(String method, String path, boolean isPrefix, Route r) {
        if (isPrefix) {
            prefix.add(new Object[] {method, path, r});
        } else {
            exact.put(method + " " + path, r);
        }
    }

    public void get(String path, Get fn) {
        add("GET", path, false, new Route(fn, null, false));
    }

    public void getPrefix(String path, Get fn) {
        add("GET", path, true, new Route(fn, null, false));
    }

    public void post(String path, Post fn) {
        add("POST", path, false, new Route(null, fn, false));
    }

    /** The function reads the body itself (an uploaded file). */
    public void postRaw(String path, Get fn) {
        add("POST", path, false, new Route(fn, null, true));
    }

    public Route find(String method, String path) {
        Route r = exact.get(method + " " + path);
        if (r != null) {
            return r;
        }
        for (Object[] p : prefix) {
            if (p[0].equals(method) && path.startsWith((String) p[1])) {
                return (Route) p[2];
            }
        }
        return null;
    }
}
