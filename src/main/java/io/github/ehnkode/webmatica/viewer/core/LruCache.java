package io.github.ehnkode.webmatica.viewer.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Small thread-safe LRU map. */
public final class LruCache<K, V> {
    private final LinkedHashMap<K, V> map;

    public LruCache(int maxItems) {
        map = new LinkedHashMap<>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> e) {
                return size() > maxItems;
            }
        };
    }

    public synchronized V get(K key) {
        return map.get(key);
    }

    public synchronized void put(K key, V value) {
        map.put(key, value);
    }

    public synchronized void clear() {
        map.clear();
    }
}
