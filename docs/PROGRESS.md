# Progress

Running log for the direct Iris backend. FPS numbers belong on the owner's RTX 3060 run of `scripts/benchmark_desktop.py`. This file does not record a frame rate.

## Iteration 1 — schedule Iris programs, keep Zink draws

The direct request reads the active Iris pack and builds a frame graph for programs it can name:

- `shadow` and `shadow_*` clear `shadowtex0` and `shadowcolor0`.
- `shadowcomp*` samples `shadowcolor0` into `shadowcolor1`.
- `gbuffers_*` share `colortex0`. Consecutive gbuffer passes merge.
- `deferred*`, `composite*`, `prepare*`, `begin`, and `final` sample the previous color target.

Programs that use compute, image load/store, storage images, atomics, geometry, or tessellation stay off that graph. The fallback string names them. `ShaderBackendSelection` still sets the active renderer to Zink, including when the coverage is `scheduled:...`. Sodium and Minecraft world draws are not captured.

Verified here: `./gradlew :native-bridge:verifyIrisPackPlan` on the AFV-Smoke pack, a pack with compute and image-store programs, a zip, and a shader-pack path that tries to leave `shaderpacks/`. No GPU. No FPS.

Unmeasured: whether Complementary Reimagined r5.9.3 schedules any program, and any frame time. The desktop A/B is `--route afv-bridge --route afv-direct` in [BENCHMARK.md](BENCHMARK.md). A large gap on this build would be a measurement bug.

## Next

Execute the scheduled shadow and gbuffer shaders for the supported GLSL subset on a Vulkan device, still falling back per program when compilation fails. World meshes are a later capture.
