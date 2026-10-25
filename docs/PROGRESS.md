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

GitHub Actions has recorded no workflow runs for this repository, including the merge of PR #1. PR #2 was merged after `./gradlew check -Dafv.requireCompositeDevice=true` and the Python harness tests passed locally. There was no GitHub check to turn green or red.

## Iteration 2 — compile the fixed shadow/gbuffer program to SPIR-V

AFV-Smoke's `shadow`, `gbuffers_textured_lit`, and `gbuffers_terrain` are one GLSL 120 program: `ftransform`, the texture matrix times `gl_MultiTexCoord0`, `gl_Color`, and `texture2D(gtexture) * tint`. That source is replaced with Vulkan GLSL 450 and compiled with shaderc. Coverage for a pack that contains it is `compiled:` plus those names.

`final` and every other shape stay off that list (`not compiled:`). A shaderc error removes only the program that failed. Compute, image, and geometry programs stay on the unsupported list from iteration 1. The active renderer is still Zink. The SPIR-V is not submitted, and world draws are not captured.

Verified here: `./gradlew :native-bridge:verifyIrisSubset` (SPIR-V magic and `spirv-val` on the smoke programs, a rearranged source, a forced compiler failure, and rejected version/preprocessor/extra-statement programs). No FPS.

Unmeasured: Complementary Reimagined r5.9.3, and any frame time. The desktop A/B is still `--route afv-bridge --route afv-direct`. A large gap on this build would be a measurement bug.

## Next

Submit the compiled shadow and gbuffer SPIR-V on a Vulkan device and read back `texture * tint`, still falling back per program when pipeline creation fails. World meshes stay on Zink.
