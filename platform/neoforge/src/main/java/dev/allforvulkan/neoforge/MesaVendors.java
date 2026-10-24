package dev.allforvulkan.neoforge;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Discover metadata only. Candidate native drivers are loaded exclusively in the probe child. */
final class MesaVendors {
    private MesaVendors() {}
    static List<Path> discover() {
        return discover(System.getProperty("afv.mesaEglVendor"), System.getenv(), List.of(
                Path.of("/etc/glvnd/egl_vendor.d"), Path.of("/usr/local/share/glvnd/egl_vendor.d"), Path.of("/usr/share/glvnd/egl_vendor.d")));
    }
    static List<Path> discover(String override, Map<String, String> environment, List<Path> defaults) {
        if (override != null) {
            try {
                Path path = Path.of(override).toAbsolutePath().normalize();
                return Files.isRegularFile(path) && Files.isReadable(path) ? List.of(path) : List.of();
            } catch (InvalidPathException ignored) { return List.of(); }
        }
        Set<Path> candidates = new LinkedHashSet<>();
        // An explicitly empty GLVND selection means no vendors, not a request to use defaults.
        if (environment.containsKey("__EGL_VENDOR_LIBRARY_FILENAMES")) {
            for (String name : environment.get("__EGL_VENDOR_LIBRARY_FILENAMES").split(":")) {
                if (name.isBlank()) continue;
                try { addMesa(candidates, Path.of(name)); } catch (InvalidPathException ignored) {}
            }
        } else {
            List<Path> directories = new ArrayList<>();
            if (environment.containsKey("__EGL_VENDOR_LIBRARY_DIRS")) {
                for (String directory : environment.get("__EGL_VENDOR_LIBRARY_DIRS").split(":")) {
                    if (directory.isBlank()) continue;
                    try { directories.add(Path.of(directory)); } catch (InvalidPathException ignored) {}
                }
            } else directories.addAll(defaults);
            for (Path directory : directories) {
                try (var files = Files.list(directory)) {
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString())).limit(64)
                            .forEach(path -> addMesa(candidates, path));
                } catch (IOException | SecurityException ignored) { /* Missing/inaccessible prefixes do not rule out later ones. */ }
            }
        }
        return candidates.stream().limit(4).toList();
    }
    private static void addMesa(Set<Path> candidates, Path path) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path) || Files.size(path) > 65536) return;
            var root = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (!root.get("file_format_version").getAsString().matches("1\\.0\\.\\d+")) return;
            var library = root.getAsJsonObject("ICD").get("library_path");
            if (!library.isJsonPrimitive() || !library.getAsJsonPrimitive().isString()) return;
            String name = Path.of(library.getAsString()).getFileName().toString().toLowerCase(Locale.ROOT);
            if (name.matches("libegl_mesa\\.so(?:\\..*)?")) candidates.add(path.toAbsolutePath().normalize());
        } catch (IOException | RuntimeException ignored) { /* Invalid/non-Mesa metadata never reaches the native loader. */ }
    }
}
