package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private ScreenHandler opened;
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
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && near(mc.player)) {
            setPhase(Phase.OPEN);
        } else {
            try {
                BaritoneSafety.walkOnly();
                walkTo(pos);
                setPhase(Phase.WALK);
                actionbar(mc, "läuft zur Kiste bei " + pos.getX() + " " + pos.getY() + " " + pos.getZ(), Formatting.AQUA);
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

    private boolean near(ClientPlayerEntity p) {
        return p.getEyePos().distanceTo(Vec3d.ofCenter(pos)) <= REACH;
    }

    public void tick(MinecraftClient mc) {
        if (phase == Phase.IDLE) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        if (p == null || mc.world == null || mc.interactionManager == null) {
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
                ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
                if (h != null && h != p.playerScreenHandler) {
                    opened = h;
                    setPhase(Phase.TAKE);
                    return;
                }
                if (age > 6000L) {
                    finish(false, "Kiste geht nicht auf");
                    return;
                }
                if (tick % 20 == 1) {
                    Vec3d c = Vec3d.ofCenter(pos);
                    Direction side = Direction.getFacing(p.getEyePos().x - c.x, p.getEyePos().y - c.y, p.getEyePos().z - c.z);
                    BlockHitResult hit = new BlockHitResult(c.add(side.getOffsetX() * 0.5, side.getOffsetY() * 0.5, side.getOffsetZ() * 0.5), side, pos, false);
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                }
            }
            case TAKE -> {
                ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
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
                    p.closeHandledScreen();
                    finish(true, null);
                    return;
                }
                if (p.getInventory().getEmptySlot() < 0 && !fitsSomewhere(p, next.getStack())) {
                    p.closeHandledScreen();
                    finish(taken > 0, "Inventar voll");
                    return;
                }
                ItemStack before = next.getStack().copy();
                mc.interactionManager.clickSlot(h.syncId, next.id, 0, SlotActionType.QUICK_MOVE, p);
                taken += before.getCount();
                wantSlots.remove(next.getIndex());
                String iid = Registries.ITEM.getId(before.getItem()).toString();
                Integer left = wantItems.get(iid);
                if (left != null) {
                    wantItems.put(iid, left - before.getCount());
                } else {
                    // took a shulker containing the wanted item
                    ContainerComponent inner = before.get(DataComponentTypes.CONTAINER);
                    if (inner != null) {
                        for (ItemStack c : inner.iterateNonEmpty()) {
                            String cid = Registries.ITEM.getId(c.getItem()).toString();
                            if (wantItems.containsKey(cid)) {
                                wantItems.put(cid, wantItems.get(cid) - c.getCount());
                            }
                        }
                    }
                }
                if (age > 60_000L) {
                    p.closeHandledScreen();
                    finish(taken > 0, "Zeit abgelaufen");
                }
            }
            default -> {
            }
        }
    }

    private static boolean fitsSomewhere(ClientPlayerEntity p, ItemStack st) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack s = inv.getStack(i);
            if (ItemStack.areItemsAndComponentsEqual(s, st) && s.getCount() < s.getMaxCount()) {
                return true;
            }
        }
        return false;
    }

    /** Storing: one stack per call from the inventory into the chest. */
    private void storeStep(MinecraftClient mc, ClientPlayerEntity p, ScreenHandler h) {
        if (storeLast != null && storeLast.hasStack()
                && Registries.ITEM.getId(storeLast.getStack().getItem()).toString().equals(storeLastId)) {
            p.closeHandledScreen();
            finish(taken > 0, "Kiste voll");
            return;
        }
        storeLast = null;
        for (Slot s : h.slots) {
            if (!(s.inventory instanceof PlayerInventory) || !s.hasStack()) {
                continue;
            }
            String want = storeSlots.get(s.getIndex());
            String iid = Registries.ITEM.getId(s.getStack().getItem()).toString();
            if (want == null || !want.equals(iid)) {
                continue;
            }
            storeSlots.remove(s.getIndex());
            int n = s.getStack().getCount();
            mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
            taken += n;
            storeLast = s;
            storeLastId = iid;
            return;
        }
        p.closeHandledScreen();
        finish(true, null);
    }

    /** Next chest slot to take. */
    private Slot nextSlot(ScreenHandler h) {
        Slot shulkerFallback = null;
        for (Slot s : h.slots) {
            if (s.inventory instanceof PlayerInventory || !s.hasStack()) {
                continue;
            }
            if (wantSlots.contains(s.getIndex())) {
                return s;
            }
            if (wantItems.isEmpty()) {
                continue;
            }
            String iid = Registries.ITEM.getId(s.getStack().getItem()).toString();
            Integer left = wantItems.get(iid);
            if (left != null && left > 0) {
                return s;
            }
            if (shulkerFallback == null && !noShulker) {
                ContainerComponent inner = s.getStack().get(DataComponentTypes.CONTAINER);
                if (inner != null) {
                    for (ItemStack c : inner.iterateNonEmpty()) {
                        Integer l = wantItems.get(Registries.ITEM.getId(c.getItem()).toString());
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
        MinecraftClient mc = MinecraftClient.getInstance();
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
        actionbar(mc, what + msg, ok ? Formatting.GREEN : Formatting.RED);
        BaritoneBridge.report(id, ok, what + msg);
        phase = Phase.IDLE;
        opened = null;
        BaritoneSafety.restore();
    }

    private static void actionbar(MinecraftClient mc, String s, Formatting f) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Webmatica] " + Lang.tr(s)).formatted(f), true);
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
