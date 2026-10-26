package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.ShadowMeshCapture;
import java.util.Collection;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps a CPU copy of each opaque section mesh before Sodium uploads it and frees the buffer. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager", remap = false)
public class SodiumShadowUploadMixin {
    @Inject(method = "uploadResults", at = @At("HEAD"), remap = false, require = 1)
    private void afvCopyShadowMeshes(CommandList commands, Collection<BuilderTaskOutput> outputs, CallbackInfo callback) {
        ShadowMeshCapture.retainUpload(outputs);
    }
}
