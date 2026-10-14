package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * "Fetch" from the web page: Baritone walks to the chest, the mod opens it and
 * shift-clicks the requested stacks (specific slots or items with a
 * count), then the chest is closed again.
 *
 * Slowly paced (one click every few ticks) so the server doesn't complain.
 * "Stop" on the web page aborts.
 */
public final class FetchTask {

    private static final FetchTask INSTANCE = new FetchTask();
    private static final double REACH = 4.2;
    private static final int CLICK_EVERY = 3;

    public static FetchTask getInstance() {
        return INSTANCE;
    }

    private enum Phase { IDLE, WALK, OPEN, TAKE }

    private Phase phase = Phase.IDLE;
    private String id;
    private BlockPos pos;
    private final Set<Integer> wantSlots = new HashSet<>();
    // Storing: player inventory index -> item that should go there
    private final Map<Integer, String> storeSlots = new LinkedHashMap<>();
    private boolean store;
    private Slot storeLast;
    private String storeLastId;
    private final Map<String, Integer> wantItems = new LinkedHashMap<>();
    private long phaseStart;
    private int tick;
    private int taken;
    private int openTries;
    private boolean noShulker;   // auto-build: Baritone can't build from shulkers
    private AbstractContainerMenu opened;
    private final List<String> notes = new ArrayList<>();

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Client thread. */
    public void start(String cmdId, JsonObject cmd) {
        if (busy()) {
            finish(false, "abgebrochen, neuer Auftrag");
        }
        id = cmdId;
        pos = new BlockPos(cmd.get("x").getAsInt(), cmd.get("y").getAsInt(), cmd.get("z").getAsInt());
        wantSlots.clear();
        storeSlots.clear();
        storeLast = null;
        store = "store".equals(cmd.has("kind") ? cmd.get("kind").getAsString() : "");
        if (store) {
            for (JsonElement e : cmd.getAsJsonArray("put")) {
                JsonObject it = e.getAsJsonObject();
                storeSlots.put(it.get("slot").getAsInt(), it.get("id").getAsString());
            }
        }
        wantItems.clear();
        notes.clear();
        if (cmd.has("slots")) {
            for (JsonElement e : cmd.getAsJsonArray("slots")) {
                wantSlots.add(e.getAsInt());
            }
        }
        if (cmd.has("items")) {
            for (JsonElement e : cmd.getAsJsonArray("items")) {
                JsonObject it = e.getAsJsonObject();
                wantItems.merge(it.get("id").getAsString(), it.has("count") ? it.get("count").getAsInt() : 64, Integer::sum);
            }
        }
        noShulker = cmd.has("noShulker") && cmd.get("noShulker").getAsBoolean();
        taken = 0;
        openTries = 0;
        opened = null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && near(mc.player)) {
            setPhase(Phase.OPEN);
        } else {
            try {
                BaritoneSafety.walkOnly();
                walkTo(pos);
                setPhase(Phase.WALK);
                actionbar(mc, "läuft zur Kiste bei " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), ChatFormatting.AQUA);
            } catch (ClassNotFoundException e) {
                finish(false, "Baritone nicht gefunden");
            } catch (Throwable t) {
                Throwable c = t.getCause() != null ? t.getCause() : t;
                finish(false, "Baritone: " + c.getClass().getSimpleName() + ": " + c.getMessage());
            }
        }
    }

    public void cancel() {
        if (busy()) {
            finish(false, "gestoppt");
        }
    }

    private long frozenAt;

    /** On the way to the chest (nothing open yet): eating may happen in between. */
    public boolean walking() {
        return phase == Phase.WALK;
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseStart = System.currentTimeMillis();
    }

    private boolean near(LocalPlayer p) {
        return p.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= REACH;
    }

    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) {
            return;
        }
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || mc.gameMode == null) {
            finish(false, "nicht mehr im Spiel");
            return;
        }
        if (AutoGuard.paused() || AutoGuard.eating()) {
            // player nearby or eating: wait without the time limits running on
            if (frozenAt == 0L) {
                frozenAt = System.currentTimeMillis();
            }
            return;
        }
        if (frozenAt != 0L) {
            phaseStart += System.currentTimeMillis() - frozenAt;
            frozenAt = 0L;
        }
        long age = System.currentTimeMillis() - phaseStart;
        tick++;
        switch (phase) {
            case WALK -> {
                if (near(p)) {
                    stopBaritone();
                    setPhase(Phase.OPEN);
                } else if (age > 180_000L) {
                    stopBaritone();
                    finish(false, "Kiste nicht erreicht (3 min)");
                } else if (age > 3000L && !baritoneActive()) {
                    // Baritone is done but too far away: try once more, then give up
                    if (openTries++ < 2) {
                        try {
                            walkTo(pos);
                        } catch (Throwable ignored) {
                        }
                        phaseStart = System.currentTimeMillis() - 1000L;
                    } else {
                        finish(false, "Baritone findet keinen Weg zur Kiste, ohne etwas abzubauen");
                    }
                }
            }
            case OPEN -> {
                AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
                if (h != null && h != p.inventoryMenu) {
                    opened = h;
                    setPhase(Phase.TAKE);
                    return;
                }
                if (age > 6000L) {
                    finish(false, "Kiste geht nicht auf");
                    return;
                }
                if (tick % 20 == 1) {
                    Vec3 c = Vec3.atCenterOf(pos);
                    Direction side = Direction.getApproximateNearest(p.getEyePosition().x - c.x, p.getEyePosition().y - c.y, p.getEyePosition().z - c.z);
                    BlockHitResult hit = new BlockHitResult(c.add(side.getStepX() * 0.5, side.getStepY() * 0.5, side.getStepZ() * 0.5), side, pos, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
            }
            case TAKE -> {
                AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
                if (h != opened) {
                    finish(taken > 0, taken > 0 ? "Kiste wurde geschlossen" : "Kiste wurde geschlossen, nichts genommen");
                    return;
                }
                if (age < 400L || tick % CLICK_EVERY != 0) {
                    return;   // contents arrive a moment after opening
                }
                if (store) {
                    storeStep(mc, p, h);
                    return;
                }
                Slot next = nextSlot(h);
                if (next == null) {
                    p.closeContainer();
                    finish(true, null);
                    return;
                }
                if (p.getInventory().getFreeSlot() < 0 && !fitsSomewhere(p, next.getItem())) {
                    p.closeContainer();
                    finish(taken > 0, "Inventar voll");
                    return;
                }
                ItemStack before = next.getItem().copy();
                Mc.quickMove(mc, h.containerId, next.index, p);
                // The client applies the move right away: what is left in the slot did not fit
                ItemStack after = next.getItem();
                int moved = before.getCount() - (ItemStack.isSameItemSameComponents(after, before) ? after.getCount() : 0);
                wantSlots.remove(next.getContainerSlot());
                if (moved <= 0) {
                    p.closeContainer();
                    finish(taken > 0, "Inventar voll");
                    return;
                }
                taken += moved;
                String iid = BuiltInRegistries.ITEM.getKey(before.getItem()).toString();
                Integer left = wantItems.get(iid);
                if (left != null) {
                    wantItems.put(iid, left - moved);
                } else {
                    // took a shulker containing the wanted item
                    ItemContainerContents inner = before.get(DataComponents.CONTAINER);
                    if (inner != null) {
                        for (ItemStack c : Mc.items(inner)) {
                            String cid = BuiltInRegistries.ITEM.getKey(c.getItem()).toString();
                            if (wantItems.containsKey(cid)) {
                                wantItems.put(cid, wantItems.get(cid) - c.getCount());
                            }
                        }
                    }
                }
                if (age > 60_000L) {
                    p.closeContainer();
                    finish(taken > 0, "Zeit abgelaufen");
                }
            }
            default -> {
            }
        }
    }

    private static boolean fitsSomewhere(LocalPlayer p, ItemStack st) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack s = inv.getItem(i);
            if (ItemStack.isSameItemSameComponents(s, st) && s.getCount() < s.getMaxStackSize()) {
                return true;
            }
        }
        return false;
    }

    /** Storing: one stack per call from the inventory into the chest. */
    private void storeStep(Minecraft mc, LocalPlayer p, AbstractContainerMenu h) {
        if (storeLast != null && storeLast.hasItem()
                && BuiltInRegistries.ITEM.getKey(storeLast.getItem().getItem()).toString().equals(storeLastId)) {
            p.closeContainer();
            finish(taken > 0, "Kiste voll");
            return;
        }
        storeLast = null;
        for (Slot s : h.slots) {
            if (!(s.container instanceof Inventory) || !s.hasItem()) {
                continue;
            }
            String want = storeSlots.get(s.getContainerSlot());
            String iid = BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString();
            if (want == null || !want.equals(iid)) {
                continue;
            }
            storeSlots.remove(s.getContainerSlot());
            int n = s.getItem().getCount();
            Mc.quickMove(mc, h.containerId, s.index, p);
            taken += n;
            storeLast = s;
            storeLastId = iid;
            return;
        }
        p.closeContainer();
        finish(true, null);
    }

    /** Next chest slot to take. */
    private Slot nextSlot(AbstractContainerMenu h) {
        Slot shulkerFallback = null;
        for (Slot s : h.slots) {
            if (s.container instanceof Inventory || !s.hasItem()) {
                continue;
            }
            if (wantSlots.contains(s.getContainerSlot())) {
                return s;
            }
            if (wantItems.isEmpty()) {
                continue;
            }
            String iid = BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString();
            Integer left = wantItems.get(iid);
            if (left != null && left > 0) {
                return s;
            }
            if (shulkerFallback == null && !noShulker) {
                ItemContainerContents inner = s.getItem().get(DataComponents.CONTAINER);
                if (inner != null) {
                    for (ItemStack c : Mc.items(inner)) {
                        Integer l = wantItems.get(BuiltInRegistries.ITEM.getKey(c.getItem()).toString());
                        if (l != null && l > 0) {
                            shulkerFallback = s;
                            break;
                        }
                    }
                }
            }
        }
        return shulkerFallback;
    }

    private void finish(boolean ok, String why) {
        Minecraft mc = Minecraft.getInstance();
        StringBuilder msg = new StringBuilder();
        String verb = store ? "eingelagert" : "genommen";
        msg.append(taken > 0 ? taken + " Items " + verb : "nichts " + verb);
        int missing = 0;
        for (int v : wantItems.values()) {
            missing += Math.max(0, v);
        }
        if (missing > 0 && taken > 0) {
            msg.append(", ").append(missing).append(" fehlen noch");
        }
        if (why != null) {
            msg.append(" (").append(why).append(")");
        }
        String what = store ? "Einlagern: " : "Holen: ";
        actionbar(mc, what + msg, ok ? ChatFormatting.GREEN : ChatFormatting.RED);
        BaritoneBridge.report(id, ok, what + msg);
        phase = Phase.IDLE;
        opened = null;
        BaritoneSafety.restore();
    }

    private static void actionbar(Minecraft mc, String s, ChatFormatting f) {
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(s)).withStyle(f));
        }
    }

    // ------------------------------------------------------------ Baritone (API via reflection)

    private static Object baritone() throws Exception {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }

    static void walkTo(BlockPos target) throws Exception {
        Object b = baritone();
        Object goal = Class.forName("baritone.api.pathing.goals.GoalGetToBlock").getConstructor(BlockPos.class).newInstance(target);
        Object proc = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(b);
        Class.forName("baritone.api.process.ICustomGoalProcess")
                .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal")).invoke(proc, goal);
    }

    private static boolean baritoneActive() {
        try {
            Object b = baritone();
            Object proc = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(b);
            return (Boolean) Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(proc);
        } catch (Throwable t) {
            return false;
        }
    }

    static void stopBaritone() {
        try {
            Object b = baritone();
            Object pb = Class.forName("baritone.api.IBaritone").getMethod("getPathingBehavior").invoke(b);
            Class.forName("baritone.api.behavior.IPathingBehavior").getMethod("cancelEverything").invoke(pb);
        } catch (Throwable ignored) {
        }
    }
}
