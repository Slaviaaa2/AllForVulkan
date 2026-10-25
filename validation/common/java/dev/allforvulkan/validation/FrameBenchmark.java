package dev.allforvulkan.validation;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in harness instrumentation; frame intervals include pacing/presentation. */
public final class FrameBenchmark {
    private static final ArrayList<Long> intervals = new ArrayList<>();
    private static boolean active;
    private static long last;
    private FrameBenchmark() {}
    public static void begin() { intervals.clear(); last = 0; active = true; }
    public static void frame() {
        if (!active) return;
        long now = System.nanoTime();
        if (last != 0 && intervals.size() < 250_000) intervals.add(now - last);
        last = now;
    }
    static double percentile(long[] sortedAscending, double rank) {
        int index = (int) Math.ceil(sortedAscending.length * rank) - 1;
        if (index < 0) index = 0;
        if (index >= sortedAscending.length) index = sortedAscending.length - 1;
        return sortedAscending[index] / 1e6;
    }
    public static void finish(Path output, Map<String, Object> conditions) {
        active = false;
        if (intervals.size() < 30) throw new IllegalStateException("Insufficient benchmark frames");
        long[] times = intervals.stream().mapToLong(Long::longValue).sorted().toArray();
        double total = Arrays.stream(times).asDoubleStream().sum();
        Map<String, Object> report = new LinkedHashMap<>(conditions);
        report.put("metric", "RenderFrameEvent.Post inter-frame interval; includes pacing and presentation, not GPU-only time");
        report.put("frames", times.length);
        report.put("seconds", total / 1e9);
        report.put("averageFps", times.length * 1e9 / total);
        report.put("p1FrameMs", percentile(times, .01));
        report.put("medianFrameMs", times[times.length / 2] / 1e6);
        report.put("p95FrameMs", percentile(times, .95));
        report.put("p99FrameMs", percentile(times, .99));
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        try { Files.writeString(output, json + "\n"); }
        catch (IOException failure) { throw new IllegalStateException("Cannot save benchmark", failure); }
        System.out.println("AFV_BENCHMARK_RESULT: " + json.replace('\n', ' '));
    }
}
