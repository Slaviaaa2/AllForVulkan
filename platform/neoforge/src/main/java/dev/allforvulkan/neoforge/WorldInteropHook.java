package dev.allforvulkan.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.interop.ZinkFrameBridge;

/** NeoForge's last world stage is before GameRenderer clears depth for the hand. */
@EventBusSubscriber(modid = "allforvulkan", value = Dist.CLIENT)
public final class WorldInteropHook {
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void afterWorld(RenderLevelStageEvent event) {
        if (BackendBootstrap.vulkan() && ZinkFrameBridge.ENABLED && event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL)
            Renderer.getInstance().finishWorld();
    }
}
