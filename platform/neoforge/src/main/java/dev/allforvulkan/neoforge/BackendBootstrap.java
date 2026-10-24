package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.VulkanSupport;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLLoader;
import org.apache.logging.log4j.LogManager;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Decides before either mixin configuration changes Minecraft's renderer. */
public final class BackendBootstrap {
    public enum Mode { AUTO, VULKAN, OPENGL }
    private static boolean initialized, vulkan;
    private static Mode requested = Mode.AUTO;
    private static String reason = "Not initialized";
    private static Path config, pending;
    private BackendBootstrap() {}

    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        Path directory = FMLLoader.getGamePath().resolve("config");
        config = directory.resolve("allforvulkan_backend.properties");
        pending = directory.resolve("allforvulkan_renderer_starting");
        if (FMLLoader.getDist() != Dist.CLIENT) { reason = "Client renderer disabled on server"; return; }
        try {
            Properties settings = new Properties();
            if (Files.isRegularFile(config)) try (Reader input = Files.newBufferedReader(config)) { settings.load(input); }
            String value = System.getProperty("afv.backend", settings.getProperty("backend", "AUTO"));
            requested = Mode.valueOf(value.trim().toUpperCase(Locale.ROOT));
            if (requested == Mode.OPENGL) { reason = "OpenGL selected in settings"; return; }
            Set<String> mods = FMLLoader.getLoadingModList().getMods().stream().map(mod -> mod.getModId()).collect(Collectors.toSet());
            if (mods.contains("sodium") || mods.contains("iris") || mods.contains("embeddium")) {
                reason = "OpenGL selected: installed terrain/shader renderer needs the pending Vulkan integration";
                return;
            }
            if (net.neoforged.fml.loading.FMLConfig.getBoolConfigValue(net.neoforged.fml.loading.FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL)) {
                reason = "OpenGL selected: save Auto or Vulkan in AFV settings and restart to prepare the Vulkan window";
                return;
            }
            if (Files.exists(pending)) {
                reason = "OpenGL recovery: previous Vulkan startup did not reach stable rendering; save a backend choice to retry";
                return;
            }
            Files.createDirectories(directory);
            // Also survives a native driver crash, which Java cannot catch.
            Files.writeString(pending, "Vulkan startup pending\n", StandardOpenOption.CREATE_NEW);
            var result = VulkanSupport.probe(Boolean.getBoolean("afv.zinkInterop"));
            vulkan = result.supported();
            reason = (vulkan ? "Vulkan preflight passed: " : "OpenGL fallback: ") + result.detail();
            if (!vulkan) Files.deleteIfExists(pending);
        } catch (IOException | RuntimeException | LinkageError failure) {
            vulkan = false;
            reason = "OpenGL fallback: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            LogManager.getLogger("AllForVulkan").info("AFV_BACKEND: requested={}, active={}, reason={}", requested, vulkan ? "Vulkan" : "OpenGL", reason);
        }
    }

    public static boolean vulkan() { initialize(); return vulkan; }
    public static Mode requested() { initialize(); return requested; }
    public static String reason() { initialize(); return reason; }

    public static void save(Mode mode) throws IOException {
        initialize();
        Properties settings = new Properties();
        settings.setProperty("backend", mode.name());
        Files.createDirectories(config.getParent());
        Path temporary = Files.createTempFile(config.getParent(), "afv-backend-", ".tmp");
        try {
            try (Writer output = Files.newBufferedWriter(temporary)) { settings.store(output, "All For Vulkan - restart required"); }
            try { Files.move(temporary, config, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, config, StandardCopyOption.REPLACE_EXISTING); }
            Files.deleteIfExists(pending);
            if (mode != Mode.OPENGL) net.neoforged.fml.loading.FMLConfig.updateConfig(
                    net.neoforged.fml.loading.FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL, false);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static void renderingStable() {
        if (!vulkan) return;
        try { Files.deleteIfExists(pending); }
        catch (IOException failure) { LogManager.getLogger("AllForVulkan").warn("Cannot clear Vulkan startup recovery marker", failure); }
    }
}
