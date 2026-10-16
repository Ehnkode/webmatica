package io.github.ehnkode.webmatica;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * "In chests: 340 (12 chests)" under every item that is in a remembered chest: in the
 * tooltip of every inventory, and for a moment above the hotbar when the item in hand changes.
 */
final class ChestTooltip {
    private static final long SHOW_MS = 3000L;

    private static String handId = "";
    private static long handSince;

    private ChestTooltip() {
    }

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            String s = line(Minecraft.getInstance(), stack);
            if (s != null) {
                lines.add(Component.literal(s).withStyle(ChatFormatting.GRAY));
            }
        });
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("webmatica", "hand_chests"), (g, tick) -> drawHand(new Gfx(g)));
    }

    /** The line for this item, or null if it is in no remembered chest (or the tooltip is off). */
    private static String line(Minecraft mc, ItemStack stack) {
        if (!IngameConfig.get().tooltip || mc.level == null || stack.isEmpty()) {
            return null;
        }
        ChestIndex.refresh(mc, false);
        if (!ChestIndex.loaded(mc)) {
            return null;
        }
        long[] t = ChestIndex.total(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        return t == null ? null : Lang.tr("In Kisten: " + t[0] + " (" + t[1] + (t[1] == 1 ? " Kiste)" : " Kisten)"));
    }

    /** Above the hotbar, like the item name the game shows when you switch slots. */
    private static void drawHand(Gfx g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || Mc.guiHidden(mc) || Mc.screen(mc) != null) {
            return;
        }
        ItemStack hand = mc.player.getMainHandItem();
        String id = hand.isEmpty() ? "" : BuiltInRegistries.ITEM.getKey(hand.getItem()).toString();
        long now = System.currentTimeMillis();
        if (!id.equals(handId)) {
            handId = id;
            handSince = now;
        }
        long age = now - handSince;
        if (id.isEmpty() || age > SHOW_MS) {
            return;
        }
        String s = line(mc, hand);
        if (s == null) {
            return;
        }
        int alpha = age < SHOW_MS - 500 ? 255 : (int) (255 * (SHOW_MS - age) / 500);
        if (alpha < 8) {
            return;
        }
        int sw = mc.getWindow().getGuiScaledWidth(), sh = mc.getWindow().getGuiScaledHeight();
        // the game's item name sits at sh - 59 in survival, 14 lower without hearts; ours goes right above it
        int y = sh - 59 + (mc.player.isCreative() || mc.player.isSpectator() ? 14 : 0) - 11;
        g.centered(mc.font, s, sw / 2, y, (alpha << 24) | 0xAAAAAA);
    }
}
