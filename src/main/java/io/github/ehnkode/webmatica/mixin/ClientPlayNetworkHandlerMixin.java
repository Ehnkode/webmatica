package io.github.ehnkode.webmatica.mixin;

import io.github.ehnkode.webmatica.ChestLidTracker;
import io.github.ehnkode.webmatica.RegionStore;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEventS2CPacket;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block-Entity-Daten (z. B. Schildtext) kommen oft erst nach dem Chunk.
 * Dann den Chunk noch einmal schreiben.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onBlockEntityUpdate", at = @At("TAIL"))
    private void mapper$onBlockEntityUpdate(BlockEntityUpdateS2CPacket packet, CallbackInfo ci) {
        BlockPos pos = packet.getPos();
        RegionStore.getInstance().markDirty(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Truhen-Deckel. Am Ende einhaengen: der erste Aufruf kommt vom Netzwerk-Thread
     * und bricht in forceMainThread ab, erst der zweite (Client-Thread) laeuft durch.
     */
    @Inject(method = "onBlockEvent", at = @At("TAIL"))
    private void mapper$onBlockEvent(BlockEventS2CPacket packet, CallbackInfo ci) {
        ChestLidTracker.getInstance().onBlockEvent(packet.getPos(), packet.getBlock(), packet.getType(), packet.getData());
    }
}
