package dev.allforvulkan.neoforge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import net.caffeinemc.mods.sodium.client.gl.device.DrawCommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlIndexType;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.Pointer;
import static org.lwjgl.opengl.GL11C.GL_TRIANGLES;
import static org.lwjgl.opengl.GL11C.GL_UNSIGNED_INT;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL32C.nglMultiDrawElementsBaseVertex;
import static org.lwjgl.opengl.GL33C.glGetQueryObjecti64;

/** Render thread time and submitted work of Sodium's terrain path, requested with afv.terrainWork.
 * Stage times cover every frame; draw counts are read one frame in eight.
 *
 * afv.terrainOcclusion answers how much of what Sodium draws is hidden. Every REPLAY_EVERY frames
 * the opaque passes are drawn a second time after both finished, one sample query per section.
 * Depth now holds the final surface, so a section with no samples could not be seen at all. That
 * also counts sections Sodium drew before their occluder, which a query around the first draw
 * would miss. The replay stalls those frames, so such a run reports no usable frame times. */
public final class TerrainWorkProbe {
    public static final boolean ENABLED = Boolean.getBoolean("afv.terrainWork");
    private static final boolean OCCLUSION = ENABLED && Boolean.getBoolean("afv.terrainOcclusion");
    public enum Stage { SETUP, GRAPH, LISTS, CHUNK_UPDATE, CHUNK_UPLOAD, LAYER, FILL, SUBMIT }
    private static final int COUNT_EVERY = 8, REPLAY_EVERY = 256, READ_AFTER = 6, MAX_REGION_SECTIONS = 256;
    private static final class Counts { long regions, draws, elements, sections; }
    private static final class Hidden { long sections, hiddenSections, elements, hiddenElements, regions, hiddenRegions, samples; }
    private record SectionQuery(String key, int region, int query, long elements) {}
    private record Replay(long frame, List<SectionQuery> queries) {}

    private static final long[][] nanos = new long[2][Stage.values().length];
    private static final long[] entered = new long[Stage.values().length];
    private static final Map<String, Counts> counts = new TreeMap<>();
    private static final Map<String, Hidden> hidden = new TreeMap<>();
    private static final ArrayDeque<Replay> pending = new ArrayDeque<>();
    private static final ArrayDeque<Integer> spare = new ArrayDeque<>();
    private static final int[] sectionStarts = new int[MAX_REGION_SECTIONS];
    private static Counts current;
    private static TerrainRenderPass solidPass;
    private static List<SectionQuery> replayed;
    private static String replayKey;
    private static boolean running, counting, replayDue, replaying;
    private static int side, sections, replayRegion;
    private static long frame, frames, countedFrames, replays;
    private TerrainWorkProbe() {}

    public static boolean replaying() { return replaying; }

    public static void enter(Stage stage) {
        if (!ENABLED || replaying) return;
        if (stage == Stage.SETUP || stage == Stage.LAYER) side = TerrainProbe.inShadowPass() ? 1 : 0;
        entered[stage.ordinal()] = System.nanoTime();
    }
    public static void exit(Stage stage) {
        if (!ENABLED || replaying) return;
        nanos[side][stage.ordinal()] += System.nanoTime() - entered[stage.ordinal()];
    }

    public static void beginRender(TerrainRenderPass pass, ChunkRenderListIterable lists) {
        if (!ENABLED) return;
        String key = TerrainProbe.name(pass);
        if (replaying) {
            replayKey = key;
            return;
        }
        if ("solid".equals(key)) solidPass = pass;
        current = counts.computeIfAbsent(key, ignored -> new Counts());
        if (counting)
            for (var iterator = lists.iterator(false); iterator.hasNext();)
                current.sections += iterator.next().getSectionsWithGeometryCount();
    }
    public static void endRender(TerrainRenderPass pass, Consumer<TerrainRenderPass> render) {
        if (!replayDue || replaying || solidPass == null || !"cutout".equals(TerrainProbe.name(pass))) return;
        replayDue = false;
        replaying = true;
        replayed = new ArrayList<>();
        replayRegion = 0;
        render.accept(solidPass);
        render.accept(pass);
        replaying = false;
        pending.add(new Replay(frame, replayed));
    }

    public static void beginFill() {
        sections = 0;
        enter(Stage.FILL);
    }
    public static void endFill() { exit(Stage.FILL); }
    public static void section(MultiDrawBatch batch) {
        if (replaying) sectionStarts[sections++] = batch.size;
    }
    /** Only the replay's batches are refilled: Sodium 0.8 otherwise reuses them, skipping section(). */
    public static MultiDrawBatch cachedBatch(MultiDrawBatch batch) {
        if (replaying) batch.clear();
        return batch;
    }

    public static void multiDraw(DrawCommandList list, MultiDrawBatch batch, GlIndexType type) {
        if (replaying) {
            replaySections(batch);
            return;
        }
        if (counting && current != null) {
            current.regions++;
            current.draws += batch.size;
            current.elements += elements(batch, 0, batch.size);
        }
        enter(Stage.SUBMIT);
        list.multiDrawElementsBaseVertex(batch, type);
        exit(Stage.SUBMIT);
    }
    private static long elements(MultiDrawBatch batch, int first, int end) {
        long total = 0;
        for (int index = first; index < end; index++) total += MemoryUtil.memGetInt(batch.pElementCount + index * 4L);
        return total;
    }
    private static void replaySections(MultiDrawBatch batch) {
        replayRegion++;
        for (int index = 0; index < sections; index++) {
            int first = sectionStarts[index], end = index + 1 < sections ? sectionStarts[index + 1] : batch.size;
            if (end <= first) continue;
            int query = spare.isEmpty() ? glGenQueries() : spare.poll();
            glBeginQuery(GL_SAMPLES_PASSED, query);
            nglMultiDrawElementsBaseVertex(GL_TRIANGLES, batch.pElementCount + first * 4L, GL_UNSIGNED_INT,
                    batch.pElementPointer + (long) first * Pointer.POINTER_SIZE, end - first, batch.pBaseVertex + first * 4L);
            glEndQuery(GL_SAMPLES_PASSED);
            replayed.add(new SectionQuery(replayKey, replayRegion, query, elements(batch, first, end)));
        }
    }

    public static void frame() {
        if (!ENABLED) return;
        frame++;
        frames++;
        counting = frame % COUNT_EVERY == 0;
        if (counting) countedFrames++;
        replayDue = OCCLUSION && running && frame % REPLAY_EVERY == 0;
        while (!pending.isEmpty() && frame - pending.peek().frame >= READ_AFTER) {
            Replay done = pending.peek();
            if (!done.queries.isEmpty() && glGetQueryObjecti(done.queries.getLast().query, GL_QUERY_RESULT_AVAILABLE) == 0) break;
            pending.poll();
            collect(done);
        }
    }
    private static void collect(Replay replay) {
        replays++;
        long regionSamples = 0;
        SectionQuery previous = null;
        for (SectionQuery section : replay.queries) {
            if (previous != null && (previous.region != section.region || !previous.key.equals(section.key))) {
                countRegion(previous.key, regionSamples);
                regionSamples = 0;
            }
            long samples = glGetQueryObjecti64(section.query, GL_QUERY_RESULT);
            spare.add(section.query);
            Hidden total = hidden.computeIfAbsent(section.key, ignored -> new Hidden());
            total.sections++;
            total.elements += section.elements;
            total.samples += samples;
            if (samples == 0) {
                total.hiddenSections++;
                total.hiddenElements += section.elements;
            }
            regionSamples += samples;
            previous = section;
        }
        if (previous != null) countRegion(previous.key, regionSamples);
    }
    private static void countRegion(String key, long samples) {
        Hidden total = hidden.get(key);
        total.regions++;
        if (samples == 0) total.hiddenRegions++;
    }

    public static void reset() {
        for (long[] sideNanos : nanos) java.util.Arrays.fill(sideNanos, 0);
        counts.clear();
        hidden.clear();
        pending.clear();
        frames = countedFrames = replays = 0;
        running = true;
    }
    public static Map<String, Object> result() {
        var report = new LinkedHashMap<String, Object>();
        report.put("frames", frames);
        report.put("countedFrames", countedFrames);
        report.put("mainMicrosPerFrame", stages(0));
        report.put("shadowMicrosPerFrame", stages(1));
        var passes = new LinkedHashMap<String, Object>();
        double perCount = Math.max(1, countedFrames);
        counts.forEach((key, total) -> {
            var values = new LinkedHashMap<String, Object>();
            values.put("regions", total.regions / perCount);
            values.put("draws", total.draws / perCount);
            values.put("triangles", total.elements / 3.0 / perCount);
            values.put("sectionsWithGeometry", total.sections / perCount);
            passes.put(key, values);
        });
        report.put("passes", passes);
        if (OCCLUSION) {
            report.put("replays", replays);
            var occlusion = new LinkedHashMap<String, Object>();
            double perReplay = Math.max(1, replays);
            hidden.forEach((key, total) -> {
                var values = new LinkedHashMap<String, Object>();
                values.put("sections", total.sections / perReplay);
                values.put("hiddenSections", total.hiddenSections / perReplay);
                values.put("triangles", total.elements / 3.0 / perReplay);
                values.put("hiddenTriangles", total.hiddenElements / 3.0 / perReplay);
                values.put("regions", total.regions / perReplay);
                values.put("hiddenRegions", total.hiddenRegions / perReplay);
                values.put("visibleSamples", total.samples / perReplay);
                occlusion.put(key, values);
            });
            report.put("occlusion", occlusion);
        }
        return report;
    }
    private static Map<String, Object> stages(int index) {
        var values = new LinkedHashMap<String, Object>();
        for (Stage stage : Stage.values())
            values.put(stage.name().toLowerCase(java.util.Locale.ROOT), nanos[index][stage.ordinal()] / 1e3 / Math.max(1, frames));
        return values;
    }
}
