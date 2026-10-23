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
that instance's `config/fml.toml` before launch. AFV fails with a specific error
if the splash is enabled, instead of attempting to use an OpenGL window.

Do not install the original VulkanMod, another VulkanMod port, Sodium, or Iris
alongside this initial port. Iris/Zink integration is not implemented. There
is no Mesa native distribution in this artifact.

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
and frame stages. Resource registration, general GL routing, and Iris's
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
`vkDeviceWaitIdle` is used to transport frames. Zink clears a synthetic magenta
32x32 patch, which is blitted into the swapchain before PRESENT. Normal AFV output
elsewhere is unchanged. Resize/shutdown drain at the existing lifecycle boundary,
then destroy GL imports before Vulkan resources and rebuild the ring on resize.

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
