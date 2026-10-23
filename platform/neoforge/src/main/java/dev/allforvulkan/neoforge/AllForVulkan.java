package dev.allforvulkan.neoforge;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.vulkanmod.Initializer;

@Mod(value = "allforvulkan", dist = Dist.CLIENT)
public final class AllForVulkan {
    public AllForVulkan() {
        Initializer.LOGGER.info("NeoForge mod loading completed for All For Vulkan");
    }
}
