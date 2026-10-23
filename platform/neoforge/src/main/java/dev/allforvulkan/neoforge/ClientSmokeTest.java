package dev.allforvulkan.neoforge;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.vulkanmod.Initializer;

/** Opt-in, log-based startup check. Never closes a normal player session. */
@EventBusSubscriber(modid = "allforvulkan", value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("afv.smokeTest");
    private static int titleTicks;
    private static int elapsedTicks;

    @SubscribeEvent
    public static void afterTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft client = Minecraft.getInstance();
        if (++elapsedTicks > 1200) {
            Initializer.LOGGER.error("AFV_SMOKE_FAIL: timed out, screen={}, overlay={}", client.screen, client.getOverlay());
            client.stop();
            return;
        }
        if (client.screen instanceof TitleScreen && client.getOverlay() == null) {
            if (++titleTicks == 60) {
                Initializer.LOGGER.info("AFV_SMOKE_PASS: title screen rendered for 60 ticks, framebuffer {}x{}",
                        client.getWindow().getWidth(), client.getWindow().getHeight());
                client.stop();
            }
        } else titleTicks = 0;
    }
}
