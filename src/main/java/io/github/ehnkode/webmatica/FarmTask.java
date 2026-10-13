package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.ChestBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Bloecke gezielt abbauen und einsammeln (Farmen, falsche Bloecke einer
 * Schematic) und volle Inventare in eine selbst aufgestellte Kiste packen.
 *
 * Abbauen: jede Box wird eine Baritone-Auswahl, "#sel cleararea" baut genau
 * diese Bloecke ab. Danach laeuft die Mod zu den liegengebliebenen Items.
 * Mit "fill" setzt "#sel fill" stattdessen den Block in die Boxen (Loecher fuellen).
 * Kiste: eine Kiste aus dem Inventar neben dich stellen, die genannten Items
 * hineinlegen und stehen lassen (der Viewer merkt sie sich wie jede Kiste).
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
    private final Set<Integer> slots = new HashSet<>();    // Kiste: nur diese Inventar-Plaetze
    private Box area;
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
    private ScreenHandler opened;
    private final Set<Integer> skipped = new HashSet<>();
    private final Set<Integer> skippedDrops = new HashSet<>();

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Stellt gerade eine Kiste auf oder fuellt sie: dann nicht essen. */
    public boolean handlingChest() {
        return phase == Phase.PLACE || phase == Phase.OPEN || phase == Phase.PUT;
    }

    /** Client-Thread. kind "breakboxes" oder "chestdump". */
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
        MinecraftClient mc = MinecraftClient.getInstance();
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
            Box bb = new Box(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
            area = area == null ? bb : area.union(bb);
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
        actionbar(mc, label + (fill != null ? ": füllt " : ": baut ") + boxes.size() + " Bereiche" + (fill != null ? " auf" : " ab"), Formatting.AQUA);
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

    public void tick(MinecraftClient mc) {
        if (phase == Phase.IDLE) {
            return;
        }
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null || mc.interactionManager == null) {
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
                if (next == null || age > 90_000L || p.getInventory().getEmptySlot() < 0) {
                    FetchTask.stopBaritone();
                    finish(true, p.getInventory().getEmptySlot() < 0 ? "Inventar voll" : null);
                    return;
                }
                BlockPos at = next.getBlockPos();
                if (!at.equals(walkTarget) || !goalActive()) {
                    if (at.equals(walkTarget)) {
                        skippedDrops.add(next.getId());   // zweimal nicht hingekommen
                        walkTarget = null;
                        return;
                    }
                    walkTarget = at;
                    try {
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
                if (mc.currentScreen != null) {
                    p.closeHandledScreen();
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
                BlockPos below = spot.down();
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                        new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false));
                placed = spot;
                phaseStart = System.currentTimeMillis();
            }
            case OPEN -> {
                ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
                if (h != null && h != p.playerScreenHandler) {
                    opened = h;
                    setPhase(Phase.PUT);
                    return;
                }
                if (age > 6000L) {
                    finish(false, "Kiste geht nicht auf");
                    return;
                }
                if (tick % 20 == 1) {
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                            new BlockHitResult(Vec3d.ofCenter(placed).add(0, 0.5, 0), Direction.UP, placed, false));
                }
            }
            case PUT -> {
                ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
                if (h != opened) {
                    finish(count > 0, "Kiste wurde geschlossen");
                    return;
                }
                if (age < 400L || tick % CLICK_EVERY != 0) {
                    return;
                }
                for (Slot s : h.slots) {
                    if (!(s.inventory instanceof PlayerInventory) || !s.hasStack() || skipped.contains(s.id)) {
                        continue;
                    }
                    boolean want = slots.isEmpty()
                            ? ids.contains(Registries.ITEM.getId(s.getStack().getItem()).toString())
                            : slots.contains(s.getIndex());
                    if (!want) {
                        continue;
                    }
                    int n = s.getStack().getCount();
                    mc.interactionManager.clickSlot(h.syncId, s.id, 0, SlotActionType.QUICK_MOVE, p);
                    if (s.hasStack() && s.getStack().getCount() == n) {
                        skipped.add(s.id);    // Kiste voll
                    } else {
                        count += n;
                    }
                    return;
                }
                p.closeHandledScreen();
                finish(true, null);
            }
            default -> {
            }
        }
    }

    private ItemEntity nearestDrop(ClientPlayerEntity p, ClientWorld w) {
        Box b = area.expand(4.0);
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity e : w.getEntitiesByClass(ItemEntity.class, b, e -> !skippedDrops.contains(e.getId()))) {
            if (!ids.isEmpty() && !ids.contains(Registries.ITEM.getId(e.getStack().getItem()).toString())) {
                continue;
            }
            double d = e.squaredDistanceTo(p);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private int countIds(ClientPlayerEntity p) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && (ids.isEmpty() || ids.contains(Registries.ITEM.getId(st.getItem()).toString()))) {
                n += st.getCount();
            }
        }
        return n;
    }

    private static int findChest(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            if (inv.getStack(i).isOf(Items.CHEST)) {
                return i;
            }
        }
        return -1;
    }

    private static int toHotbar(MinecraftClient mc, ClientPlayerEntity p, int slot) {
        if (slot < PlayerInventory.getHotbarSize()) {
            return slot;
        }
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, p.getInventory().getSelectedSlot(), SlotActionType.SWAP, p);
        return -1;
    }

    /** Freier Platz neben dir, mit festem Boden und Luft darueber (zum Aufklappen), ohne Kiste daneben. */
    private static BlockPos findSpot(ClientPlayerEntity p, ClientWorld w) {
        BlockPos feet = p.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()
                            || !w.getBlockState(pos.down()).isSideSolidFullSquare(w, pos.down(), Direction.UP)) {
                        continue;
                    }
                    boolean chestNext = false;
                    for (Direction d : Direction.Type.HORIZONTAL) {
                        if (w.getBlockState(pos.offset(d)).getBlock() instanceof ChestBlock) {
                            chestNext = true;   // sonst wird es eine Doppelkiste mit fremdem Inhalt
                        }
                    }
                    double d = p.getEyePos().distanceTo(Vec3d.ofCenter(pos));
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

    // ------------------------------------------------------------ Baritone (API per Reflection)

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
        MinecraftClient mc = MinecraftClient.getInstance();
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
        actionbar(mc, msg, ok ? Formatting.GREEN : Formatting.RED);
        BaritoneBridge.report(id, ok, msg);
        phase = Phase.IDLE;
        opened = null;
    }

    private static void actionbar(MinecraftClient mc, String s, Formatting f) {
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Webmatica] " + Lang.tr(s)).formatted(f), true);
        }
    }
}
