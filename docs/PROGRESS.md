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

`final` and every other shape stay off that list (`not compiled:`). A shaderc error removes only the program that failed. Compute, image, and geometry programs stay on the unsupported list from iteration 1. The active renderer is still Zink. This iteration does not submit the SPIR-V. World draws are not captured.

Verified here: `./gradlew :native-bridge:verifyIrisSubset` (SPIR-V magic and `spirv-val` on the smoke programs, a rearranged source, a forced compiler failure, and rejected version/preprocessor/extra-statement programs). No FPS.

Unmeasured: Complementary Reimagined r5.9.3, and any frame time. The desktop A/B is still `--route afv-bridge --route afv-direct`. A large gap on this build would be a measurement bug.

PR #3 merged the same way: Actions still had no runs, and the local check passed.

## Iteration 3 — draw the compiled programs on Vulkan

`IrisSubsetPass` creates its own Vulkan 1.2 device. For each compiled program it uploads a 1×1 texture `(64, 128, 192, 255)`, draws one triangle with UV `0.5` and tint `0.5`, and reads an 8×8 target. The expected color is `(32, 64, 96, 255)`. `shadow`, `gbuffers_textured_lit`, and `gbuffers_terrain` each get their own image. An empty SPIR-V program is listed in the fallback and the other programs still draw. The active in-game renderer stays Zink. No Iris mesh is captured.

Verified here: `./gradlew :native-bridge:verifyIrisSubsetDraw -Dafv.requireCompositeDevice=true` on lavapipe. No FPS.

Unmeasured: Complementary Reimagined r5.9.3, and any frame time. The desktop A/B is still `--route afv-bridge --route afv-direct`. A large gap on this build would be a measurement bug, because the game does not submit these passes.

## Iteration 4 — the draw takes a caller mesh

`IrisSubsetPass.Mesh` is three vertices (position, UV, tint) and one RGBA texel, plus the color the readback must match. The smoke triangle is that record with tint `0.5`. A second mesh with tint `1` reads back the texel unchanged: `(64, 128, 192, 255)`. A texture that is not 1×1 RGBA is rejected before a device is created. The game still does not supply this mesh.

Verified here: `./gradlew :native-bridge:verifyIrisSubsetDraw -Dafv.requireCompositeDevice=true` on lavapipe. No FPS.

## Iteration 5 — read Iris shadowcolor0 in a headless client

`scripts/shadow_probe.py` launches the NeoForge 1.21.1 client with the pinned Iris and Sodium jars, the AFV-Shadow pack, and a generated flat world. The backend is OpenGL (`-Dafv.backend=OPENGL`), so this run does not enter the Zink bridge and does not require Vulkan presents. Xvfb `:99` and llvmpipe provide the GL context. The probe reads `shadowcolor0` with `glGetTexImage` and writes `afv-shadow-off.rgba`.

Iris does not read `shadowMapResolution` from `shaders.properties`. Without a shader `const`, the attachment is the default 1024×1024 clear of `(255, 255, 255, 255)`. AFV-Shadow sets `const int shadowMapResolution = 32` and `const vec4 shadowcolor0ClearColor` to `(128, 64, 192, 255)`. The fragment shader writes that same color. The harness rejects any other size or a center outside tolerance 8.

Verified here, under Xvfb and llvmpipe: `AFV_SHADOW_PROBE_PASS: width=32 height=32 format=RGBA hook=false file=afv-shadow-off.rgba nonzero=1024/1024 center=128,64,192,255`. No crash. `./gradlew :verifyShadowImage` checks the center-half compare. The client harness is not a GitHub Actions job. A warm run finished in under a minute; the script still allows 45 minutes. No FPS.

The property `afv.irisShadow` is forwarded and defaults to false. Nothing in the game reads it yet, so this run cannot enable a Vulkan shadow pass. The active renderer is unchanged. World draws stay on OpenGL for this probe and on Zink for a normal Iris launch.

`.github/workflows/build.yml` runs `./gradlew check` before `./gradlew build` on pull requests and pushes. That does not include the client harness. A runner with no Vulkan driver gets `vkCreateInstance` error -9. Those headless device tasks skip unless `-Dafv.requireCompositeDevice=true`.

## Next

Put an Iris `renderShadows` hook behind `afv.irisShadow`, still defaulting off. When the flag is on, draw the same constant at the shadow resolution on Vulkan and upload it only if the center already matches. A mismatch or a device failure leaves Iris's GL image in place. Do not cancel Iris's shadow pass. The probe's on and diff modes are the check. Real Sodium meshes are still later.
