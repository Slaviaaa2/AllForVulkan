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
    private static int ticks, stableTicks, phase, inspectUntil;
    private static int benchmarkUntil;
    private static boolean benchmarkFinished;
    private static long drawBaseline;
    private static CompletableFuture<Void> reload;

    @SubscribeEvent public static void frame(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
        if (ENABLED) dev.allforvulkan.validation.FrameBenchmark.frame();
    }

    @SubscribeEvent
    public static void afterTick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        Minecraft client = Minecraft.getInstance();
        if (++ticks > 6000) throw new IllegalStateException("AFV_SMOKE_FAIL: timeout at phase " + phase);
        if (client.getOverlay() != null) { stableTicks = 0; return; }
        if (phase == 0 && client.screen instanceof TitleScreen) {
            if (++stableTicks < 60) return;
            verifyAtlas(client);
            Initializer.LOGGER.info("AFV_SMOKE_TITLE: framebuffer {}x{}", client.getWindow().getWidth(), client.getWindow().getHeight());
            if (Boolean.getBoolean("afv.smokeSettings")) {
                phase = 10;
                stableTicks = 0;
                client.setScreen(new BackendScreen(client.screen));
                return;
            }
            if (!WORLD) { pass(client); return; }
            phase = 1;
            stableTicks = 0;
            drawBaseline = BackendBootstrap.vulkan() ? DrawBuffers.getSubmittedDraws() : 0;
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
            if (phase == 1 && stableTicks == 0 && benchmarkUntil == 0 && Integer.getInteger("afv.benchmarkTicks", 0) > 0)
                client.player.moveTo(.5, -60, .5, 0, 0);
            if (++stableTicks < 120) return;
            int duration = Integer.getInteger("afv.benchmarkTicks", 0);
            if (phase == 1 && duration > 0 && !benchmarkFinished) {
                if (benchmarkUntil == 0) {
                    benchmarkUntil = ticks + duration;
                    dev.allforvulkan.validation.FrameBenchmark.begin();
                }
                if (ticks < benchmarkUntil) return;
                var conditions = new java.util.LinkedHashMap<String, Object>();
                conditions.put("renderer", BackendBootstrap.vulkan() ? "AFV Vulkan" : org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER));
                conditions.put("backendReason", BackendBootstrap.reason());
                conditions.put("width", client.getWindow().getWidth());
                conditions.put("height", client.getWindow().getHeight());
                conditions.put("renderDistance", client.options.renderDistance().get());
                conditions.put("simulationDistance", client.options.simulationDistance().get());
                conditions.put("vsync", client.options.enableVsync().get());
                conditions.put("frameLimit", client.options.framerateLimit().get());
                conditions.put("camera", client.player.position().toString());
                conditions.put("display", System.getenv("DISPLAY"));
                dev.allforvulkan.validation.FrameBenchmark.finish(java.nio.file.Path.of("afv-benchmark.json"), conditions);
                benchmarkFinished = true;
            }
            if (phase == 1 && Integer.getInteger("afv.smokeInspectTicks", 0) > 0) {
                if (inspectUntil == 0) {
                    inspectUntil = ticks + Integer.getInteger("afv.smokeInspectTicks", 0);
                    Initializer.LOGGER.info("AFV_INSPECT_WORLD: stable world available for scoped visual inspection");
                }
                if (ticks < inspectUntil) return;
            }
            stableTicks = 0;
            verifyAtlas(client);
            if (BackendBootstrap.vulkan() && DrawBuffers.getSubmittedDraws() <= drawBaseline)
                throw new IllegalStateException("AFV_SMOKE_FAIL: world has not submitted terrain draws");
            if (phase == 1) {
                if (BackendBootstrap.vulkan()) Initializer.LOGGER.info("AFV_SMOKE_WORLD: visible sections={}, submitted terrain draws={}",
                        WorldRenderer.getInstance().getVisibleSectionsCount(), DrawBuffers.getSubmittedDraws() - drawBaseline);
                else Initializer.LOGGER.info("AFV_SMOKE_OPENGL_WORLD: player and world active");
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
        else if (phase == 10 && client.screen instanceof BackendScreen && ++stableTicks >= Math.max(300, Integer.getInteger("afv.smokeInspectTicks", 0))) {
            Initializer.LOGGER.info("AFV_SMOKE_SETTINGS_PASS: config screen remained active");
            pass(client);
        }
    }

    private static void verifyAtlas(Minecraft client) {
        if (client.level != null && Boolean.getBoolean("afv.smokeIris"))
            dev.allforvulkan.validation.IrisAssertions.requireActive();
        if (!BackendBootstrap.vulkan()) {
            String renderer = org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER);
            if (Boolean.getBoolean("afv.expectZink") && !renderer.toLowerCase(java.util.Locale.ROOT).contains("zink"))
                throw new IllegalStateException("Expected real Zink renderer, got " + renderer);
            int texture = client.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId();
            int old = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL11.GL_TEXTURE_BINDING_2D);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture);
            int width = org.lwjgl.opengl.GL11.glGetTexLevelParameteri(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_TEXTURE_WIDTH);
            org.lwjgl.opengl.GL11.glBindTexture(org.lwjgl.opengl.GL11.GL_TEXTURE_2D, old);
            if (width <= 0) throw new IllegalStateException("OpenGL block atlas is missing");
            Initializer.LOGGER.info("AFV_SMOKE_OPENGL_ATLAS_PASS: id={}, width={}", texture, width);
            return;
        }
        var registry = dev.allforvulkan.bridge.GlTextureRegistry.active();
        if (registry == null) return;
        int id = client.getTextureManager().getTexture(net.minecraft.client.renderer.texture.TextureAtlas.LOCATION_BLOCKS).getId();
        var image = net.vulkanmod.gl.VkGlTexture.getTexture(id).getVulkanImage();
        registry.verifyDimensions(id, image.width, image.height);
        if (registry.uploads() == 0) throw new IllegalStateException("No real Minecraft GL uploads");
        Initializer.LOGGER.info("AFV_ZINK_ATLAS_PASS: id={}, {}x{}, allocations={}, uploads={}", id, image.width, image.height, registry.allocations(), registry.uploads());
    }

    private static void pass(Minecraft client) {
        if (BackendBootstrap.vulkan() && net.vulkanmod.vulkan.interop.ZinkFrameBridge.ENABLED) {
            var bridge = net.vulkanmod.vulkan.Renderer.getInstance().getZinkBridge();
            if (bridge == null || bridge.verifiedFrames() < 120 || bridge.verifiedScenes() < 120)
                throw new IllegalStateException("AFV_SMOKE_FAIL: insufficient verified Zink swapchain patches");
            if (WORLD && bridge.worldFrames() < 120) throw new IllegalStateException("World interop stage did not execute");
            Initializer.LOGGER.info("AFV_ZINK_WORLD_STAGE_PASS: {} world frames, depth copied before hand clear and GUI", bridge.worldFrames());
            Initializer.LOGGER.info("AFV_ZINK_COMPOSITION_PASS: {} GPU-composed patches verified", bridge.verifiedFrames());
            Initializer.LOGGER.info("AFV_ZINK_SCENE_PASS: {} scene regions verified, effect={}", bridge.verifiedScenes(),
                    net.vulkanmod.vulkan.interop.ZinkFrameBridge.effectName());
        }
        Initializer.LOGGER.info("AFV_SMOKE_PASS: scenario={}", WORLD ? "world-resize-reload-disconnect" : "title");
        phase = 5;
        client.stop();
    }
}
