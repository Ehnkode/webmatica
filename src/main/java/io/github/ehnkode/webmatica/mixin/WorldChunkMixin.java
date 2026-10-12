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
 * Merkt sich Chunks, in denen sich auf dem Client ein Block geaendert hat.
 *
 * Das faengt alle Wege ab: Block-Updates vom Server, Chunk-Delta-Pakete und
 * die Client-Vorhersage beim eigenen Bauen/Abbauen (fuer die 2b2t oft kein
 * eigenes Paket zurueckschickt). Beim Laden eines Chunks aus dem Paket wird
 * diese Methode nicht benutzt, sie feuert also nur bei echten Aenderungen.
 */
@Mixin(WorldChunk.class)
public abstract class WorldChunkMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void mapper$onSetBlockState(BlockPos pos, BlockState state, int flags,
                                        CallbackInfoReturnable<BlockState> cir) {
        WorldChunk self = (WorldChunk) (Object) this;
        // null = Block war schon so, nichts geaendert.
        if (cir.getReturnValue() != null && self.getWorld().isClient()) {
            RegionStore.getInstance().markDirty(self.getPos().x, self.getPos().z);
        }
    }
}
