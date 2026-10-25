package dev.allforvulkan.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

@EventBusSubscriber(modid = "allforvulkan", value = Dist.CLIENT)
public final class BackendHealthHook {
    private static boolean marked;
    @SubscribeEvent public static void afterTick(ClientTickEvent.Post event) {
        if (!marked && BackendBootstrap.usesVulkan() && (BackendBootstrap.usesZink()
                ? ZinkClientSession.completedFrames() : net.vulkanmod.vulkan.Renderer.getInstance().presentedFrames()) >= 120) {
            BackendBootstrap.renderingStable();
            marked = true;
        }
    }
}
