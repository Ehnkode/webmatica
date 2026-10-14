package io.github.ehnkode.webmatica;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Unpack shulkers from the inventory: place briefly next to you, take out the
 * wanted items, break it again and pick it up. For auto-build, because
 * Baritone can only build from loose items.
 *
 * The shulker is never placed inside the schematic frame (if one is given)
 * and only where there is room above to open it.
 */
public final class UnpackTask extends BotTask<UnpackTask.Phase> {

    private static final UnpackTask INSTANCE = new UnpackTask();
    private static final int CLICK_EVERY = 3;

    public static UnpackTask getInstance() {
        return INSTANCE;
    }

    enum Phase { IDLE, PLACE, OPEN, TAKE, BREAK, PICKUP }

    private final Map<String, Integer> want = new LinkedHashMap<>();
    private int[] box;            // x0 y0 z0 x1 y1 z1, must not be built on
    private BlockPos placed;
    private String shulkerId;
    private int taken;
    private int tries;
    private int shulkersLeft;
    private boolean walked;

    private UnpackTask() {
        super(Phase.IDLE);
    }

    /** Client thread. */
    public void start(String cmdId, JsonObject cmd) {
        begin(cmdId);
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

    @Override
    protected void step(Minecraft mc, LocalPlayer p, long age) {
        ClientLevel w = mc.level;
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
                if (Mc.screen(mc) != null) {
                    p.closeContainer();
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
                ItemStack st = p.getInventory().getItem(hot);
                shulkerId = BuiltInRegistries.ITEM.getKey(st.getItem()).toString();
                p.getInventory().setSelectedSlot(hot);
                BlockPos below = spot.below();
                BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(below).add(0, 0.5, 0), Direction.UP, below, false);
                mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                placed = spot;
                phaseStart = System.currentTimeMillis();
            }
            case OPEN -> {
                AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
                if (h instanceof ShulkerBoxMenu) {
                    opened = h;
                    setPhase(Phase.TAKE);
                    return;
                }
                if (age > 6000L) {
                    setPhase(Phase.BREAK);   // pick it up anyway
                    return;
                }
                if (tick % 20 == 1) {
                    BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(placed), Direction.UP, placed, false);
                    mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
                }
            }
            case TAKE -> {
                AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
                if (h != opened) {
                    setPhase(Phase.BREAK);
                    return;
                }
                if (age < 400L || tick % CLICK_EVERY != 0) {
                    return;
                }
                Slot next = null;
                for (Slot s : h.slots) {
                    if (s.container instanceof Inventory || !s.hasItem()) {
                        continue;
                    }
                    Integer left = want.get(BuiltInRegistries.ITEM.getKey(s.getItem().getItem()).toString());
                    if (left != null && left > 0) {
                        next = s;
                        break;
                    }
                }
                if (next == null || p.getInventory().getFreeSlot() < 0) {
                    p.closeContainer();
                    setPhase(Phase.BREAK);
                    return;
                }
                ItemStack before = next.getItem().copy();
                Mc.quickMove(mc, h.containerId, next.index, p);
                taken += before.getCount();
                want.merge(BuiltInRegistries.ITEM.getKey(before.getItem()).toString(), -before.getCount(), Integer::sum);
            }
            case BREAK -> {
                if (Mc.screen(mc) != null) {
                    p.closeContainer();
                    return;
                }
                BlockState bs = w.getBlockState(placed);
                if (!(bs.getBlock() instanceof ShulkerBoxBlock)) {
                    mc.gameMode.stopDestroyBlock();
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
                    mc.gameMode.startDestroyBlock(placed, Direction.UP);
                } else {
                    mc.gameMode.continueDestroyBlock(placed, Direction.UP);
                }
                Mc.swing(mc, p, InteractionHand.MAIN_HAND);
            }
            case PICKUP -> {
                boolean back = countItem(p, shulkerId) > shulkersLeft;
                if (!back && !walked && age > 1500L) {
                    // not picked up by itself: walk over briefly (without breaking)
                    walked = true;
                    try {
                        BaritoneSafety.walkOnly();
                        BaritoneApi.walkTo(placed);
                    } catch (Throwable ignored) {
                    }
                }
                if (back || age > 12_000L) {
                    if (walked) {
                        BaritoneApi.stop();
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

    private int findShulker(LocalPlayer p) {
        Inventory inv = p.getInventory();
        int best = -1, bestGain = 0;
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            ItemStack st = inv.getItem(i);
            if (!(st.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            ItemContainerContents c = st.get(DataComponents.CONTAINER);
            if (c == null) {
                continue;
            }
            int gain = 0;
            for (ItemStack in : Mc.items(c)) {
                Integer left = want.get(BuiltInRegistries.ITEM.getKey(in.getItem()).toString());
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
    private static int toHotbar(Minecraft mc, LocalPlayer p, int slot) {
        if (slot < Inventory.getSelectionSize()) {
            return slot;
        }
        int hot = p.getInventory().getSelectedSlot();
        // Player inventory: slot number in the window = index for 9..35, key = hotbar slot
        Mc.swap(mc, p.inventoryMenu.containerId, slot, hot, p);
        return -1;
    }

    private static void selectTool(LocalPlayer p, BlockState bs) {
        Inventory inv = p.getInventory();
        int best = inv.getSelectedSlot();
        float speed = inv.getItem(best).getDestroySpeed(bs);
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            float s = inv.getItem(i).getDestroySpeed(bs);
            if (s > speed) {
                best = i;
                speed = s;
            }
        }
        inv.setSelectedSlot(best);
    }

    private BlockPos findSpot(LocalPlayer p, ClientLevel w) {
        BlockPos feet = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;   // not under/inside the player
                    }
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (inBox(pos) || inBox(pos.above())) {
                        continue;
                    }
                    if (!w.getBlockState(pos).isAir() || !w.getBlockState(pos.above()).isAir()) {
                        continue;
                    }
                    if (!w.getBlockState(pos.below()).isFaceSturdy(w, pos.below(), Direction.UP)) {
                        continue;
                    }
                    double d = p.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
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

    private static int countItem(LocalPlayer p, String itemId) {
        int n = 0;
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack st = inv.getItem(i);
            if (!st.isEmpty() && BuiltInRegistries.ITEM.getKey(st.getItem()).toString().equals(itemId)) {
                n += st.getCount();
            }
        }
        return n;
    }

    @Override
    protected void finish(boolean ok, String why) {
        report(ok, "Auspacken: " + (taken > 0 ? taken + " Items" : "nichts") + " genommen" + (why != null ? " (" + why + ")" : ""));
    }
}
