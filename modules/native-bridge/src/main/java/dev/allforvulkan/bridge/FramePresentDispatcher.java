package dev.allforvulkan.bridge;

import java.util.Map;
import java.util.ArrayDeque;
import java.util.concurrent.*;
import java.util.function.IntSupplier;

/** Ordered, bounded jobs on one native queue worker. The render thread owns
 * collection; drain every job before accessing/recreating its resources. */
public final class FramePresentDispatcher implements AutoCloseable {
    private record Result(int code, long nativeNanos) {}
    private final Thread owner = Thread.currentThread();
    private final ExecutorService worker;
    private final ArrayDeque<Future<Result>> pending = new ArrayDeque<>();
    private final int capacity;
    private boolean closed;
    private long calls, nativeNanos, joinNanos;

    public FramePresentDispatcher(boolean asynchronous) {
        this(asynchronous, 1);
    }
    public FramePresentDispatcher(boolean asynchronous, int capacity) {
        if (capacity < 1 || (!asynchronous && capacity != 1)) throw new IllegalArgumentException("Invalid presentation capacity");
        this.capacity = capacity;
        worker = asynchronous ? Executors.newSingleThreadExecutor(task -> {
            var thread = new Thread(task, "AFV-Present");
            thread.setDaemon(true);
            return thread;
        }) : null;
    }

    public boolean asynchronous() { return worker != null; }
    public int pendingCount() { assertOwner(); return pending.size(); }
    public boolean completionReady() { assertOwner(); return !pending.isEmpty() && pending.peekFirst().isDone(); }

    public void submit(IntSupplier nativePresent) {
        assertOwner();
        if (closed || pending.size() >= capacity)
            throw new IllegalStateException("Present dispatcher closed or still pending");
        pending.addLast(worker == null ? CompletableFuture.completedFuture(invoke(nativePresent))
                : worker.submit(() -> invoke(nativePresent)));
    }

    /** Returns a completed VkResult, or null if no request is outstanding. */
    public Integer complete() {
        assertOwner();
        if (pending.isEmpty()) return null;
        var next = pending.removeFirst();
        Result result;
        {
            long start = System.nanoTime();
            boolean interrupted = false;
            try {
                // Queue/swapchain resources cannot be released until the native
                // call actually ends. Restore interruption after draining it.
                for (;;) {
                    try { result = next.get(); break; }
                    catch (InterruptedException retry) { interrupted = true; }
                }
            } catch (ExecutionException failure) { throw propagate(failure.getCause()); }
            finally {
                if (worker != null) joinNanos += System.nanoTime() - start;
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        calls++;
        nativeNanos += result.nativeNanos();
        return result.code();
    }

    public Map<String, Object> statistics() {
        assertOwner();
        return Map.of("mode", asynchronous() ? "async" : "sync", "calls", calls, "capacity", capacity,
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
        Throwable failure = null;
        try {
            while (!pending.isEmpty()) {
                try { complete(); }
                catch (RuntimeException | Error next) {
                    if (failure == null) failure = next;
                    else if (failure != next) failure.addSuppressed(next);
                }
            }
        } finally { closed = true; if (worker != null) worker.shutdown(); }
        if (failure != null) throw propagate(failure);
    }
}
