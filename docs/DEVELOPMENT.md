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
Then build M1's isolated Mesa/Zink context, physical-device identity checks,
external-memory capability probing and bidirectional semaphore sharing. No fake
GL capabilities, readback-based product path or Iris compatibility claims.
