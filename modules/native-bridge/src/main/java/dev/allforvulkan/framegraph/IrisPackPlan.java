package dev.allforvulkan.framegraph;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Reads an Iris shader pack into shadow, gbuffer, and composite passes.
 * Unsupported programs stay listed so the caller can keep them on Zink. */
public final class IrisPackPlan {
    public enum Stage { SHADOW, GBUFFER, COMPOSITE }

    public static final class Program {
        public final String name;
        public final Stage stage;
        public final boolean supported;
        public final String reason;
        public final String vertex;
        public final String fragment;
        Program(String name, Stage stage, boolean supported, String reason, String vertex, String fragment) {
            this.name = name;
            this.stage = stage;
            this.supported = supported;
            this.reason = reason;
            this.vertex = vertex;
            this.fragment = fragment;
        }
    }

    private static final List<String> GBUFFER_ORDER = List.of(
            "gbuffers_basic", "gbuffers_line", "gbuffers_textured", "gbuffers_textured_lit",
            "gbuffers_skybasic", "gbuffers_skytextured", "gbuffers_clouds", "gbuffers_terrain",
            "gbuffers_terrain_solid", "gbuffers_terrain_cutout", "gbuffers_damagedblock", "gbuffers_block",
            "gbuffers_beaconbeam", "gbuffers_item", "gbuffers_entities", "gbuffers_entities_glowing",
            "gbuffers_armor_glint", "gbuffers_spidereyes", "gbuffers_hand", "gbuffers_weather",
            "gbuffers_water", "gbuffers_hand_water");

    private final List<Program> programs;
    private final boolean dimensionFolders;

    private IrisPackPlan(List<Program> programs, boolean dimensionFolders) {
        this.programs = programs;
        this.dimensionFolders = dimensionFolders;
    }

    public static IrisPackPlan empty() { return new IrisPackPlan(List.of(), false); }

    public List<Program> programs() { return programs; }

    public List<Program> supported() {
        return programs.stream().filter(program -> program.supported).toList();
    }

    public String supportedSummary() {
        return String.join(",", supported().stream().map(program -> program.name).toList());
    }

    public String blockedSummary() {
        var parts = new ArrayList<String>();
        for (var program : programs) if (!program.supported) parts.add(program.name + " (" + program.reason + ")");
        if (dimensionFolders) parts.add("dimension folders (not separate passes)");
        return String.join("; ", parts);
    }

    /** Shadow, then gbuffers of one target, then composites that sample the gbuffer. */
    public FrameGraph toGraph() {
        var graph = new FrameGraph();
        boolean gbufferOpen = false;
        String read = "colortex0";
        String write = "colortex1";
        for (var program : supported()) {
            switch (program.stage) {
                case SHADOW -> {
                    if (program.name.startsWith("shadowcomp"))
                        graph.pass(program.name, FrameGraph.Kind.GRAPHICS)
                                .read("shadowcolor0", FrameGraph.Use.SAMPLE).color("shadowcolor1", FrameGraph.Load.DISCARD);
                    else graph.pass(program.name, FrameGraph.Kind.GRAPHICS)
                            .depth("shadowtex0", FrameGraph.Load.CLEAR).color("shadowcolor0", FrameGraph.Load.CLEAR);
                }
                case GBUFFER -> {
                    graph.pass(program.name, FrameGraph.Kind.GRAPHICS)
                            .color("colortex0", gbufferOpen ? FrameGraph.Load.LOAD : FrameGraph.Load.CLEAR).ordered();
                    gbufferOpen = true;
                }
                case COMPOSITE -> {
                    graph.pass(program.name, FrameGraph.Kind.GRAPHICS)
                            .read(read, FrameGraph.Use.SAMPLE).color(write, FrameGraph.Load.DISCARD);
                    String swap = read;
                    read = write;
                    write = swap;
                }
            }
        }
        return graph;
    }

    public static IrisPackPlan fromGameDirectory(Path game) {
        try {
            Path propertiesFile = game.resolve("config").resolve("iris.properties");
            if (!Files.isRegularFile(propertiesFile)) return empty();
            var properties = new Properties();
            try (Reader reader = Files.newBufferedReader(propertiesFile)) { properties.load(reader); }
            if ("false".equalsIgnoreCase(properties.getProperty("enableShaders", "true").trim())) return empty();
            String pack = properties.getProperty("shaderPack", "").trim();
            if (pack.isEmpty()) return empty();
            Path root = game.resolve("shaderpacks").normalize();
            Path file = root.resolve(pack).normalize();
            if (!file.startsWith(root) || !Files.exists(file)) return empty();
            if (Files.isDirectory(file)) return read(file);
            return pack.toLowerCase(Locale.ROOT).endsWith(".zip") ? readZip(file) : empty();
        } catch (IOException | RuntimeException failure) {
            return empty();
        }
    }

    public static IrisPackPlan read(Path pack) throws IOException {
        Path shaders = Files.isDirectory(pack.resolve("shaders")) ? pack.resolve("shaders") : pack;
        var sources = new LinkedHashMap<String, String>();
        boolean dimensions = false;
        if (!Files.isDirectory(shaders)) return empty();
        try (var paths = Files.list(shaders)) {
            for (Path path : paths.toList()) {
                if (Files.isDirectory(path)) {
                    dimensions = true;
                    continue;
                }
                String name = path.getFileName().toString();
                if (shaderName(name) == null) continue;
                sources.put(name, Files.readString(path));
            }
        }
        return fromSources(sources, dimensions);
    }

    public static IrisPackPlan readZip(Path zip) throws IOException {
        var sources = new LinkedHashMap<String, String>();
        boolean dimensions = false;
        try (var archive = new ZipFile(zip.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName().replace('\\', '/');
                if (!name.startsWith("shaders/") || name.contains("..")) continue;
                String relative = name.substring("shaders/".length());
                if (relative.isEmpty()) continue;
                if (relative.indexOf('/') >= 0) {
                    dimensions = true;
                    continue;
                }
                if (shaderName(relative) == null) continue;
                try (InputStream input = archive.getInputStream(entry)) {
                    sources.put(relative, new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        return fromSources(sources, dimensions);
    }

    public static IrisPackPlan fromSources(Map<String, String> sources, boolean dimensionFolders) {
        var grouped = new LinkedHashMap<String, Map<String, String>>();
        for (var entry : sources.entrySet()) {
            String base = shaderName(entry.getKey());
            if (base == null) continue;
            grouped.computeIfAbsent(base, key -> new LinkedHashMap<>())
                    .put(extension(entry.getKey()), entry.getValue());
        }
        var names = new ArrayList<>(grouped.keySet());
        names.sort(Comparator.comparingInt(IrisPackPlan::rank).thenComparing(name -> name));
        var programs = new ArrayList<Program>();
        for (String name : names) {
            Stage stage = stage(name);
            if (stage == null) continue;
            programs.add(classify(name, stage, grouped.get(name)));
        }
        return new IrisPackPlan(List.copyOf(programs), dimensionFolders);
    }

    private static Program classify(String name, Stage stage, Map<String, String> files) {
        if (files.containsKey("csh")) return new Program(name, stage, false, "compute", "", "");
        if (files.containsKey("gsh") || files.containsKey("tcs") || files.containsKey("tes"))
            return new Program(name, stage, false, "tessellation or geometry", "", "");
        String vertex = files.getOrDefault("vsh", "");
        String fragment = files.getOrDefault("fsh", "");
        if (fragment.isEmpty()) return new Program(name, stage, false, "missing fragment shader", vertex, "");
        if (stage != Stage.COMPOSITE && vertex.isEmpty())
            return new Program(name, stage, false, "missing vertex shader", "", fragment);
        String blocked = unsupported(vertex + "\n" + fragment);
        if (blocked != null) return new Program(name, stage, false, blocked, vertex, fragment);
        return new Program(name, stage, true, "", vertex, fragment);
    }

    static String unsupported(String source) {
        String text = source.toLowerCase(Locale.ROOT);
        if (text.contains("imageload") || text.contains("imagestore") || text.contains("imageatomic")) return "image load/store";
        if (text.contains("image2d") || text.contains("uimage") || text.contains("iimage")) return "storage image";
        if (text.contains("local_size")) return "compute";
        if (text.contains("atomicadd") || text.contains("atomicexchange") || text.contains("atomiccompswap")) return "atomics";
        if (text.contains("std430") || text.contains("std140") && text.contains("buffer ")) return "storage buffer";
        return null;
    }

    private static int rank(String name) {
        Stage stage = stage(name);
        if (stage == Stage.SHADOW && !name.startsWith("shadowcomp")) return 0;
        if (stage == Stage.SHADOW) return 1;
        if (stage == Stage.GBUFFER) {
            int index = GBUFFER_ORDER.indexOf(name);
            return 100 + (index < 0 ? 50 : index);
        }
        if (name.startsWith("deferred")) return 200;
        if (name.startsWith("composite")) return 300;
        return 400;
    }

    private static Stage stage(String name) {
        if (name.equals("shadow") || name.startsWith("shadow_") || name.startsWith("shadowcomp")) return Stage.SHADOW;
        if (name.startsWith("gbuffers_")) return Stage.GBUFFER;
        if (name.startsWith("prepare") || name.equals("begin")) return Stage.COMPOSITE;
        if (name.startsWith("composite") || name.startsWith("deferred") || name.equals("final")) return Stage.COMPOSITE;
        return null;
    }

    private static String shaderName(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot <= 0) return null;
        String extension = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!extension.equals("vsh") && !extension.equals("fsh") && !extension.equals("gsh")
                && !extension.equals("csh") && !extension.equals("tcs") && !extension.equals("tes")) return null;
        return filename.substring(0, dot);
    }

    private static String extension(String filename) {
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
}
