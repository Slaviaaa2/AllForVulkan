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

The property `afv.irisShadow` is forwarded and defaults to false. This iteration does not upload a Vulkan image. The active renderer is unchanged. World draws stay on OpenGL for this probe and on Zink for a normal Iris launch.

`.github/workflows/build.yml` runs `./gradlew check` before `./gradlew build`. The client harness is not in that job. A runner with no Vulkan ICD gets `vkCreateInstance` error -9, and those device tasks skip unless `-Dafv.requireCompositeDevice=true`. PR #6's Actions run passed after that skip.

## Iteration 6 — Vulkan shadow color, flag off by default

`afv.irisShadow` defaults off. The config key `irisShadow` is the same switch, and the system property wins. The hook does not cancel Iris's shadow pass.

With the flag on, a private Vulkan device draws a 32×32 image of `(128, 64, 192, 255)` (AFV-Shadow's clear color) and caches those pixels. The hook reads `shadowcolor0`. It uploads only when the center half already matches within tolerance 8. A mismatch, a forced fallback, or a missing device leaves Iris's GL image in place.

`ShadowRenderer.renderShadows` was not entered in this Sodium run, so that mixin never ran. The hook is `IrisRenderingPipeline.renderShadows` plus the client frame event, which is the call the harness observed. The active renderer is still OpenGL for this probe and Zink for a normal Iris launch. World draws are not captured.

Verified here, under Xvfb and llvmpipe:

- Flag off: `AFV_IRIS_SHADOW_HOOK`, no pass line, center `(128, 64, 192, 255)`.
- Flag on: an early `AFV_IRIS_SHADOW_FALLBACK: center mismatch`, then `AFV_IRIS_SHADOW_PASS: device=llvmpipe (LLVM 20.1.2, 256 bits), size=32`. Center diff against the off image was 0/256.
- Forced fallback: `AFV_IRIS_SHADOW_FALLBACK: forced`, no pass line, center diff 0/256.
- `./gradlew :native-bridge:verifyShadowConstant -Dafv.requireCompositeDevice=true` read the same constant back from lavapipe.

No FPS. Real Sodium meshes are still later. Complementary is not this pack, so a center mismatch keeps its GL shadow.

## Iteration 7 — captured Sodium shadow mesh, flag still off

`afv.irisShadow` still defaults off and the hook still does not cancel Iris's shadow pass. While the flag is on, the pinned Sodium 0.6 upload is copied and the shadow draw records which opaque sections it submitted. Iris 1.8.12 appends attributes after the 20-byte compact prefix. Each vertex becomes the subset mesh: camera-relative position, atlas UV with Iris's texcoord shrink, and the stored tint. The lightmap is not applied.

`ShadowMeshPass` draws that mesh only when the pack's `shadow` shader is the fixed texture×tint program. Iris's ortho is classic OpenGL clip space, so z is rewritten with `z' = 0.5 z + 0.5 w`. Y is mirrored with a negative viewport and the readback is flipped to match `glGetTexImage`. Clip Y is then nudged by 1/32 of a pixel: Vulkan was keeping the upper pixel when an edge sat on a pixel center, and llvmpipe keeps the lower one. The block atlas is uploaded with every GL mip. The readback replaces `shadowcolor0` only when every pixel has a sample within one texel and tolerance 8. Otherwise Iris's GL image stays.

AFV-Shadow-Mesh is the non-constant pack. Iris reads `const` directives from fragment shaders only, so `shadowDistance = 32` lives in `shadow.fsh` and the map is 32×32. The clear is `(32, 0, 32)`. The probe places red, blue, white, and lime wool, hides the player, and reopens one flat world so off, on, and fallback see the same chunks.

Verified under Xvfb, llvmpipe, and lavapipe, with the flag off by default:

- `AFV_SHADOW_MESH_DRAW_PASS: device=llvmpipe (LLVM 20.1.2, 256 bits), vertices=12, size=32 left=255,0,0,255 right=0,255,0,255 negativeZ=0,0,255,255`. An edge through a pixel center fills the lower pixel and leaves the upper one clear.
- On: `AFV_SHADOW_MESH_CAPTURE: matched=true last=4 max=4 result=match` and `AFV_SHADOW_PROBE_PASS: width=32 height=32 format=RGBA hook=true file=afv-shadow-on.rgba nonzero=1024/1024 distinct=7 center=85,111,52,255 depthWritten=256 depthMin=0.3921416 depthMax=1.0`.
- Off: `AFV_IRIS_SHADOW_HOOK`, no pass line, `AFV_SHADOW_PROBE_PASS: width=32 height=32 format=RGBA hook=false file=afv-shadow-off.rgba nonzero=1024/1024 distinct=7 center=85,111,52,255 depthWritten=256 depthMin=0.3921416 depthMax=1.0`.
- Forced fallback: `AFV_IRIS_SHADOW_FALLBACK: forced`, no pass line, and the same center and depth as off.
- Diff against that off image: `AFV_SHADOW_DIFF: mismatches=0/1024 tolerance=8 mesh=True` for both the on image and the fallback image.
- GitHub Actions `build (21)` succeeded on `3345ea3`.

This does not move Complementary Reimagined. Its shadow program is not the texture×tint subset, the flag is off, and the GL shadow pass still runs. No FPS.

## Next

Cancel the GL shadow pass for this texture×tint subset now that the replay stays matched. Keep the flag off by default, and keep Iris's image when the replay misses. Until a Complementary pass is actually skipped, a desktop frame time should not move.
