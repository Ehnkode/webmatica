package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;

/**
 * Protection during auto-build, toggled by the viewer (/api/control "guard"):
 * <ul>
 *   <li>tools: Baritone's itemSaver on so no tool breaks (own settings are restored afterwards)</li>
 *   <li>eat: when hungry, pause Baritone briefly, take food in hand, eat, continue</li>
 * </ul>
 * The class also remembers whether the viewer paused Baritone because of a player;
 * fetching and unpacking then wait instead of running into their time limits.
 */
public final class AutoGuard {

    private static final int HUNGRY = 14;               // eat at 14 of 20 hunger points
    private static final long EAT_TIMEOUT = 4000L;
    private static final Set<String> NO_EAT = Set.of(
            "minecraft:rotten_flesh", "minecraft:spider_eye", "minecraft:poisonous_potato", "minecraft:pufferfish",
            "minecraft:chorus_fruit", "minecraft:suspicious_stew", "minecraft:golden_apple",
            "minecraft:enchanted_golden_apple", "minecraft:raw_chicken", "minecraft:chicken");

    private static volatile boolean paused;
    private static Object[] savedTools;
    private static boolean eating;
    private static boolean pausedForEating;
    private static int prevSlot = -1;
    private static int foodBefore;
    private static long eatStart;
    private static long nextTry;

    private AutoGuard() {
    }

    /** Viewer paused Baritone because of a player (#pause) or let it run again. */
    public static void setPaused(boolean p) {
        paused = p;
    }

    public static boolean paused() {
        return paused;
    }

    public static boolean eating() {
        return eating;
    }

    /** Client thread. */
    public static void tick(Minecraft mc) {
        JsonObject g = BuildControl.getInstance().guard();
        boolean eat = g != null && g.has("eat") && g.get("eat").getAsBoolean();
        boolean tools = g != null && g.has("tools") && g.get("tools").getAsBoolean();
        tools(tools);
        LocalPlayer p = mc.player;
        if (p == null || mc.gameMode == null) {
            if (eating) {
                endEat(mc);
            }
            return;
        }
        if (eating) {
            continueEat(mc, p);
        } else if (eat) {
            maybeEat(mc, p);
        }
    }

    // ------------------------------------------------------------ Tools

    private static void tools(boolean on) {
        try {
            if (on && savedTools == null) {
                savedTools = new Object[] {BaritoneSafety.get("itemSaver"), BaritoneSafety.get("itemSaverThreshold")};
                BaritoneSafety.set("itemSaver", Boolean.TRUE);
                BaritoneSafety.set("itemSaverThreshold", 10);
            } else if (!on && savedTools != null) {
                BaritoneSafety.set("itemSaver", savedTools[0]);
                BaritoneSafety.set("itemSaverThreshold", savedTools[1]);
                savedTools = null;
            }
        } catch (Throwable t) {
            savedTools = on ? new Object[0] : null;     // no Baritone or too old: don't retry every tick
        }
    }

    // ------------------------------------------------------------ Eating

    private static void maybeEat(Minecraft mc, LocalPlayer p) {
        long now = System.currentTimeMillis();
        if (now < nextTry || p.getFoodData().getFoodLevel() > HUNGRY || Mc.screen(mc) != null
                || p.isUsingItem() || FetchTask.getInstance().busy() && !FetchTask.getInstance().walking()
                || UnpackTask.getInstance().busy() || FarmTask.getInstance().handlingChest()) {
            return;
        }
        nextTry = now + 10_000L;
        Inventory inv = p.getInventory();
        int best = -1, bestFood = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getItem(i);
            FoodProperties f = st.isEmpty() ? null : st.get(DataComponents.FOOD);
            if (f == null || NO_EAT.contains(BuiltInRegistries.ITEM.getKey(st.getItem()).toString())) {
                continue;
            }
            int score = f.nutrition() * 2 + (i < 9 ? 1 : 0);
            if (score > bestFood) {
                best = i;
                bestFood = score;
            }
        }
        if (best < 0) {
            say(mc, "Hunger, aber kein Essen im Inventar", ChatFormatting.RED);
            nextTry = now + 60_000L;
            return;
        }
        prevSlot = inv.getSelectedSlot();
        int hot = best;
        if (best >= 9) {
            // swap into a hotbar slot: an empty one, else the currently selected one
            hot = prevSlot;
            for (int i = 0; i < 9; i++) {
                if (inv.getItem(i).isEmpty()) {
                    hot = i;
                    break;
                }
            }
            Mc.swap(mc, p.inventoryMenu.containerId, best, hot, p);
        }
        pausedForEating = !paused && BaritoneBridge.exec("pause");
        inv.setSelectedSlot(hot);
        foodBefore = p.getFoodData().getFoodLevel();
        eatStart = now;
        eating = true;
        mc.options.keyUse.setDown(true);
        mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
    }

    private static void continueEat(Minecraft mc, LocalPlayer p) {
        long age = System.currentTimeMillis() - eatStart;
        if (p.getFoodData().getFoodLevel() > foodBefore || age > EAT_TIMEOUT || Mc.screen(mc) != null
                || age > 600L && !p.isUsingItem()) {
            boolean ok = p.getFoodData().getFoodLevel() > foodBefore;
            endEat(mc);
            if (ok) {
                say(mc, "gegessen (" + p.getFoodData().getFoodLevel() + "/20)", ChatFormatting.GREEN);
                nextTry = System.currentTimeMillis() + 1500L;
            }
            return;
        }
        mc.options.keyUse.setDown(true);
        if (!p.isUsingItem() && age % 1000L < 50L) {
            mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
        }
    }

    private static void endEat(Minecraft mc) {
        eating = false;
        mc.options.keyUse.setDown(false);
        if (mc.player != null && prevSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
        }
        prevSlot = -1;
        if (pausedForEating && !paused) {
            BaritoneBridge.exec("resume");
        }
        pausedForEating = false;
    }

    private static void say(Minecraft mc, String msg, ChatFormatting f) {
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(msg)).withStyle(f));
        }
    }
}
