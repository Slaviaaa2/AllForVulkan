package dev.allforvulkan.bridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Scheduling, bounded ownership and native failure propagation without a GPU. */
public final class FramePresentDispatcherChecks {
    public static void main(String[] args) throws Exception {
        var owner = Thread.currentThread();
        try (var sync = new FramePresentDispatcher(false)) {
            sync.submit(() -> {
                require(Thread.currentThread() == owner, "Sync present moved threads");
                return 7;
            });
            require(sync.complete() == 7 && sync.complete() == null, "Sync result consumed incorrectly");
        }
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var async = new FramePresentDispatcher(true)) {
            try {
                async.submit(() -> {
                    require(Thread.currentThread() != owner, "Async present stayed on render thread");
                    entered.countDown();
                    await(release);
                    return 13;
                });
                require(entered.await(2, TimeUnit.SECONDS), "Worker did not start");
                // Reaching this line while the native operation is blocked proves
                // the caller can execute the next frame instead of waiting here.
                require((long) async.statistics().get("calls") == 0, "Pending request counted as completed");
                try { async.submit(() -> 0); throw new AssertionError("Unbounded second request accepted"); }
                catch (IllegalStateException expected) { }
            } finally { release.countDown(); }
            require(async.complete() == 13 && async.complete() == null, "Async completion lost or duplicated");
            var original = new IllegalStateException("native failure");
            async.submit(() -> { throw original; });
            try { async.complete(); throw new AssertionError("Worker failure swallowed"); }
            catch (IllegalStateException actual) { require(actual == original, "Worker failure replaced"); }
            async.submit(() -> 19);
            require(async.complete() == 19, "Failed request retained ownership");
        }
        var started = new CountDownLatch(1);
        var finish = new CountDownLatch(1);
        try (var async = new FramePresentDispatcher(true)) {
            async.submit(() -> { started.countDown(); await(finish); return 23; });
            require(started.await(2, TimeUnit.SECONDS), "Interrupt test worker missing");
            var releaser = new Thread(finish::countDown);
            Thread.currentThread().interrupt();
            releaser.start();
            require(async.complete() == 23, "Interrupted drain abandoned native operation");
            require(Thread.interrupted(), "Caller interruption was lost");
            releaser.join();
        }
        var closed = new FramePresentDispatcher(true);
        closed.close(); closed.close();
        try { closed.submit(() -> 0); throw new AssertionError("Closed dispatcher accepted a request"); }
        catch (IllegalStateException expected) { }
        System.out.println("AFV_PRESENT_DISPATCH_PASS: sync/async threads, overlap, one pending request, failure propagation, interrupted drain, shutdown");
    }
    private static void await(CountDownLatch latch) {
        try { require(latch.await(2, TimeUnit.SECONDS), "Timed out waiting for test signal"); }
        catch (InterruptedException failure) { throw new IllegalStateException(failure); }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
