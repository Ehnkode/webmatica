package io.github.ehnkode.webmatica;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client-Entrypoint. Verdrahtet die Fabric-Events mit dem Recorder.
 */
public class MapperClient implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("webmatica");

    private static KeyBinding toggleKey;
    private static ClientWorld lastWorld;

    @Override
    public void onInitializeClient() {
        RegionStore store = RegionStore.getInstance();

        ClientChunkEvents.CHUNK_LOAD.register(store::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(store::onChunkUnload);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> store.onDisconnect());
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> store.shutdown());

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.webmatica.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F9,
                KeyBinding.Category.create(Identifier.of("webmatica", "main"))));

        ClientTickEvents.END_CLIENT_TICK.register(MapperClient::onEndTick);

        PositionReporter.getInstance().start();
        LitematicaBridge.getInstance().start();
        BaritoneBridge.getInstance().start();
        BuildControl.getInstance().start();
        ContainerTracker.getInstance().register();
        LOGGER.info("[webmatica] Recorder aktiv, Ziel: <rundir>/webmatica_recording/<server>/, Live-Position -> {}",
                PositionReporter.url());
    }

    private static void onEndTick(MinecraftClient mc) {
        RegionStore store = RegionStore.getInstance();

        // Dimensionswechsel: offene Aenderungen gehoeren zur alten Welt.
        if (mc.world != lastWorld) {
            lastWorld = mc.world;
            store.onDisconnect();
        }

        while (toggleKey.wasPressed()) {
            boolean on = !store.isEnabled();
            store.setEnabled(on);
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("[Webmatica] Aufnahme " + (on ? "AN" : "AUS"))
                        .formatted(on ? Formatting.GREEN : Formatting.RED), true);
            }
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
        BaritoneSafety.tick();
        AutoGuard.tick(mc);
        ContainerTracker.getInstance().tick(mc);
    }
}
