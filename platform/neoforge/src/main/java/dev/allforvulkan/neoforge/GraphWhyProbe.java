package dev.allforvulkan.neoforge;

import java.util.Map;
import java.util.TreeMap;
import net.minecraft.client.Camera;
import com.mojang.blaze3d.systems.RenderSystem;

/** Diagnosis counters for why Sodium re-runs its graph search, requested with afv.graphWhy. */
public final class GraphWhyProbe {
    public static final boolean ENABLED = Boolean.getBoolean("afv.graphWhy");
    private static final Map<String, Integer> callers = new TreeMap<>();
    private static int frames, updates, revisitTrue, builtTrue, builtAll, posMoved, rotMoved, fogMoved, projMoved, dirtyCalls, errStacks;
    private static double x, y, z, xr, yr;
    private static float fog;
    private static org.joml.Matrix4f proj;
    private static boolean have;

    private GraphWhyProbe() {}

    public static void dirty() {
        dirtyCalls++;
        StackTraceElement[] st = new Throwable().getStackTrace();
        String who = st.length > 1 ? st[1].getClassName().replaceAll(".*\\.", "") + "." + st[1].getMethodName() + ":" + st[1].getLineNumber() : "?";
        if (st.length > 2 && st[1].getClassName().endsWith("RenderSectionManager")) who += "<-" + st[2].getClassName().replaceAll(".*\\.", "") + "." + st[2].getMethodName() + ":" + st[2].getLineNumber();
        callers.merge(who, 1, Integer::sum);
    }
    public static void update() { updates++; }
    public static void revisit(boolean v) { if (v) revisitTrue++; }
    public static void built(boolean v) { builtAll++; if (v) builtTrue++; }

    public static void setup(Camera c) {
        var p = c.getPosition();
        float f = RenderSystem.getShaderFogEnd();
        var m = new org.joml.Matrix4f(RenderSystem.getProjectionMatrix());
        if (have) {
            if (p.x != x || p.y != y || p.z != z) posMoved++;
            if (c.getXRot() != xr || c.getYRot() != yr) rotMoved++;
            if (f != fog) fogMoved++;
            if (!m.equals(proj, 1e-4f)) projMoved++;
        }
        x = p.x; y = p.y; z = p.z; xr = c.getXRot(); yr = c.getYRot(); fog = f; proj = m; have = true;
        if (++frames % 300 == 0) {
            System.out.println("AFV_GRAPHWHY frames=" + frames + " update=" + updates + " dirty=" + dirtyCalls + " revisitTrue=" + revisitTrue
                + " buildResultsTrue=" + builtTrue + "/" + builtAll + " pos=" + posMoved + " rot=" + rotMoved + " fog=" + fogMoved + " proj=" + projMoved
                + " fogEnd=" + fog + " callers=" + callers);
            updates = revisitTrue = builtTrue = builtAll = posMoved = rotMoved = fogMoved = projMoved = dirtyCalls = 0;
            callers.clear();
        }
    }

    public static void glError(int type, int id) {
        if (type == 0x824C && errStacks < 4) {
            errStacks++;
            StringBuilder b = new StringBuilder("AFV_GLERR thread=" + Thread.currentThread().getName() + " id=" + id);
            for (StackTraceElement e : new Throwable().getStackTrace()) b.append("\n    at ").append(e);
            System.out.println(b);
        }
    }
}
