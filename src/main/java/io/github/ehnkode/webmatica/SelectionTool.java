package io.github.ehnkode.webmatica;

import com.google.gson.JsonObject;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Area selection in game, the same way as in the 3D viewer (WorldEdit/Axiom style): left click
 * sets the first corner, right click the second, the mouse wheel pushes the side you look at
 * outwards or back in (sprint key = 8 blocks). Sneaking takes the air block in front of the face.
 * While the mode is on, clicks never reach the server, so nothing gets broken or placed by
 * accident. The box is drawn with particles and sent to the viewer with the position.
 */
final class SelectionTool {
    private static final double RANGE = 160;
    private static final int[] DIM_Y_OVERWORLD = {-64, 319};

    private static boolean active;
    private static BlockPos p1;
    private static BlockPos p2;
    private static String dim = "";
    private static int tick;

    private SelectionTool() {
    }

    static void register() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("webmatica", "selection"), (g, t) -> drawHint(new Gfx(g)));
    }

    static boolean active() {
        return active;
    }

    static void setActive(Minecraft mc, boolean on) {
        active = on;
        if (mc.player != null) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr(on ? "Auswahlmodus an" : "Auswahlmodus aus"))
                    .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
    }

    static void clear() {
        p1 = null;
        p2 = null;
    }

    /** {x0, y0, z0, x1, y1, z1} of the box in the current dimension, or null. */
    static int[] box(Minecraft mc) {
        if (mc.level == null || (p1 == null && p2 == null) || !dim.equals(dimId(mc))) {
            return null;
        }
        BlockPos a = p1 != null ? p1 : p2, b = p2 != null ? p2 : p1;
        return new int[] {Math.min(a.getX(), b.getX()), Math.min(a.getY(), b.getY()), Math.min(a.getZ(), b.getZ()),
                Math.max(a.getX(), b.getX()), Math.max(a.getY(), b.getY()), Math.max(a.getZ(), b.getZ())};
    }

    static String size(int[] b) {
        return (b[3] - b[0] + 1) + " × " + (b[4] - b[1] + 1) + " × " + (b[5] - b[2] + 1);
    }

    static String dimId(Minecraft mc) {
        return mc.level == null ? "" : mc.level.dimension().identifier().toString();
    }

    /** Dimension as the viewer names it ("overworld", "the_nether", ...). */
    static String viewerDim(Minecraft mc) {
        String d = dimId(mc);
        return d.contains(":") ? d.substring(d.indexOf(':') + 1) : d;
    }

    /** For the position report: the box, so the viewer shows the same selection. */
    static JsonObject json(Minecraft mc) {
        int[] b = box(mc);
        if (b == null) {
            return null;
        }
        JsonObject o = new JsonObject();
        o.addProperty("dim", viewerDim(mc));
        String[] k = {"x0", "y0", "z0", "x1", "y1", "z1"};
        for (int i = 0; i < 6; i++) {
            o.addProperty(k[i], b[i]);
        }
        return o;
    }

    /**
     * Start of the client tick, before the game handles the attack and use keys: take the
     * clicks for the corners and keep the keys from reaching the game.
     */
    static void startTick(Minecraft mc) {
        if (!active || mc.player == null || mc.level == null || Mc.screen(mc) != null) {
            return;
        }
        while (mc.options.keyAttack.consumeClick()) {
            corner(mc, true);
        }
        while (mc.options.keyUse.consumeClick()) {
            corner(mc, false);
        }
        // held buttons: no mining, no repeated placing
        mc.options.keyAttack.setDown(false);
        mc.options.keyUse.setDown(false);
    }

    private static void corner(Minecraft mc, boolean first) {
        LocalPlayer p = mc.player;
        HitResult hit = p.pick(RANGE, 1.0f, false);
        if (!(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
            Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr("Kein Block in Sicht")).withStyle(ChatFormatting.RED));
            return;
        }
        BlockPos pos = bh.getBlockPos();
        if (mc.options.keyShift.isDown()) {
            pos = pos.relative(bh.getDirection());
        }
        String d = dimId(mc);
        if (!d.equals(dim)) {
            clear();
            dim = d;
        }
        if (first) {
            p1 = pos.immutable();
        } else {
            p2 = pos.immutable();
        }
        report(mc, first ? "Ecke 1" : "Ecke 2", pos);
    }

    private static void report(Minecraft mc, String what, BlockPos pos) {
        int[] b = box(mc);
        String s = Lang.tr(what) + ": " + pos.getX() + " " + pos.getY() + " " + pos.getZ()
                + (b != null ? " · " + size(b) : "");
        Mc.actionbar(mc, Component.literal("[Webmatica] " + s).withStyle(ChatFormatting.GREEN));
    }

    /** Mouse wheel (render thread = client thread). True if the selection took it. */
    static boolean scroll(double amount) {
        Minecraft mc = Minecraft.getInstance();
        int[] b = active && mc.player != null && Mc.screen(mc) == null ? box(mc) : null;
        if (b == null || amount == 0) {
            return false;
        }
        int n = (amount > 0 ? 1 : -1) * (mc.options.keySprint.isDown() ? 8 : 1);
        Vec3 look = mc.player.getViewVector(1.0f);
        double ax = Math.abs(look.x), ay = Math.abs(look.y), az = Math.abs(look.z);
        int[] lim = limits(mc);
        if (ay >= ax && ay >= az) {
            if (look.y > 0) {
                b[4] = Math.max(b[1], Math.min(lim[1], b[4] + n));
            } else {
                b[1] = Math.min(b[4], Math.max(lim[0], b[1] - n));
            }
        } else if (ax >= az) {
            if (look.x > 0) {
                b[3] = Math.max(b[0], b[3] + n);
            } else {
                b[0] = Math.min(b[3], b[0] - n);
            }
        } else if (look.z > 0) {
            b[5] = Math.max(b[2], b[5] + n);
        } else {
            b[2] = Math.min(b[5], b[2] - n);
        }
        p1 = new BlockPos(b[0], b[1], b[2]);
        p2 = new BlockPos(b[3], b[4], b[5]);
        Mc.actionbar(mc, Component.literal("[Webmatica] " + Lang.tr("Auswahl") + ": " + size(b)).withStyle(ChatFormatting.GREEN));
        return true;
    }

    private static int[] limits(Minecraft mc) {
        return mc.level == null ? DIM_Y_OVERWORLD
                : new int[] {mc.level.getMinY(), mc.level.getMaxY()};
    }

    /** End of the client tick: draw the box. */
    static void tick(Minecraft mc) {
        int[] b = box(mc);
        if (b == null || mc.player == null || ++tick % 10 != 0) {
            return;
        }
        double x0 = b[0], y0 = b[1], z0 = b[2], x1 = b[3] + 1, y1 = b[4] + 1, z1 = b[5] + 1;
        double len = 4 * ((x1 - x0) + (y1 - y0) + (z1 - z0));
        double step = Math.max(0.5, len / 300);
        double[][] edges = {
                {x0, y0, z0, x1, y0, z0}, {x0, y1, z0, x1, y1, z0}, {x0, y0, z1, x1, y0, z1}, {x0, y1, z1, x1, y1, z1},
                {x0, y0, z0, x0, y1, z0}, {x1, y0, z0, x1, y1, z0}, {x0, y0, z1, x0, y1, z1}, {x1, y0, z1, x1, y1, z1},
                {x0, y0, z0, x0, y0, z1}, {x1, y0, z0, x1, y0, z1}, {x0, y1, z0, x0, y1, z1}, {x1, y1, z0, x1, y1, z1},
        };
        Vec3 me = mc.player.position();
        for (double[] e : edges) {
            double l = Math.abs(e[3] - e[0]) + Math.abs(e[4] - e[1]) + Math.abs(e[5] - e[2]);
            int n = Math.max(1, (int) Math.ceil(l / step));
            for (int i = 0; i <= n; i++) {
                double f = (double) i / n;
                double x = e[0] + (e[3] - e[0]) * f, y = e[1] + (e[4] - e[1]) * f, z = e[2] + (e[5] - e[2]) * f;
                double dx = x - me.x, dz = z - me.z;
                if (dx * dx + dz * dz > 128 * 128) {
                    continue;
                }
                mc.level.addParticle(ParticleTypes.END_ROD, true, true, x, y, z, 0.0, 0.0, 0.0);
            }
        }
        for (BlockPos c : new BlockPos[] {p1, p2}) {
            if (c != null) {
                mc.level.addParticle(ParticleTypes.HAPPY_VILLAGER, true, true, c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, 0, 0, 0);
            }
        }
    }

    /** Short help at the top of the screen while the mode is on. */
    private static void drawHint(Gfx g) {
        Minecraft mc = Minecraft.getInstance();
        if (!active || mc.player == null || Mc.guiHidden(mc) || Mc.screen(mc) != null) {
            return;
        }
        int sw = mc.getWindow().getGuiScaledWidth();
        int[] b = box(mc);
        String head = Lang.tr("Auswahlmodus") + (b != null ? " · " + size(b) : "");
        String help = Lang.tr("Linksklick: Ecke 1 · Rechtsklick: Ecke 2 · Mausrad: vergrößern (Sprinten = 8) · F8: Aktionen");
        int w = Math.max(mc.font.width(head), mc.font.width(help)) + 8;
        g.fill(sw / 2 - w / 2, 2, sw / 2 + w / 2, 26, 0x90000000);
        g.centered(mc.font, head, sw / 2, 5, WmScreen.GREEN);
        g.centered(mc.font, help, sw / 2, 15, WmScreen.GRAY);
    }
}
