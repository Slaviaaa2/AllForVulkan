package dev.allforvulkan.neoforge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL33C.*;

/** GPU time per Minecraft render stage, through GL timestamps taken at profiler section changes.
 * A vendor driver and Zink both implement them, so the same stage can be compared across drivers.
 * The time between two stamps belongs to the section the first one entered. One frame in eight
 * is sampled. */
public final class SectionProbe {
    public static final boolean ENABLED = Boolean.getBoolean("afv.sectionProbe");
    private static final int SAMPLE_EVERY = 8, READ_AFTER = 6;
    private record Stamp(String section, int query) {}
    private record Sampled(long frame, List<Stamp> stamps) {}

    private static final ArrayDeque<String> stack = new ArrayDeque<>();
    private static final ArrayDeque<Sampled> pending = new ArrayDeque<>();
    private static final ArrayDeque<Integer> spare = new ArrayDeque<>();
    private static final Map<String, Long> nanos = new TreeMap<>();
    private static Thread renderThread;
    private static List<Stamp> current;
    private static long frame, sampledFrames;
    private SectionProbe() {}

    private static boolean onRenderThread() {
        return ENABLED && renderThread != null && Thread.currentThread() == renderThread;
    }
    private static void stamp(String section) {
        if (current == null) return;
        int query = spare.isEmpty() ? glGenQueries() : spare.poll();
        glQueryCounter(query, GL_TIMESTAMP);
        current.add(new Stamp(section, query));
    }
    public static void push(String section) {
        if (!onRenderThread()) return;
        stack.push(section);
        stamp(section);
    }
    public static void pop() {
        if (!onRenderThread() || stack.isEmpty()) return;
        stack.pop();
        stamp(stack.isEmpty() ? "outside" : stack.peek());
    }
    public static void popPush(String section) {
        if (!onRenderThread()) return;
        if (!stack.isEmpty()) stack.pop();
        stack.push(section);
        stamp(section);
    }
    public static void frame() {
        if (!ENABLED) return;
        renderThread = Thread.currentThread();
        frame++;
        if (current != null) {
            stamp("end");
            pending.add(new Sampled(frame, current));
            current = null;
        }
        if (frame % SAMPLE_EVERY == 0) {
            current = new ArrayList<>(128);
            stamp(stack.isEmpty() ? "outside" : stack.peek());
        }
        while (!pending.isEmpty() && frame - pending.peek().frame >= READ_AFTER) {
            Sampled done = pending.peek();
            if (glGetQueryObjecti(done.stamps.getLast().query, GL_QUERY_RESULT_AVAILABLE) == 0) break;
            pending.poll();
            sampledFrames++;
            long previous = 0;
            String section = null;
            for (Stamp stamp : done.stamps) {
                long time = glGetQueryObjecti64(stamp.query, GL_QUERY_RESULT);
                if (section != null && time > previous) nanos.merge(section, time - previous, Long::sum);
                previous = time;
                section = stamp.section;
                spare.add(stamp.query);
            }
        }
    }
    public static void reset() { nanos.clear(); sampledFrames = 0; }
    public static Map<String, Object> result() {
        var report = new LinkedHashMap<String, Object>();
        report.put("sampledFrames", sampledFrames);
        nanos.entrySet().stream().sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .forEach(entry -> report.put(entry.getKey(), entry.getValue() / 1e3 / Math.max(1, sampledFrames)));
        return report;
    }
}
