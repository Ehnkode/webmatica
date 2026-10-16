package io.github.ehnkode.webmatica;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;

/** "In chests: 340 (12 chests)" under every item that is in a remembered chest. */
final class ChestTooltip {
    private ChestTooltip() {
    }

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            Minecraft mc = Minecraft.getInstance();
            if (!IngameConfig.get().tooltip || mc.level == null || stack.isEmpty()) {
                return;
            }
            ChestIndex.refresh(mc, false);
            if (!ChestIndex.loaded(mc)) {
                return;
            }
            long[] t = ChestIndex.total(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            if (t != null) {
                lines.add(Component.literal(Lang.tr("In Kisten: " + t[0] + " (" + t[1] + (t[1] == 1 ? " Kiste)" : " Kisten)")))
                        .withStyle(ChatFormatting.GRAY));
            }
        });
    }
}
