package dev.allforvulkan.validation;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLDebugMessageCallback;
import java.nio.IntBuffer;
import java.util.stream.Collectors;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL43.*;

/** Explicit diagnostic only; the same bounded trace works for both GL presentation routes. */
public final class GlDebugTrace {
    private static GLDebugMessageCallback callback;
    private static int errors;
    private GlDebugTrace() {}
    public static void installIfRequested() {
        if (!Boolean.getBoolean("afv.debugGlClient") || callback != null || !GL.getCapabilities().OpenGL43) return;
        callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) -> {
            if (type == GL_DEBUG_TYPE_ERROR && ++errors <= 8)
                System.out.println("AFV_GL_DEBUG_ERROR: " + GLDebugMessageCallback.getMessage(length, message) + " at " +
                        StackWalker.getInstance().walk(frames -> frames.limit(20).map(Object::toString).collect(Collectors.joining(" <- "))));
        });
        glDebugMessageCallback(callback, 0);
        glDebugMessageControl(GL_DONT_CARE, GL_DONT_CARE, GL_DONT_CARE, (IntBuffer)null, false);
        glDebugMessageControl(GL_DONT_CARE, GL_DEBUG_TYPE_ERROR, GL_DONT_CARE, (IntBuffer)null, true);
        glEnable(GL_DEBUG_OUTPUT);
        glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
    }
    public static void close() {
        if (callback == null) return;
        glDebugMessageCallback(null, 0);
        callback.free(); callback = null;
    }
}
