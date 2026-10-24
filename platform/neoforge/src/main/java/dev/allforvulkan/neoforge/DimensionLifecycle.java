package dev.allforvulkan.neoforge;

import dev.allforvulkan.validation.GlFrameChecks;
import dev.allforvulkan.validation.IrisAssertions;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.vulkanmod.Initializer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import static org.lwjgl.egl.EGL14.eglGetCurrentContext;

/** Real dimension transfers, only inside the freshly created private QA world. */
final class DimensionLifecycle {
    // Return through the Nether to avoid the End-to-Overworld credits flow.
    private static final List<ResourceKey<Level>> ROUTE = List.of(Level.NETHER, Level.END, Level.NETHER, Level.OVERWORLD);
    private final Minecraft client;
    private final MinecraftServer server;
    private final long context;
    private final String pack;
    private final Map<ResourceKey<Level>, Object> pipelines = new HashMap<>();
    private CompletableFuture<Void> transfer;
    private int step, stableTicks, inspectTicks;
    private boolean validated;
    private long firstFrame;

    DimensionLifecycle(Minecraft client) {
        if (!BackendBootstrap.zinkClient()) throw new IllegalStateException("Dimension cycle requires the actual Zink/Vulkan presenter");
        this.client = client;
        server = client.getSingleplayerServer();
        if (server == null || !server.getWorldData().getLevelName().startsWith("AFV-Smoke-"))
            throw new IllegalStateException("Refusing to modify a non-QA world for dimension validation");
        context = eglGetCurrentContext();
        pack = IrisAssertions.configuredPack();
        pipelines.put(Level.OVERWORLD, IrisAssertions.requireActive());
        request();
    }

    boolean tick() {
        if (context == 0 || eglGetCurrentContext() != context) throw new IllegalStateException("Dimension transfer replaced the original GL context");
        if (!transfer.isDone()) return false;
        transfer.join();
        ResourceKey<Level> expected = ROUTE.get(step);
        if (client.level == null || client.player == null || client.screen != null || !client.level.dimension().equals(expected)
                || !client.level.hasChunkAt(client.player.blockPosition())) { stableTicks = 0; return false; }
        if (stableTicks++ == 0) firstFrame = ZinkClientSession.presentedFrames();
        if (stableTicks < 120 || ZinkClientSession.presentedFrames() - firstFrame < 120) return false;
        int inspection = Math.max(0, Integer.getInteger("afv.smokeDimensionInspectTicks", 0));
        if (!validated) {
            validate(expected);
            validated = true;
            if (inspection > 0) Initializer.LOGGER.info("AFV_DIMENSION_INSPECT: {}, stable rendered world; holding {} client ticks for scoped inspection",
                    expected.location(), inspection);
        }
        if (inspectTicks++ < inspection) return false;
        // Validate again after an inspection interval, before the world is changed.
        if (inspection > 0) validate(expected);
        Initializer.LOGGER.info("AFV_DIMENSION_STAGE_PASS: {}, actual client chunks, Iris dimension/pipeline, same GL context, {} Vulkan frames",
                expected.location(), ZinkClientSession.presentedFrames() - firstFrame);
        if (++step == ROUTE.size()) {
            Initializer.LOGGER.info("AFV_DIMENSION_CYCLE_PASS: Overworld -> Nether -> End -> Nether -> Overworld, three distinct dimension pipelines, pack={}", pack);
            return true;
        }
        request();
        return false;
    }

    private void validate(ResourceKey<Level> expected) {
        IrisAssertions.requireDimension(expected.location().toString());
        Object pipeline = IrisAssertions.requireActive();
        for (var entry : pipelines.entrySet()) {
            if (!entry.getKey().equals(expected) && entry.getValue() == pipeline)
                throw new IllegalStateException("Iris retained another dimension's pipeline for " + expected.location());
        }
        if (!pack.equals(IrisAssertions.configuredPack())) throw new IllegalStateException("Dimension transition changed the selected shader pack");
        pipelines.put(expected, pipeline);
        GlFrameChecks.sampleColor(client.getMainRenderTarget().getColorTextureId());
    }

    private void request() {
        stableTicks = inspectTicks = 0;
        validated = false;
        var destination = ROUTE.get(step);
        var playerId = client.player.getUUID();
        transfer = CompletableFuture.runAsync(() -> {
            var level = server.getLevel(destination);
            var player = server.getPlayerList().getPlayer(playerId);
            if (level == null || player == null) throw new IllegalStateException("QA dimension/player disappeared before transfer");
            int ground = destination.equals(Level.NETHER) ? 127 : destination.equals(Level.END) ? 96 : -61;
            if (!destination.equals(Level.OVERWORLD)) {
                for (int x = -5; x <= 5; x++) for (int z = -2; z <= 12; z++) {
                    level.setBlockAndUpdate(new BlockPos(x, ground, z), Blocks.SMOOTH_STONE.defaultBlockState());
                    for (int y = 1; y <= 5; y++) level.setBlockAndUpdate(new BlockPos(x, ground + y, z), Blocks.AIR.defaultBlockState());
                }
                for (int y = 1; y <= 4; y++) level.setBlockAndUpdate(new BlockPos(1, ground + y, 8), Blocks.STONE_BRICKS.defaultBlockState());
                for (int y = 1; y <= 3; y++) level.setBlockAndUpdate(new BlockPos(-2, ground + y, 6), Blocks.GLASS.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(3, ground + 1, 6), Blocks.SEA_LANTERN.defaultBlockState());
            }
            player.teleportTo(level, .5, ground + 1, .5, 0, 8);
            player.getAbilities().flying = true;
            player.onUpdateAbilities();
            if (!player.level().dimension().equals(destination)) throw new IllegalStateException("A mod refused the QA dimension transfer to " + destination.location());
            Initializer.LOGGER.info("AFV_DIMENSION_TRANSFER: {}", destination.location());
        }, server);
    }
}
