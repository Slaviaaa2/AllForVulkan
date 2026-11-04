package dev.allforvulkan.neoforge;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.opengl.GL46C.*;

/** Measures Sodium's terrain passes through the GL queries both the vendor driver and Zink
 * implement, so the two can be compared directly. SAMPLES_PASSED counts what passed the depth
 * test as it was drawn: with early depth testing that is close to what was shaded, and the
 * excess over the screen area is overdraw. One frame in eight is sampled to limit the
 * measurement's own cost.
 *
 * afv.terrainProbeMode separates geometry from fragment work, and blanks the image while it does:
 * "discard" enables GL_RASTERIZER_DISCARD around every terrain draw, "alternate" cycles sampled
 * frames through normal, discard, and a one-pixel scissor, so all three see the same world in one
 * run. Discard leaves vertex work; the scissor adds primitive setup but keeps fragments away.
 * afv.terrainProbeStats adds pipeline statistics queries, which also show the driver did not skip
 * the draws under discard. */
public final class TerrainProbe {
    public static final boolean PROBE = Boolean.getBoolean("afv.terrainProbe");
    private static final String MODE = System.getProperty("afv.terrainProbeMode", "normal");
    private static final boolean DISCARD = "discard".equals(MODE), ALTERNATE = "alternate".equals(MODE);
    private static final int NORMAL_FRAME = 0, DISCARD_FRAME = 1, SCISSOR_FRAME = 2;
    private static final String[] VARIANT_PREFIX = {"", "discard-", "scissor-"};
    private static final int SAMPLE_EVERY = 8, READ_AFTER = 6;
    private static final int[] TARGETS = Boolean.getBoolean("afv.terrainProbeStats")
            ? new int[]{GL_TIME_ELAPSED, GL_SAMPLES_PASSED, GL_VERTEX_SHADER_INVOCATIONS, GL_CLIPPING_INPUT_PRIMITIVES,
                    GL_CLIPPING_OUTPUT_PRIMITIVES, GL_FRAGMENT_SHADER_INVOCATIONS}
            : new int[]{GL_TIME_ELAPSED, GL_SAMPLES_PASSED};
    private static final String[] RESULT_NAMES = {"gpuMicrosPerFrame", "samplesPerFrame", "vertexInvocationsPerFrame",
            "clipInputPrimitivesPerFrame", "clipOutputPrimitivesPerFrame", "fragmentInvocationsPerFrame"};
    private record Pending(String key, long frame, int[] queries) {}
    private static final class Total {
        final long[] values = new long[TARGETS.length];
        final int variant;
        Total(int variant) { this.variant = variant; }
    }

    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static final ArrayDeque<int[]> spare = new ArrayDeque<>();
    private static final Map<String, Total> totals = new TreeMap<>();
    private static java.lang.reflect.Method shadowState;
    private static boolean shadowStateResolved, sampling;
    private static long frame, sampledFrames;
    private static final long[] variantFrames = new long[VARIANT_PREFIX.length];
    private static int variant;
    private static int[] active;
    private static String activeKey;
    private TerrainProbe() {}

    static boolean inShadowPass() {
        if (!shadowStateResolved) {
            shadowStateResolved = true;
            try { shadowState = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState").getMethod("areShadowsCurrentlyBeingRendered"); }
            catch (ReflectiveOperationException | LinkageError absent) { shadowState = null; }
        }
        try { return shadowState != null && (boolean) shadowState.invoke(null); }
        catch (ReflectiveOperationException failure) { return false; }
    }
    static String name(TerrainRenderPass pass) {
        String base = pass.isTranslucent() ? "translucent" : pass.supportsFragmentDiscard() ? "cutout" : "solid";
        String shadow = inShadowPass() ? "shadow-" : "";
        return shadow + base;
    }
    public static void frame() {
        if (!PROBE) return;
        frame++;
        sampling = frame % SAMPLE_EVERY == 0;
        variant = DISCARD ? DISCARD_FRAME : NORMAL_FRAME;
        if (sampling) {
            if (ALTERNATE) variant = (int) (sampledFrames % VARIANT_PREFIX.length);
            sampledFrames++;
            variantFrames[variant]++;
        }
        while (!pending.isEmpty() && frame - pending.peek().frame >= READ_AFTER) {
            Pending done = pending.peek();
            if (!available(done.queries)) break;
            pending.poll();
            String key = done.key;
            int doneVariant = 0;
            for (int index = 1; index < VARIANT_PREFIX.length; index++)
                if (key.startsWith(VARIANT_PREFIX[index])) doneVariant = index;
            int finalVariant = doneVariant;
            Total total = totals.computeIfAbsent(key, ignored -> new Total(finalVariant));
            for (int index = 0; index < done.queries.length; index++)
                total.values[index] += glGetQueryObjecti64(done.queries[index], GL_QUERY_RESULT);
            spare.add(done.queries);
        }
    }
    private static boolean available(int[] queries) {
        for (int query : queries) if (glGetQueryObjecti(query, GL_QUERY_RESULT_AVAILABLE) == 0) return false;
        return true;
    }
    public static void reset() {
        totals.clear();
        pending.clear();
        sampledFrames = 0;
        java.util.Arrays.fill(variantFrames, 0);
    }
    public static Map<String, Object> result() {
        var report = new LinkedHashMap<String, Object>();
        report.put("sampledFrames", sampledFrames);
        totals.forEach((key, total) -> {
            var values = new LinkedHashMap<String, Object>();
            double frames = Math.max(1, variantFrames[total.variant]);
            for (int index = 0; index < total.values.length; index++)
                values.put(RESULT_NAMES[index], total.values[index] / (index == 0 ? 1e3 : 1) / frames);
            report.put(key, values);
        });
        return report;
    }
    public static void beginPass(TerrainRenderPass pass) {
        if (!PROBE || TerrainWorkProbe.replaying()) return;
        if (variant == DISCARD_FRAME) glEnable(GL_RASTERIZER_DISCARD);
        else if (variant == SCISSOR_FRAME) {
            glEnable(GL_SCISSOR_TEST);
            glScissor(0, 0, 1, 1);
        }
        if (!sampling || active != null) return;
        if (spare.isEmpty()) {
            active = new int[TARGETS.length];
            glGenQueries(active);
        } else active = spare.poll();
        activeKey = VARIANT_PREFIX[variant] + name(pass);
        for (int index = 0; index < TARGETS.length; index++) glBeginQuery(TARGETS[index], active[index]);
    }
    public static void endPass() {
        if (!PROBE || TerrainWorkProbe.replaying()) return;
        if (active != null) {
            for (int index = TARGETS.length - 1; index >= 0; index--) glEndQuery(TARGETS[index]);
            pending.add(new Pending(activeKey, frame, active));
            active = null;
        }
        if (variant == DISCARD_FRAME) glDisable(GL_RASTERIZER_DISCARD);
        else if (variant == SCISSOR_FRAME) glDisable(GL_SCISSOR_TEST);
    }
}
