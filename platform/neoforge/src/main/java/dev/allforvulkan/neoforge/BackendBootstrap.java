package dev.allforvulkan.neoforge;

import dev.allforvulkan.bridge.VulkanSupport;
import dev.allforvulkan.framegraph.ShaderBackendSelection;
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
    public enum Presentation { BRIDGE, NATIVE }
    public enum ShaderBackend { ZINK, DIRECT }
    private static final Set<String> BASE_MODS = Set.of("minecraft", "neoforge", "allforvulkan");
    private static boolean initialized, vulkan, zinkClient, zinkNative, presentationFailure;
    private static Mode requested = Mode.AUTO;
    private static Presentation presentation = Presentation.BRIDGE;
    private static ShaderBackend shaderBackend = ShaderBackend.ZINK;
    private static ShaderBackendSelection.Decision shaderDecision = ShaderBackendSelection.choose("zink", false);
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
            presentation = Presentation.valueOf(System.getProperty("afv.presentation", settings.getProperty("presentation", "BRIDGE")).trim().toUpperCase(Locale.ROOT));
            shaderBackend = ShaderBackend.valueOf(System.getProperty("afv.shaderBackend", settings.getProperty("shaderBackend", "ZINK")).trim().toUpperCase(Locale.ROOT));
            shaderDecision = ShaderBackendSelection.choose(shaderBackend.name(), false);
            if (requested == Mode.OPENGL) { reason = "OpenGL selected in settings"; return; }
            Set<String> mods = FMLLoader.getLoadingModList().getMods().stream().map(mod -> mod.getModId()).collect(Collectors.toSet());
            // Do not replace a third-party mod's terrain/GL hooks with the
            // native renderer. Preserve the existing client pipeline and let
            // the real GL driver translate it, including unfamiliar mods.
            boolean moddedClient = mods.stream().anyMatch(mod -> !BASE_MODS.contains(mod));
            var packPlan = shaderBackend == ShaderBackend.DIRECT && moddedClient
                    ? dev.allforvulkan.framegraph.IrisPackPlan.fromGameDirectory(FMLLoader.getGamePath())
                    : dev.allforvulkan.framegraph.IrisPackPlan.empty();
            shaderDecision = ShaderBackendSelection.choose(shaderBackend.name(), moddedClient, packPlan);
            boolean clientBridge = Boolean.getBoolean("afv.zinkClient") || moddedClient || presentation == Presentation.NATIVE;
            boolean nativeWindow = clientBridge && presentation == Presentation.NATIVE;
            boolean asyncForced = "true".equalsIgnoreCase(System.getProperty("afv.asyncPresent")) || Boolean.getBoolean("afv.experimentalAsyncPresent");
            if (nativeWindow && (Boolean.getBoolean("afv.experimentalDirectOutput") || Boolean.getBoolean("afv.experimentalFramePipeline")
                    || asyncForced || Boolean.getBoolean("afv.smokeFinalOutput")))
                throw new IllegalArgumentException("Native Zink presentation cannot use AFV bridge experiments or bridge readback");
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
                if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux")) {
                    reason = "OpenGL fallback: Zink client bridge requires Linux and an installed Mesa EGL vendor";
                    Files.deleteIfExists(pending);
                    return;
                }
                var selection = ZinkPreflight.select(FMLLoader.getGamePath(), MesaVendors.discover(), nativeWindow);
                if (selection.vendor() == null) {
                    reason = "OpenGL fallback: " + selection.result().detail();
                    Files.deleteIfExists(pending);
                    return;
                }
                MesaEnvironment.configure(selection.vendor().toString());
                if (nativeWindow) {
                    zinkNative = true;
                    reason = "Zink native Vulkan presentation: " + selection.result().detail();
                    return;
                }
            }
            var result = VulkanSupport.probe(clientBridge || Boolean.getBoolean("afv.zinkInterop"), clientBridge);
            zinkClient = result.supported() && clientBridge;
            vulkan = result.supported() && !clientBridge;
            reason = (zinkClient ? "Vulkan presentation with Zink client: " : vulkan ? "Vulkan preflight passed: " : "OpenGL fallback: ") + result.detail();
            if (!result.supported()) { MesaEnvironment.restore(); Files.deleteIfExists(pending); }
        } catch (IOException | RuntimeException | LinkageError failure) {
            vulkan = zinkClient = zinkNative = false;
            MesaEnvironment.restore();
            reason = "OpenGL fallback: " + failure.getClass().getSimpleName() + ": " + failure.getMessage();
        } finally {
            LogManager.getLogger("AllForVulkan").info("AFV_BACKEND: requested={}, active={}, reason={}", requested, vulkan || zinkClient || zinkNative ? "Vulkan" : "OpenGL", reason);
            LogManager.getLogger("AllForVulkan").info("AFV_SHADER_BACKEND: requested={}, active={}, coverage={}, fallback={}",
                    shaderDecision.requested(), shaderDecision.active(), shaderDecision.coverage(), shaderDecision.fallback());
        }
    }

    public static boolean vulkan() { initialize(); return vulkan; }
    public static boolean zinkClient() { initialize(); return zinkClient; }
    public static boolean zinkNative() { initialize(); return zinkNative; }
    public static boolean usesZink() { initialize(); return zinkClient || zinkNative; }
    public static Presentation presentation() { initialize(); return presentation; }
    public static ShaderBackend shaderBackend() { initialize(); return shaderBackend; }
    public static String shaderBackendRequested() { initialize(); return shaderDecision.requested(); }
    public static String shaderBackendActive() { initialize(); return shaderDecision.active(); }
    public static String shaderBackendFallback() { initialize(); return shaderDecision.fallback(); }
    public static String shaderBackendCoverage() { initialize(); return shaderDecision.coverage(); }
    public static boolean usesVulkan() { initialize(); return vulkan || zinkClient || zinkNative; }
    public static Mode requested() { initialize(); return requested; }
    public static String reason() { initialize(); return reason; }

    public static void save(Mode mode) throws IOException { save(mode, presentation(), shaderBackend()); }
    public static void save(Mode mode, Presentation selectedPresentation) throws IOException { save(mode, selectedPresentation, shaderBackend()); }
    public static void save(Mode mode, Presentation selectedPresentation, ShaderBackend selectedShader) throws IOException {
        initialize();
        Properties settings = new Properties();
        if (Files.isRegularFile(config)) try (Reader input = Files.newBufferedReader(config)) { settings.load(input); }
        settings.setProperty("backend", mode.name());
        settings.setProperty("presentation", selectedPresentation.name());
        settings.setProperty("shaderBackend", selectedShader.name());
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
        if (presentationFailure || (!vulkan && !zinkClient && !zinkNative)) return;
        try { Files.deleteIfExists(pending); }
        catch (IOException failure) { LogManager.getLogger("AllForVulkan").warn("Cannot clear Vulkan startup recovery marker", failure); }
    }
    public static void presentationFailed(Throwable failure) {
        if ((!zinkClient && !zinkNative) || pending == null) return;
        presentationFailure = true;
        try { Files.writeString(pending, "Vulkan presentation failed: " + failure.getClass().getSimpleName() + "\n"); }
        catch (IOException markerFailure) { LogManager.getLogger("AllForVulkan").warn("Cannot save Vulkan presentation recovery marker", markerFailure); }
    }
}
