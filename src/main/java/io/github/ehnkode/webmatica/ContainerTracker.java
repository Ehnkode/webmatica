package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Chest inventory: remembers the contents of every container (shulker, barrel,
 * hopper, dispenser, ender chest ...) you open and sends it to the viewer.
 *
 * Read-only: it only looks at what the server sends for display anyway.
 * Which container it is comes from the last right-click on a block.
 */
public final class ContainerTracker {

    private static final ContainerTracker INSTANCE = new ContainerTracker();
    private static final long OPEN_WINDOW_MS = 15000L;   // 2b2t lags: the window may open late
    private static final int SETTLE_TICKS = 6;

    public static ContainerTracker getInstance() {
        return INSTANCE;
    }

    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webmatica-containers");
        t.setDaemon(true);
        return t;
    });

    private BlockPos usePos;
    private BlockPos useOther;
    private String useBlock;
    private long useTime;

    private BlockPos ownPos;
    private BlockPos ownOther;
    private long ownTime;

    private AbstractContainerMenu current;
    private BlockPos currentPos;
    private BlockPos currentOther;
    private String currentBlock;
    private String lastSent;
    private String pending;
    private int stableTicks;

    public void register() {
        // Chest boat, minecart with chest, donkey: their container must not be saved under
        // the position of a block clicked shortly before
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            usePos = null;
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClientSide()) {
                usePos = hit.getBlockPos().immutable();
                net.minecraft.world.level.block.state.BlockState bs = world.getBlockState(usePos);
                useBlock = BuiltInRegistries.BLOCK.getKey(bs.getBlock()).toString();
                // double chest: determine the other half exactly (don't guess, chests are often packed tightly)
                useOther = null;
                try {
                    if (bs.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                            && bs.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                        useOther = usePos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(bs)).immutable();
                    }
                } catch (Throwable ignored) {
                }
                useTime = System.currentTimeMillis();
                ownPos = usePos;
                ownOther = useOther;
                ownTime = useTime;
            }
            return InteractionResult.PASS;
        });
    }

    /** Broken containers, checked again after {@link #GONE_DELAY_MS} (2b2t often undoes a predicted break). */
    private static final long GONE_DELAY_MS = 1500L;
    private final java.util.Map<BlockPos, Gone> gone = new java.util.concurrent.ConcurrentHashMap<>();

    private record Gone(net.minecraft.world.level.Level level, net.minecraft.world.level.block.Block block, BlockPos other, long at) {
    }

    /**
     * From {@code WorldChunkMixin}: a block with a block entity (chest, shulker, barrel ...) was
     * replaced by a different block. If it stays gone, the viewer forgets its contents.
     */
    public void blockReplaced(net.minecraft.world.level.Level level, BlockPos pos,
                              net.minecraft.world.level.block.state.BlockState old,
                              net.minecraft.world.level.block.state.BlockState now) {
        if (!old.hasBlockEntity() || now.getBlock() == old.getBlock()
                || old.getBlock() instanceof net.minecraft.world.level.block.EnderChestBlock) {
            return;
        }
        BlockPos other = null;
        try {
            if (old.getBlock() instanceof net.minecraft.world.level.block.ChestBlock
                    && old.getValue(net.minecraft.world.level.block.ChestBlock.TYPE) != net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
                other = pos.relative(net.minecraft.world.level.block.ChestBlock.getConnectedDirection(old)).immutable();
            }
        } catch (Throwable ignored) {
        }
        gone.put(pos.immutable(), new Gone(level, old.getBlock(), other, System.currentTimeMillis()));
    }

    private void sendGone(Minecraft mc) {
        if (gone.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (java.util.Iterator<java.util.Map.Entry<BlockPos, Gone>> it = gone.entrySet().iterator(); it.hasNext(); ) {
            java.util.Map.Entry<BlockPos, Gone> e = it.next();
            Gone g = e.getValue();
            if (now - g.at() < GONE_DELAY_MS) {
                continue;
            }
            it.remove();
            if (mc.level == null || mc.level != g.level() || mc.level.getBlockState(e.getKey()).getBlock() == g.block()) {
                continue;       // other world, or the server put the block back
            }
            JsonObject o = new JsonObject();
            o.addProperty("server", RecordingTarget.serverId(mc));
            Identifier dim = mc.level.dimension().identifier();
            o.addProperty("dim", "minecraft".equals(dim.getNamespace()) ? dim.getPath() : dim.toString());
            JsonArray keys = new JsonArray();
            for (BlockPos p : new BlockPos[] {e.getKey(), g.other()}) {
                if (p != null) {
                    JsonArray xyz = new JsonArray();
                    xyz.add(p.getX());
                    xyz.add(p.getY());
                    xyz.add(p.getZ());
                    keys.add(xyz);
                }
            }
            o.add("at", keys);
            post("/api/containers/gone", o.toString());
        }
    }

    private void post(String path, String body) {
        sender.submit(() -> {
            try {
                String base = PositionReporter.url();
                int i = base.indexOf("/api/");
                URI uri = URI.create((i > 0 ? base.substring(0, i) : "http://127.0.0.1:8770") + path);
                HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(2000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = body.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) {
                // viewer off: the viewer drops it later from the recording
            }
        });
    }

    public void tick(Minecraft mc) {
        sendGone(mc);
        if (UnpackTask.getInstance().busy()) {
            usePos = null;      // a briefly placed shulker is not a chest
            return;
        }
        AbstractContainerMenu h = Mc.screen(mc) instanceof AbstractContainerScreen<?> hs ? hs.getMenu() : null;
        if (h != current) {
            flush(mc);
            current = null;
            currentPos = null;
            lastSent = null;
            pending = null;
            if (h != null && usePos != null && System.currentTimeMillis() - useTime < OPEN_WINDOW_MS) {
                Highlighter.getInstance().opened(usePos, useOther);
            }
            if (h != null && isStorage(h) && usePos != null
                    && System.currentTimeMillis() - useTime < OPEN_WINDOW_MS) {
                current = h;
                currentPos = usePos;
                currentOther = useOther;
                currentBlock = useBlock;
                usePos = null;
            }
        }
        if (current == null || mc.player == null || mc.level == null) {
            return;
        }
        ownPos = currentPos;
        ownOther = currentOther;
        ownTime = System.currentTimeMillis();
        String body = snapshot(mc);
        if (body.equals(pending)) {
            stableTicks++;
        } else {
            pending = body;
            stableTicks = 0;
        }
        // contents arrive a tick after opening; only send once they have settled
        if (stableTicks == SETTLE_TICKS && !body.equals(lastSent)) {
            send(body);
            lastSent = body;
        }
    }

    /** A chest you are using yourself right now (or just used)? */
    public boolean isOwn(BlockPos p) {
        if (System.currentTimeMillis() - ownTime > OPEN_WINDOW_MS) {
            return false;
        }
        return p.equals(ownPos) || p.equals(ownOther);
    }

    private void flush(Minecraft mc) {
        if (current != null && pending != null && !pending.equals(lastSent)) {
            send(pending);
        }
    }

    private static boolean isStorage(AbstractContainerMenu h) {
        return h instanceof ChestMenu || h instanceof ShulkerBoxMenu
                || h instanceof HopperMenu || h instanceof DispenserMenu;
    }

    private String snapshot(Minecraft mc) {
        JsonObject o = new JsonObject();
        o.addProperty("server", RecordingTarget.serverId(mc));
        Identifier dim = mc.level.dimension().identifier();
        o.addProperty("dim", "minecraft".equals(dim.getNamespace()) ? dim.getPath() : dim.toString());
        o.addProperty("x", currentPos.getX());
        o.addProperty("y", currentPos.getY());
        o.addProperty("z", currentPos.getZ());
        o.addProperty("block", currentBlock);
        if (currentOther != null) {
            JsonArray other = new JsonArray();
            other.add(currentOther.getX());
            other.add(currentOther.getY());
            other.add(currentOther.getZ());
            o.add("other", other);
        }
        try {
            Component title = Mc.screen(mc) != null ? Mc.screen(mc).getTitle() : null;
            if (title != null) {
                o.addProperty("title", title.getString());
            }
        } catch (Throwable ignored) {
        }
        JsonArray items = new JsonArray();
        int slots = 0;
        for (Slot s : current.slots) {
            if (s.container instanceof Inventory) {
                continue;
            }
            slots++;
            ItemStack st = s.getItem();
            if (st.isEmpty()) {
                continue;
            }
            JsonObject it = item(st);
            it.addProperty("slot", s.getContainerSlot());
            ItemContainerContents inner = st.get(DataComponents.CONTAINER);
            if (inner != null) {
                JsonArray content = new JsonArray();
                for (ItemStack c : Mc.items(inner)) {
                    content.add(item(c));
                }
                if (!content.isEmpty()) {
                    it.add("contents", content);
                }
            }
            items.add(it);
        }
        o.addProperty("slots", slots);
        o.add("items", items);
        return o.toString();
    }

    /** A stack as JSON, shulkers with contents (also for the player inventory). */
    static JsonObject itemWithContents(ItemStack st) {
        JsonObject it = item(st);
        ItemContainerContents inner = st.get(DataComponents.CONTAINER);
        if (inner != null) {
            JsonArray content = new JsonArray();
            for (ItemStack c : Mc.items(inner)) {
                content.add(item(c));
            }
            if (!content.isEmpty()) {
                it.add("contents", content);
            }
        }
        return it;
    }

    private static JsonObject item(ItemStack st) {
        JsonObject it = new JsonObject();
        it.addProperty("id", BuiltInRegistries.ITEM.getKey(st.getItem()).toString());
        it.addProperty("count", st.getCount());
        Component name = st.getCustomName();
        if (name != null) {
            it.addProperty("name", name.getString());
        }
        return it;
    }

    private static int countItems(String body) {
        try {
            return com.google.gson.JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("items").size();
        } catch (Throwable t) {
            return 0;
        }
    }

    private void send(String body) {
        // timestamp only when sending, so identical contents compare equal
        String withT = body.substring(0, body.length() - 1) + ",\"t\":" + System.currentTimeMillis() + "}";
        sender.submit(() -> {
            try {
                String base = PositionReporter.url();
                int i = base.indexOf("/api/");
                URI uri = URI.create((i > 0 ? base.substring(0, i) : "http://127.0.0.1:8770") + "/api/containers");
                HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
                c.setConnectTimeout(800);
                c.setReadTimeout(2000);
                c.setDoOutput(true);
                c.setRequestMethod("POST");
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = withT.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(b.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(b);
                }
                int code = c.getResponseCode();
                c.disconnect();
                if (code == 200) {
                    int n = countItems(withT);
                    Minecraft mc = Minecraft.getInstance();
                    mc.execute(() -> {
                        if (mc.player != null) {
                            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr("Kiste gespeichert (" + n + " Stacks)"))
                                    .withStyle(net.minecraft.ChatFormatting.GOLD));
                        }
                    });
                }
            } catch (Throwable ignored) {
                // viewer off: resend the chest on next open
            }
        });
    }
}
