package io.github.ehnkode.webmatica;

import com.mojang.blaze3d.platform.InputConstants;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.ChunkPos;
//? if >=26.1 {
/*import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStackTemplate;
*///?} else {
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.world.inventory.ClickType;
//?}
//? if >=26.3 {
/*import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.item.component.SwingAnimation;
*///?}

/**
 * Everything that differs between the supported Minecraft versions, in one place.
 * The rest of the mod calls these instead of the game directly. Stonecutter picks
 * the matching branch per version (the //? comments).
 */
public final class Mc {
    private Mc() {
    }

    /** The open screen, or null. */
    public static Screen screen(Minecraft mc) {
        //? if >=26.2 {
        /*return mc.gui.screen();
        *///?} else {
        return mc.screen;
        //?}
    }

    /** Short message above the hotbar. */
    public static void actionbar(Minecraft mc, Component c) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        //? if >=26.1 {
        /*p.sendOverlayMessage(c);
        *///?} else {
        p.displayClientMessage(c, true);
        //?}
    }

    /** Message in the chat, only on this client. */
    public static void chat(Minecraft mc, Component c) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return;
        }
        //? if >=26.1 {
        /*p.sendSystemMessage(c);
        *///?} else {
        p.displayClientMessage(c, false);
        //?}
    }

    /** Shift-click on a slot. */
    public static void quickMove(Minecraft mc, int containerId, int slot, Player p) {
        //? if >=26.1 {
        /*mc.gameMode.handleContainerInput(containerId, slot, 0, ContainerInput.QUICK_MOVE, p);
        *///?} else {
        mc.gameMode.handleInventoryMouseClick(containerId, slot, 0, ClickType.QUICK_MOVE, p);
        //?}
    }

    /** Swaps a slot with a hotbar slot (number key on the slot). */
    public static void swap(Minecraft mc, int containerId, int slot, int hotbar, Player p) {
        //? if >=26.1 {
        /*mc.gameMode.handleContainerInput(containerId, slot, hotbar, ContainerInput.SWAP, p);
        *///?} else {
        mc.gameMode.handleInventoryMouseClick(containerId, slot, hotbar, ClickType.SWAP, p);
        //?}
    }

    /** Arm swing, also sent to the server. */
    public static void swing(Minecraft mc, LocalPlayer p, InteractionHand hand) {
        //? if >=26.3 {
        /*p.swing(hand, p.getItemInHand(hand).getOrDefault(DataComponents.INTERACT_ANIMATION, SwingAnimation.DEFAULT), false);
        p.connection.send(new ServerboundPunchPacket());
        *///?} else {
        p.swing(hand);
        //?}
    }

    /** Non-empty stacks inside a shulker or bundle component. */
    public static List<ItemStack> items(ItemContainerContents contents) {
        List<ItemStack> out = new ArrayList<>();
        //? if >=26.1 {
        /*for (ItemStackTemplate t : contents.nonEmptyItems()) {
            out.add(t.create());
        }
        *///?} else {
        for (ItemStack s : contents.nonEmptyItems()) {
            out.add(s);
        }
        //?}
        return out;
    }

    /** Registers a keyboard key binding. */
    public static KeyMapping registerKey(String name, int key, KeyMapping.Category category) {
        //? if >=26.3 {
        /*KeyMapping mapping = new KeyMapping(name, InputConstants.Type.KEYBOARD, key, category);
        *///?} else {
        KeyMapping mapping = new KeyMapping(name, InputConstants.Type.KEYSYM, key, category);
        //?}
        return register(mapping);
    }

    private static KeyMapping register(KeyMapping key) {
        //? if >=26.1 {
        /*return KeyMappingHelper.registerKeyMapping(key);
        *///?} else {
        return KeyBindingHelper.registerKeyBinding(key);
        //?}
    }

    public static int x(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.x();
        *///?} else {
        return pos.x;
        //?}
    }

    public static int z(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.z();
        *///?} else {
        return pos.z;
        //?}
    }

    public static long pack(ChunkPos pos) {
        //? if >=26.1 {
        /*return pos.pack();
        *///?} else {
        return pos.toLong();
        //?}
    }

    public static long pack(int x, int z) {
        //? if >=26.1 {
        /*return ChunkPos.pack(x, z);
        *///?} else {
        return ChunkPos.asLong(x, z);
        //?}
    }

    public static ChunkPos unpack(long packed) {
        //? if >=26.1 {
        /*return ChunkPos.unpack(packed);
        *///?} else {
        return new ChunkPos(packed);
        //?}
    }
}
