package dev.allforvulkan.neoforge.mixin;

import dev.allforvulkan.neoforge.TerrainWorkProbe;
import dev.allforvulkan.neoforge.TerrainWorkProbe.Stage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Applied only when terrain work measurement is requested on exactly Sodium 0.8.13, which builds
 * render lists outside the graph search. */
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager", remap = false)
public class SodiumRenderLists08Mixin {
    @Inject(method = "finalizeRenderLists(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;)V", at = @At("HEAD"), remap = false, require = 1)
    private void afvBeginLists(CallbackInfo callback) { TerrainWorkProbe.enter(Stage.LISTS); }
    @Inject(method = "finalizeRenderLists(Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;)V", at = @At("RETURN"), remap = false, require = 1)
    private void afvEndLists(CallbackInfo callback) { TerrainWorkProbe.exit(Stage.LISTS); }
}
