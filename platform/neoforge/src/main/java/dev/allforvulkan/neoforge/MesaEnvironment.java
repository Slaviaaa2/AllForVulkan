package dev.allforvulkan.neoforge;

import com.sun.jna.Library;
import com.sun.jna.Native;
import java.util.LinkedHashMap;
import java.util.Map;

/** Only the launched game process is changed; the parent desktop is untouched. */
final class MesaEnvironment {
    interface LibC extends Library {
        int setenv(String name, String value, int overwrite);
        int unsetenv(String name);
        String getenv(String name);
    }
    private static LibC libc;
    private static final Map<String, String> previous = new LinkedHashMap<>();
    static void configure(String vendor) {
        libc = Native.load("c", LibC.class);
        try {
            set("MESA_LOADER_DRIVER_OVERRIDE", "zink");
            set("GALLIUM_DRIVER", "zink");
            set("__EGL_VENDOR_LIBRARY_FILENAMES", vendor);
        } catch (RuntimeException | LinkageError failure) { restore(); throw failure; }
    }
    private static void set(String name, String value) {
        previous.putIfAbsent(name, libc.getenv(name));
        if (libc.setenv(name, value, 1) != 0) throw new IllegalStateException("Cannot select Mesa: " + name);
    }
    static void restore() {
        if (libc == null) return;
        previous.forEach((name, value) -> { if (value == null) libc.unsetenv(name); else libc.setenv(name, value, 1); });
        previous.clear();
    }
}
