package dev.allforvulkan.neoforge;

import dev.allforvulkan.framegraph.SodiumShadowDecoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;

/** Copies Sodium section meshes at upload and remembers which of them the shadow pass drew.
 * The flag gates both steps. A normal launch does not retain the bytes. */
public final class ShadowMeshCapture {
    public record Draw(byte[] packed, int vertices, int stride, float regionX, float regionY, float regionZ) {}

    public record Snapshot(float[] modelView, float[] projection, List<Draw> draws, int stride, int generation) {
        public int vertexCount() {
            int count = 0;
            for (Draw draw : draws) count += draw.vertices();
            return count;
        }
    }

    private static final Map<Object, Map<SectionKey, Stored>> MESHES = new IdentityHashMap<>();
    private static final List<Draw> DRAWS = new ArrayList<>();
    private static final float[] MODEL_VIEW = new float[16];
    private static final float[] PROJECTION = new float[16];
    private static boolean matrices;
    private static int generation;
    private static int lastSections;
    private static int maxSections;
    private static int drawLogs;

    private ShadowMeshCapture() {}

    public static void beginShadow() {
        if (!BackendBootstrap.irisShadow()) return;
        DRAWS.clear();
        matrices = false;
    }

    public static void retainUpload(Iterable<?> outputs) {
        if (!BackendBootstrap.irisShadow() || outputs == null) return;
        for (Object output : outputs) {
            if (!(output instanceof ChunkBuildOutput build) || build.render == null || build.meshes == null) continue;
            for (var entry : build.meshes.entrySet()) {
                if (!(entry.getKey() instanceof TerrainRenderPass pass) || pass.isTranslucent()) continue;
                store(pass, build.render.getOriginX(), build.render.getOriginY(), build.render.getOriginZ(), entry.getValue());
            }
        }
    }

    public static void retainDraw(ChunkRenderMatrices cameraMatrices, ChunkRenderListIterable lists,
                                  TerrainRenderPass pass, CameraTransform camera) {
        if (pass == null || pass.isTranslucent() || !shadows() || lists == null || camera == null) return;
        if (Boolean.getBoolean("afv.shadowMesh")) noteShadowDraw(lists, camera, pass);
        if (!BackendBootstrap.irisShadow()) return;
        if (!matrices && cameraMatrices != null && cameraMatrices.modelView() != null && cameraMatrices.projection() != null) {
            cameraMatrices.modelView().get(MODEL_VIEW);
            cameraMatrices.projection().get(PROJECTION);
            matrices = true;
        }
        var sections = MESHES.get(pass);
        if (sections == null) return;
        var iterator = lists.iterator(false);
        while (iterator.hasNext()) {
            ChunkRenderList list = iterator.next();
            RenderRegion region = list.getRegion();
            if (region == null) continue;
            float regionX = region.getOriginX() - camera.intX - camera.fracX;
            float regionY = region.getOriginY() - camera.intY - camera.fracY;
            float regionZ = region.getOriginZ() - camera.intZ - camera.fracZ;
            var indexes = list.sectionsWithGeometryIterator(false);
            if (indexes == null) continue;
            while (indexes.hasNext()) {
                var section = region.getSection(indexes.nextByteAsInt());
                if (section == null) continue;
                Stored stored = sections.get(new SectionKey(section.getOriginX(), section.getOriginY(), section.getOriginZ()));
                if (stored == null) continue;
                DRAWS.add(new Draw(stored.packed, stored.vertices, stored.stride, regionX, regionY, regionZ));
            }
        }
    }

    public static Snapshot snapshot() {
        int stride = DRAWS.isEmpty() ? SodiumShadowDecoder.COMPACT_BYTES : DRAWS.get(0).stride();
        return new Snapshot(MODEL_VIEW.clone(), PROJECTION.clone(), List.copyOf(DRAWS), stride, generation);
    }

    public static boolean hasMatrices() { return matrices; }

    public static int lastSections() { return lastSections; }

    public static int maxSections() { return maxSections; }

    private static void noteShadowDraw(ChunkRenderListIterable lists, CameraTransform camera, TerrainRenderPass pass) {
        int sections = 0;
        var iterator = lists.iterator(false);
        while (iterator.hasNext()) {
            var indexes = iterator.next().sectionsWithGeometryIterator(false);
            if (indexes == null) continue;
            while (indexes.hasNext()) {
                indexes.nextByteAsInt();
                sections++;
            }
        }
        boolean changed = sections != lastSections || drawLogs == 0;
        lastSections = sections;
        if (sections > maxSections) maxSections = sections;
        if (!changed || drawLogs >= 8) return;
        drawLogs++;
        var mask = java.nio.ByteBuffer.allocateDirect(4);
        GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
        System.out.println("AFV_SHADOW_MESH_DRAWS: sections=" + sections
                + " camera=" + camera.intX + "," + camera.intY + "," + camera.intZ
                + " fbo=" + GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING)
                + " draw0=" + GL11.glGetInteger(GL30.GL_DRAW_BUFFER0)
                + " depthTest=" + GL11.glIsEnabled(GL11.GL_DEPTH_TEST)
                + " cull=" + GL11.glIsEnabled(GL11.GL_CULL_FACE)
                + " colorMask=" + (mask.get(0) != 0) + "," + (mask.get(1) != 0) + "," + (mask.get(2) != 0) + "," + (mask.get(3) != 0)
                + " pass=" + pass);
    }

    private static void store(TerrainRenderPass pass, int x, int y, int z, BuiltSectionMeshParts parts) {
        if (parts == null || parts.getVertexData() == null || parts.getVertexCounts() == null) return;
        int vertices = 0;
        for (int count : parts.getVertexCounts()) vertices += count;
        if (vertices < 4) return;
        ByteBuffer view = parts.getVertexData().getDirectBuffer();
        if (view == null) return;
        view = view.duplicate();
        int length = view.remaining();
        if (length <= 0 || length > 2_000_000 || length % vertices != 0) return;
        int stride = length / vertices;
        if (stride < SodiumShadowDecoder.COMPACT_BYTES) return;
        byte[] packed = new byte[length];
        view.get(packed);
        MESHES.computeIfAbsent(pass, key -> new java.util.HashMap<>())
                .put(new SectionKey(x, y, z), new Stored(packed, vertices, stride));
        generation++;
    }

    private static boolean shadows() {
        try {
            return (boolean) Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState")
                    .getMethod("areShadowsCurrentlyBeingRendered").invoke(null);
        } catch (ReflectiveOperationException failure) {
            return false;
        }
    }

    private record SectionKey(int x, int y, int z) {}

    private record Stored(byte[] packed, int vertices, int stride) {}
}
