package net.vulkanmod.render.chunk.build.renderer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.vulkanmod.render.chunk.build.light.LightPipeline;
import net.vulkanmod.render.chunk.build.thread.BuilderResources;
import net.vulkanmod.render.chunk.cull.QuadFacing;
import net.vulkanmod.render.vertex.TerrainRenderType;

/** NeoForge liquid rendering also dispatches IClientFluidTypeExtensions hooks. */
public final class FluidRenderer {
    private BuilderResources resources;
    public FluidRenderer(LightPipeline flat, LightPipeline smooth) { }
    public void setResources(BuilderResources resources) { this.resources = resources; }

    public void renderLiquid(BlockState blockState, FluidState fluidState, BlockPos blockPos) {
        var type = TerrainRenderType.getRemapped(TerrainRenderType.get(ItemBlockRenderTypes.getRenderLayer(fluidState)));
        var builder = resources.builderPack.builder(type);
        builder.setBlockAttributes(blockState);
        var consumer = builder.getBufferBuilder(QuadFacing.UNDEFINED.ordinal());
        Minecraft.getInstance().getBlockRenderer().renderLiquid(blockPos, resources.getRegion(), consumer, blockState, fluidState);
    }
}
