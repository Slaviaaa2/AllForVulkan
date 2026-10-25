package dev.allforvulkan.bridge;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import org.lwjgl.system.MemoryUtil;

/** Opt-in GL entrypoint routing. Native TLS keeps other GL threads unredirected. */
public final class DirectDefaultFramebuffer {
    public static final boolean ENABLED = Boolean.getBoolean("afv.experimentalDirectOutput");
    private static boolean loaded;
    private DirectDefaultFramebuffer() {}

    public static long address(ByteBuffer name, long original) {
        if (!ENABLED || original == 0) return original;
        load();
        return resolve(MemoryUtil.memAddress(name), original);
    }
    private static synchronized void load() {
        if (loaded) return;
        String resource = "/dev/allforvulkan/bridge/native/linux-x86_64/libafv_framebuffer.so";
        try (var input = DirectDefaultFramebuffer.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("Direct output experiment requires the Linux x86_64 native router");
            var file = Files.createTempFile("afv-framebuffer-", ".so");
            try {
                Files.copy(input, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.load(file.toAbsolutePath().toString());
            } finally { Files.deleteIfExists(file); }
            loaded = true;
        } catch (java.io.IOException failure) { throw new IllegalStateException("Load direct-output GL router", failure); }
    }
    public static void begin(int framebuffer) {
        if (!ENABLED || !loaded || framebuffer <= 0) throw new IllegalStateException("Direct-output router is unavailable");
        redirect(framebuffer);
    }
    public static void end() { if (loaded) redirect(0); }
    public static long[] statistics() { return loaded ? counters() : new long[3]; }
    private static native long resolve(long name, long original);
    private static native void redirect(int framebuffer);
    private static native long[] counters();
}
