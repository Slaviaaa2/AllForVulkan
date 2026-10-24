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
    private static boolean initialized, vulkan, zinkClient;
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
            boolean glRenderer = mods.contains("sodium") || mods.contains("iris") || mods.contains("embeddium");
            boolean clientBridge = Boolean.getBoolean("afv.zinkClient") || (requested == Mode.VULKAN && glRenderer);
            if (!clientBridge && glRenderer) {
                reason = "OpenGL selected automatically for the installed renderer; select Vulkan to try the Linux Zink client bridge";
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
            if (clientBridge) {
                Path vendor = Path.of(System.getProperty("afv.mesaEglVendor", "/usr/share/glvnd/egl_vendor.d/50_mesa.json"));
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux") || !Files.isReadable(vendor)) {
                    reason = "OpenGL fallback: Zink client bridge requires Linux and an installed Mesa EGL vendor";
                    Files.deleteIfExists(pending);
                    return;
                }
                var zink = ZinkPreflight.inspect(FMLLoader.getGamePath(), vendor);
                if (!zink.supported()) {
                    reason = "OpenGL fallback: " + zink.detail();
                    Files.deleteIfExists(pending);
                    return;
                }
                MesaEnvironment.configure(vendor.toString());
            }
            var result = VulkanSupport.probe(clientBridge || Boolean.getBoolean("afv.zinkInterop"), clientBridge);
            zinkClient = result.supported() && clientBridge;
            vulkan = result.supported() && !clientBridge;
            reason = (zinkClient ? "Vulkan presentation with Zink client: " : vulkan ? "Vulkan preflight passed: " : "OpenGL fallback: ") + result.detail();
            if (!result.supported()) { MesaEnvironment.restore(); Files.deleteIfExists(pending); }
        } catch (IOException | RuntimeException | LinkageError failure) {
            vulkan = zinkClient = false;
            MesaEnvironment.restore();
            reason = "OpenGL fallback: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            LogManager.getLogger("AllForVulkan").info("AFV_BACKEND: requested={}, active={}, reason={}", requested, vulkan || zinkClient ? "Vulkan" : "OpenGL", reason);
        }
    }

    public static boolean vulkan() { initialize(); return vulkan; }
    public static boolean zinkClient() { initialize(); return zinkClient; }
    public static boolean usesVulkan() { initialize(); return vulkan || zinkClient; }
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
        if (!vulkan && !zinkClient) return;
        try { Files.deleteIfExists(pending); }
        catch (IOException failure) { LogManager.getLogger("AllForVulkan").warn("Cannot clear Vulkan startup recovery marker", failure); }
    }
}
