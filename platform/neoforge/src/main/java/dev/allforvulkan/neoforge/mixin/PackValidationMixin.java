package dev.allforvulkan.neoforge.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Pack validation should not register mods' IDE-only optional-dependency tests. */
@Mixin(targets = "net.neoforged.neoforge.gametest.GameTestHooks")
public abstract class PackValidationMixin {
    @Inject(method = "isGametestEnabled", at = @At("HEAD"), cancellable = true)
    private static void afv$productionGameTests(CallbackInfoReturnable<Boolean> result) {
        result.setReturnValue(false);
    }
}
