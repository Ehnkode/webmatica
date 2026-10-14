package io.github.ehnkode.webmatica;

/**
 * While walking (goto, fetching) Baritone must not break or place anything,
 * otherwise it takes the shortest path straight through the base. The user's own
 * allowBreak/allowPlace values are saved and restored as soon as Baritone is
 * done or a build command (Litematica, clearing) arrives.
 * The viewer option "dig" (guard switch) lifts this: then Baritone may dig on
 * the way, e.g. when you are far away from your own base.
 */
public final class BaritoneSafety {

    private static final String[] KEYS = {"allowBreak", "allowPlace"};
    private static Object[] saved;
    private static long since;

    private BaritoneSafety() {
    }

    /** Client thread. */
    public static void walkOnly() {
        if (digAllowed()) {
            return;
        }
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
            // no Baritone: nothing to do
        }
    }

    /** Client thread. Restores the previous values. */
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

    /** The viewer's "Baritone may dig on the way" switch (off when the viewer doesn't answer). */
    private static boolean digAllowed() {
        com.google.gson.JsonObject g = BuildControl.getInstance().guard();
        return g != null && g.has("dig") && g.get("dig").getAsBoolean();
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
