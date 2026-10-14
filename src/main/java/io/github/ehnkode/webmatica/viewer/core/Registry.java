package io.github.ehnkode.webmatica.viewer.core;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

/** Thread-safe string -> int interner. Readers see a consistent name array without locking. */
public final class Registry {
    private final ConcurrentHashMap<String, Integer> ids = new ConcurrentHashMap<>();
    private volatile String[] names = new String[0];
    private int size;

    public Registry(String first) {
        id(first);
    }

    public int id(String key) {
        Integer i = ids.get(key);
        if (i != null) {
            return i;
        }
        synchronized (this) {
            i = ids.get(key);
            if (i != null) {
                return i;
            }
            String[] n = names;
            if (size == n.length) {
                n = Arrays.copyOf(n, Math.max(64, n.length * 2));
            }
            n[size] = key;
            names = n;           // publish the array before the id becomes visible
            ids.put(key, size);
            return size++;
        }
    }

    public String name(int id) {
        return names[id];
    }

    public int size() {
        synchronized (this) {
            return size;
        }
    }

    /** Snapshot of the names known right now (index = id). */
    public String[] names() {
        synchronized (this) {
            return Arrays.copyOf(names, size);
        }
    }
}
