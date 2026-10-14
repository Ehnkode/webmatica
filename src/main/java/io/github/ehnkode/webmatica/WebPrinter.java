package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AnvilBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CakeBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DiodeBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.FlowerPotBlock;
import net.minecraft.world.level.block.GrindstoneBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.LoomBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StonecutterBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Webmatica's own printer: places the blocks of the schematics set to "In game"
 * in the viewer while you walk past them. No Litematica needed; the viewer sends
 * the target blocks around the player (GET /api/printer/blocks).
 *
 * Normal: turns the head (only towards the server, the camera stays) so stairs,
 * logs and the like come out the right way, and takes blocks from the whole
 * inventory.
 *
 * Server-safe ({@link ServerSafe}): only what a player could do by hand. No
 * faked look direction (blocks that would come out the wrong way from where you
 * look are left for later), only block faces you can see, no further than your
 * normal reach, only blocks already in the hotbar, at most 5 blocks a second.
 *
 * It never breaks anything and never places in mid-air: every block is placed
 * against a block that is already there, lowest layer first.
 */
public final class WebPrinter {

    private static final WebPrinter INSTANCE = new WebPrinter();

    public static WebPrinter getInstance() {
        return INSTANCE;
    }

    /** Fastest pace in server-safe mode: about holding right-click. */
    private static final double SAFE_BPS = 5.0;
    /** Properties the placement direction decides; the others follow from neighbours. */
    private static final Set<String> ORIENTATION = Set.of("facing", "axis", "half", "type", "rotation", "hinge",
            "face", "attachment", "orientation", "hanging", "vertical_direction", "part");

    // ------------------------------------------------------------ Settings (from the viewer)

    private volatile boolean on;
    private volatile double range = 4.5;
    private volatile double bps = 10.0;

    private long keyAt;

    /** Client thread, from BuildControl on every poll. */
    void configure(Minecraft mc, boolean on, double range, double bps) {
        if (on != this.on && System.currentTimeMillis() - keyAt < 2500L) {
            on = this.on;        // the key was just pressed: the viewer hasn't seen it yet
        }
        if (on != this.on && mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr("Printer " + (on ? "AN" : "AUS")))
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        }
        this.on = on;
        this.range = Math.max(1.0, Math.min(6.0, range));
        this.bps = Math.max(1.0, Math.min(20.0, bps));
        if (!on) {
            targets.clear();
            states.clear();
        }
    }

    public boolean isOn() {
        return on;
    }

    /** Key in game: asks the viewer to switch, so the web page shows the same. */
    public void toggleFromKey(Minecraft mc) {
        boolean want = !on;
        String srv = serverId;
        keyAt = 0;
        configure(mc, want, range, bps);
        keyAt = System.currentTimeMillis();
        if (srv == null) {
            return;
        }
        new Thread(() -> {
            try {
                JsonObject web = new JsonObject();
                web.addProperty("on", want);
                JsonObject o = new JsonObject();
                o.addProperty("srv", srv);
                o.add("web", web);
                post("/api/control", o.toString());
            } catch (Throwable ignored) {
                // viewer off: switched only here, the next poll may switch it back
            }
        }, "webmatica-printer-key").start();
    }

    // ------------------------------------------------------------ HTTP thread

    private volatile String serverId;
    private volatile String dim;
    private volatile BlockPos center;
    private volatile Fetched fetched;
    private Thread thread;

    private record Fetched(List<String> palette, int[] blocks, long at) {
    }

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new Thread(this::loop, "webmatica-printer");
        thread.setDaemon(true);
        thread.start();
    }

    private static String base() {
        String u = PositionReporter.url();
        int i = u.indexOf("/api/");
        return i > 0 ? u.substring(0, i) : "http://127.0.0.1:8770";
    }

    private void loop() {
        while (true) {
            try {
                String srv = serverId, d = dim;
                BlockPos c = center;
                if (on && srv != null && d != null && c != null) {
                    fetched = fetch(srv, d, c, (int) Math.ceil(range) + 3);
                }
                Thread.sleep(on ? 400L : 1000L);
            } catch (InterruptedException ie) {
                return;
            } catch (Throwable t) {
                try { Thread.sleep(2000L); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private static Fetched fetch(String srv, String dim, BlockPos c, int r) throws Exception {
        URI uri = URI.create(base() + "/api/printer/blocks?srv=" + URLEncoder.encode(srv, StandardCharsets.UTF_8)
                + "&dim=" + URLEncoder.encode(dim, StandardCharsets.UTF_8)
                + "&x=" + c.getX() + "&y=" + c.getY() + "&z=" + c.getZ() + "&r=" + r);
        HttpURLConnection con = (HttpURLConnection) uri.toURL().openConnection();
        con.setConnectTimeout(800);
        con.setReadTimeout(2000);
        try (InputStream in = con.getInputStream()) {
            JsonObject o = JsonParser.parseString(new String(in.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            List<String> pal = new ArrayList<>();
            for (JsonElement e : o.getAsJsonArray("palette")) {
                pal.add(e.getAsString());
            }
            JsonArray arr = o.getAsJsonArray("blocks");
            int[] b = new int[arr.size()];
            for (int i = 0; i < b.length; i++) {
                b[i] = arr.get(i).getAsInt();
            }
            return new Fetched(pal, b, System.currentTimeMillis());
        } finally {
            con.disconnect();
        }
    }

    private static void post(String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) URI.create(base() + path).toURL().openConnection();
        c.setConnectTimeout(800);
        c.setReadTimeout(1500);
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
    }

    // ------------------------------------------------------------ Client thread

    private final Map<BlockPos, BlockState> targets = new HashMap<>();
    private final Map<String, BlockState> states = new HashMap<>();
    private Fetched applied;
    private ClientLevel lastLevel;
    private int cooldown;
    private long placed;
    private int todo, wrong, blocked, noItem;
    private final Map<String, Integer> missingItems = new LinkedHashMap<>();

    public void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null) {
            serverId = null;
            return;
        }
        serverId = RecordingTarget.serverId(mc);
        Identifier id = level.dimension().identifier();
        dim = "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
        center = p.blockPosition();
        if (level != lastLevel) {
            lastLevel = level;
            targets.clear();
            applied = null;
        }
        if (!on) {
            return;
        }
        Fetched f = fetched;
        if (f != null && f != applied) {
            applied = f;
            readTargets(f);
        }
        if (f == null || System.currentTimeMillis() - f.at() > 5000L) {
            return;       // viewer silent: rather do nothing than place from an old list
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (Mc.screen(mc) != null || p.isSpectator() || mc.gameMode == null) {
            return;
        }
        if (placeOne(mc, p, level)) {
            placed++;
            double pace = ServerSafe.on() ? Math.min(bps, SAFE_BPS) : bps;
            cooldown = Math.max(0, (int) Math.ceil(20.0 / pace) - 1);
        }
    }

    private void readTargets(Fetched f) {
        targets.clear();
        List<BlockState> pal = new ArrayList<>(f.palette().size());
        for (String s : f.palette()) {
            pal.add(states.computeIfAbsent(s, WebPrinter::parse));
        }
        int[] b = f.blocks();
        for (int i = 0; i + 3 < b.length; i += 4) {
            BlockState st = b[i + 3] < pal.size() ? pal.get(b[i + 3]) : null;
            if (st != null) {
                targets.put(new BlockPos(b[i], b[i + 1], b[i + 2]), st);
            }
        }
    }

    /** Places the next block (lowest first, then nearest). True when one was placed. */
    private boolean placeOne(Minecraft mc, LocalPlayer p, ClientLevel level) {
        boolean safe = ServerSafe.on();
        Vec3 eye = p.getEyePosition();
        double reach = safe ? Math.min(range, p.blockInteractionRange()) : Math.min(range, p.blockInteractionRange() + 1.0);
        List<BlockPos> open = new ArrayList<>();
        int nTodo = 0, nWrong = 0;
        for (Map.Entry<BlockPos, BlockState> e : targets.entrySet()) {
            BlockState have = level.getBlockState(e.getKey());
            BlockState want = e.getValue();
            if (matches(have, want)) {
                continue;
            }
            nTodo++;
            if (!have.canBeReplaced() && !slabMerge(have, want)) {
                nWrong++;
                continue;
            }
            if (Vec3.atCenterOf(e.getKey()).distanceTo(eye) <= reach + 1.0) {
                open.add(e.getKey());
            }
        }
        todo = nTodo;
        wrong = nWrong;
        open.sort((a, b) -> a.getY() != b.getY() ? Integer.compare(a.getY(), b.getY())
                : Double.compare(Vec3.atCenterOf(a).distanceToSqr(eye), Vec3.atCenterOf(b).distanceToSqr(eye)));
        int stuck = 0, item = 0;
        Map<String, Integer> missing = new LinkedHashMap<>();
        for (BlockPos pos : open) {
            BlockState want = targets.get(pos);
            Item it = want.getBlock().asItem();
            if (it == Items.AIR) {
                continue;
            }
            int slot = findItem(p.getInventory(), it, safe);
            if (slot < 0) {
                item++;
                missing.merge(BuiltInRegistries.ITEM.getKey(it).toString(), 1, Integer::sum);
                continue;
            }
            if (blocksPlayer(level, pos, want, p)) {
                continue;
            }
            Plan plan = plan(level, p, pos, want, p.getInventory().getItem(slot).isEmpty() ? new ItemStack(it) : p.getInventory().getItem(slot), safe, eye, reach);
            if (plan == null) {
                stuck++;
                continue;
            }
            blocked = stuck;
            noItem = item;
            missingItems.clear();
            missingItems.putAll(missing);
            return place(mc, p, slot, plan);
        }
        blocked = stuck;
        noItem = item;
        missingItems.clear();
        missingItems.putAll(missing);
        return false;
    }

    private record Plan(BlockHitResult hit, float yaw, float pitch, boolean turn) {
    }

    /**
     * Finds a click that gives exactly the wanted block: which neighbour to click
     * on, where on its face, and (outside server-safe) which way to look.
     */
    private Plan plan(ClientLevel level, LocalPlayer p, BlockPos pos, BlockState want, ItemStack stack,
                      boolean safe, Vec3 eye, double reach) {
        float yaw0 = p.getYRot(), pitch0 = p.getXRot();
        List<BlockHitResult> hits = clicks(level, pos, want, eye, reach, safe, p);
        if (hits.isEmpty()) {
            return null;
        }
        try {
            // first without turning: what you look at right now
            for (BlockHitResult h : hits) {
                if (matches(predict(p, stack, h), want)) {
                    return new Plan(h, yaw0, pitch0, false);
                }
            }
            if (safe) {
                return null;
            }
            boolean sixteen = want.getBlock().getStateDefinition().getProperty("rotation") != null;
            int steps = sixteen ? 16 : 4;
            for (float pitch : new float[]{0f, 75f, -75f}) {
                for (int i = 0; i < steps; i++) {
                    float yaw = i * 360f / steps;
                    p.setYRot(yaw);
                    p.setXRot(pitch);
                    for (BlockHitResult h : hits) {
                        if (matches(predict(p, stack, h), want)) {
                            return new Plan(h, yaw, pitch, true);
                        }
                    }
                }
            }
            return null;
        } finally {
            p.setYRot(yaw0);
            p.setXRot(pitch0);
        }
    }

    /** Possible clicks: on each solid neighbour's face that touches pos, a few points per face. */
    private static List<BlockHitResult> clicks(ClientLevel level, BlockPos pos, BlockState want, Vec3 eye, double reach,
                                               boolean safe, LocalPlayer p) {
        List<BlockHitResult> out = new ArrayList<>();
        BlockState have = level.getBlockState(pos);
        if (slabMerge(have, want)) {
            // second half of a double slab: click into the slab that is already there
            Direction face = have.getValue(SlabBlock.TYPE) == SlabType.BOTTOM ? Direction.UP : Direction.DOWN;
            Vec3 hit = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face == Direction.UP ? 0.0 : -0.5, face.getStepZ() * 0.5);
            if (hit.distanceTo(eye) <= reach && (!safe || visible(level, p, eye, pos, face, hit))) {
                out.add(new BlockHitResult(hit, face, pos, false));
            }
            return out;
        }
        for (Direction d : Direction.values()) {
            BlockPos n = pos.relative(d);
            BlockState ns = level.getBlockState(n);
            if (ns.canBeReplaced() || ns.getCollisionShape(level, n).isEmpty() || interactive(ns)) {
                continue;
            }
            Direction face = d.getOpposite();
            Vec3 c = Vec3.atCenterOf(n).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            List<Vec3> points = new ArrayList<>();
            points.add(c);
            if (face.getAxis() != Direction.Axis.Y) {
                points.add(c.add(0, 0.3, 0));
                points.add(c.add(0, -0.3, 0));
            }
            for (Vec3 hit : points) {
                if (hit.distanceTo(eye) > reach) {
                    continue;
                }
                if (safe && !visible(level, p, eye, n, face, hit)) {
                    continue;
                }
                out.add(new BlockHitResult(hit, face, n, false));
            }
        }
        return out;
    }

    /** The face points at the player and nothing is in between. */
    private static boolean visible(ClientLevel level, LocalPlayer p, Vec3 eye, BlockPos n, Direction face, Vec3 hit) {
        Vec3 toEye = eye.subtract(hit);
        if (toEye.x * face.getStepX() + toEye.y * face.getStepY() + toEye.z * face.getStepZ() <= 0) {
            return false;
        }
        Vec3 into = hit.add(face.getStepX() * -0.05, face.getStepY() * -0.05, face.getStepZ() * -0.05);
        BlockHitResult r = level.clip(new ClipContext(eye, into, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return r.getType() == HitResult.Type.BLOCK && r.getBlockPos().equals(n) && r.getDirection() == face;
    }

    /** Clicking it would open or switch something instead of placing. */
    private static boolean interactive(BlockState s) {
        Block b = s.getBlock();
        return b instanceof EntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
                || b instanceof DiodeBlock || b instanceof NoteBlock || b instanceof CraftingTableBlock
                || b instanceof AnvilBlock || b instanceof GrindstoneBlock || b instanceof StonecutterBlock
                || b instanceof LoomBlock || b instanceof CakeBlock || b instanceof ComposterBlock
                || b instanceof RespawnAnchorBlock || b instanceof FlowerPotBlock || s.is(Blocks.REDSTONE_WIRE)
                || b instanceof BellBlock;
    }

    private static BlockState predict(LocalPlayer p, ItemStack stack, BlockHitResult hit) {
        try {
            BlockPlaceContext ctx = new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit);
            if (!(stack.getItem() instanceof BlockItem bi) || !ctx.canPlace()) {
                return null;
            }
            return bi.getBlock().getStateForPlacement(ctx);
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean blocksPlayer(ClientLevel level, BlockPos pos, BlockState want, LocalPlayer p) {
        VoxelShape s = want.getCollisionShape(level, pos);
        if (s.isEmpty()) {
            return false;
        }
        AABB box = s.bounds().move(pos);
        return box.intersects(p.getBoundingBox());
    }

    private boolean place(Minecraft mc, LocalPlayer p, int slot, Plan plan) {
        Inventory inv = p.getInventory();
        if (slot >= 9) {
            // from the main inventory into the selected hotbar slot (never in server-safe)
            Mc.swap(mc, p.inventoryMenu.containerId, slot, inv.getSelectedSlot(), p);
        } else if (slot != inv.getSelectedSlot()) {
            inv.setSelectedSlot(slot);
        }
        float yaw0 = p.getYRot(), pitch0 = p.getXRot();
        if (plan.turn()) {
            p.connection.send(Mc.rotation(p, plan.yaw(), plan.pitch()));
            p.setYRot(plan.yaw());
            p.setXRot(plan.pitch());
        }
        try {
            mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, plan.hit());
            Mc.swing(mc, p, InteractionHand.MAIN_HAND);
        } finally {
            if (plan.turn()) {
                p.setYRot(yaw0);
                p.setXRot(pitch0);
                p.connection.send(Mc.rotation(p, yaw0, pitch0));
            }
        }
        return true;
    }

    /** Hotbar slot (0-8) or, outside server-safe, inventory slot (9-35) with the item; -1 if none. */
    private static int findItem(Inventory inv, Item it, boolean safe) {
        int sel = inv.getSelectedSlot();
        if (inv.getItem(sel).is(it)) {
            return sel;
        }
        for (int i = 0; i < 9; i++) {
            if (inv.getItem(i).is(it)) {
                return i;
            }
        }
        if (!safe) {
            for (int i = 9; i < 36; i++) {
                if (inv.getItem(i).is(it)) {
                    return i;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------ Block states

    /** Same block, and the same where the placement direction decides (facing, half, ...). */
    static boolean matches(BlockState have, BlockState want) {
        if (have == null || have.getBlock() != want.getBlock()) {
            return false;
        }
        boolean chest = want.getBlock() instanceof ChestBlock;
        for (Property<?> prop : want.getProperties()) {
            String n = prop.getName();
            if (!ORIENTATION.contains(n) || chest && n.equals("type")) {
                continue;
            }
            if (!have.hasProperty(prop) || !have.getValue(prop).equals(want.getValue(prop))) {
                return false;
            }
        }
        return true;
    }

    /** A single slab where the target is the double slab of the same kind. */
    private static boolean slabMerge(BlockState have, BlockState want) {
        return have.getBlock() == want.getBlock() && have.getBlock() instanceof SlabBlock
                && want.getValue(SlabBlock.TYPE) == SlabType.DOUBLE && have.getValue(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    /** "minecraft:oak_stairs[facing=north,half=bottom]" -> state; null if unknown. */
    static BlockState parse(String s) {
        int i = s.indexOf('[');
        Identifier id = Identifier.tryParse(i < 0 ? s : s.substring(0, i));
        if (id == null) {
            return null;
        }
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        if (block.isEmpty()) {
            return null;
        }
        BlockState st = block.get().defaultBlockState();
        if (i >= 0 && s.endsWith("]")) {
            for (String kv : s.substring(i + 1, s.length() - 1).split(",")) {
                int eq = kv.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                Property<?> prop = block.get().getStateDefinition().getProperty(kv.substring(0, eq).trim());
                if (prop != null) {
                    st = with(st, prop, kv.substring(eq + 1).trim());
                }
            }
        }
        return st;
    }

    private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> prop, String value) {
        return prop.getValue(value).map(v -> s.setValue(prop, v)).orElse(s);
    }

    // ------------------------------------------------------------ Status for the viewer

    JsonObject status() {
        JsonObject o = new JsonObject();
        o.addProperty("on", on);
        o.addProperty("range", range);
        o.addProperty("bps", bps);
        o.addProperty("safe", ServerSafe.on());
        o.addProperty("placed", placed);
        o.addProperty("todo", on ? todo : 0);
        o.addProperty("wrong", on ? wrong : 0);
        o.addProperty("blocked", on ? blocked : 0);
        o.addProperty("noItem", on ? noItem : 0);
        JsonArray miss = new JsonArray();
        if (on) {
            missingItems.keySet().stream().limit(6).forEach(miss::add);
        }
        o.add("missing", miss);
        return o;
    }
}
