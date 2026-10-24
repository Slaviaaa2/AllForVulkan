package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.VulkanSupport;
import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Never load an unverified Mesa driver in the game merely to inspect capabilities. */
final class ZinkPreflight {
    static VulkanSupport.Result inspect(Path game, Path vendor) throws IOException {
        Path directory = game.resolve(".allforvulkan/preflight");
        Files.createDirectories(directory);
        Path helper = Files.createTempFile(directory, "zink-probe-", ".jar");
        Path log = directory.resolve("zink-probe.log");
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
            if (!process.waitFor(45, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                return new VulkanSupport.Result(false, "Zink driver preflight timed out; see " + log);
            }
            // The helper emits only bounded diagnostic output from its private 16/31px images.
            String output = Files.readString(log);
            if (process.exitValue() != 0 || !output.contains("AFV_ZINK_PROBE_PASS") || !output.contains("AFV_EXTERNAL_IMAGE_PASS")
                    || !output.contains("AFV_WSI_SUPPORT_PASS"))
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
