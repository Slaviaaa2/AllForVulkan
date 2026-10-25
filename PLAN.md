# All For Vulkan roadmap

AFV targets Minecraft 1.21.1 on NeoForge, with Iris compatibility as the primary
integration goal. The Linux Mesa/Zink client route preserves the existing
OpenGL frontend while AFV owns Vulkan presentation. The inherited native
renderer remains a separate path; its scene-sharing diagnostic is not a complete
Iris backend. See [architecture](ARCHITECTURE.md) for module boundaries.

## Compatibility

Preserve observable OpenGL behavior, resource ownership, rendering order and
Iris/Sodium lifecycle hooks. Expand coverage of shader passes, framebuffer
operations, resource reload, resizing, world transitions and third-party mods.
Record limitations by API, driver, platform and mod version rather than inferring
complete compatibility from successful startup.

## Performance

Reduce copies, redundant state changes and synchronization overhead without
changing shader behavior. The bridge presents asynchronously by default;
synchronous presentation and a frame-pipeline depth above 1 stay available as
explicit switches. The Iris path's next structural change is a pass-level frame
graph rather than a 1:1 GL translator. The scheduler and a headless composite
chain are in tree; Iris passes still run on Zink, and the in-game direct switch
records that fallback. See [DIRECT_BACKEND.md](docs/DIRECT_BACKEND.md) for the
component breakdown and [BENCHMARK.md](docs/BENCHMARK.md) for the desktop
measurement. Keep unfinished experiments configurable until correctness, frame
pacing, and a repeated desktop comparison justify a new default. See the
[validation guide](docs/VALIDATION.md).

## Distribution

Improve installation and platform/driver coverage, then establish tested default
presets and publish versioned builds with concise release notes. Additional
loaders and Minecraft versions depend on extracting shared renderer/interop
services from version-specific integration. They are future targets, not current
supported configurations.
