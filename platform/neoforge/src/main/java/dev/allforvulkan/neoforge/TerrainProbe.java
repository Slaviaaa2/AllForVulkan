package dev.allforvulkan.neoforge;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33C.*;

/** Measures Sodium's terrain passes through the GL queries both the vendor driver and Zink
 * implement, so the two can be compared directly. SAMPLES_PASSED counts what passed the depth
 * test as it was drawn: with early depth testing that is close to what was shaded, and the
 * excess over the screen area is overdraw. One frame in eight is sampled to limit the
 * measurement's own cost. */
public final class TerrainProbe {
    public static final boolean PROBE = Boolean.getBoolean("afv.terrainProbe");
    private static final int SAMPLE_EVERY = 8, READ_AFTER = 6;
    private record Pending(String key, long frame, int time, int samples) {}
    private static final class Total { long nanos, samples; }

    private static final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private static final ArrayDeque<int[]> spare = new ArrayDeque<>();
    private static final Map<String, Total> totals = new TreeMap<>();
    private static java.lang.reflect.Method shadowState;
    private static boolean shadowStateResolved, sampling;
    private static long frame, sampledFrames;
    private static int[] active;
    private static String activeKey;
    private TerrainProbe() {}

    private static boolean inShadowPass() {
        if (!shadowStateResolved) {
            shadowStateResolved = true;
            try { shadowState = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState").getMethod("areShadowsCurrentlyBeingRendered"); }
            catch (ReflectiveOperationException | LinkageError absent) { shadowState = null; }
        }
        try { return shadowState != null && (boolean) shadowState.invoke(null); }
        catch (ReflectiveOperationException failure) { return false; }
    }
    private static String name(TerrainRenderPass pass) {
        String base = pass.isTranslucent() ? "translucent" : pass.supportsFragmentDiscard() ? "cutout" : "solid";
        String shadow = inShadowPass() ? "shadow-" : "";
        return shadow + base;
    }
    public static void frame() {
        if (!PROBE) return;
        frame++;
        sampling = frame % SAMPLE_EVERY == 0;
        if (sampling) sampledFrames++;
        while (!pending.isEmpty() && frame - pending.peek().frame >= READ_AFTER) {
            Pending done = pending.peek();
            if (glGetQueryObjecti(done.time, GL_QUERY_RESULT_AVAILABLE) == 0 || glGetQueryObjecti(done.samples, GL_QUERY_RESULT_AVAILABLE) == 0) break;
            pending.poll();
            Total total = totals.computeIfAbsent(done.key, key -> new Total());
            total.nanos += glGetQueryObjecti64(done.time, GL_QUERY_RESULT);
            total.samples += glGetQueryObjecti64(done.samples, GL_QUERY_RESULT);
            spare.add(new int[]{done.time, done.samples});
        }
    }
    public static void reset() { totals.clear(); pending.clear(); sampledFrames = 0; }
    public static Map<String, Object> result() {
        var report = new LinkedHashMap<String, Object>();
        report.put("sampledFrames", sampledFrames);
        totals.forEach((key, total) -> report.put(key, Map.of(
                "gpuMicrosPerFrame", total.nanos / 1e3 / Math.max(1, sampledFrames),
                "samplesPerFrame", total.samples / (double) Math.max(1, sampledFrames))));
        return report;
    }
    public static void beginPass(TerrainRenderPass pass) {
        if (!PROBE || !sampling || active != null) return;
        active = spare.isEmpty() ? new int[]{glGenQueries(), glGenQueries()} : spare.poll();
        activeKey = name(pass);
        glBeginQuery(GL_TIME_ELAPSED, active[0]);
        glBeginQuery(GL_SAMPLES_PASSED, active[1]);
    }
    public static void endPass() {
        if (active == null) return;
        glEndQuery(GL_SAMPLES_PASSED);
        glEndQuery(GL_TIME_ELAPSED);
        pending.add(new Pending(activeKey, frame, active[0], active[1]));
        active = null;
    }
}
