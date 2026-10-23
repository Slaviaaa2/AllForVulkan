package dev.allforvulkan.bridge;

import org.lwjgl.system.JNI;
import org.lwjgl.system.Library;
import org.lwjgl.system.SharedLibrary;

/** Exported Linux FDs must be closed once, or explicitly transferred to GL. */
final class OwnedFd implements AutoCloseable {
    private static final SharedLibrary LIBC = Library.loadNative("org.lwjgl", "libc.so.6");
    private static final long CLOSE = LIBC.getFunctionAddress("close");
    private int fd;
    OwnedFd(int fd) {
        if (fd < 0) throw new IllegalArgumentException("Invalid exported FD");
        this.fd = fd;
    }
    int value() { if (fd < 0) throw new IllegalStateException("FD ownership was transferred"); return fd; }
    void transferred() { fd = -1; }
    @Override public void close() {
        if (fd >= 0) {
            int owned = fd;
            fd = -1;
            if (JNI.callI(owned, CLOSE) != 0) throw new IllegalStateException("Failed to close exported FD");
        }
    }
}
