package io.github.ehnkode.webmatica;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
//? if <26.1 {
import net.minecraft.world.level.gamerules.GameRules;
//?}

/**
 * Game tests for the dev client: {@code ./gradlew runClient -Pautotest=all -Pautoquit}
 * (or a list such as {@code -Pautotest=walk-stairs,printer-orient}).
 *
 * Creates a fresh superflat survival world, builds each scenario's course with
 * commands, lets the printer or the own walking engine do the job on its own
 * and checks the result: every block right, goal reached, no damage taken.
 * Results go to {@code run/<version>/autotest/results.txt}. No viewer needed.
 *
 * Only active when the system property {@code webmatica.autotest} is set.
 * Idea from Test-RTS (MIT), written anew for Webmatica.
 */
final class AutoTest {

    private static final String WORLD = "webmatica-autotest";
    /** Feet height on the classic superflat (grass at y -61). */
    private static final int Y = -60;
    private static final int ARENA = 40;

    private AutoTest() {
    }

    // ------------------------------------------------------------ Scenarios

    private enum Kind { WALK, UNREACHABLE, PRINT, FETCH }

    private record Scenario(String name, Kind kind, List<String> setup, BlockPos start, BlockPos goal,
                            Map<BlockPos, String> blocks, boolean safe, int timeoutTicks) {
    }

    private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<>();

    private static void walk(String name, BlockPos start, BlockPos goal, int timeoutSec, String... setup) {
        SCENARIOS.put(name, new Scenario(name, Kind.WALK, List.of(setup), start, goal, Map.of(), false, timeoutSec * 20));
    }

    private static void print(String name, boolean safe, Map<BlockPos, String> blocks, String... setup) {
        SCENARIOS.put(name, new Scenario(name, Kind.PRINT, List.of(setup), new BlockPos(0, Y, 0), null, blocks, safe, 30 * 20));
    }

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, Y + y, z);
    }

    private static String fill(int x0, int y0, int z0, int x1, int y1, int z1, String block) {
        return String.format(Locale.ROOT, "fill %d %d %d %d %d %d %s", x0, Y + y0, z0, x1, Y + y1, z1, block);
    }

    static {
        walk("walk-flat", at(0, 0, 0), at(30, 0, 10), 30);
        walk("walk-stairs", at(0, 0, 0), at(12, 5, 0), 40,
                fill(5, 0, -1, 5, 0, 1, "stone"), fill(6, 0, -1, 6, 1, 1, "stone"), fill(7, 0, -1, 7, 2, 1, "stone"),
                fill(8, 0, -1, 8, 3, 1, "stone"), fill(9, 0, -1, 14, 4, 1, "stone"));
        walk("walk-slabs", at(0, 0, 0), at(9, 2, 0), 40,
                fill(4, 0, -1, 4, 0, 1, "stone_slab"), fill(5, 0, -1, 5, 0, 1, "stone"),
                fill(6, 0, -1, 6, 0, 1, "stone"), fill(6, 1, -1, 6, 1, 1, "stone_slab"),
                fill(7, 0, -1, 10, 1, 1, "stone"));
        walk("walk-wall", at(0, 0, 0), at(12, 0, 0), 60,
                fill(6, 0, -12, 6, 3, 12, "stone"));
        walk("walk-drop", at(0, 3, 0), at(9, 0, 0), 30,
                fill(-1, 0, -1, 4, 2, 1, "stone"));
        walk("walk-lava", at(0, 0, 0), at(12, 0, 0), 60,
                fill(5, -1, -4, 7, -1, 4, "lava"));
        walk("walk-water", at(0, 0, 0), at(12, 0, 0), 60,
                fill(4, -1, -6, 8, -1, 6, "water"));
        walk("walk-far", at(0, 0, 0), at(150, 0, 30), 120);
        SCENARIOS.put("walk-unreachable", new Scenario("walk-unreachable", Kind.UNREACHABLE,
                List.of(fill(10, 0, -2, 14, 3, 2, "bedrock hollow")), at(0, 0, 0), at(12, 0, 0), Map.of(), false, 60 * 20));
        SCENARIOS.put("fetch-chest", new Scenario("fetch-chest", Kind.FETCH,
                List.of(fill(6, 0, -5, 6, 2, 5, "stone"), fill(14, 0, 3, 14, 0, 3, "chest"),
                        String.format(Locale.ROOT, "item replace block 14 %d 3 container.0 with cobblestone 32", Y)),
                at(0, 0, 0), at(14, 0, 3), Map.of(), false, 90 * 20));

        Map<BlockPos, String> wall = new LinkedHashMap<>();
        for (int x = -2; x <= 2; x++) {
            for (int y = 0; y < 4; y++) {
                wall.put(at(x, y, 3), "minecraft:stone_bricks");
            }
        }
        print("printer-wall", false, wall);
        // server-safe only clicks faces the player can see: from the ground that is
        // the two rows below eye height (a real player couldn't do more from here either)
        Map<BlockPos, String> low = new LinkedHashMap<>();
        wall.forEach((pos, st) -> {
            if (pos.getY() < Y + 2) {
                low.put(pos, st);
            }
        });
        print("printer-safe", true, low);

        Map<BlockPos, String> orient = new LinkedHashMap<>();
        orient.put(at(-2, 0, 3), "minecraft:oak_log[axis=x]");
        orient.put(at(-1, 0, 3), "minecraft:oak_log[axis=z]");
        orient.put(at(0, 0, 3), "minecraft:oak_log[axis=y]");
        orient.put(at(1, 0, 3), "minecraft:furnace[facing=east]");
        orient.put(at(2, 0, 3), "minecraft:furnace[facing=north]");
        orient.put(at(-2, 0, -3), "minecraft:stone_brick_stairs[facing=north,half=bottom]");
        orient.put(at(-1, 0, -3), "minecraft:stone_brick_stairs[facing=east,half=bottom]");
        orient.put(at(0, 0, -3), "minecraft:stone_brick_stairs[facing=south,half=bottom]");
        orient.put(at(1, 0, -3), "minecraft:stone_brick_stairs[facing=west,half=bottom]");
        orient.put(at(-2, 1, 3), "minecraft:stone_brick_stairs[facing=west,half=top]");
        orient.put(at(-1, 1, 3), "minecraft:stone_brick_slab[type=top]");
        orient.put(at(0, 1, 3), "minecraft:stone_brick_slab[type=bottom]");
        // logs take their axis from the clicked face, so they need a neighbour on that axis;
        // the upper stair and slab need a side face to click high up: two stone pillars
        print("printer-orient", false, orient, fill(-3, 0, 3, -3, 1, 3, "stone"), fill(-1, 0, 4, -1, 1, 4, "stone"));
    }

    private static final Map<String, List<String>> ITEMS = Map.of(
            "printer-wall", List.of("stone_bricks 64"),
            "printer-safe", List.of("stone_bricks 64"),
            "printer-orient", List.of("oak_log 16", "furnace 4", "stone_brick_stairs 16", "stone_brick_slab 16"));

    // ------------------------------------------------------------ Run

    private enum Phase { TITLE, JOINING, NEXT, SETUP, RUN, FINISHED }

    private static Phase phase = Phase.TITLE;
    private static final List<Scenario> queue = new ArrayList<>();
    private static Scenario current;
    private static int wait;
    private static int ticks;
    private static boolean hurt;
    private static String hurtWhy;
    private static long startPlaced;
    private static int passed;
    private static int total;
    private static Path out;
    private static List<String> palette;
    private static int[] packed;

    static void install() {
        String spec = System.getProperty("webmatica.autotest", "all");
        for (String n : spec.split(",")) {
            n = n.trim();
            if (n.equals("all")) {
                queue.addAll(SCENARIOS.values());
            } else if (n.endsWith("*")) {
                String pre = n.substring(0, n.length() - 1);
                SCENARIOS.forEach((k, v) -> {
                    if (k.startsWith(pre)) {
                        queue.add(v);
                    }
                });
            } else if (SCENARIOS.containsKey(n)) {
                queue.add(SCENARIOS.get(n));
            } else if (!n.isEmpty()) {
                System.out.println("[AUTOTEST] unknown scenario " + n + ", known: " + String.join(", ", SCENARIOS.keySet()));
            }
        }
        total = queue.size();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            try {
                tick(mc);
            } catch (RuntimeException e) {
                e.printStackTrace();
                result("CRASH", current != null ? current.name() : "-", e.toString());
                end(mc);
            }
        });
    }

    private static void tick(Minecraft mc) {
        if (phase == Phase.FINISHED) {
            return;
        }
        if (wait > 0) {
            wait--;
            return;
        }
        switch (phase) {
            case TITLE -> {
                // the title screen, or on a first start the accessibility screen before it
                if (mc.level == null && Mc.screen(mc) != null && ++ticks > 40) {
                    out = mc.gameDirectory.toPath().resolve("autotest");
                    try {
                        Files.createDirectories(out);
                        Files.writeString(out.resolve("results.txt"), "");
                    } catch (IOException ignored) {
                    }
                    mc.options.pauseOnLostFocus = false;
                    mc.options.onboardAccessibility = false;
                    mc.options.renderDistance().set(8);
                    mc.options.menuBackgroundBlurriness().set(0);
                    mc.options.save();
                    createWorld(mc);
                    phase = Phase.JOINING;
                }
            }
            case JOINING -> {
                if (mc.player != null && mc.level != null && Mc.screen(mc) == null) {
                    cmd(mc, "time set noon");
                    cmd(mc, "weather clear");
                    wait = 60;
                    phase = Phase.NEXT;
                }
            }
            case NEXT -> {
                if (queue.isEmpty()) {
                    result("DONE", passed + "/" + total, "passed");
                    end(mc);
                    return;
                }
                current = queue.remove(0);
                setup(mc, current);
                wait = 40;
                phase = Phase.SETUP;
            }
            case SETUP -> {
                start(mc, current);
                ticks = 0;
                hurt = false;
                hurtWhy = null;
                phase = Phase.RUN;
            }
            case RUN -> {
                ticks++;
                LocalPlayer p = mc.player;
                if (p == null) {
                    finish(mc, false, "player gone");
                    return;
                }
                if (!hurt && (p.hurtTime > 0 || p.isOnFire() || p.isInLava())) {
                    hurt = true;
                    hurtWhy = p.isInLava() ? "in lava" : p.isOnFire() ? "on fire" : "took damage";
                }
                check(mc, p, current);
            }
            default -> {
            }
        }
    }

    private static void setup(Minecraft mc, Scenario s) {
        WebPrinter.getInstance().testFeed(null, null, 0, 0);
        NativeWalker.stop();
        Walker.stop();
        FetchTask.getInstance().cancel();
        ServerSafe.set(mc, "autotest", false);
        for (int y = 0; y < 24; y += 4) {
            cmd(mc, fill(-ARENA, y, -ARENA, ARENA, y + 3, ARENA, "air"));
        }
        cmd(mc, fill(-ARENA, -1, -ARENA, ARENA, -1, ARENA, "grass_block"));
        cmd(mc, "kill @e[type=item]");
        cmd(mc, "gamemode survival");
        cmd(mc, "clear @s");
        cmd(mc, "effect clear @s");
        cmd(mc, "effect give @s saturation 1 10 true");
        for (String c : s.setup()) {
            cmd(mc, c);
        }
        for (String it : ITEMS.getOrDefault(s.name(), List.of())) {
            cmd(mc, "give @s " + it);
        }
        BlockPos st = s.start();
        cmd(mc, String.format(Locale.ROOT, "tp @s %d %d %d -90 20", st.getX(), st.getY(), st.getZ()));
        System.out.println("[AUTOTEST] setting up " + s.name());
    }

    private static void start(Minecraft mc, Scenario s) {
        switch (s.kind()) {
            case WALK, UNREACHABLE -> NativeWalker.walkOnto(s.goal());
            case FETCH -> {
                JsonObject cmd = new JsonObject();
                cmd.addProperty("x", s.goal().getX());
                cmd.addProperty("y", s.goal().getY());
                cmd.addProperty("z", s.goal().getZ());
                JsonArray items = new JsonArray();
                JsonObject it = new JsonObject();
                it.addProperty("id", "minecraft:cobblestone");
                it.addProperty("count", 32);
                items.add(it);
                cmd.add("items", items);
                FetchTask.getInstance().start("autotest", cmd);
            }
            case PRINT -> {
                ServerSafe.set(mc, "autotest", s.safe());
                palette = new ArrayList<>();
                List<Integer> b = new ArrayList<>();
                s.blocks().forEach((pos, state) -> {
                    int i = palette.indexOf(state);
                    if (i < 0) {
                        palette.add(state);
                        i = palette.size() - 1;
                    }
                    b.add(pos.getX());
                    b.add(pos.getY());
                    b.add(pos.getZ());
                    b.add(i);
                });
                packed = b.stream().mapToInt(Integer::intValue).toArray();
                startPlaced = WebPrinter.getInstance().placedCount();
                WebPrinter.getInstance().testFeed(palette, packed, 4.5, 10);
            }
        }
    }

    private static void check(Minecraft mc, LocalPlayer p, Scenario s) {
        double secs = ticks / 20.0;
        boolean timeout = ticks > s.timeoutTicks();
        switch (s.kind()) {
            case WALK -> {
                double d = p.position().distanceTo(net.minecraft.world.phys.Vec3.atBottomCenterOf(s.goal()));
                if (hurt) {
                    finish(mc, false, hurtWhy);
                } else if (!NativeWalker.active()) {
                    finish(mc, d < 1.5 && !NativeWalker.failed(),
                            NativeWalker.failed() ? "gave up " + fmt(d) + " blocks away" : fmt(d) + " blocks from goal");
                } else if (timeout) {
                    finish(mc, false, "timeout, " + fmt(d) + " blocks away");
                }
            }
            case UNREACHABLE -> {
                if (!NativeWalker.active()) {
                    finish(mc, NativeWalker.failed() && !hurt, NativeWalker.failed() ? "gave up as expected" : "claims to have arrived");
                } else if (timeout) {
                    finish(mc, false, "still trying after " + fmt(secs) + " s");
                }
            }
            case FETCH -> {
                int have = p.getInventory().countItem(Items.COBBLESTONE);
                if (!FetchTask.getInstance().busy()) {
                    finish(mc, have >= 32 && !hurt, have + " cobblestone" + (hurt ? ", " + hurtWhy : ""));
                } else if (timeout) {
                    finish(mc, false, "timeout, " + have + " cobblestone");
                }
            }
            case PRINT -> {
                WebPrinter.getInstance().testFeed(palette, packed, 4.5, 10);
                int wrong = 0;
                for (Map.Entry<BlockPos, String> e : s.blocks().entrySet()) {
                    BlockState have = mc.level.getBlockState(e.getKey());
                    if (!WebPrinter.matches(have, WebPrinter.parse(e.getValue()))) {
                        wrong++;
                    }
                }
                long placed = WebPrinter.getInstance().placedCount() - startPlaced;
                double rate = placed / Math.max(0.05, secs);
                if (wrong == 0) {
                    boolean ok = !s.safe() || rate <= 5.5;
                    finish(mc, ok, s.blocks().size() + " blocks in " + fmt(secs) + " s (" + fmt(rate) + "/s)"
                            + (ok ? "" : ", faster than server-safe allows"));
                } else if (timeout) {
                    finish(mc, false, wrong + " of " + s.blocks().size() + " blocks missing or wrong: " + wrongList(mc, s));
                }
            }
        }
    }

    private static String wrongList(Minecraft mc, Scenario s) {
        List<String> l = new ArrayList<>();
        s.blocks().forEach((pos, want) -> {
            BlockState have = mc.level.getBlockState(pos);
            if (!WebPrinter.matches(have, WebPrinter.parse(want)) && l.size() < 5) {
                l.add(pos.toShortString() + " want " + want + " have " + have);
            }
        });
        return String.join("; ", l);
    }

    private static void finish(Minecraft mc, boolean ok, String info) {
        if (ok) {
            passed++;
        }
        result(ok ? "PASS" : "FAIL", current.name(), fmt(ticks / 20.0) + " s · " + info);
        WebPrinter.getInstance().testFeed(null, null, 0, 0);
        NativeWalker.stop();
        FetchTask.getInstance().cancel();
        ServerSafe.set(mc, "autotest", false);
        wait = 20;
        phase = Phase.NEXT;
    }

    private static void end(Minecraft mc) {
        phase = Phase.FINISHED;
        if (Boolean.getBoolean("webmatica.autotest.quit")) {
            mc.stop();
        }
    }

    private static void result(String status, String name, String info) {
        String line = String.format(Locale.ROOT, "%-5s %-18s %s", status, name, info);
        System.out.println("[AUTOTEST] " + line);
        if (out != null) {
            try {
                Files.writeString(out.resolve("results.txt"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    private static String fmt(double d) {
        return String.format(Locale.ROOT, "%.1f", d);
    }

    private static void cmd(Minecraft mc, String c) {
        if (mc.player != null) {
            mc.player.connection.sendCommand(c);
        }
    }

    // ------------------------------------------------------------ World

    private static void createWorld(Minecraft mc) {
        Path dir = mc.gameDirectory.toPath().resolve("saves").resolve(WORLD);
        if (Files.exists(dir)) {
            try (Stream<Path> s = Files.walk(dir)) {
                s.sorted(Comparator.reverseOrder()).forEach(f -> f.toFile().delete());
            } catch (IOException ignored) {
            }
        }
        //? if >=26.1 {
        /*LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL,
                new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true, WorldDataConfiguration.DEFAULT);
        *///?} else {
        LevelSettings settings = new LevelSettings(WORLD, GameType.SURVIVAL, false, Difficulty.PEACEFUL, true,
                new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures()), WorldDataConfiguration.DEFAULT);
        //?}
        mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(1234L, false, false),
                lookup -> lookup.lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                Mc.screen(mc));
    }
}
