package dev.allforvulkan.baseline;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import static org.lwjgl.opengl.GL11.*;

/** Test-only mod: validates real Iris shader activation without loading AFV. */
@Mod(value = "afvbaseline", dist = Dist.CLIENT)
@EventBusSubscriber(modid = "afvbaseline", value = Dist.CLIENT)
public final class BaselineSmoke {
    private static int phase, stable, ticks;
    @SubscribeEvent public static void tick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft client = Minecraft.getInstance();
        if (++ticks > 6000) throw new IllegalStateException("AFV_IRIS_BASELINE_FAIL: timeout at phase " + phase);
        if (client.getOverlay() != null) { stable = 0; return; }
        if (phase == 0 && client.screen instanceof TitleScreen && ++stable >= 60) {
            String renderer = glGetString(GL_RENDERER);
            System.out.println("IRIS_BASELINE_GL_RENDERER=" + renderer);
            if (Boolean.getBoolean("afv.expectZink") && !renderer.toLowerCase(java.util.Locale.ROOT).contains("zink"))
                throw new IllegalStateException("Expected actual Zink renderer, got " + renderer);
            phase = 1;
            stable = 0;
            String name = "AFV-Iris-Baseline-" + System.currentTimeMillis();
            client.createWorldOpenFlows().createFreshLevel(name,
                    new LevelSettings(name, GameType.CREATIVE, false, Difficulty.PEACEFUL, true,
                            new GameRules(), WorldDataConfiguration.DEFAULT), new WorldOptions(123456789L, false, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                            .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), client.screen);
        } else if (phase >= 1 && phase <= 3 && client.level != null && client.player != null && client.screen == null) {
            if (++stable < 120) return;
            stable = 0;
            assertIrisPipeline();
            if (phase == 1) {
                System.out.println("AFV_IRIS_BASELINE_WORLD: " + client.levelRenderer.getSectionStatistics());
                GLFW.glfwSetWindowSize(client.getWindow().getWindow(), 960, 540);
                phase = 2;
            } else if (phase == 2) {
                if (client.getWindow().getWidth() != 960 || client.getWindow().getHeight() != 540)
                    throw new IllegalStateException("Iris baseline resize failed");
                System.out.println("AFV_IRIS_BASELINE_RESIZE: 960x540");
                try { Class.forName("net.irisshaders.iris.Iris").getMethod("reload").invoke(null); }
                catch (ReflectiveOperationException error) { throw new IllegalStateException("Iris shader reload failed", error); }
                phase = 3;
            } else {
                System.out.println("AFV_IRIS_BASELINE_RELOAD: shader pipeline reloaded");
                phase = 4;
                client.level.disconnect();
                client.disconnect();
                client.setScreen(new TitleScreen());
            }
        } else if (phase == 4 && client.screen instanceof TitleScreen && ++stable >= 60) {
            System.out.println("AFV_IRIS_BASELINE_PASS: shaderpack, world, resize, shader reload, disconnect");
            phase = 5;
            client.stop();
        }
    }
    private static void assertIrisPipeline() {
        try {
            Class<?> iris = Class.forName("net.irisshaders.iris.Iris");
            Optional<?> error = (Optional<?>) iris.getMethod("getStoredError").invoke(null);
            if (error.isPresent()) throw new IllegalStateException("Iris stored a shader error", (Throwable) error.get());
            if (!(boolean) iris.getMethod("isPackInUseQuick").invoke(null)) throw new IllegalStateException("Iris fell back to shaders off");
            Object manager = iris.getMethod("getPipelineManager").invoke(null);
            Object pipeline = ((Optional<?>) manager.getClass().getMethod("getPipeline").invoke(manager)).orElseThrow();
            if (pipeline.getClass().getSimpleName().contains("Vanilla")) throw new IllegalStateException("Iris fell back to the vanilla pipeline");
            System.out.println("IRIS_BASELINE_ACTIVE_PIPELINE=" + pipeline.getClass().getName());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot validate Iris pipeline", error); }
    }
}
