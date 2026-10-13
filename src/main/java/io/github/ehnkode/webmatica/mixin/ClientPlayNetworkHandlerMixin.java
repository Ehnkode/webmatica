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
 * Block entity data (e.g. sign text) often arrives after the chunk.
 * Then write the chunk once more.
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {

    @Inject(method = "onBlockEntityUpdate", at = @At("TAIL"))
    private void mapper$onBlockEntityUpdate(BlockEntityUpdateS2CPacket packet, CallbackInfo ci) {
        BlockPos pos = packet.getPos();
        RegionStore.getInstance().markDirty(pos.getX() >> 4, pos.getZ() >> 4);
    }

    /**
     * Chest lids. Inject at the end: the first call comes from the network thread
     * and aborts in forceMainThread, only the second (client thread) runs through.
     */
    @Inject(method = "onBlockEvent", at = @At("TAIL"))
    private void mapper$onBlockEvent(BlockEventS2CPacket packet, CallbackInfo ci) {
        ChestLidTracker.getInstance().onBlockEvent(packet.getPos(), packet.getBlock(), packet.getType(), packet.getData());
    }
}
