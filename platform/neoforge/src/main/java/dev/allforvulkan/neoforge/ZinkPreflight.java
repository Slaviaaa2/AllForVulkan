package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.VulkanSupport;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.List;
import org.apache.logging.log4j.LogManager;

/** Never load an unverified Mesa driver in the game merely to inspect capabilities. */
final class ZinkPreflight {
    record Selection(Path vendor, VulkanSupport.Result result) {}

    static Selection select(Path game, List<Path> vendors) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        Path directory = game.resolve(".allforvulkan/preflight");
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zink-probe.log"), "No Zink vendor has passed this launch\n");
        for (int i = 1; i <= 4; i++) Files.deleteIfExists(directory.resolve("zink-probe-" + i + ".log"));
        VulkanSupport.Result result = new VulkanSupport.Result(false, "No readable Mesa EGL vendor metadata was found");
        int attempt = 0;
        for (Path vendor : vendors) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0 || Thread.currentThread().isInterrupted()) break;
            int index = ++attempt;
            LogManager.getLogger("AllForVulkan").info("AFV_ZINK_VENDOR_PROBE: attempt={}, vendor={}", index, vendor);
            result = inspect(game, vendor, remaining, "zink-probe-" + index + ".log");
            Path log = game.resolve(".allforvulkan/preflight/zink-probe-" + index + ".log");
            if (Files.isRegularFile(log)) Files.copy(log, log.resolveSibling("zink-probe.log"), StandardCopyOption.REPLACE_EXISTING);
            if (result.supported()) {
                LogManager.getLogger("AllForVulkan").info("AFV_ZINK_VENDOR_SELECTED: attempt={}, vendor={}", index, vendor);
                return new Selection(vendor, result);
            }
            LogManager.getLogger("AllForVulkan").warn("AFV_ZINK_VENDOR_REJECTED: attempt={}, {}", index, result.detail());
        }
        return new Selection(null, result);
    }

    static VulkanSupport.Result inspect(Path game, Path vendor) throws IOException {
        return inspect(game, vendor, TimeUnit.SECONDS.toNanos(45), "zink-probe.log");
    }

    private static VulkanSupport.Result inspect(Path game, Path vendor, long timeout, String logName) throws IOException {
        long deadline = System.nanoTime() + timeout;
        Path directory = game.resolve(".allforvulkan/preflight");
        Files.createDirectories(directory);
        Path helper = Files.createTempFile(directory, "zink-probe-", ".jar");
        Path log = directory.resolve(logName);
        Files.writeString(log, "Zink preflight has not started: " + vendor + "\n");
        Process process = null;
        try {
            try (var resource = ZinkPreflight.class.getResourceAsStream("/META-INF/afv/zink-probe.jar")) {
                if (resource == null) return new VulkanSupport.Result(false, "Bundled Zink preflight helper is missing");
                Files.copy(resource, helper, StandardCopyOption.REPLACE_EXISTING);
            }
            var builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-Xmx128m", "-Dorg.lwjgl.system.stackSize=1024", "-Dorg.lwjgl.opengl.explicitInit=true",
                    "-XX:-CreateCoredumpOnCrash", "-XX:ErrorFile=" + directory.resolve("driver-error-%p.log").toAbsolutePath(),
                    "-jar", helper.toAbsolutePath().toString(), "--wsi");
            builder.directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            builder.environment().put("GALLIUM_DRIVER", "zink");
            builder.environment().put("__EGL_VENDOR_LIBRARY_FILENAMES", vendor.toAbsolutePath().toString());
            process = builder.start();
            if (!process.waitFor(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                return new VulkanSupport.Result(false, "Zink driver preflight timed out; see " + log);
            }
            // The helper emits only bounded diagnostic output from its private 16/31px images.
            String output = Files.readString(log);
            if (process.exitValue() != 0 || !output.contains("AFV_ZINK_PROBE_PASS") || !output.contains("AFV_EXTERNAL_IMAGE_PASS")
                    || !output.contains("AFV_WSI_SUPPORT_PASS") || !output.contains("AFV_INDEXED_STATE_PASS"))
                return new VulkanSupport.Result(false, "Zink driver preflight failed (exit " + process.exitValue() + "); see " + log);
            return new VulkanSupport.Result(true, "Real Zink GL 4.6, compute and bidirectional external-image synchronization passed");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return new VulkanSupport.Result(false, "Zink preflight interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                try { process.waitFor(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
            Files.deleteIfExists(helper);
        }
    }
}
