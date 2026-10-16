package io.github.ehnkode.webmatica.mixin;

import io.github.ehnkode.webmatica.SelectionHooks;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the selection mode the mouse wheel resizes the selection instead of switching the hotbar slot. */
@Mixin(MouseHandler.class)
public abstract class MouseScrollMixin {

    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void webmatica$onScroll(long window, double horizontal, double vertical, CallbackInfo ci) {
        if (SelectionHooks.scroll(vertical)) {
            ci.cancel();
        }
    }
}
