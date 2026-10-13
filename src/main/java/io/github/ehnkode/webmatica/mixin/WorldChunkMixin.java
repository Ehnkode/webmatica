package io.github.ehnkode.webmatica.mixin;

import io.github.ehnkode.webmatica.RegionStore;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Remembers chunks in which a block changed on the client.
 *
 * This catches every path: block updates from the server, chunk delta packets and
 * the client prediction when building/breaking yourself (for which 2b2t often sends
 * no packet back). Loading a chunk from the packet doesn't use
 * this method, so it only fires on real changes.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void mapper$onSetBlockState(BlockPos pos, BlockState state, int flags,
                                        CallbackInfoReturnable<BlockState> cir) {
        WorldChunk self = (WorldChunk) (Object) this;
        // null = block was already like this, nothing changed.
        if (cir.getReturnValue() != null && self.getWorld().isClient()) {
            RegionStore.getInstance().markDirty(self.getPos().x, self.getPos().z);
        }
    }
}
