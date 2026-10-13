package io.github.ehnkode.webmatica.mixin;

import io.github.ehnkode.webmatica.ChestLidTracker;
import io.github.ehnkode.webmatica.RegionStore;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Block entity data (e.g. sign text) often arrives after the chunk.
 * Then write the chunk once more.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "handleBlockEntityData", at = @At("TAIL"))
    private void mapper$onBlockEntityUpdate(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
        BlockPos pos = packet.getPos();
        RegionStore.getInstance().markDirty(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Chest lids. Inject at the end: the first call comes from the network thread
     * and aborts in forceMainThread, only the second (client thread) runs through.
     */
    @Inject(method = "handleBlockEvent", at = @At("TAIL"))
    private void mapper$onBlockEvent(ClientboundBlockEventPacket packet, CallbackInfo ci) {
        ChestLidTracker.getInstance().onBlockEvent(packet.getPos(), packet.getBlock(), packet.getB0(), packet.getB1());
    }
}
