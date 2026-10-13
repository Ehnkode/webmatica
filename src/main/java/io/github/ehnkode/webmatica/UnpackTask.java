package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unpack shulkers from the inventory: place briefly next to you, take out the
 * wanted items, break it again and pick it up. For auto-build, because
 * Baritone can only build from loose items.
 *
 * The shulker is never placed inside the schematic frame (if one is given)
 * and only where there is room above to open it.
 */
public final class UnpackTask {

    private static final UnpackTask INSTANCE = new UnpackTask();
    private static final int CLICK_EVERY = 3;

    public static UnpackTask getInstance() {
        return INSTANCE;
    }

    private enum Phase { IDLE, PLACE, OPEN, TAKE, BREAK, PICKUP }

    private Phase phase = Phase.IDLE;
    private String id;
    private final Map<String, Integer> want = new LinkedHashMap<>();
    private int[] box;            // x0 y0 z0 x1 y1 z1, must not be built on
    private BlockPos placed;
    private String shulkerId;
    private long phaseStart;
    private int tick;
    private int taken;
    private int tries;
    private ScreenHandler opened;
    private int shulkersLeft;
    private boolean walked;

    public boolean busy() {
        return phase != Phase.IDLE;
    }

    /** Client thread. */
    public void start(String cmdId, JsonObject cmd) {
        if (busy()) {
            finish(false, "abgebrochen, neuer Auftrag");
        }
        id = cmdId;
        want.clear();
        for (JsonElement e : cmd.getAsJsonArray("items")) {
            JsonObject it = e.getAsJsonObject();
            want.merge(it.get("id").getAsString(), it.has("count") ? it.get("count").getAsInt() : 64, Integer::sum);
        }
        box = null;
        if (cmd.has("box")) {
            var a = cmd.getAsJsonArray("box");
            box = new int[6];
            for (int i = 0; i < 6; i++) {
                box[i] = a.get(i).getAsInt();
            }
        }
        placed = null;
        taken = 0;
        tries = 0;
        opened = null;
        setPhase(Phase.PLACE);
    }

    public void cancel() {
        if (busy()) {
            finish(false, "gestoppt");
        }
    }

    private long frozenAt;

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
            case PLACE -> {
                if (placed != null) {
                    if (w.getBlockState(placed).getBlock() instanceof ShulkerBoxBlock) {
                        shulkersLeft = countItem(p, shulkerId);
                        setPhase(Phase.OPEN);
                    } else if (age > 3000L) {
                        if (++tries >= 3) {
                            finish(false, "Shulker lässt sich nicht abstellen");
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
                int inv = findShulker(p);
                if (inv < 0) {
                    finish(false, "kein passender Shulker im Inventar");
                    return;
                }
                int hot = toHotbar(mc, p, inv);
                if (hot < 0) {
                    return;   // swap in progress, next tick
                }
                BlockPos spot = findSpot(p, w);
                if (spot == null) {
                    finish(false, "kein Platz zum Abstellen (frei, mit Platz darüber)");
                    return;
                }
                ItemStack st = p.getInventory().getStack(hot);
                shulkerId = Registries.ITEM.getId(st.getItem()).toString();
                p.getInventory().setSelectedSlot(hot);
                BlockPos below = spot.down();
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(below).add(0, 0.5, 0), Direction.UP, below, false);
                mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                placed = spot;
                phaseStart = System.currentTimeMillis();
            }
            case OPEN -> {
                ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
                if (h instanceof ShulkerBoxScreenHandler) {
                    opened = h;
                    setPhase(Phase.TAKE);
                    return;
                }
                if (age > 6000L) {
                    setPhase(Phase.BREAK);   // pick it up anyway
                    return;
                }
                if (tick % 20 == 1) {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(placed), Direction.UP, placed, false);
                    mc.interactionManager.interactBlock(p, Hand.MAIN_HAND, hit);
                }
            }
            case TAKE -> {
                ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
                if (h != opened) {
                    setPhase(Phase.BREAK);
                    return;
                }
                if (age < 400L || tick % CLICK_EVERY != 0) {
                    return;
                }
                Slot next = null;
                for (Slot s : h.slots) {
                    if (s.inventory instanceof PlayerInventory || !s.hasStack()) {
                        continue;
                    }
                    Integer left = want.get(Registries.ITEM.getId(s.getStack().getItem()).toString());
                    if (left != null && left > 0) {
                        next = s;
                        break;
                    }
                }
                if (next == null || p.getInventory().getEmptySlot() < 0) {
                    p.closeHandledScreen();
                    setPhase(Phase.BREAK);
                    return;
                }
                ItemStack before = next.getStack().copy();
                mc.interactionManager.clickSlot(h.syncId, next.id, 0, SlotActionType.QUICK_MOVE, p);
                taken += before.getCount();
                want.merge(Registries.ITEM.getId(before.getItem()).toString(), -before.getCount(), Integer::sum);
            }
            case BREAK -> {
                if (mc.currentScreen != null) {
                    p.closeHandledScreen();
                    return;
                }
                BlockState bs = w.getBlockState(placed);
                if (!(bs.getBlock() instanceof ShulkerBoxBlock)) {
                    mc.interactionManager.cancelBlockBreaking();
                    walked = false;
                    setPhase(Phase.PICKUP);
                    return;
                }
                if (age > 20_000L) {
                    finish(false, "Shulker lässt sich nicht abbauen, steht noch bei "
                            + placed.getX() + " " + placed.getY() + " " + placed.getZ());
                    return;
                }
                if (age < 100L) {
                    selectTool(p, bs);
                    mc.interactionManager.attackBlock(placed, Direction.UP);
                } else {
                    mc.interactionManager.updateBlockBreakingProgress(placed, Direction.UP);
                }
                p.swingHand(Hand.MAIN_HAND);
            }
            case PICKUP -> {
                boolean back = countItem(p, shulkerId) > shulkersLeft;
                if (!back && !walked && age > 1500L) {
                    // not picked up by itself: walk over briefly (without breaking)
                    walked = true;
                    try {
                        BaritoneSafety.walkOnly();
                        FetchTask.walkTo(placed);
                    } catch (Throwable ignored) {
                    }
                }
                if (back || age > 12_000L) {
                    if (walked) {
                        FetchTask.stopBaritone();
                        BaritoneSafety.restore();
                    }
                    finish(taken > 0, back ? null : "Shulker liegt noch am Boden bei "
                            + placed.getX() + " " + placed.getY() + " " + placed.getZ());
                }
            }
            default -> {
            }
        }
    }

    private int findShulker(ClientPlayerEntity p) {
        PlayerInventory inv = p.getInventory();
        int best = -1, bestGain = 0;
        for (int i = 0; i < PlayerInventory.MAIN_SIZE; i++) {
            ItemStack st = inv.getStack(i);
            if (!(st.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            ContainerComponent c = st.get(DataComponentTypes.CONTAINER);
            if (c == null) {
                continue;
            }
            int gain = 0;
            for (ItemStack in : c.iterateNonEmpty()) {
                Integer left = want.get(Registries.ITEM.getId(in.getItem()).toString());
                if (left != null && left > 0) {
                    gain += Math.min(left, in.getCount());
                }
            }
            if (gain > bestGain) {
                best = i;
                bestGain = gain;
            }
        }
        return best;
    }

    /** Is the shulker in the hotbar? Otherwise swap it there and return -1. */
    private static int toHotbar(MinecraftClient mc, ClientPlayerEntity p, int slot) {
        if (slot < PlayerInventory.getHotbarSize()) {
            return slot;
        }
        int hot = p.getInventory().getSelectedSlot();
        // Player inventory: slot number in the window = index for 9..35, key = hotbar slot
        mc.interactionManager.clickSlot(p.playerScreenHandler.syncId, slot, hot, SlotActionType.SWAP, p);
        return -1;
    }

    private static void selectTool(ClientPlayerEntity p, BlockState bs) {
        PlayerInventory inv = p.getInventory();
        int best = inv.getSelectedSlot();
        float speed = inv.getStack(best).getMiningSpeedMultiplier(bs);
        for (int i = 0; i < PlayerInventory.getHotbarSize(); i++) {
            float s = inv.getStack(i).getMiningSpeedMultiplier(bs);
            if (s > speed) {
                best = i;
                speed = s;
            }
        }
        inv.setSelectedSlot(best);
    }

    private BlockPos findSpot(ClientPlayerEntity p, ClientWorld w) {
        BlockPos feet = p.getBlockPos();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;   // not under/inside the player
                    }
                    BlockPos pos = feet.add(dx, dy, dz);
                    if (inBox(pos) || inBox(pos.up())) {
                        continue;
                    }
                    if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.up()).isAir()) {
                        continue;
                    }
                    if (!w.getBlockState(pos.down()).isSideSolidFullSquare(w, pos.down(), Direction.UP)) {
                        continue;
                    }
                    double d = p.getEyePos().distanceTo(Vec3d.ofCenter(pos));
                    if (d < 1.2 || d > 4.0) {
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

    private boolean inBox(BlockPos pos) {
        return box != null && pos.getX() >= box[0] && pos.getX() <= box[3] && pos.getY() >= box[1] && pos.getY() <= box[4]
                && pos.getZ() >= box[2] && pos.getZ() <= box[5];
    }

    private static int countItem(ClientPlayerEntity p, String itemId) {
        int n = 0;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack st = inv.getStack(i);
            if (!st.isEmpty() && Registries.ITEM.getId(st.getItem()).toString().equals(itemId)) {
                n += st.getCount();
            }
        }
        return n;
    }

    private void finish(boolean ok, String why) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String msg = "Auspacken: " + (taken > 0 ? taken + " Items" : "nichts") + " genommen" + (why != null ? " (" + why + ")" : "");
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[Webmatica] " + Lang.tr(msg)).formatted(ok ? Formatting.GREEN : Formatting.RED), true);
        }
        BaritoneBridge.report(id, ok, msg);
        phase = Phase.IDLE;
        opened = null;
    }
}
