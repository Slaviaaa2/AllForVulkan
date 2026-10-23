package dev.allforvulkan.neoforge;

import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.chunk.WorldRenderer;
import net.vulkanmod.render.chunk.buffer.DrawBuffers;
import org.lwjgl.glfw.GLFW;

/** Explicitly enabled checks; never touch existing saves or close normal sessions. */
@EventBusSubscriber(modid = "allforvulkan", value = Dist.CLIENT)
public final class ClientSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("afv.smokeTest");
    private static final boolean WORLD = Boolean.getBoolean("afv.smokeWorld");
    private static int ticks, stableTicks, phase;
    private static long drawBaseline;
    private static CompletableFuture<Void> reload;

    @SubscribeEvent
    public static void afterTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft client = Minecraft.getInstance();
        if (++ticks > 6000) throw new IllegalStateException("AFV_SMOKE_FAIL: timeout at phase " + phase);
        if (client.getOverlay() != null) { stableTicks = 0; return; }
        if (phase == 0 && client.screen instanceof TitleScreen) {
            if (++stableTicks < 60) return;
            Initializer.LOGGER.info("AFV_SMOKE_TITLE: framebuffer {}x{}", client.getWindow().getWidth(), client.getWindow().getHeight());
            if (!WORLD) { pass(client); return; }
            phase = 1;
            stableTicks = 0;
            drawBaseline = DrawBuffers.getSubmittedDraws();
            String name = "AFV-Smoke-" + System.currentTimeMillis();
            client.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT),
                    new WorldOptions(123456789L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
            return;
        }
        if (phase >= 1 && phase <= 3 && client.level != null && client.player != null && client.screen == null) {
            if (++stableTicks < 120) return;
            stableTicks = 0;
            if (DrawBuffers.getSubmittedDraws() <= drawBaseline)
                throw new IllegalStateException("AFV_SMOKE_FAIL: world has not submitted terrain draws");
            if (phase == 1) {
                Initializer.LOGGER.info("AFV_SMOKE_WORLD: visible sections={}, submitted terrain draws={}",
                        WorldRenderer.getInstance().getVisibleSectionsCount(), DrawBuffers.getSubmittedDraws() - drawBaseline);
                GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 960, 540);
                phase = 2;
            } else if (phase == 2) {
                if (client.getWindow().getWidth() != 960 || client.getWindow().getHeight() != 540)
                    throw new IllegalStateException("AFV_SMOKE_FAIL: resize did not reach expected framebuffer size");
                Initializer.LOGGER.info("AFV_SMOKE_RESIZE: framebuffer 960x540");
                reload = client.reloadResourcePacks();
                phase = 3;
            } else if (reload.isDone()) {
                reload.join();
                Initializer.LOGGER.info("AFV_SMOKE_RELOAD: resources reloaded with active world");
                phase = 4;
                client.level.disconnect();
                client.disconnect();
                client.setScreen(new TitleScreen());
            }
        } else if (phase == 4 && client.screen instanceof TitleScreen && ++stableTicks >= 60) pass(client);
    }

    private static void pass(Minecraft client) {
        if (net.vulkanmod.vulkan.interop.ZinkFrameBridge.ENABLED) {
            var bridge = net.vulkanmod.vulkan.Renderer.getInstance().getZinkBridge();
            if (bridge == null || bridge.verifiedFrames() < 120)
                throw new IllegalStateException("AFV_SMOKE_FAIL: insufficient verified Zink swapchain patches");
            Initializer.LOGGER.info("AFV_ZINK_COMPOSITION_PASS: {} GPU-composed patches verified", bridge.verifiedFrames());
        }
        Initializer.LOGGER.info("AFV_SMOKE_PASS: scenario={}", WORLD ? "world-resize-reload-disconnect" : "title");
        phase = 5;
        client.stop();
    }
}
