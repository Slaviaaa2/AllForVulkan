package dev.allforvulkan.neoforge;

import java.nio.file.*;
import java.util.*;

/** Driver-free checks of GLVND metadata selection; no EGL/Vulkan code is initialized. */
public final class MesaVendorChecks {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("afv-mesa-vendors-");
        try {
            Path first = Files.createDirectory(root.resolve("first")), second = Files.createDirectory(root.resolve("second"));
            Path mesa = metadata(first.resolve("50_mesa.json"), "libEGL_mesa.so.0");
            Path custom = metadata(second.resolve("20_custom.json"), "/opt/private/libEGL_mesa.so.0");
            Path relative = metadata(second.resolve("30_relative.json"), "./libEGL_mesa.so.0");
            Path nvidia = metadata(first.resolve("10_nvidia.json"), "libEGL_nvidia.so.0");
            Files.writeString(first.resolve("01_invalid.json"), "not JSON");
            Files.writeString(first.resolve("02_future.json"), "{\"file_format_version\":\"2.0.0\",\"ICD\":{\"library_path\":\"libEGL_mesa.so.0\"}}");
            Files.writeString(first.resolve("03_oversized.json"), " ".repeat(65537));
            expect(MesaVendors.discover(null, Map.of(), List.of(root.resolve("absent"), first, second)), mesa, custom, relative);
            expect(MesaVendors.discover(null, Map.of("__EGL_VENDOR_LIBRARY_DIRS", second.toString()), List.of(first)), custom, relative);
            expect(MesaVendors.discover(null, Map.of("__EGL_VENDOR_LIBRARY_FILENAMES", custom + ":" + nvidia + ":" + mesa + ":" + custom,
                    "__EGL_VENDOR_LIBRARY_DIRS", first.toString()), List.of(first)), custom, mesa);
            expect(MesaVendors.discover(null, Map.of("__EGL_VENDOR_LIBRARY_FILENAMES", ""), List.of(first)));
            expect(MesaVendors.discover(null, Map.of("__EGL_VENDOR_LIBRARY_DIRS", ""), List.of(first)));
            expect(MesaVendors.discover(null, Map.of("__EGL_VENDOR_LIBRARY_FILENAMES", nvidia.toString()), List.of(first)));
            expect(MesaVendors.discover(root.resolve("missing.json").toString(), Map.of(), List.of(first)));
            // Explicit overrides are probed as requested, without silently replacing them.
            expect(MesaVendors.discover(nvidia.toString(), Map.of("__EGL_VENDOR_LIBRARY_FILENAMES", custom.toString()), List.of(second)), nvidia);
            for (int i = 60; i < 66; i++) metadata(first.resolve(i + "_mesa.json"), "libEGL_mesa.so.0");
            if (MesaVendors.discover(null, Map.of(), List.of(first)).size() != 4) throw new AssertionError("Unbounded driver probe candidates");
            System.out.println("AFV_MESA_VENDOR_PASS: custom prefixes, ordered filenames/directories, explicit/empty overrides, malformed/non-Mesa metadata, deduplication, bounded candidates");
        } finally {
            try (var files = Files.walk(root)) {
                for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static Path metadata(Path path, String library) throws Exception {
        Files.writeString(path, "{\"file_format_version\":\"1.0.0\",\"ICD\":{\"library_path\":\"" + library + "\"}}");
        return path.toAbsolutePath().normalize();
    }
    private static void expect(List<Path> actual, Path... expected) {
        if (!actual.equals(List.of(expected))) throw new AssertionError("Mesa metadata precedence differs: " + actual + " != " + List.of(expected));
    }
}
