package io.github.ehnkode.webmatica;

/**
 * Beim Hinlaufen (goto, Holen) darf Baritone nichts abbauen und nichts setzen,
 * sonst nimmt es den kuerzesten Weg quer durch die Base. Die eigenen Werte von
 * allowBreak/allowPlace werden gemerkt und wieder hergestellt, sobald Baritone
 * fertig ist oder ein Bau-Befehl (Litematica, Freiraeumen) kommt.
 */
public final class BaritoneSafety {

    private static final String[] KEYS = {"allowBreak", "allowPlace"};
    private static Object[] saved;
    private static long since;

    private BaritoneSafety() {
    }

    /** Client-Thread. */
    public static void walkOnly() {
        try {
            if (saved == null) {
                Object[] s = new Object[KEYS.length];
                for (int i = 0; i < KEYS.length; i++) {
                    s[i] = get(KEYS[i]);
                }
                saved = s;
            }
            for (String k : KEYS) {
                set(k, Boolean.FALSE);
            }
            since = System.currentTimeMillis();
        } catch (Throwable ignored) {
            // kein Baritone: nichts zu tun
        }
    }

    /** Client-Thread. Stellt die Werte von vorher wieder her. */
    public static void restore() {
        if (saved == null) {
            return;
        }
        try {
            for (int i = 0; i < KEYS.length; i++) {
                set(KEYS[i], saved[i]);
            }
        } catch (Throwable ignored) {
        }
        saved = null;
    }

    public static void tick() {
        if (saved == null || FetchTask.getInstance().busy() || AutoGuard.paused() || AutoGuard.eating() || System.currentTimeMillis() - since < 3000L) {
            return;
        }
        try {
            Object b = baritone();
            Object pb = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(b);
            boolean pathing = (Boolean) Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("isPathing").invoke(pb);
            Object proc = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(b);
            boolean active = (Boolean) Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(proc);
            if (!pathing && !active) {
                restore();
            }
        } catch (Throwable t) {
            restore();
        }
    }

    private static Object baritone() throws Exception {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static Object setting(String key) throws Exception {
        Object settings = Class.forName("baritone.api.BaritoneAPI").getMethod("getSettings").invoke(null);
        return settings.getClass().getField(key).get(settings);
    }

    static Object get(String key) throws Exception {
        Object s = setting(key);
        return s.getClass().getField("value").get(s);
    }

    static void set(String key, Object v) throws Exception {
        Object s = setting(key);
        s.getClass().getField("value").set(s, v);
    }
}
