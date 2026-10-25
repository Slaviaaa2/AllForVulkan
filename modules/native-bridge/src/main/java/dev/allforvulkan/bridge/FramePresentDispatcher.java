package dev.allforvulkan.bridge;

import java.util.Map;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/** One outstanding native present, overlapping only the next frame's GL work.
 * Complete it before touching the AFV queue/swapchain or destroying resources. */
public final class FramePresentDispatcher implements AutoCloseable {
    private record Result(int code, long nativeNanos) {}
    private final Thread owner = Thread.currentThread();
    private final ExecutorService worker;
    private Future<Result> pending;
    private Result immediate;
    private boolean closed;
    private long calls, nativeNanos, joinNanos;

    public FramePresentDispatcher(boolean asynchronous) {
        worker = asynchronous ? Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "AFV-Present");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    public boolean asynchronous() { return worker != null; }

    public void submit(IntSupplier nativePresent) {
        assertOwner();
        if (closed || pending != null || immediate != null)
            throw new IllegalStateException("Present dispatcher closed or still pending");
        if (worker == null) immediate = invoke(nativePresent);
        else pending = worker.submit(() -> invoke(nativePresent));
    }

    /** Returns a completed VkResult, or null if no request is outstanding. */
    public Integer complete() {
        assertOwner();
        Result result = immediate;
        immediate = null;
        if (pending != null) {
            long start = System.nanoTime();
            boolean interrupted = false;
            try {
                // Queue/swapchain resources cannot be released until the native
                // call actually ends. Restore interruption after draining it.
                for (;;) {
                    try { result = pending.get(); break; }
                    catch (InterruptedException retry) { interrupted = true; }
                }
            } catch (ExecutionException failure) { throw propagate(failure.getCause()); }
            finally {
                pending = null;
                joinNanos += System.nanoTime() - start;
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        if (result == null) return null;
        calls++;
        nativeNanos += result.nativeNanos();
        return result.code();
    }

    public Map<String, Object> statistics() {
        assertOwner();
        return Map.of("mode", asynchronous() ? "async" : "sync", "calls", calls,
                "nativeAverageMs", calls == 0 ? 0d : nativeNanos / (calls * 1_000_000d),
                "joinAverageMs", calls == 0 ? 0d : joinNanos / (calls * 1_000_000d));
    }

    private static Result invoke(IntSupplier operation) {
        long start = System.nanoTime();
        return new Result(operation.getAsInt(), System.nanoTime() - start);
    }
    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException runtime) return runtime;
        if (failure instanceof Error error) throw error;
        return new IllegalStateException("Native presentation worker failed", failure);
    }
    private void assertOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Present dispatcher used outside the render thread");
    }
    @Override public void close() {
        assertOwner();
        if (closed) return;
        try { complete(); }
        finally { closed = true; if (worker != null) worker.shutdown(); }
    }
}
