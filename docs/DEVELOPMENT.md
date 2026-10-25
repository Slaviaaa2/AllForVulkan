# AFV development (NeoForge 1.21.1)

## Requirements and build

- Full JDK **21**, including `javac` (a Java 21 JRE alone is insufficient).
- `JAVA_HOME` must point at that JDK; Gradle wrapper 8.12 is included.
- Minecraft 1.21.1, NeoForge **21.1.228**, LWJGL **3.3.3** are pinned.

```sh
./gradlew build
python3 scripts/verify_artifact.py
./gradlew runClient
```

`runClient` prepares **only this repository's `run/config/fml.toml`** with
`earlyWindowControl = false`. The NeoForge OpenGL splash window cannot be used
as a Vulkan surface. For a separately installed client, set the same value in
that instance's `config/fml.toml` before launch. If the splash is already enabled, startup selects OpenGL. Saving Auto or Vulkan
in the AFV config screen disables the early GL splash for the next launch.

Do not install the original VulkanMod or another VulkanMod port alongside AFV.
With any third-party mod installed, Auto and Vulkan preserve the existing GL
client through the Linux Zink route below, after a real driver preflight. If it
is unavailable, they select OpenGL. This avoids replacing unfamiliar mods' GL
and terrain hooks with AFV's native renderer. AFV alone still uses the native
renderer; the vanilla Zink force flag remains available for validation.
No Mesa native distribution is bundled.

## Zink GL client with AFV Vulkan presentation

Choose **Auto** or **Vulkan** in AFV's mod-list configuration and restart.
With third-party mods installed, the existing GL frontend runs on a surfaceless
Zink context after preflight succeeds. AFV creates the visible NO_API GLFW window, its own Vulkan device and
swapchain. It matches Zink's GPU UUID, imports dedicated color images using
opaque FDs, and transfers ownership with two external binary semaphores per
frame slot. GL blits the final default framebuffer to the shared image; Vulkan
blits it, including the GL/WSI vertical coordinate conversion, into the acquired
swapchain image. There is no CPU frame-image transport. Present completion
semaphores belong to individual swapchain images, not frame slots. Resize drains
both queues and recreates shared images/WSI; the GL context and game resources
survive. VSync changes follow the same recreation path.

Transfers are recorded once per shared-frame-slot/swapchain-image pair and
resubmitted after the owning slot fence completes. The normal frame path does
not reset/begin/end or record Vulkan transfer command buffers. These commands
retain the same image barriers, semaphore waits/signals and vertical blit.
Recreation drains the device, frees all cached commands before referenced
resources, then records a new generation. The opt-in final-output diagnostic
records a second variant per pair and alternates it with the normal variant,
so one smoke run also exercises normal command reuse. No simultaneous-use or
one-time-submit flags are used on reusable commands.

Presentation-only shared images use a copy-specific external layout contract:
Vulkan releases in `TRANSFER_DST_OPTIMAL`, GL waits with `LAYOUT_TRANSFER_DST_EXT`
and signals with `LAYOUT_TRANSFER_SRC_EXT`, and Vulkan acquires directly in
`TRANSFER_SRC_OPTIMAL`. The acquire still transfers queue ownership; it no longer
changes the image from GENERAL to TRANSFER_SRC. Scene sampling/rendering/depth
images retain their GENERAL contract. `glSignalSemaphoreEXT` already flushes by
specification, so shared-image submission does not issue a second `glFlush`.
The isolated preflight tests the production SharedImage in both contracts, with
changing exact pixels at two sizes and the same external semaphores. The copy
layout pass is required before enabling the GL client in the game. Diagnostics
alternate whole slot batches so both slots exercise normal and readback commands.

The EGL drawable matches the real window size and includes depth/stencil.
`Minecraft.runTick` prepares its size before drawing, retaining the same GL
context, live objects and viewport/scissor state. It is not resized after a
frame has already been drawn. Presentation reads the actual default framebuffer,
including Iris's final window pass and last direct GL output, rather than
assuming the main color texture is the final screen. Minimized frames skip WSI;
a completed frame can be scaled if the window size changes before presentation.

The preflight and presenter share the same surface format/extent policy. They
search for an available 8-bit RGBA/BGRA destination with blit support and a
matching exportable shared source; an unusable first format does not rule out
other valid pairs. Iconified/zero-size surfaces suspend presentation without
destroying the current images or creating zero-sized swapchains. Restoration
rechecks capabilities and recreates the swapchain. Image acquisition uses a
finite timeout, so a surface that stops making progress cannot block the render
thread indefinitely. An out-of-date creation/acquisition is retried next frame.

The GL client also handles `VK_ERROR_SURFACE_LOST_KHR` from acquisition,
presentation, swapchain creation and surface-property queries. It drains both
APIs, destroys the old swapchain/image/command generation, replaces the real
surface on the same GLFW window, checks the existing present queue, renews WSI
acquisition semaphores, and recreates the swapchain. GL context, Iris resources,
world and Vulkan device remain alive. Three replacement attempts without a
successful presentation fail explicitly; presentation failures restore the
next-launch OpenGL recovery marker even after startup was stable. A device loss
or a replacement surface requiring a different device/queue is not hot-switched.
Surface queries check VkResult and retry changing enumerations instead of using
undefined capability data or null format/present-mode lists.

For a short performance checkpoint, use the same private minimal Iris/Sodium
profile, shader/options, scene, resolution and distances and add
`--benchmark-ticks 400 --warmup-ticks 600 --uncapped` to its smoke launch. This
waits 120 stable world ticks plus 600 warmup ticks before a roughly 20-second
sample. Do not enable `--final-output-probe` for FPS comparisons; its readback and
marker work is diagnostic. Record mean FPS and p95 frame time with the actual
conditions and nested X display label. The launcher rejects a missing new result
and archives benchmark JSON plus launch/artifact/shader metadata under
`.allforvulkan/benchmarks/` in the private client. This provides a single-run
section indicator, not the user's normal DTFuel FPS.

Surface queue selection is shared by the isolated preflight and the game. A
combined graphics/present family is preferred, with separate present queues
allowed. Present support is always queried on the actual surface; a compute
queue is never guessed to support presentation. Zero-count families are ignored.
Native rendering additionally requires compute support, prefers dedicated
compute/transfer families, and accepts the transfer support implicit in graphics
or compute capability. An unsuitable native GPU yields an unsuitable result so
device enumeration can continue. The GL client needs only graphics/present queues
on AFV's device, retaining the same Zink GPU UUID constraint.

Steady-state presentation uses a named framebuffer blit and a narrow
`GlBlitScope`. The draw framebuffer binding is never changed. The read binding
is changed temporarily only if needed to query the default read buffer (OpenGL
has no named query for that state), and is restored before the actual copy.
The default read selector is changed only if necessary. Scissor index 0 and
framebuffer sRGB are temporarily disabled when enabled; viewport, masks,
blending, shaders and texture bindings are untouched. The default front/back
choice is cached for the context's fixed EGL config, and default completeness
is checked at drawable creation/replacement, rather than every frame.
The broader state scope is retained for shared-image
creation, EGL drawable replacement and rendering diagnostics; it preserves all
indexed color masks/blend enables and fractional viewport/scissor state.
Restoring index zero with legacy APIs would overwrite other indices. The
isolated preflight verifies exact color-blit pixels and normal/exception cleanup
under adversarial indexed state, then checks MRT output, nested scopes and resize.

This route retains Iris/Sodium terrain, shadows, MRTs, buffers and frame hooks
in the real GL implementation. AFV's native terrain renderer and its GL-wrapper
mixins are disabled for this route; the native renderer remains the other mode.
It is distinct from both the `--zink` GL/EGL comparison and the native renderer's
`afv.zinkInterop` post-processing diagnostic.

Linux and an installed Mesa EGL/Zink driver are required. `zinkProbeJar` embeds a
standalone preflight helper, including its own LWJGL dependencies, in the mod.
Before changing the game's native environment or applying client mixins, a child
JVM tests real GL 4.6, compute pixels, matching Vulkan GPU UUIDs, opaque FD images
and bidirectional semaphore synchronization. It also creates an unfocused hidden
NO_API GLFW window, checks the actual surface's graphics/present queues,
transfer/blit formats and same-GPU shared-format import, and creates a real
swapchain on that surface. GLFW is bundled only inside the isolated probe JAR;
the game continues to use its own GLFW. Failure, driver-process death or a
45-second total probe budget selects OpenGL. Logs are under `.allforvulkan/preflight/` in
the instance. This isolates preflight driver failures; runtime driver failures
still use the existing next-launch recovery marker. A successful preflight does
not prove every shader pack or WSI configuration works.

For a vanilla client, `-Dafv.zinkClient=true` forces this route for testing.
`-Dafv.mesaEglVendor=/path/to/vendor.json` selects a particular installed Mesa
vendor. Otherwise metadata discovery honors GLVND's explicit filenames, then
explicit directories, then standard EGL vendor directories; an explicitly empty
selection remains empty. Up to four Mesa candidates are tested in isolated
children within the shared budget, so a broken installation does not hide a
later working candidate. Candidate logs are `zink-probe-N.log`; `zink-probe.log`
contains the last attempt. JSON metadata is inspected without loading drivers in
the game. The Zink environment changes affect only the launched game process.
The private-client launcher accepts `--mesa-egl-vendor` for a manual override and
`--expect-backend Vulkan` or `OpenGL` to assert the actual startup decision.

For a verified private DTFuel copy (see preparation commands later in this file):

```sh
python3 scripts/launch_modpack_client.py \
  --instance /path/to/private-client --install /path/to/CurseForge/Install \
  --java-home /path/to/jdk21 --afv-jar build/libs/AllForVulkan-1.21.1-neoforge-0.1.0-alpha.1.jar \
  --smoke --local-xim --zink-client --backend AUTO --require-iris \
  --shader-pack /path/to/ComplementaryReimagined_r5.9.3.zip \
  --scene --final-output-probe --inspect-ticks 600
```

With `--require-iris`, this exercises the same Vulkan selection as the setting,
without the vanilla force flag. `--scene` builds stone, glass, leaves, water,
an emissive block and entities only in the newly created smoke world. The
command above checks correctness without collecting a performance benchmark.
Optional measurement flags remain available for explicitly matched comparisons;
`--uncapped` changes VSync/frame cap only in the private client's options.
The smoke test requires 120 real AFV Vulkan presentations, an actual active Iris
pipeline, nonblack bounded GL color samples, resize, reload, disconnect and exit.
Use `--debug-gl` only for bounded synchronous GL error diagnosis.
`--final-output-probe` draws a test-only 8x8 marker directly into the last default
framebuffer on alternating frames and verifies its pixels in AFV's actual Vulkan shared image after
the slot fence. No main texture is changed. `--backend AUTO --zink-client
--require-iris` checks automatic selection without the vanilla force flag.

Add `--iris-cycle` to the private-client command to disable shaders through
Iris's public config API, resize once more while shaders are off, re-enable the
same pack, and call Iris's shader-only reload before the resource-pack reload.
Each mode must render at least 60 frames and remain active for 60 client ticks.
The test checks a real vanilla pipeline while disabled, distinct real Iris
pipelines after enabling/reloading, unchanged selected pack and nonblack color
samples. The production launcher requires `AFV_IRIS_CYCLE_PASS`; it cannot pass
merely because the client exits cleanly. This explicit diagnostic requires
`--smoke --require-iris` and changes only the verified private instance.

With a real window manager on the dedicated display, add `--window-cycle`
to check actual iconification/restoration and two VSync changes. The client must
remain responsive while iconified with no AFV Vulkan presentations, then render
at least 60 frames in each resumed mode, retaining the same EGL context and active
Iris pipeline. The launcher requires `AFV_WINDOW_CYCLE_PASS`. It requires
`--smoke --zink-client`; a missing window manager is an explicit test failure,
not a simulated minimize. No screenshot is needed for these state assertions.

Use `--dimension-cycle` with `--smoke --require-iris --zink-client` to travel
Overworld → Nether → End → Nether → Overworld after the resource reload. It uses
real server dimension transfers and client chunks, not a fabricated render-level
key. Small lit platforms are created only in the freshly named AFV smoke world;
existing saves are rejected. The return route avoids End credits. Each stage
requires 120 client ticks, 120 actual Vulkan frames, the expected Iris dimension,
an active pack with no stored shader error, an independent pipeline per dimension,
nonblack color samples and the same EGL context. The selected shader pack remains
unchanged. The launcher requires `AFV_DIMENSION_CYCLE_PASS`. No FPS measurement
is performed unless benchmarking is separately requested.

For dimension screenshots, add `--dimension-inspect-ticks 200`. The client emits
`AFV_DIMENSION_INSPECT` only after real world/pipeline/frame checks and then holds
the current world for that many client ticks. Synchronize a scoped Minecraft
viewport capture to that marker, not to the server transfer or loading screen.
The same assertions run again before leaving the inspected dimension.

A second independent profile can select only exact source JAR filenames:

```sh
python3 scripts/prepare_modpack_client.py \
  --source /path/to/DTFuel --destination /path/to/iris-sodium-only \
  --mod-file iris-neoforge-1.8.14-beta.1+mc1.21.1.jar \
  --mod-file sodium-neoforge-0.8.13+mc1.21.1.jar
```

Selected profiles omit pack overrides and include no implicit dependencies;
select each required dependency explicitly. Their manifest records the full
source's JAR count, selected JARs and hashes, and `selected-mods` profile type.
Full-profile creation retains the previous copy behavior. Both modes refuse an
existing destination or a destination inside the original pack. Before launch,
all recorded MOD hashes are verified and extra JARs are rejected, apart from
the launcher's own AFV artifact. Run `python3 -m unittest discover -s tests
-p test_modpack_profiles.py` for independent-copy/profile-integrity checks.

For a matched Zink/EGL presentation comparison, replace `--zink-client` with
`--zink --x11-no-modifiers` when that fallback is required by the nested X server.
Keep scene, shader options, warmup, resolution and game settings identical. This
compares presentation routes with the same Zink frontend; it does not establish
an FPS gain over the GPU vendor's native OpenGL driver.

## Current source boundary

- `modules/core-api`: standalone Java module, loader services contract only.
  No Minecraft, NeoForge, Fabric, or LWJGL dependency.
- `platform/neoforge`: entrypoint, loader metadata service, early client hook.
  Initialization uses the loading mod list and runs GLFW on the render thread,
  before window construction, rather than on NeoForge's parallel mod worker.
- `src/main/java/net/vulkanmod`: inherited renderer and Minecraft 1.21.1
  integration. This is **not yet** a Minecraft-independent Vulkan core.
- `src/main/resources`: preserved `vulkanmod` resource namespace plus AFV
  metadata, two mixin configurations and translated access transformers.

NeoForge's patched model tessellator and liquid renderer write directly to
VulkanMod's terrain vertex consumer. ModelData is snapshotted on the render
thread before chunk workers receive it. Model render layers and liquid extension
hooks use NeoForge's own dispatch. Directional terrain face splitting and the
upstream sub-block AO path are not used by this initial adapter; quads are
written into the general bucket. Further mod compatibility needs actual tests.

LWJGL Vulkan/shaderc/VMA libraries and Windows/Linux x64/macOS x64/arm64 native
jars are embedded for distribution. Their JPMS module descriptors are stripped
and classifier IDs separated for NeoForge JarJar. This is packaging coverage,
not a claim of runtime support on every OS or GPU. Desktop ARM Linux/Windows
natives are not bundled.

## Repeatable startup check

```sh
./gradlew -Dafv.smokeTest=true runClient
```

This opt-in mode disables accessibility onboarding **in the local development
instance only**, waits for the real title screen with no loading overlay for
60 client ticks, emits `AFV_SMOKE_PASS`, then shuts down normally. Check for that
marker as well as the process exit code. It does not verify pixel correctness.
A timeout emits `AFV_SMOKE_FAIL`. Normal launches never auto-close.

LWJGL is in NeoForge's bootstrap layer and cannot be replaced by the inherited
Fabric-style LWJGL mixins. AFV routes Minecraft's own GL wrappers to the Vulkan
backend; arbitrary third-party LWJGL/native GL calls are **not intercepted** by
this port. Inherited GL mixin classes are retained as reference but not registered.
The early mixin plugin sets a minimum 1 MiB LWJGL thread-local stack for modern
driver extension enumeration. Shader resources are read through the mod class
loader, and shaderc include buffers live until the native release callback.

## Next milestones

Finish M0 title/world/resize/reload checks on real GPUs, including an independent
Iris baseline. Extract renderer dependencies into common modules incrementally.
Extend M1's isolated Mesa/Zink context and external image/semaphore sharing
from the current game-frame diagnostic into resource and rendering integration. No fake
GL capabilities, readback-based product path or Iris compatibility claims.

## World lifecycle and Iris comparisons

```sh
# AFV: creates a fresh flat save, verifies actual indexed terrain submissions,
# resizes to 960x540, reloads resources, disconnects and returns to title.
./gradlew -Dafv.smokeTest=true -Dafv.smokeWorld=true runClient

# Unmodified, SHA-512-pinned Iris 1.8.12 + Sodium 0.6.13, without AFV.
# A test-only mod checks that an IrisRenderingPipeline and shaders stay active.
./gradlew runIrisBaseline

# Same test under real Mesa/Zink desktop GL, using GLFW's EGL context path.
./gradlew runIrisZinkBaseline

# Standalone M1 prototype: compute pixels and external-memory/semaphore exchange.
./gradlew :native-bridge:run
```

Iris comparisons use **only** `run-iris-baseline/` and the `baseline` source set.
`compatibility/iris-baseline.lock.json` pins both upstream JARs and SHA-512 hashes;
`prepareIrisBaseline` downloads/verifies them and copies the tiny, repository-owned
`tests/shaderpacks/AFV-Smoke` pack. Unknown JARs in that comparison directory cause
preparation to stop rather than silently altering the fixed test configuration.
The test-only mod and its mixins are not included in AFV's production JAR.
Run these GUI comparisons sequentially: they share one isolated directory.
Normal AFV clients load only `allforvulkan`; they do not load the baseline fixture.

The native bridge is embedded in the Minecraft JAR and also provides a standalone
probe application. It requires Linux x64, system Mesa/Zink and the Mesa EGL
vendor manifest. Override its path with `-PmesaEglVendor=/path/to/mesa.json` for
both Zink tasks. Environment overrides are applied only to the respective child
process. It verifies actual GL renderer strings/capabilities and Vulkan physical
GPU UUID equality before allocating an exportable, dedicated RGBA8 optimal image.
Opaque memory and semaphore FDs transfer ownership to GL on successful imports.
External queue ownership, layouts and semaphore signal/wait pairs are explicit.

The native probe tests 16x16 and 31x31 allocations, eight round trips each:
Vulkan clears green, Zink sees green, GL clears magenta, Vulkan sees magenta.
Private diagnostic readbacks assert the result; **image transport is shared GPU
memory**, not a CPU copy. The single test command buffer is fence-serialized for
verification, and teardown drains the test devices. These waits/readbacks are
not an implemented product frame path. Depth/stencil, MSAA, MRT, other formats,
other GPUs/OSes, and full-frame/pipeline integration remain to be implemented/tested.

Passing Iris under Zink alone does not mean Iris is integrated with AFV.
The opt-in diagnostic now connects this sharing boundary to AFV's own VkDevice
and frame stages. CPU texture registration is implemented; GPU-updated resource sharing, general GL routing, and Iris's
Sodium-specific terrain/vertex and rendering hooks remain to be implemented.

## In-game Zink image composition (Linux diagnostic)

```sh
./gradlew -Dafv.zinkInterop=true -Dafv.smokeTest=true -Dafv.smokeWorld=true runClient
```

The game creates a real Mesa EGL GL 4.6 context on its render thread, verifies
Zink's GPU UUID against AFV's VkPhysicalDevice, and imports dedicated external
images allocated on **AFV's existing VkDevice**. Each in-flight frame has an image,
bidirectional binary external semaphores and a release command buffer. The normal
AFV frame fence protects reuse; no extra CPU fence wait, readback, `glFinish` or
`vkDeviceWaitIdle` is used to transport frames. With smoke tests enabled, a real
GLSL shader samples a CPU-uploaded 2x2 Minecraft texture into a 32x32 diagnostic
corner on top of the scene post-processing result. The full scene now travels
Vulkan -> Zink GL -> Vulkan before PRESENT; the default GL pass is identity. Resize/shutdown drain at the existing lifecycle boundary,
then destroy GL imports before Vulkan resources and rebuild the ring on resize.
The GL context and Minecraft texture registry survive resize; they close only
after renderer shutdown, so texture names and loaded assets remain valid.

With `afv.smokeTest=true` only, a separate buffer checks **only this bounded patch**
after the normal frame fence. It emits numbers/pass markers, never image files.
`AFV_ZINK_COMPOSITION_PASS` requires at least 120 verified patches. A frame discarded
before submission is not verified. Without the smoke flag there is no readback.
Without the interop flag, no EGL context or external FD device extension is needed.
The Gradle client applies Mesa environment overrides only to its child process
when this flag is enabled; `-PmesaEglVendor=...` overrides the system manifest.

This diagnostic supports one render-thread GL context, Linux opaque FDs and an
RGBA8 image. It is not an Iris backend, general GL interception, a complete image
registry, depth sharing, or a full shader-pack integration. Iris/Sodium admission
restrictions remain until their actual rendering paths are supported.


## CPU texture registration and transfer checks

When `afv.zinkInterop=true`, Minecraft-generated texture IDs come from the real
GL namespace. `GlTextureRegistry` owns these names, while `VkGlTexture` keeps
the corresponding Vulkan images. The **original CPU input** is uploaded to each
API: this currently stores two copies of asset data on the GPU. It does not
use Vulkan readback as an upload path. TextureUtil preparation and NativeImage
uploads cover atlases, fonts, animated regions, mip levels and sampler settings;
GL state touched by internal allocation/upload/sampling is restored. Swapchain
aliases reserve names without owning the swapchain VkImage; resize deletes old
alias names and rebuilds only the external frame resources.

CPU upload conversion honors unpack row length, alignment and skipped rows/pixels,
RGB/BGRA channel order, signed bytes, and both packed 8_8_8_8 orders. Source bounds
are checked for ByteBuffer and CPU-backed PBO uploads. These PBOs are still CPU
buffers, not general GL buffer objects. Upload buffers keep their original
positions; PBO deletion clears bindings. Vulkan staging handles rowLength=0
and copies exactly through the last uploaded row.

```sh
# Also runs automatically as part of check/build; no GUI or GPU context needed.
./gradlew verifyTextureUpload
```

The in-game smoke test compares private 2x2 Vulkan and real-GL results for packed
RGBA/BGRA and REV, exercises BGRA row skips and PBO byte offsets, then samples that
texture in GLSL. Only the resulting 32x32 asset patch and a separate 32x32 scene comparison
region are read back per completed frame. The block atlas's real GL dimensions and upload counts are checked before
and after world/resize/reload transitions. Test setup waits/readbacks are separate
from product image transport.

This is a CPU-upload input bridge. GL writes to these texture objects are not yet
propagated back to Vulkan, and GPU-rendered Minecraft targets do not acquire GL
storage through this registry. General buffers/FBOs, depth, MRT and Iris/Sodium
rendering integration remain required for full compatibility.


## Vulkan scene through a real GL frame pass

```sh
# The existing smoke command now also checks the scene's identity round trip.
./gradlew -Dafv.zinkInterop=true -Dafv.smokeTest=true -Dafv.smokeWorld=true runClient

# Force a real color change in GL and compare its expected numeric result.
./gradlew -Dafv.zinkInterop=true -Dafv.zinkEffect=invert -Dafv.smokeTest=true -Dafv.smokeWorld=true runClient
```

`GlFramePass` is a loader-independent native-bridge contract. It receives the
real input GL texture, output GL FBO, extent and sRGB flag. These names belong
to the frame slot and must not be retained. The default `SceneColorPass` performs
GLSL identity/invert over the entire framebuffer. World frames now run before hand/GUI rendering (see World depth sharing below);
this does not replace Iris's world, shadow or terrain hooks.

AFV's first submission renders Minecraft, blits the swapchain color into a shared
input image, releases input/output queue ownership, and signals two GL acquire
semaphores. Mesa GL waits, samples the input and writes the shared output. The
second AFV submission waits for both GL release semaphores, reacquires both
images, blits the result to the swapchain and transitions to PRESENT. Only the
second submit signals the normal frame fence/renderFinished semaphore. There is
no per-frame CPU fence wait beyond AFV's existing frame fence and no readback in
product transport. Frame-sized images and composition commands are rebuilt on
resize; the GL context, image registry and pass persist.

The smoke test compares only the 32x32 scene region at (32,0), before and after
GL, using small host buffers after the normal fence. A separate (0,0) 32x32
corner continues the CPU-asset sampler check. No full-frame CPU capture or image
file is generated. `AFV_ZINK_SCENE_PASS` requires at least 120 completed scene
comparisons; invert requires the RGB complement (alpha unchanged). Supported
image formats are RGBA/BGRA8 UNORM or sRGB; this machine's actual swapchain path
was UNORM. The sRGB branch uses sRGB shared images, GL framebuffer conversion,
and linear-space inversion, but still needs its own surface-format runtime test.
Smoke assertions currently require a viewport of at least 64x32.

General GL buffers/FBO registries, arbitrary GPU-updated asset synchronization,
arbitrary depth/stencil render targets, MSAA, MRT and Iris/Sodium integration are still outstanding.


## Renderer settings and startup recovery

Open Mods → All For Vulkan → Config. Renderer offers Auto, Vulkan and OpenGL
(Japanese: 自動 / Vulkan / OpenGL). Save and restart Minecraft. The active backend
and selection reason are displayed. A requested Vulkan backend still selects
OpenGL if mandatory capabilities or renderer integration are unavailable.

`config/allforvulkan_backend.properties` stores `backend=AUTO|VULKAN|OPENGL`.
A per-launch `-Dafv.backend=OPENGL` override is useful for recovery and does not
rewrite that choice. Before selecting Vulkan, AFV checks the loader/device API
(>=1.2), graphics queues, logicOp, anisotropy, swapchain extension and logical
device creation. External FD extensions are additionally required for the
experimental Zink route. Native device selection applies the same mandatory
feature checks. Unsupported multi-draw uses AFV's direct draw path.

`config/allforvulkan_renderer_starting` survives an abnormal startup, including
native failures Java cannot catch. After 120 successfully presented frames it
is removed. If it remains on the next launch, AFV selects OpenGL; saving a
backend choice acknowledges recovery and permits another attempt after restart.
This does not provide a live backend switch after renderer mixins are applied,
nor guarantee protection from every driver fault after successful startup.

## World depth sharing and projection

`GlFramePass.Frame` exposes borrowed `colorTexture`, `depthTexture`, output FBO,
extent, sRGB flag and WORLD_END / FRAME_END stage. NeoForge AFTER_LEVEL is before
the hand clears depth. AFV submits the world, shares color/depth/output with GL
using three acquire/release semaphore pairs, copies GL output back, and resumes
the main pass with LOAD for the hand and GUI. The final UI submission owns the
usual frame fence and presentation semaphore. Title frames use FRAME_END.

Depth formats implemented: D16, X8_D24, D24S8, D32F, D32FS8. D24S8 was exercised
on RTX 3060; the others still need device coverage. `-Dafv.zinkEffect=depth`
samples depth in the GL pass. Test-only comparison uses a 32×32 scene region
near the lower center, with at most one quantization step for depth visualization.
The corner asset diagnostic remains a separate 32×32 region. No CPU transport
of frame images is used in the product path. MRT, MSAA and Iris hooks remain work.

JOML is in NeoForge's bootstrap layer. Projection conversion therefore occurs
at VRenderSystem/ProjMat upload boundaries, not via a Matrix4f mixin. GL matrices
remain unchanged for mods. `verifyProjection` checks near/far, GUI depth and
input preservation.

## Dedicated pack and benchmark runs

```sh
python3 scripts/prepare_modpack_client.py --source /mnt/hdd/MinecraftModpacks/DTFuel --destination "$PWD/build/compatibility/dtfuel-client"
./gradlew -Pneo_version=21.1.251 -PafvGameDir="$PWD/build/compatibility/dtfuel-client" -PafvClientHeap=6G -Dafv.smokeTest=true -Dafv.smokeWorld=true runClient
./gradlew -Dafv.benchmarkTicks=600 runIrisBaseline
./gradlew -Dafv.benchmarkTicks=600 runIrisZinkBaseline
```

The copy command requires a new destination, resolves symlinks into independent
files and verifies every mod's SHA-256. Original saves/configs/mods are not edited.
A pack's fallback OpenGL result does not satisfy the separate Vulkan integration
goal. The DTFuel copy pins the pack's own NeoForge/Iris/Sodium versions; the small
Iris baseline uses its independent lock file.

Opt-in benchmarks warm up the fixed-seed flat world, set a fixed camera and
record RenderFrameEvent.Post intervals for the requested client ticks. JSON
contains renderer, resolution, camera, render/simulation distances, vsync, frame
limit, sample duration, average FPS and median/p95/p99 intervals. These include
pacing/presentation, not GPU-only times. A single synthetic-pack run is not a
DTFuel performance claim. Verify identical hardware/ICD and settings before
comparing: Xephyr may expose llvmpipe to normal GL even while Vulkan uses the
physical GPU. Such results cannot establish a Vulkan speedup.

The current validation X session uses Xephyr on `:97`; its Xauthority file is
`build/compatibility/xsession/Xauthority`. Export DISPLAY/XAUTHORITY only for the
test subprocess. The client runs separately from the user's working desktop.
Captures must still target the identified client window or a smaller region;
virtual desktops do not authorize full-screen capture.


For a production pack run, prefer the installed launcher metadata and the built
JAR instead of the development launch. This avoids IDE-only GameTest registration
and mod assertions. The helper reads public version/library/assets metadata,
uses an offline QA identity, and never reads launcher accounts:

```sh
DISPLAY=:97 XAUTHORITY="$PWD/build/compatibility/xsession/Xauthority" \
python3 scripts/launch_modpack_client.py \
  --instance build/compatibility/dtfuel-client \
  --install /mnt/hdd/CurseForge/Install --java-home /path/to/jdk21 \
  --afv-jar build/libs/AllForVulkan-1.21.1-neoforge-0.1.0-alpha.1.jar \
  --smoke --local-xim --zink --x11-no-modifiers
```

`--local-xim` supplies the nested X session with a local XIM context, avoiding
CocoaInput's null-XIC failure when the host input-method server is absent there.
`--zink` explicitly selects the GL comparison route (AFV native Vulkan renderer
disabled), forces real Mesa EGL/Zink, and asserts the resulting GL renderer.
No GL version/capability strings are spoofed. Source pack files remain untouched.
`--shader-fixture tests/shaderpacks/AFV-Smoke --require-iris` enables the private
test pack and requires an actual IrisRenderingPipeline in the world. The original
private client's Iris config is backed up. `--benchmark-ticks 600` and
`--inspect-ticks 900` optionally extend the stable-world phase.


For nested X11 servers where Zink/EGL presents black windows,
`--x11-no-modifiers` sets Mesa's `LIBGL_KOPPER_DRI2=true` in that child only.
This is an explicit presentation compatibility choice, recorded in the run
manifest; include it in performance conditions. See [Mesa environment variables](https://docs.mesa3d.org/envvars.html#libgl-kopper-dri2).
EGL runs also resolve GL entrypoints through eglGetProcAddress rather than the
default GLX loader. A 32×32 numeric sample of Minecraft's actual color texture
rejects an entirely black target during GL smoke checks; it is not frame transport
or proof of full-image correctness.

```sh
# Add to the production command above:
--shader-pack /path/to/ComplementaryReimagined_r5.9.3.zip --require-iris \
--benchmark-ticks 300 --inspect-ticks 600
```

The adjacent `.zip.txt` is copied automatically, with input hashes stored in
`.afv-launch-manifest.json`. Iris may rewrite the private copy's comment timestamp;
compare option values separately from whole-file hashes. Never edit the original
user shader/options file. The smoke harness requests a different size even if the
client initially launches at 960×540.

## Batched presentation image dependencies

The reusable client presentation command records two image-dependency commands
instead of four. At entry, the shared-image EXTERNAL acquire and the WSI image's
UNDEFINED -> TRANSFER_DST transition share TOP_OF_PIPE -> TRANSFER. At exit, the
shared-image EXTERNAL release and the WSI image's TRANSFER_DST -> PRESENT
transition share TRANSFER -> BOTTOM_OF_PIPE. Each image retains its original
layout, access masks, queue-family indices and subresource range. Binary external
semaphores, per-slot fences and per-image present semaphores are unchanged.

`SharedImage.acquire/release` accept an optional distinct companion image barrier
for those exact execution scopes. Initial ownership and general/native callers
keep the standalone path. The final-output diagnostic uses the same batched
commands, with its existing readback operation between acquire and release.
This reduces dependency commands submitted to the driver; it does not remove
synchronization or alter Iris passes. Transfer commands are already cached, so
there is no claim of four fewer per-frame Java recording calls.

See the [Vulkan pipeline-barrier specification](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdPipelineBarrier.html)
for the common execution scope and per-image memory barriers.

## Exact-format presentation copies

Among surfaces with the same UNORM/sRGB interpretation, prefer an exact RGBA
shared/surface format match. The normal GL color blit then flips vertically into
the shared image, and Vulkan uses `vkCmdCopyImage` with identical formats and
extents. BGRA/RGBA pairs retain the existing GL copy and Vulkan conversion/flip
blit. UNORM remains preferred over sRGB; copy preference never changes encoding.
Transfer and blit format feature checks, shared allocation, external ownership,
semaphores, fences and cached command lifetimes remain in effect.

`PresentationTransfer` contains the actual GL and Vulkan operations used by the
presenter and the isolated GPU probe. The probe runs copy and blit strategies at
16x16 and 31x31, with 8 changing nonuniform RGBA images each, and compares every
returned pixel against a vertically flipped reference. Both strategy markers are
required before enabling the Zink client. This exercises the copy path even if
the current window system offers only BGRA. Only the probe reads whole private
test images back; the normal client path remains GPU-only. The final-output
marker diagnostic reads the reflected marker position for exact-format copies.

See [Vulkan image copies](https://docs.vulkan.org/refpages/latest/refpages/source/vkCmdCopyImage.html)
for copy format/extent/usage constraints. No format conversion is attempted by
the raw-copy strategy, and no performance gain is assumed without measurement.

## Running AFV against a pinned alternate Iris/Sodium pair

An existing checksum-pinned cache can provide a separate production profile;
this does not install AFV into the original baseline directory:

```sh
python3 scripts/prepare_modpack_client.py \
  --source run-iris-baseline \
  --dependency-lock compatibility/iris-baseline.lock.json \
  --neoforge 21.1.251 \
  --destination build/compatibility/iris-1.8.12-sodium-0.6.13-client
```

The preparer selects only lock-listed filenames and verifies their SHA-512 values
before creating the destination. It omits source configuration, overrides and
saves, copies independent JARs, and records the exact lock and its checksum in a
`locked-mods` profile. The optional runtime NeoForge override is explicit: the
lock's original 21.1.228 and tested installed 21.1.251 are both retained in the
manifest. It does not edit the lock, source cache or comparison environment.
The normal production launcher rechecks copied JAR SHA-256 values and includes
the lock provenance in the launch/benchmark metadata. Use its existing
`--require-iris --iris-cycle --window-cycle` checks and the supplied shader pack;
FPS samples must still use the same documented scene/settings and no final-output
readback. A different dependency pair is a separate profile, not an AFV speedup.

## Experiment: overlap Vulkan presentation with GL frame production

`-Dafv.experimentalAsyncPresent=true` (private launcher: `--async-present`) moves
`vkQueuePresentKHR` onto an AFV-owned worker. After submitting the current transfer,
the render thread can produce the next GL frame while the native presentation
call runs. At the next presentation boundary it joins the prior result before
any AFV queue submission, image acquisition, swapchain access or recreation.
Only one native request may be outstanding. The worker builds its native structs
on its own MemoryStack from captured scalar handles; no render-thread stack
pointer crosses threads. GL calls, Iris passes and GL/Vulkan image ownership
remain on the existing paths.

The default is synchronous. Compare OFF/ON using the same JAR, profile, scene and
short benchmark protocol. `asyncPresent` is recorded in launch and benchmark
metadata. `AFV_PRESENT_DISPATCH_STATS` reports completed calls and cumulative
native-call/join averages for the entire run (including startup/lifecycle stages),
not GPU time or just the benchmark window. Native result handling stays on the
render thread; only completed successful/suboptimal requests count as presented.
The window-cycle probe drains pre-iconify work before measuring suspension.

The [Vulkan presentation contract](https://docs.vulkan.org/refpages/latest/refpages/source/vkQueuePresentKHR.html)
permits a blocking present call, making overlap a measurable hypothesis. The
experiment does not execute concurrent host operations on AFV's queue/swapchain.

## Experiment: three-slot GL producer / Vulkan transfer consumer

`-Dafv.experimentalFramePipeline=true` (launcher: `--frame-pipeline`) extends the
worker experiment to the entire acquire/transfer/present operation. Three shared
images form a bounded FIFO ring. The render thread copies the current final GL
output and captures scalar semaphore handles. One worker waits for that slot's
fence, acquires a WSI image, submits the cached transfer, then presents. Before
submitting another job the producer collects the oldest when the look-ahead limit
is reached. `afv.experimentalPipelineDepth` (launcher `--pipeline-depth`) selects
1–3 outstanding jobs, independently of the three-image GPU ring; the default is 1.
Depth 3 experimentally increased average throughput but bunched producer frames,
so a deeper CPU queue must not be equated with smooth frame delivery.
All AFV queue and swapchain work stays on that consumer until
the main thread drains it for resource recreation. GL calls and SharedImage's
owner-thread methods stay on the render thread.

If WSI acquisition times out or rejects the surface, a cached ownership-return
command still consumes the GL signal and returns that image to GL. Otherwise the
producer would block forever on its next ring traversal. Unpresented jobs are
counted separately. Ring return commands are freed alongside transfer commands
after draining jobs and the device. Readback diagnostic state and reuse counters
are collected on the consumer with atomic totals; no GL diagnostic call runs there.

The default remains the original synchronous path; `--async-present` alone is
the one-job present-only experiment. Compare that experiment against
`--frame-pipeline` with the same JAR. Benchmark metadata now includes the completed
Vulkan-present delta, dropped-frame delta and pending-job count, so producer FPS
can be checked against actual presentation throughput. Divide
`completedVulkanPresents` by the reported sample seconds. The pipeline's dispatcher
time is an entire acquire/transfer/present job, while the present-only mode times
just the native present operation; those averages are not equivalent GPU timings.
Completed presents count successful/suboptimal returns from `vkQueuePresentKHR`;
they do not prove how many distinct images the compositor/monitor displayed, or
measure input-to-photon latency. The producer frame-time percentiles remain a
separate measure of CPU frame delivery.

## Experiment: direct default-framebuffer output

`afv.experimentalDirectOutput` (launcher `--direct-output`) installs a small
Linux x86_64 native entrypoint router in the EGL FunctionProvider. Both LWJGL's
native core entrypoints and Java/array overloads receive the same routed addresses.
The current render thread's FBO 0 is redirected to the Vulkan-exported color
image before the game frame, including MOD writes after Minecraft's final blit.
Other GL threads have no active alias. Bindings, default read/draw selectors,
bound attachment metadata, default-object mutation validation, named blits and
named clears are handled at that boundary. Real EGL FBO 0 retains the default
selector/metadata contract; alias attachments never become application-visible
through binding/attachment queries. Invalidation may retain undefined contents.

The direct target has RGBA8 color and private depth24/stencil8 storage, matching
the current EGL config. Other shared color formats keep the copy path. Vulkan
performs the vertical flip since there is no longer a GL copy to fuse it with.
The existing final-output marker is drawn while routing is active, then GL
signals external ownership, routing is detached and the cached Vulkan transfer
reads that same image. A rejected frame consumes the signal using the ownership
return command. The router is detached before resource destruction.

This removes the final GL color copy; the Vulkan WSI transfer remains. Early
loading frames outside the normal runTick use the original copy. Direct and copied
frame counters are logged separately. Native code is built by the bridge's Gradle
resource task with the selected JDK headers and bundled inside the bridge JAR.
The experiment is off by default and its native router is loaded only on opt-in.
Only the enumerated entrypoints are routed; arbitrary vendor extensions and MODs
switching unrelated GL contexts on the same render thread require further work.
The ordinary copy route remains available for comparison. The current direct
target additionally requires the WSI and EGL extents to match.
