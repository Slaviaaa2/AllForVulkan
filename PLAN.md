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
changing shader behavior. Evaluate architectural experiments, including direct
output and asynchronous presentation, against the same artifact and rendering
conditions. Keep experiments configurable until correctness, frame pacing and
performance justify default settings. See the [validation guide](docs/VALIDATION.md).

## Distribution

Improve installation and platform/driver coverage, then establish tested default
presets and publish versioned builds with concise release notes. Additional
loaders and Minecraft versions depend on extracting shared renderer/interop
services from version-specific integration. They are future targets, not current
supported configurations.
