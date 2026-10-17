package io.github.ehnkode.webmatica;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;

/**
 * Every call into Baritone goes through here. Baritone is optional, so it is only
 * reached by reflection, always through its public API interfaces (the
 * implementations are not public). Without Baritone the throwing methods throw
 * ClassNotFoundException; the others return false or do nothing.
 *
 * Client thread.
 */
final class BaritoneApi {

    private BaritoneApi() {
    }

    private static Object primary() throws Exception {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }

    /** IBaritone getter, e.g. "getCustomGoalProcess". */
    private static Object part(String getter) throws Exception {
        return Class.forName("baritone.api.IBaritone").getMethod(getter).invoke(primary());
    }

    // ------------------------------------------------------------ Commands

    /** Run a command line without "#", as if typed. False if Baritone rejects it. */
    static boolean execute(String line) throws Exception {
        Object cm = part("getCommandManager");
        Method exec = Class.forName("baritone.api.command.manager.ICommandManager").getMethod("execute", String.class);
        return !Boolean.FALSE.equals(exec.invoke(cm, line));
    }

    /** Like {@link #execute}, silently: false also when Baritone is missing. */
    static boolean tryExecute(String line) {
        try {
            return execute(line);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Runs the lines one by one without Baritone's chat echo ("Selection added",
     * "Position 1 has been set", "Filling now"): its logger setting is muted for the
     * duration. Returns the first rejected line, or null if all ran.
     */
    static String executeQuietly(java.util.List<String> lines) {
        Object old = null;
        boolean muted = false;
        try {
            old = get("logger");
            set("logger", (java.util.function.Consumer<Object>) msg -> { });
            muted = true;
        } catch (Throwable ignored) {
            // older Baritone without the logger setting: run with the echo
        }
        try {
            for (String l : lines) {
                if (!tryExecute(l)) {
                    return l;
                }
            }
            return null;
        } finally {
            if (muted) {
                try {
                    set("logger", old);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------ Settings

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

    // ------------------------------------------------------------ Walking

    /** Walk next to the block (close enough to open it). */
    static void walkTo(BlockPos target) throws Exception {
        goal("baritone.api.pathing.goals.GoalGetToBlock", target);
    }

    /** Walk onto the block. */
    static void walkOnto(BlockPos target) throws Exception {
        goal("baritone.api.pathing.goals.GoalBlock", target);
    }

    /** Walk to x/z, any height. */
    static void walkXZ(int x, int z) throws Exception {
        setGoal(Class.forName("baritone.api.pathing.goals.GoalXZ").getConstructor(int.class, int.class).newInstance(x, z));
    }

    private static void goal(String goalClass, BlockPos target) throws Exception {
        setGoal(Class.forName(goalClass).getConstructor(BlockPos.class).newInstance(target));
    }

    private static void setGoal(Object goal) throws Exception {
        Class.forName("baritone.api.process.ICustomGoalProcess")
                .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal"))
                .invoke(part("getCustomGoalProcess"), goal);
    }

    /** Stop whatever Baritone is doing. */
    static void stop() {
        try {
            Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything").invoke(part("getPathingBehavior"));
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------ State

    private static boolean active(String getter) throws Exception {
        return (Boolean) Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(part(getter));
    }

    /** Still on the way to a goal (pathing, or the goal process is active). */
    static boolean walking() throws Exception {
        boolean pathing = (Boolean) Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("isPathing")
                .invoke(part("getPathingBehavior"));
        return pathing || active("getCustomGoalProcess");
    }

    /** The goal process (walkTo/walkOnto) is active; false without Baritone. */
    static boolean goalActive() {
        try {
            return active("getCustomGoalProcess");
        } catch (Throwable t) {
            return false;
        }
    }

    /** The builder (#sel cleararea, #sel fill, #litematica) is active; false without Baritone. */
    static boolean builderActive() {
        try {
            return active("getBuilderProcess");
        } catch (Throwable t) {
            return false;
        }
    }
}
