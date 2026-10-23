package net.vulkanmod.render.chunk.build.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.vulkanmod.render.chunk.build.RenderRegion;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;
import org.joml.Vector3f;

/** Uses NeoForge's patched model tessellator, including model render layers. */
public final class BlockRenderer {
    private final PoseStack pose = new PoseStack();
    private final RandomSource random = RandomSource.create(42);
    private BuilderResources resources;
    private RenderRegion region;

    public BlockRenderer(LightPipeline flat, LightPipeline smooth) { }
    public void setResources(BuilderResources resources) { this.resources = resources; }
    public void prepareForWorld(RenderRegion region, boolean cull) { this.region = region; }

    public void renderBlock(BlockState state, BlockPos blockPos, Vector3f position) {
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var model = dispatcher.getBlockModel(state);
        var modelData = model.getModelData(region, blockPos, state, region.getModelData(blockPos));
        long seed = state.getSeed(blockPos);
        random.setSeed(seed);
        pose.pushPose();
        try {
            pose.translate(position.x, position.y, position.z);
            for (var layer : model.getRenderTypes(state, random, modelData)) {
                var type = TerrainRenderType.getRemapped(TerrainRenderType.get(layer));
                var builder = resources.builderPack.builder(type);
                builder.setBlockAttributes(state);
                var consumer = builder.getBufferBuilder(QuadFacing.UNDEFINED.ordinal());
                random.setSeed(seed);
                dispatcher.getModelRenderer().tesselateBlock(region, model, state, blockPos,
                        pose, consumer, true, random, seed, OverlayTexture.NO_OVERLAY, modelData, layer);
            }
        } finally {
            pose.popPose();
        }
    }
}
