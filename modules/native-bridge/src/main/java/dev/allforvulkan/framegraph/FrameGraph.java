package dev.allforvulkan.framegraph;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Pass graph for the direct backend. Scheduling is explicit: layouts, barriers,
 * render-pass groups and recording waves. It does not replay GL calls. */
public final class FrameGraph {
    public enum Kind { GRAPHICS, COMPUTE, TRANSFER }
    public enum Load { CLEAR, LOAD, DISCARD }

    /** How a pass uses one image or buffer. Framebuffer writes of the same use
     * stay in one render group; every other read/write change becomes a barrier. */
    public enum Use {
        UNDEFINED(false, false),
        COLOR(true, true),
        DEPTH(true, true),
        SAMPLE(false, false),
        STORAGE_READ(false, false),
        STORAGE_WRITE(true, false),
        TRANSFER_SRC(false, false),
        TRANSFER_DST(true, false);

        public final boolean writes;
        public final boolean framebuffer;
        Use(boolean writes, boolean framebuffer) {
            this.writes = writes;
            this.framebuffer = framebuffer;
        }
    }

    public static final class Attachment {
        public final String image;
        public final Load load;
        public Attachment(String image, Load load) {
            this.image = image;
            this.load = load;
        }
        public boolean equals(Object other) {
            return other instanceof Attachment attachment && image.equals(attachment.image) && load == attachment.load;
        }
        public int hashCode() { return image.hashCode() * 31 + load.hashCode(); }
    }

    public static final class Access {
        public final String resource;
        public final Use use;
        public Access(String resource, Use use) {
            this.resource = resource;
            this.use = use;
        }
    }

    public static final class Barrier {
        public final int beforePass;
        public final String resource;
        public final Use from;
        public final Use to;
        public Barrier(int beforePass, String resource, Use from, Use to) {
            this.beforePass = beforePass;
            this.resource = resource;
            this.from = from;
            this.to = to;
        }
        public String toString() {
            return "before " + beforePass + " " + resource + " " + from + " -> " + to;
        }
    }

    public static final class Pass {
        final String name;
        final Kind kind;
        final List<Attachment> color = new ArrayList<>();
        final List<Access> accesses = new ArrayList<>();
        String depth;
        boolean allowParallel = true;
        Pass(String name, Kind kind) {
            if (name == null || name.isBlank()) throw new IllegalArgumentException("Pass needs a name");
            this.name = name;
            this.kind = kind;
        }
        public Pass color(String image, Load load) {
            color.add(new Attachment(image, load));
            accesses.add(new Access(image, Use.COLOR));
            return this;
        }
        public Pass depth(String image, Load load) {
            if (depth != null) throw new IllegalStateException(name + " already has depth");
            depth = image;
            accesses.add(new Access(image, load == Load.LOAD ? Use.DEPTH : Use.DEPTH));
            return this;
        }
        public Pass read(String resource, Use use) {
            if (use.writes) throw new IllegalArgumentException(use + " is not a read");
            accesses.add(new Access(resource, use));
            return this;
        }
        public Pass write(String resource, Use use) {
            if (!use.writes || use.framebuffer) throw new IllegalArgumentException(use + " is not an external write");
            accesses.add(new Access(resource, use));
            return this;
        }
        /** Keeps API order inside a recording wave. Independent passes may still share the wave. */
        public Pass ordered() { allowParallel = false; return this; }
        public String name() { return name; }
        public Kind kind() { return kind; }
        public List<Attachment> color() { return List.copyOf(color); }
        public boolean isOrdered() { return !allowParallel; }
    }

    public static final class Compiled {
        public final List<Pass> passes;
        public final List<Barrier> barriers;
        public final List<List<Integer>> groups;
        public final List<List<Integer>> waves;
        Compiled(List<Pass> passes, List<Barrier> barriers, List<List<Integer>> groups, List<List<Integer>> waves) {
            this.passes = passes;
            this.barriers = barriers;
            this.groups = groups;
            this.waves = waves;
        }
        public int barrierCount() { return barriers.size(); }
        public List<Barrier> barriersBefore(int pass) {
            var found = new ArrayList<Barrier>();
            for (var barrier : barriers) if (barrier.beforePass == pass) found.add(barrier);
            return found;
        }
    }

    private final List<Pass> passes = new ArrayList<>();

    public Pass pass(String name, Kind kind) {
        var pass = new Pass(name, kind);
        passes.add(pass);
        return pass;
    }

    public Compiled compile() {
        if (passes.isEmpty()) throw new IllegalStateException("Frame graph has no passes");
        var barriers = new ArrayList<Barrier>();
        var state = new LinkedHashMap<String, Use>();
        var groups = new ArrayList<List<Integer>>();
        List<Integer> group = null;
        Set<String> groupWrites = null;
        List<Attachment> groupColor = null;
        String groupDepth = null;
        for (int index = 0; index < passes.size(); index++) {
            Pass pass = passes.get(index);
            var before = new ArrayList<Barrier>();
            var seen = new LinkedHashSet<String>();
            for (var access : pass.accesses) {
                if (!seen.add(access.resource))
                    throw new IllegalArgumentException(pass.name + " uses " + access.resource + " more than once");
                Use previous = state.getOrDefault(access.resource, Use.UNDEFINED);
                if (needsBarrier(previous, access.use))
                    before.add(new Barrier(index, access.resource, previous, access.use));
            }
            final Set<String> writes = groupWrites;
            boolean merge = group != null && pass.kind == Kind.GRAPHICS && passes.get(group.get(0)).kind == Kind.GRAPHICS
                    && before.isEmpty() && sameTargets(pass, groupColor, groupDepth)
                    && pass.color.stream().noneMatch(attachment -> attachment.load == Load.CLEAR)
                    && pass.accesses.stream().noneMatch(access -> writes.contains(access.resource) && !access.use.framebuffer);
            if (!merge) {
                group = new ArrayList<>();
                groups.add(group);
                groupWrites = new LinkedHashSet<>();
                groupColor = pass.color;
                groupDepth = pass.depth;
                barriers.addAll(before);
            }
            group.add(index);
            for (var access : pass.accesses) {
                state.put(access.resource, access.use);
                if (access.use.writes) groupWrites.add(access.resource);
            }
        }
        return new Compiled(List.copyOf(passes), List.copyOf(barriers), List.copyOf(groups), waves());
    }

    private List<List<Integer>> waves() {
        int count = passes.size();
        var incoming = new int[count];
        var edges = new ArrayList<List<Integer>>();
        for (int i = 0; i < count; i++) edges.add(new ArrayList<>());
        for (int earlier = 0; earlier < count; earlier++) for (int later = earlier + 1; later < count; later++) {
            if (depends(passes.get(earlier), passes.get(later))) {
                edges.get(earlier).add(later);
                incoming[later]++;
            }
        }
        var waves = new ArrayList<List<Integer>>();
        var ready = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) if (incoming[i] == 0) ready.add(i);
        int seen = 0;
        while (!ready.isEmpty()) {
            var wave = List.copyOf(ready);
            waves.add(wave);
            var next = new ArrayList<Integer>();
            for (int pass : wave) {
                seen++;
                for (int later : edges.get(pass)) if (--incoming[later] == 0) next.add(later);
            }
            ready = next;
        }
        if (seen != count) throw new IllegalStateException("Frame graph dependency cycle");
        return List.copyOf(waves);
    }

    private static boolean sameTargets(Pass pass, List<Attachment> color, String depth) {
        if (pass.color.size() != color.size() || !java.util.Objects.equals(pass.depth, depth)) return false;
        for (int i = 0; i < color.size(); i++) if (!pass.color.get(i).image.equals(color.get(i).image)) return false;
        return true;
    }

    private static boolean depends(Pass earlier, Pass later) {
        var produced = new LinkedHashMap<String, Use>();
        for (var access : earlier.accesses) produced.put(access.resource, access.use);
        for (var access : later.accesses) {
            Use previous = produced.get(access.resource);
            if (previous != null && orders(previous, access.use)) return true;
        }
        return false;
    }

    static boolean needsBarrier(Use from, Use to) {
        if (from == Use.UNDEFINED) return to != Use.UNDEFINED;
        if (from == to && from.framebuffer) return false;
        if (!from.writes && !to.writes && from == to) return false;
        return from != to || from.writes || to.writes;
    }

    static boolean orders(Use from, Use to) {
        if (from == Use.UNDEFINED || to == Use.UNDEFINED) return false;
        return from.writes || to.writes;
    }

    public static String describe(Compiled compiled) {
        return String.format(Locale.ROOT, "passes=%d, barriers=%d, groups=%d, waves=%d",
                compiled.passes.size(), compiled.barriers.size(), compiled.groups.size(), compiled.waves.size());
    }

    public Map<String, Use> finalUses() {
        var uses = new LinkedHashMap<String, Use>();
        for (var pass : passes) for (var access : pass.accesses) uses.put(access.resource, access.use);
        return uses;
    }
}
