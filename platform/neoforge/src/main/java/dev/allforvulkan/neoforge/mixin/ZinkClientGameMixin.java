package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.ZinkClientSession;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class ZinkClientGameMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void afv$prepareDrawable(boolean renderLevel, CallbackInfo ci) { ZinkClientSession.prepareFrame(); }
}
