package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;

import java.util.Set;

/**
 * Schutz waehrend des Autobaus, vom Viewer ein- und ausgeschaltet (/api/control "guard"):
 * <ul>
 *   <li>tools: Baritones itemSaver an, damit kein Werkzeug kaputtgeht (eigene Werte kommen danach zurueck)</li>
 *   <li>eat: bei Hunger Baritone kurz anhalten, Essen in die Hand nehmen, essen, weiter</li>
 * </ul>
 * Ausserdem merkt sich die Klasse, ob der Viewer Baritone wegen eines Spielers pausiert hat;
 * Holen und Auspacken warten dann, statt in ihre Zeitgrenzen zu laufen.
 */
public final class AutoGuard {

    private static final int HUNGRY = 14;               // ab 14 von 20 Hungerpunkten essen
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

    /** Viewer hat Baritone wegen eines Spielers angehalten (#pause) bzw. laufen lassen. */
    public static void setPaused(boolean p) {
        paused = p;
    }

    public static boolean paused() {
        return paused;
    }

    public static boolean eating() {
        return eating;
    }

    /** Client-Thread. */
    public static void tick(MinecraftClient mc) {
        JsonObject g = BuildControl.getInstance().guard();
        boolean eat = g != null && g.has("eat") && g.get("eat").getAsBoolean();
        boolean tools = g != null && g.has("tools") && g.get("tools").getAsBoolean();
        tools(tools);
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.interactionManager == null) {
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

    // ------------------------------------------------------------ Werkzeug

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
            savedTools = on ? new Object[0] : null;     // kein Baritone oder zu alt: nicht jeden Tick neu versuchen
        }
    }

    // ------------------------------------------------------------ Essen

    private static void maybeEat(MinecraftClient mc, ClientPlayerEntity p) {
        long now = System.currentTimeMillis();
        if (now < nextTry || p.getHungerManager().getFoodLevel() > HUNGRY || mc.currentScreen != null
                || p.isUsingItem() || FetchTask.getInstance().busy() && !FetchTask.getInstance().walking()
                || UnpackTask.getInstance().busy() || FarmTask.getInstance().handlingChest()) {
            return;
        }
        nextTry = now + 10_000L;
        PlayerInventory inv = p.getInventory();
        int best = -1, bestFood = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            FoodComponent f = st.isEmpty() ? null : st.get(DataComponentTypes.FOOD);
            if (f == null || NO_EAT.contains(Registries.ITEM.getId(st.getItem()).toString())) {
                continue;
            }
            int score = f.nutrition() * 2 + (i < 9 ? 1 : 0);
            if (score > bestFood) {
                best = i;
                bestFood = score;
            }
        }
        if (best < 0) {
            say(mc, "Hunger, aber kein Essen im Inventar", Formatting.RED);
            nextTry = now + 60_000L;
            return;
        }
        prevSlot = inv.getSelectedSlot();
        int hot = best;
        if (best >= 9) {
            // ins Hotbar-Fach tauschen: ein leeres, sonst das gerade gewaehlte
            hot = prevSlot;
            for (int i = 0; i < 9; i++) {
                if (inv.getStack(i).isEmpty()) {
                    hot = i;
                    break;
                }
            }
            mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, best, hot, SlotActionType.SWAP, p);
        }
        pausedForEating = !paused && BaritoneBridge.exec("pause");
        inv.setSelectedSlot(hot);
        foodBefore = p.getHungerManager().getFoodLevel();
        eatStart = now;
        eating = true;
        mc.options.useKey.setPressed(true);
        mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
    }

    private static void continueEat(MinecraftClient mc, ClientPlayerEntity p) {
        long age = System.currentTimeMillis() - eatStart;
        if (p.getHungerManager().getFoodLevel() > foodBefore || age > EAT_TIMEOUT || mc.currentScreen != null
                || age > 600L && !p.isUsingItem()) {
            boolean ok = p.getHungerManager().getFoodLevel() > foodBefore;
            endEat(mc);
            if (ok) {
                say(mc, "gegessen (" + p.getHungerManager().getFoodLevel() + "/20)", Formatting.GREEN);
                nextTry = System.currentTimeMillis() + 1500L;
            }
            return;
        }
        mc.options.useKey.setPressed(true);
        if (!p.isUsingItem() && age % 1000L < 50L) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        }
    }

    private static void endEat(MinecraftClient mc) {
        eating = false;
        mc.options.useKey.setPressed(false);
        if (mc.player != null && prevSlot >= 0) {
            mc.player.getInventory().setSelectedSlot(prevSlot);
        }
        prevSlot = -1;
        if (pausedForEating && !paused) {
            BaritoneBridge.exec("resume");
        }
        pausedForEating = false;
    }

    private static void say(MinecraftClient mc, String msg, Formatting f) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Webmatica] " + Lang.tr(msg)).formatted(f), true);
        }
    }
}
