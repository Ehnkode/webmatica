package io.github.ehnkode.webmatica.mixin;

import io.github.ehnkode.webmatica.ContainerTracker;
import io.github.ehnkode.webmatica.Mc;
import io.github.ehnkode.webmatica.RegionStore;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
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
 * this method, so it only fires on real changes. Broken chests and shulkers are
 * also reported to the viewer from here.
 */
@Mixin(LevelChunk.class)
public abstract class WorldChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void mapper$onSetBlockState(BlockPos pos, BlockState state, int flags,
                                        CallbackInfoReturnable<BlockState> cir) {
        LevelChunk self = (LevelChunk) (Object) this;
        // null = block was already like this, nothing changed.
        if (cir.getReturnValue() != null && self.getLevel().isClientSide()) {
            RegionStore.getInstance().markDirty(Mc.x(self.getPos()), Mc.z(self.getPos()));
            ContainerTracker.getInstance().blockReplaced(self.getLevel(), pos, cir.getReturnValue(), state);
        }
    }
}
