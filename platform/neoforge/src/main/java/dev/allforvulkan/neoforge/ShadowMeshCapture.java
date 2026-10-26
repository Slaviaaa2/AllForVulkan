package dev.allforvulkan.neoforge;

import dev.allforvulkan.framegraph.SodiumShadowDecoder;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
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
    private static int passId;
    private static int lastSections;
    private static int maxSections;
    private static int renderLogs;
    private static boolean loggedShadowLookup;

    private ShadowMeshCapture() {}

    public static void beginShadow() {
        if (!BackendBootstrap.irisShadow()) return;
        DRAWS.clear();
        matrices = false;
        passId++;
    }

    public static int passId() { return passId; }

    /** Camera, projection, and section placement. A repeated value means the replay inputs did not move. */
    public static int fingerprint() {
        int hash = mix(1, MODEL_VIEW);
        hash = mix(hash, PROJECTION);
        for (Draw draw : DRAWS) {
            hash = 31 * hash + Float.floatToIntBits(draw.regionX());
            hash = 31 * hash + Float.floatToIntBits(draw.regionY());
            hash = 31 * hash + Float.floatToIntBits(draw.regionZ());
            hash = 31 * hash + draw.vertices();
        }
        return hash;
    }

    private static int mix(int hash, float[] matrix) {
        if (matrix == null) return hash * 31;
        for (float value : matrix) hash = 31 * hash + Float.floatToIntBits(value);
        return hash;
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

    /** True when this call recorded opaque shadow terrain. The caller may then skip the GL draw. */
    public static boolean retainDraw(ChunkRenderMatrices cameraMatrices, ChunkRenderListIterable lists,
                                  TerrainRenderPass pass, CameraTransform camera) {
        if (Boolean.getBoolean("afv.shadowMesh")) noteRender(pass, lists, camera);
        if (pass == null || pass.isTranslucent() || !shadows() || lists == null || camera == null) return false;
        if (!BackendBootstrap.irisShadow()) return false;
        if (!matrices && cameraMatrices != null && cameraMatrices.modelView() != null && cameraMatrices.projection() != null) {
            cameraMatrices.modelView().get(MODEL_VIEW);
            cameraMatrices.projection().get(PROJECTION);
            matrices = true;
        }
        var sections = MESHES.get(pass);
        if (sections == null) return false;
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
        return true;
    }

    public static Snapshot snapshot() {
        int stride = DRAWS.isEmpty() ? SodiumShadowDecoder.COMPACT_BYTES : DRAWS.get(0).stride();
        return new Snapshot(MODEL_VIEW.clone(), PROJECTION.clone(), List.copyOf(DRAWS), stride, generation);
    }

    public static boolean hasMatrices() { return matrices; }

    public static int lastSections() { return lastSections; }

    public static int maxSections() { return maxSections; }

    private static void noteRender(TerrainRenderPass pass, ChunkRenderListIterable lists, CameraTransform camera) {
        boolean shadow = shadows();
        int sections = 0;
        if (shadow && lists != null && pass != null && !pass.isTranslucent()) {
            var iterator = lists.iterator(false);
            while (iterator.hasNext()) {
                var indexes = iterator.next().sectionsWithGeometryIterator(false);
                if (indexes == null) continue;
                while (indexes.hasNext()) {
                    indexes.nextByteAsInt();
                    sections++;
                }
            }
            lastSections = sections;
            if (sections > maxSections) maxSections = sections;
        }
        if ((!shadow && renderLogs > 0) || renderLogs >= 6) return;
        renderLogs++;
        System.out.println("AFV_SHADOW_MESH_RENDER: shadow=" + shadow
                + " sections=" + sections
                + " translucent=" + (pass != null && pass.isTranslucent())
                + " camera=" + (camera == null ? "null" : camera.intX + "," + camera.intY + "," + camera.intZ)
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
            if (!loggedShadowLookup) {
                loggedShadowLookup = true;
                System.out.println("AFV_SHADOW_MESH_RENDER: lookup " + failure.getClass().getSimpleName() + " " + failure.getMessage());
            }
            return false;
        }
    }

    private record SectionKey(int x, int y, int z) {}

    private record Stored(byte[] packed, int vertices, int stride) {}
}
