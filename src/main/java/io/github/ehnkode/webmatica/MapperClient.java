package io.github.ehnkode.webmatica;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Wires the Fabric events to the recorder.
 */
public class MapperClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("webmatica");

    private static KeyMapping toggleKey;
    private static KeyMapping viewerKey;
    private static ClientLevel lastWorld;

    @Override
    public void onInitializeClient() {
        RegionStore store = RegionStore.getInstance();

        ClientChunkEvents.CHUNK_LOAD.register(store::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(store::onChunkUnload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> store.onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            store.shutdown();
            ViewerLauncher.shutdown();
        });

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("webmatica", "main"));
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.webmatica.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F9, category));
        viewerKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.webmatica.viewer", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_F10, category));

        ClientTickEvents.END_CLIENT_TICK.register(MapperClient::onEndTick);

        PositionReporter.getInstance().start();
        LitematicaBridge.getInstance().start();
        BaritoneBridge.getInstance().start();
        BuildControl.getInstance().start();
        ContainerTracker.getInstance().register();
        LOGGER.info("[webmatica] Recorder aktiv, Ziel: <rundir>/webmatica_recording/<server>/, Live-Position -> {}",
                PositionReporter.url());
    }

    private static void onEndTick(Minecraft mc) {
        RegionStore store = RegionStore.getInstance();

        // Dimension change: pending changes belong to the old world.
        if (mc.level != lastWorld) {
            lastWorld = mc.level;
            store.onDisconnect();
        }

        while (toggleKey.consumeClick()) {
            boolean on = !store.isEnabled();
            store.setEnabled(on);
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("[Webmatica] " + Lang.tr("Aufnahme " + (on ? "AN" : "AUS")))
                        .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED), true);
            }
        }

        while (viewerKey.consumeClick()) {
            ViewerLauncher.open(mc);
        }

        ClientInfo.write(mc);
        store.tick(mc);
        PositionReporter.getInstance().tick(mc);
        LitematicaBridge.getInstance().tick(mc);
        BaritoneBridge.getInstance().tick(mc);
        BuildControl.getInstance().tick(mc);
        Highlighter.getInstance().tick(mc);
        FetchTask.getInstance().tick(mc);
        UnpackTask.getInstance().tick(mc);
        FarmTask.getInstance().tick(mc);
        BaritoneSafety.tick();
        AutoGuard.tick(mc);
        ContainerTracker.getInstance().tick(mc);
    }
}
