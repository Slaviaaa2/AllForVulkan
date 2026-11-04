package dev.allforvulkan.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.vulkanmod.Initializer;

/** ImmediatelyFast's fast_buffer_upload replaces glBufferData with glBufferSubData on
 * vertex buffers that are rewritten every frame. A vendor GL driver can rename such a
 * buffer. On Zink the write synchronizes the GL thread, the driver thread and the GPU,
 * which leaves the render thread waiting and the GPU underused. glBufferData orphans
 * the previous storage instead, so uploads stay asynchronous.
 *
 * ImmediatelyFast loads its entry class from its mixin plugin, before mixins can target
 * it, so the runtime setting is changed when a world starts. */
@EventBusSubscriber(modid = "allforvulkan", value = Dist.CLIENT)
public final class ZinkBufferUploads {
    private ZinkBufferUploads() {}

    @SubscribeEvent
    public static void worldStarted(ClientPlayerNetworkEvent.LoggingIn event) {
        if (!BackendBootstrap.usesZink() || !ModList.get().isLoaded("immediatelyfast")) return;
        try {
            Object runtime = Class.forName("net.raphimc.immediatelyfast.ImmediatelyFast").getField("runtimeConfig").get(null);
            var setting = runtime.getClass().getField("fast_buffer_upload");
            if (!setting.getBoolean(runtime)) return;
            setting.setBoolean(runtime, false);
            Initializer.LOGGER.info("AFV_ZINK_BUFFER_UPLOAD: ImmediatelyFast in-place vertex uploads disabled on Zink");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Initializer.LOGGER.warn("AFV_ZINK_BUFFER_UPLOAD: ImmediatelyFast changed its runtime config; frame rate may suffer on Zink", failure);
        }
    }
}
