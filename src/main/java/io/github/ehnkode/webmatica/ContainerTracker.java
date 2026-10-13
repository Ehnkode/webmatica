package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

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

    private ScreenHandler current;
    private BlockPos currentPos;
    private BlockPos currentOther;
    private String currentBlock;
    private String lastSent;
    private String pending;
    private int stableTicks;

    public void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient()) {
                usePos = hit.getBlockPos().toImmutable();
                net.minecraft.block.BlockState bs = world.getBlockState(usePos);
                useBlock = Registries.BLOCK.getId(bs.getBlock()).toString();
                // double chest: determine the other half exactly (don't guess, chests are often packed tightly)
                useOther = null;
                try {
                    if (bs.getBlock() instanceof net.minecraft.block.ChestBlock
                            && bs.get(net.minecraft.block.ChestBlock.CHEST_TYPE) != net.minecraft.block.enums.ChestType.SINGLE) {
                        useOther = usePos.offset(net.minecraft.block.ChestBlock.getFacing(bs)).toImmutable();
                    }
                } catch (Throwable ignored) {
                }
                useTime = System.currentTimeMillis();
                ownPos = usePos;
                ownOther = useOther;
                ownTime = useTime;
            }
            return ActionResult.PASS;
        });
    }

    public void tick(MinecraftClient mc) {
        if (UnpackTask.getInstance().busy()) {
            usePos = null;      // a briefly placed shulker is not a chest
            return;
        }
        ScreenHandler h = mc.currentScreen instanceof HandledScreen<?> hs ? hs.getScreenHandler() : null;
        if (h != current) {
            flush(mc);
            current = null;
            currentPos = null;
            lastSent = null;
            pending = null;
            if (h != null && isStorage(h) && usePos != null
                    && System.currentTimeMillis() - useTime < OPEN_WINDOW_MS) {
                current = h;
                currentPos = usePos;
                currentOther = useOther;
                currentBlock = useBlock;
                usePos = null;
            }
        }
        if (current == null || mc.player == null || mc.world == null) {
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

    private void flush(MinecraftClient mc) {
        if (current != null && pending != null && !pending.equals(lastSent)) {
            send(pending);
        }
    }

    private static boolean isStorage(ScreenHandler h) {
        return h instanceof GenericContainerScreenHandler || h instanceof ShulkerBoxScreenHandler
                || h instanceof HopperScreenHandler || h instanceof Generic3x3ContainerScreenHandler;
    }

    private String snapshot(MinecraftClient mc) {
        JsonObject o = new JsonObject();
        o.addProperty("server", RecordingTarget.serverId(mc));
        Identifier dim = mc.world.getRegistryKey().getValue();
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
            Text title = mc.currentScreen != null ? mc.currentScreen.getTitle() : null;
            if (title != null) {
                o.addProperty("title", title.getString());
            }
        } catch (Throwable ignored) {
        }
        JsonArray items = new JsonArray();
        int slots = 0;
        for (Slot s : current.slots) {
            if (s.inventory instanceof PlayerInventory) {
                continue;
            }
            slots++;
            ItemStack st = s.getStack();
            if (st.isEmpty()) {
                continue;
            }
            JsonObject it = item(st);
            it.addProperty("slot", s.getIndex());
            ContainerComponent inner = st.get(DataComponentTypes.CONTAINER);
            if (inner != null) {
                JsonArray content = new JsonArray();
                for (ItemStack c : inner.iterateNonEmpty()) {
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
        ContainerComponent inner = st.get(DataComponentTypes.CONTAINER);
        if (inner != null) {
            JsonArray content = new JsonArray();
            for (ItemStack c : inner.iterateNonEmpty()) {
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
        it.addProperty("id", Registries.ITEM.getId(st.getItem()).toString());
        it.addProperty("count", st.getCount());
        Text name = st.getCustomName();
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
                    MinecraftClient mc = MinecraftClient.getInstance();
                    mc.execute(() -> {
                        if (mc.player != null) {
                            mc.player.sendMessage(Text.literal("[Webmatica] " + Lang.tr("Kiste gespeichert (" + n + " Stacks)"))
                                    .formatted(net.minecraft.util.Formatting.GOLD), true);
                        }
                    });
                }
            } catch (Throwable ignored) {
                // viewer off: resend the chest on next open
            }
        });
    }
}
