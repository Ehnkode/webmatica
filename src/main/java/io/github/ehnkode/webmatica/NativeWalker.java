package io.github.ehnkode.webmatica;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Webmatica's own walking, for when Baritone is missing or stuck (see {@link Walker}).
 *
 * A* over the positions the player can stand on (feet block): walk, walk
 * diagonally, step or jump up one block, drop down up to three. Floor heights
 * come from the real collision shapes (slabs, carpets, stairs), so a path never
 * asks for a jump higher than the player can make. It never breaks or places a
 * block, avoids lava, fire, cactus and the like, and only walks through shallow
 * water. Far goals and unloaded chunks: it walks to the best point it found and
 * plans again from there.
 *
 * Walking presses the player's own movement keys and turns the view towards
 * the next step, like a player would. Client thread.
 */
final class NativeWalker {

    private static final int MAX_NODES = 25_000;
    private static final int MAX_DROP = 3;
    private static final double MAX_RISE = 1.2;
    /** How long without progress before planning again, and how often before giving up. */
    private static final long STUCK_MS = 2500L;
    private static final int MAX_REPLANS = 6;

    private NativeWalker() {
    }

    // ------------------------------------------------------------ State

    private static Predicate<BlockPos> goal;
    private static BlockPos goalCenter;
    private static List<BlockPos> path = Collections.emptyList();
    private static int index;
    private static boolean active;
    private static boolean failed;
    private static int replans;
    private static long progressAt;
    private static double bestDist;
    /** Distance to the goal when the current path was planned. */
    private static double planDist;
    private static boolean keysHeld;

    /** Walk next to the block (close enough to open it). */
    static void walkTo(BlockPos target) {
        start(p -> p.getY() >= target.getY() - 2 && p.getY() <= target.getY() + 1
                && Math.abs(p.getX() - target.getX()) <= 1 && Math.abs(p.getZ() - target.getZ()) <= 1
                && !p.equals(target), target);
    }

    /** Walk onto the block. */
    static void walkOnto(BlockPos target) {
        start(p -> p.equals(target), target);
    }

    /** Walk to x/z, any height. */
    static void walkXZ(int x, int z, int yHint) {
        start(p -> p.getX() == x && p.getZ() == z, new BlockPos(x, yHint, z));
    }

    private static void start(Predicate<BlockPos> g, BlockPos center) {
        goal = g;
        goalCenter = center;
        active = true;
        failed = false;
        replans = 0;
        path = Collections.emptyList();
        index = 0;
        progressAt = System.currentTimeMillis();
        bestDist = Double.MAX_VALUE;
        planDist = Double.MAX_VALUE;
    }

    static void stop() {
        active = false;
        goal = null;
        path = Collections.emptyList();
        release();
    }

    static boolean active() {
        return active;
    }

    /** The last goal ended without reaching it (no way found). */
    static boolean failed() {
        return failed;
    }

    // ------------------------------------------------------------ Following

    static void tick(Minecraft mc) {
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (!active) {
            return;
        }
        if (p == null || level == null) {
            stop();
            return;
        }
        if (AutoGuard.paused() || AutoGuard.eating() || Mc.screen(mc) != null) {
            release();
            return;
        }
        BlockPos feet = standingPos(level, p);
        if (goal.test(feet)) {
            stop();
            return;
        }
        long now = System.currentTimeMillis();
        double d = p.position().distanceTo(Vec3.atBottomCenterOf(goalCenter));
        if (d < bestDist - 0.5) {
            bestDist = d;
            progressAt = now;
        }
        boolean stuck = now - progressAt > STUCK_MS;
        if (path.isEmpty() || index >= path.size() || stuck) {
            if (d < planDist - 4) {
                replans = 0;            // a partial path got us closer: plan the next stretch
            } else if (stuck || !path.isEmpty()) {
                replans++;
            }
            if (replans > MAX_REPLANS) {
                failed = true;
                stop();
                return;
            }
            path = find(level, feet, goal, goalCenter);
            index = path.isEmpty() ? 0 : 1;
            progressAt = now;
            bestDist = d;
            planDist = d;
            if (path.size() < 2) {
                failed = true;
                stop();
                return;
            }
        }
        follow(mc, p, level);
    }

    private static void follow(Minecraft mc, LocalPlayer p, ClientLevel level) {
        BlockPos next = path.get(index);
        Vec3 pos = p.position();
        double dx = next.getX() + 0.5 - pos.x, dz = next.getZ() + 0.5 - pos.z;
        double flat = Math.sqrt(dx * dx + dz * dz);
        double floor = floorY(level, next);
        if (flat < 0.35 && Math.abs(pos.y - floor) < 0.7) {
            index++;
            if (index >= path.size()) {
                release();
                return;
            }
            next = path.get(index);
            dx = next.getX() + 0.5 - pos.x;
            dz = next.getZ() + 0.5 - pos.z;
            floor = floorY(level, next);
        }
        // turn towards the next step, at most 35 degrees a tick
        float want = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float turn = Mth.clamp(Mth.wrapDegrees(want - p.getYRot()), -35f, 35f);
        p.setYRot(p.getYRot() + turn);
        p.setXRot(Mth.clamp(p.getXRot(), -20f, 30f));
        boolean facing = Math.abs(Mth.wrapDegrees(want - p.getYRot())) < 50f;
        mc.options.keyUp.setDown(facing);
        boolean jump = floor - pos.y > 0.6 && p.onGround() && Math.sqrt(dx * dx + dz * dz) < 1.6
                || p.horizontalCollision && p.onGround()
                || p.isInWater() && floor >= pos.y - 0.2;
        mc.options.keyJump.setDown(jump);
        mc.options.keySprint.setDown(false);
        mc.options.keyShift.setDown(false);
        keysHeld = true;
    }

    private static void release() {
        if (!keysHeld) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        mc.options.keyUp.setDown(false);
        mc.options.keyJump.setDown(false);
        keysHeld = false;
    }

    // ------------------------------------------------------------ Path finding

    private record Node(BlockPos pos, double g, double f, Node parent) {
    }

    /** A* from start; when the goal is out of reach, the path to the closest point found. */
    static List<BlockPos> find(ClientLevel level, BlockPos start, Predicate<BlockPos> goal, BlockPos center) {
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f(), b.f()));
        Map<BlockPos, Double> best = new HashMap<>();
        Node first = new Node(start, 0, h(start, center), null);
        open.add(first);
        best.put(start, 0.0);
        Node closest = first;
        int n = 0;
        while (!open.isEmpty() && n++ < MAX_NODES) {
            Node cur = open.poll();
            if (cur.g() > best.getOrDefault(cur.pos(), Double.MAX_VALUE)) {
                continue;
            }
            if (goal.test(cur.pos())) {
                return trace(cur);
            }
            if (h(cur.pos(), center) < h(closest.pos(), center)) {
                closest = cur;
            }
            for (int[] d : DIRS) {
                BlockPos side = cur.pos().offset(d[0], 0, d[1]);
                if (d[0] != 0 && d[1] != 0) {
                    // diagonal only when both straight neighbours are free, so no corner gets cut
                    if (!clear(level, cur.pos().offset(d[0], 0, 0)) || !clear(level, cur.pos().offset(0, 0, d[1]))) {
                        continue;
                    }
                }
                BlockPos to = land(level, cur.pos(), side);
                if (to == null) {
                    continue;
                }
                double cost = (d[0] != 0 && d[1] != 0 ? 1.414 : 1.0) + cost(level, to) + (to.getY() < cur.pos().getY() ? 0.3 * (cur.pos().getY() - to.getY()) : 0)
                        + (to.getY() > cur.pos().getY() ? 0.5 : 0);
                double g = cur.g() + cost;
                if (g < best.getOrDefault(to, Double.MAX_VALUE)) {
                    best.put(to, g);
                    open.add(new Node(to, g, g + h(to, center), cur));
                }
            }
        }
        return closest == first ? Collections.emptyList() : trace(closest);
    }

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private static double h(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(), dy = a.getY() - b.getY();
        return Math.sqrt(dx * dx + dz * dz) + Math.abs(dy) * 0.5;
    }

    private static List<BlockPos> trace(Node n) {
        List<BlockPos> out = new ArrayList<>();
        for (Node c = n; c != null; c = c.parent()) {
            out.add(c.pos());
        }
        Collections.reverse(out);
        return out;
    }

    /**
     * Where the player ends up when stepping from {@code from} towards the column
     * of {@code side}: same height, one up, or up to three down. Null if none fits.
     */
    private static BlockPos land(ClientLevel level, BlockPos from, BlockPos side) {
        double floorFrom = floorY(level, from);
        for (int dy = 1; dy >= -MAX_DROP; dy--) {
            BlockPos to = side.above(dy);
            if (!level.isLoaded(to)) {
                return null;
            }
            if (!standable(level, to)) {
                if (dy <= 0 && !clear(level, to)) {
                    return null;        // a wall at this height: nothing further down is reachable
                }
                continue;
            }
            double rise = floorY(level, to) - floorFrom;
            if (rise > MAX_RISE) {
                continue;
            }
            if (rise > 0.6 && !clear(level, from.above(2))) {
                continue;               // no room to jump
            }
            if (dy < 0) {
                // dropping: the whole column above the landing spot must be free
                for (int y = dy + 1; y <= 0; y++) {
                    if (!clear(level, side.above(y)) || !clear(level, side.above(y + 1))) {
                        return null;
                    }
                }
            }
            return to;
        }
        return null;
    }

    /** Feet and head fit here and there is ground to stand on. */
    private static boolean standable(ClientLevel level, BlockPos pos) {
        BlockState feet = level.getBlockState(pos);
        if (danger(feet) || danger(level.getBlockState(pos.above()))) {
            return false;
        }
        double feetTop = top(level, pos, feet);
        if (feetTop > 0.5) {
            return false;               // a full block, not a floor
        }
        if (!clear(level, pos.above()) || !level.getBlockState(pos.above()).getFluidState().isEmpty()) {
            return false;               // no room for the head, or it would be under water
        }
        if (feetTop > 0 && !clear(level, pos.above(2)) && feetTop > 0.2) {
            return false;               // standing on a slab raises the head into the block above
        }
        if (feetTop > 0) {
            return true;
        }
        BlockPos below = pos.below();
        BlockState b = level.getBlockState(below);
        if (danger(b) || b.is(Blocks.MAGMA_BLOCK)) {
            return false;
        }
        FluidState fl = feet.getFluidState();
        if (!fl.isEmpty() && !fl.is(Fluids.WATER) && !fl.is(Fluids.FLOWING_WATER)) {
            return false;
        }
        double t = top(level, below, b);
        return t > 0.5 && t <= 1.0;
    }

    /** Nothing to collide with, and nothing that hurts. */
    private static boolean clear(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        return !danger(s) && s.getCollisionShape(level, pos).isEmpty();
    }

    private static double cost(ClientLevel level, BlockPos pos) {
        return level.getBlockState(pos).getFluidState().isEmpty() ? 0 : 2.0;
    }

    /** Top of the collision shape inside the block, 0..1.5; 0 for none. */
    private static double top(ClientLevel level, BlockPos pos, BlockState s) {
        VoxelShape shape = s.getCollisionShape(level, pos);
        return shape.isEmpty() ? 0 : shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    /** Height the player stands at on this spot. */
    static double floorY(ClientLevel level, BlockPos pos) {
        double t = top(level, pos, level.getBlockState(pos));
        if (t > 0) {
            return pos.getY() + t;
        }
        double b = top(level, pos.below(), level.getBlockState(pos.below()));
        return pos.getY() - 1 + b;
    }

    private static boolean danger(BlockState s) {
        Block b = s.getBlock();
        FluidState f = s.getFluidState();
        return f.is(Fluids.LAVA) || f.is(Fluids.FLOWING_LAVA)
                || b == Blocks.FIRE || b == Blocks.SOUL_FIRE || b == Blocks.CACTUS || b == Blocks.SWEET_BERRY_BUSH
                || b == Blocks.POWDER_SNOW || b == Blocks.WITHER_ROSE || b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
                || b == Blocks.COBWEB || b == Blocks.POINTED_DRIPSTONE;
    }

    /** The spot the player stands on (on a slab: the slab's own block). */
    private static BlockPos standingPos(ClientLevel level, LocalPlayer p) {
        return BlockPos.containing(p.getX(), p.getY() + 0.01, p.getZ());
    }
}
