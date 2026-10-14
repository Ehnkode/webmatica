package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Break and collect specific blocks (farms, wrong blocks of a
 * schematic) and pack full inventories into a self-placed chest.
 *
 * Breaking: each box becomes a Baritone selection, "#sel cleararea" breaks exactly
 * these blocks. Then the mod walks to the dropped items.
 * With "fill", "#sel fill" places the block into the boxes instead (filling holes).
 * Chest: place a chest from the inventory next to you, put the given items
 * in and leave it (the viewer remembers it like any other chest).
 */
public final class FarmTask {

    private static final FarmTask INSTANCE = new FarmTask();
    private static final int CLICK_EVERY = 3;

    public static FarmTask getInstance() {
        return INSTANCE;
    }

    private enum Phase { IDLE, BREAK, PICKUP, PLACE, OPEN, PUT }

    private Phase phase = Phase.IDLE;
    private String id;
    private String label;
    private final Set<String> ids = new HashSet<>();
    private final Set<Integer> slots = new HashSet<>();    // chest: only these inventory slots
    private AABB area;
    private boolean pickup;
    private String fill;
    private long phaseStart;
    private long breakLimit;
    private long frozenAt;
    private int tick;
    private int before;
    private int count;
    private int tries;
    private BlockPos placed;
    private BlockPos walkTarget;
    private AbstractContainerMenu opened;
    private final Set<Integer> skipped = new HashSet<>();
    private final Set<Integer> skippedDrops = new HashSet<>();

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Currently placing or filling a chest: don't eat then. */
    public boolean handlingChest() {
        return phase == Phase.PLACE || phase == Phase.OPEN || phase == Phase.PUT;
    }

    /** Client thread. kind "breakboxes" or "chestdump". */
    public void start(String cmdId, JsonObject cmd) {
        if (busy()) {
            finish(false, "abgebrochen, neuer Auftrag");
        }
        id = cmdId;
        ids.clear();
        if (cmd.has("ids")) {
            for (JsonElement e : cmd.getAsJsonArray("ids")) {
                ids.add(e.getAsString());
            }
        }
        slots.clear();
        if (cmd.has("slots")) {
            for (JsonElement e : cmd.getAsJsonArray("slots")) {
                slots.add(e.getAsInt());
            }
        }
        count = 0;
        tries = 0;
        placed = null;
        opened = null;
        skipped.clear();
        skippedDrops.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            id = cmdId;
            finish(false, "nicht im Spiel");
            return;
        }
        if ("chestdump".equals(cmd.get("kind").getAsString())) {
            label = "Kiste";
            setPhase(Phase.PLACE);
            return;
        }
        label = cmd.has("label") ? cmd.get("label").getAsString() : "Abbauen";
        fill = cmd.has("fill") ? cmd.get("fill").getAsString() : null;
        pickup = fill == null && (!cmd.has("pickup") || cmd.get("pickup").getAsBoolean());
        breakLimit = cmd.has("timeout") ? cmd.get("timeout").getAsLong() * 1000L : 300_000L;
        JsonArray boxes = cmd.getAsJsonArray("boxes");
        List<String> lines = new ArrayList<>();
        lines.add("sel clear");
        area = null;
        for (JsonElement e : boxes) {
            JsonArray b = e.getAsJsonArray();
            int x0 = b.get(0).getAsInt(), y0 = b.get(1).getAsInt(), z0 = b.get(2).getAsInt();
            int x1 = b.get(3).getAsInt(), y1 = b.get(4).getAsInt(), z1 = b.get(5).getAsInt();
            lines.add("sel pos1 " + x0 + " " + y0 + " " + z0);
            lines.add("sel pos2 " + x1 + " " + y1 + " " + z1);
            AABB bb = new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
            area = area == null ? bb : area.minmax(bb);
        }
        if (area == null) {
            finish(true, "nichts zu tun");
            return;
        }
        lines.add(fill != null ? "sel fill " + fill : "sel cleararea");
        BaritoneSafety.restore();
        for (String l : lines) {
            if (!BaritoneBridge.exec(l)) {
                finish(false, "Baritone hat \"" + l + "\" abgelehnt");
                return;
            }
        }
        before = countIds(mc.player);
        setPhase(Phase.BREAK);
        actionbar(mc, label + (fill != null ? ": füllt " : ": baut ") + boxes.size() + " Bereiche" + (fill != null ? " auf" : " ab"), ChatFormatting.AQUA);
    }

    public void cancel() {
        if (busy()) {
            finish(false, "gestoppt");
        }
    }

    private void setPhase(Phase p) {
        phase = p;
        phaseStart = System.currentTimeMillis();
    }

    public void tick(Minecraft mc) {
        if (phase == Phase.IDLE) {
            return;
        }
        LocalPlayer p = mc.player;
        ClientLevel w = mc.level;
        if (p == null || w == null || mc.gameMode == null) {
            finish(false, "nicht mehr im Spiel");
            return;
        }
        if (AutoGuard.paused() || AutoGuard.eating()) {
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
            case BREAK -> {
                if (age > 2500L && !builderActive()) {
                    walkTarget = null;
                    if (pickup) {
                        setPhase(Phase.PICKUP);
                    } else {
                        finish(true, null);
                    }
                } else if (age > breakLimit) {
                    FetchTask.stopBaritone();
                    finish(countIds(p) > before, "Zeit abgelaufen");
                }
            }
            case PICKUP -> {
                if (tick % 10 != 0) {
                    return;
                }
                ItemEntity next = nearestDrop(p, w);
                if (next == null || age > 90_000L || p.getInventory().getFreeSlot() < 0) {
                    FetchTask.stopBaritone();
                    finish(true, p.getInventory().getFreeSlot() < 0 ? "Inventar voll" : null);
                    return;
                }
                BlockPos at = next.blockPosition();
                if (!at.equals(walkTarget) || !goalActive()) {
                    if (at.equals(walkTarget)) {
                        skippedDrops.add(next.getId());   // couldn't reach it twice
                        walkTarget = null;
                        return;
                    }
                    walkTarget = at;
                    try {
                        // picking up drops is walking: no digging through the base (unless allowed)
                        BaritoneSafety.walkOnly();
                        walkOnto(at);
                    } catch (Throwable t) {
                        finish(countIds(p) > before, "Baritone: " + t.getClass().getSimpleName());
                    }
                }
            }
            case PLACE -> {
                if (placed != null) {
                    if (w.getBlockState(placed).getBlock() instanceof ChestBlock) {
                        setPhase(Phase.OPEN);
                    } else if (age > 3000L) {
                        if (++tries >= 3) {
                            finish(false, "Kiste lässt sich nicht abstellen");
                        } else {
                            placed = null;
                            setPhase(Phase.PLACE);
                        }
                    }
                    return;
                }
                if (Mc.screen(mc) != null) {
                    p.closeContainer();
                    return;
                }
                int inv = findChest(p);
                if (inv < 0) {
                    finish(false, "keine Kiste im Inventar");
                    return;
                }
                int hot = toHotbar(mc, p, inv);
                if (hot < 0) {
                    return;
                }
                BlockPos spot = findSpot(p, w);
                if (spot == null) {
                    finish(false, "kein Platz für eine Kiste");
                    return;
                }
                p.getInventory().setSelectedSlot(hot);
                BlockPos below = spot.below();
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                        new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false));
                placed = spot;
                phaseStart = System.currentTimeMillis();
            }
            case OPEN -> {
                AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
                if (h != null && h != p.inventoryMenu) {
                    opened = h;
                    setPhase(Phase.PUT);
                    return;
                }
                if (age > 6000L) {
                    finish(false, "Kiste geht nicht auf");
                    return;
                }
                if (tick % 20 == 1) {
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND,
                            new BlockHitResult(Vec3.atCenterOf(placed).add(0, 0.5, 0), Direction.UP, placed, false));
                }
            }
            case PUT -> {
                AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
                if (h != opened) {
                    finish(count > 0, "Kiste wurde geschlossen");
                    return;
                }
                if (age < 400L || tick % CLICK_EVERY != 0) {
                    return;
                }
                for (Slot s : h.slots) {
                    if (!(s.container instanceof Inventory) || !s.hasItem() || skipped.contains(s.index)) {
                        continue;
                    }
                    boolean want = slots.isEmpty()
                            ? ids.contains(BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString())
                            : slots.contains(s.getContainerSlot());
                    if (!want) {
                        continue;
                    }
                    int n = s.getItem().getCount();
                    Mc.quickMove(mc, h.containerId, s.index, p);
                    if (s.hasItem() && s.getItem().getCount() == n) {
                        skipped.add(s.index);    // chest full
                    } else {
                        count += n;
                    }
                    return;
                }
                p.closeContainer();
                finish(true, null);
            }
            default -> {
            }
        }
    }

    private ItemEntity nearestDrop(LocalPlayer p, ClientLevel w) {
        AABB b = area.inflate(4.0);
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : w.getEntitiesOfClass(ItemEntity.class, b, e -> !skippedDrops.contains(e.getId()))) {
            if (!ids.isEmpty() && !ids.contains(BuiltInRegistries.ITEM.getKey(e.getItem().getItem()).toString())) {
                continue;
            }
            double d = e.distanceToSqr(p);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private int countIds(LocalPlayer p) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && (ids.isEmpty() || ids.contains(BuiltInRegistries.ITEM.getKey(st.getItem()).toString()))) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static int findChest(LocalPlayer p) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).is(Items.CHEST)) {
                return i;
            }
        }
        return -1;
    }

    private static int toHotbar(Minecraft mc, LocalPlayer p, int slot) {
        if (slot < Inventory.getSelectionSize()) {
            return slot;
        }
        Mc.swap(mc, p.inventoryMenu.containerId, slot, p.getInventory().getSelectedSlot(), p);
        return -1;
    }

    /** Free spot next to you, with solid ground and air above (to open), no chest adjacent. */
    private static BlockPos findSpot(LocalPlayer p, ClientLevel w) {
        BlockPos feet = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.above()).isAir()
                            || !w.getBlockState(pos.below()).isFaceSturdy(w, pos.below(), Direction.UP)) {
                        continue;
                    }
                    boolean chestNext = false;
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        if (w.getBlockState(pos.relative(d)).getBlock() instanceof ChestBlock) {
                            chestNext = true;   // otherwise it becomes a double chest with foreign contents
                        }
                    }
                    double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
                    if (chestNext || d < 1.2 || d > 4.0) {
                        continue;
                    }
                    if (d < bestD) {
                        bestD = d;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------ Baritone (API via reflection)

    private static Object baritone() throws Exception {
        Object provider = Class.forName("baritone.api.BaritoneAPI").getMethod("getProvider").invoke(null);
        return Class.forName("baritone.api.IBaritoneProvider").getMethod("getPrimaryBaritone").invoke(provider);
    }

    private static boolean active(String getter) {
        try {
            Object proc = Class.forName("baritone.api.IBaritone").getMethod(getter).invoke(baritone());
            return (Boolean) Class.forName("baritone.api.process.IBaritoneProcess").getMethod("isActive").invoke(proc);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean builderActive() {
        return active("getBuilderProcess");
    }

    private static boolean goalActive() {
        return active("getCustomGoalProcess");
    }

    private static void walkOnto(BlockPos target) throws Exception {
        Object goal = Class.forName("baritone.api.pathing.goals.GoalBlock").getConstructor(BlockPos.class).newInstance(target);
        Object proc = Class.forName("baritone.api.IBaritone").getMethod("getCustomGoalProcess").invoke(baritone());
        Class.forName("baritone.api.process.ICustomGoalProcess")
                .getMethod("setGoalAndPath", Class.forName("baritone.api.pathing.goals.Goal")).invoke(proc, goal);
    }

    private void finish(boolean ok, String why) {
        Minecraft mc = Minecraft.getInstance();
        String msg;
        if (label != null && label.equals("Kiste")) {
            msg = "Kiste: " + count + " Items eingelagert" + (placed != null ? " bei " + placed.getX() + " " + placed.getY() + " " + placed.getZ() : "");
        } else if (fill != null) {
            msg = label + ": fertig";
        } else {
            int got = mc.player != null ? Math.max(0, countIds(mc.player) - before) : 0;
            msg = label + ": fertig, " + got + " Items eingesammelt";
        }
        if (why != null) {
            msg += " (" + why + ")";
        }
        actionbar(mc, msg, ok ? ChatFormatting.GREEN : ChatFormatting.RED);
        BaritoneBridge.report(id, ok, msg);
        phase = Phase.IDLE;
        opened = null;
    }

    private static void actionbar(Minecraft mc, String s, ChatFormatting f) {
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(s)).withStyle(f));
        }
    }
}
