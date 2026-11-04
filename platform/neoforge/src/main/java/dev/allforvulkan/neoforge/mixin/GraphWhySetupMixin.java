package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.GraphWhyProbe;
import net.minecraft.client.Camera;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer", remap = false)
public class GraphWhySetupMixin {
    @Inject(method = "setupTerrain(Lnet/minecraft/client/Camera;Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;ZZ)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvSetup(Camera camera, @org.spongepowered.asm.mixin.injection.Coerce Object viewport, boolean a, boolean b, CallbackInfo callback) { GraphWhyProbe.setup(camera); }
}
