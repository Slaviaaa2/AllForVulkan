# Direct Iris frame graph

この文書は、Iris のシェーダー経路を Mesa Zink の呼び出し単位の変換に頼らず、パス単位の明示的な Vulkan フレームグラフで駆動する設計です。今回入っているのは CPU スケジューラと、ゲームプロセスの外で動く合成チェーンです。ゲーム内で `direct` を選んでも Iris のパスはまだ載っていないため、現行の Zink 経路へ戻ります。この変更だけで FPS が上がるとは扱いません。測定は [BENCHMARK.md](BENCHMARK.md) の実デスクトップ手順で行います。

## Decision

AFV's shader path today lets Minecraft, Sodium, and Iris issue GL. Mesa Zink turns that into Vulkan on the NVIDIA driver, and AFV replaces the buffer swap (`ZinkClientFrameMixin` to `ZinkPresenter`). Shadow, gbuffer, composite, compute, and uniform traffic stay inside Zink. Changing only `vkQueuePresentKHR` cannot move the cost of those passes.

The inherited VulkanMod terrain renderer is a different path. `MixinPlugin.shouldApplyMixin` applies the renderer mixins only when `BackendBootstrap.vulkan()` is true, and that flag stays false once another mod is loaded. Iris and the native renderer remain mutually exclusive. Pointing Iris at that renderer would drop the GL frontend the pack depends on.

A 1:1 GL interpreter inside the mod would reimplement Zink's job: every call, every state bit, and a generic render-pass guess. The performance lever is the opposite granularity. Iris already has passes. The direct backend captures those passes and schedules them: merged render passes, explicit barriers, layout tracking, a persistent uniform ring, a pipeline cache, descriptor reuse, frames in flight, and recording of independent waves.

This slice does not do that capture yet. It builds the scheduler and proves one composite chain on a headless Vulkan device, with no Zink process in that test.

## Prior art

- **VulkanMod renderer in this tree.** Explicit pipelines and frames in flight for Minecraft's own terrain and GUI. It is the non-shader path and stays selected only when AFV is the graphics mod.
- **Mesa Zink.** A Gallium driver that maps GL state and draws onto Vulkan, including its own batching and render-pass inference. The current AFV shader path leaves every Iris pass in that mapping.
- **Iris.** Passes are shadow, gbuffers, composite or deferred fullscreen draws, and compute, with FBO switches, uniforms, SSBOs, and images. Public Iris issues ([#1515](https://github.com/IrisShaders/Iris/issues/1515), [#1826](https://github.com/IrisShaders/Iris/issues/1826)) record that Iris is an OpenGL pipeline and is not planned to share a VulkanMod context. Later discussion on [#1722](https://github.com/IrisShaders/Iris/issues/1722) points at Aperture, a separate Vulkan shader loader, rather than executing today's Iris packs unchanged. Complementary Reimagined r5.9.3 is an Iris pack, so Aperture is not a backend for it.
- **ANGLE's Vulkan backend and Zink.** Both show that call-level translation can be correct while a general driver decides barriers. A game-specific graph can merge fullscreen passes only after it owns them.
- **MoltenVK.** A direct Vulkan backend can target it on macOS. Driving GL through Zink and then MoltenVK translates twice. That is the portability reason to own the passes inside the mod, not a claim that this slice already runs there.

## Scheduler

`FrameGraph` is not a GL call log. A pass has a kind (`GRAPHICS`, `COMPUTE`, `TRANSFER`), color and depth attachments, and named reads or writes. `compile()` produces:

- A barrier when a resource's use changes. `UNDEFINED` to anything used is a barrier. Two framebuffer writes of the same attachment do not insert one. A sampled read of an image that the previous group wrote does.
- A render group of consecutive graphics passes that target the same color images and depth, have no new barrier, and do not clear. Load operation is not part of the target identity, so a clear followed by a load of the same image can merge when the other rules allow it. A clear still starts a new group when it is the clear itself.
- Recording waves. A later pass depends on an earlier one when either side writes a shared resource, including a color write after a color write. Independent passes share a wave. Merged draws stay ordered across waves because a framebuffer write orders them.

The composite chain used by the test is:

1. Clear `scene` as a transfer destination.
2. Exposure samples `scene` and writes `lit`.
3. Grade samples `lit` and writes `output`.
4. Read `output` back as a transfer source.

That compiles to 6 barriers, 4 groups, and 4 waves. Two fullscreen passes that write the same color image merge into one group. A pass that samples its own previous color target does not merge. Two clears of different images are one wave.

`UniformRing` is a persistent mapped ring. A slice can be written again only after `retire`. `PipelineLibrary` remembers a shader, format, and sample-count key and round-trips it through a text snapshot. `DescriptorCache` reuses a slot for the same buffer range, view, sampler, and layout.

## What executes now

`CompositeChain` creates its own Vulkan 1.2 instance and device. It does not open Minecraft and it does not load Zink. The chain is an 8×8 `R8G8B8A8_UNORM` graph:

- Clear scene to byte color 64, 128, 192, 255.
- A fullscreen triangle samples that image. The vertex shader is the usual oversized triangle (`gl_VertexIndex`), and the fragment shader multiplies the sample by a uniform `vec4`.
- Exposure uses `(0.5, 0.5, 0.5, 1)`. Grade uses `(1, 1, 1, 1)`. The readback must be 32, 64, 96, 255.
- The graphics pipeline is created, destroyed, and created again from the `VkPipelineCache` blob. The CPU library must miss once and then hit.
- Three submissions use two ring slots per frame. The third submission repeats frame 0 so the descriptor cache hits. Layout tracking across frames uses the image's real layout; the compiler's initial `UNDEFINED` is only the first-pass plan. Frame 0 must issue exactly the compiled barrier count.
- The parallel-clear wave records two command buffers and submits them together. Red and green read back as solid colors. That is one submit of independent buffers, not a worker thread.

The executor prefers a device named by `afv.compositeDevice`. Otherwise it takes the first device that can sample and transfer the format, stopping early on llvmpipe when no name was requested. A missing loader or device skips. `afv.requireCompositeDevice=true` turns that skip into a failure.

## In-game flag

`afv.shaderBackend=zink|direct` (and the same key in the AFV settings screen) is recorded on the `AFV_SHADER_BACKEND` log line, separate from `AFV_BACKEND`. `ShaderBackendSelection` keeps the active path on Zink:

- No third-party mod is loaded. The slice is not the native terrain renderer, so the current renderer stays.
- Iris or another mod is loaded and the shader pack has no supported program. Coverage stays `composite-chain`.
- The pack has supported programs, and none of them are the fixed texture×tint shadow or gbuffer program. Coverage is `scheduled:` plus those program names. Unsupported programs are named in the fallback string. World draws still run on Zink, so the client does not switch renderers.
- Some shadow or gbuffer programs are exactly that fixed program (`ftransform`, `gl_MultiTexCoord0`, `gl_Color`, `texture2D(gtexture) * tint`). Those compile to SPIR-V. Coverage is `compiled:` plus their names. `final`, composites, and every other shape are listed after `not compiled:`. A shaderc error drops only that program. World draws still run on Zink. The client does not submit the SPIR-V.

The pack reader uses `config/iris.properties` and the file under `shaderpacks/`. A missing pack, `enableShaders=false`, or an unreadable zip leaves the plan empty and keeps the previous fallback. The reader does not follow paths outside `shaderpacks/`.

The fallback string is the honest label. A benchmark route that requested `direct` and shows that string is still a Zink run.

## Tests

`./gradlew :native-bridge:verifyFrameGraph` checks the barrier, merge, and wave counts, the ring, both caches, the presentation defaults, and runs `spirv-val` when `/usr/bin/spirv-val` exists.

`./gradlew :native-bridge:verifyCompositeChain` runs the device chain. On a machine without a suitable Vulkan device it skips unless the require flag is set. This VM's lavapipe run is a correctness check of the pixels and the cache counters. It is not an FPS sample.

`./gradlew :native-bridge:verifyIrisSubset` compiles the AFV-Smoke shadow and gbuffer programs with shaderc and checks the SPIR-V. Programs outside that fixed source stay uncompiled. It does not create a Vulkan device.

`./gradlew :native-bridge:verifyIrisSubsetDraw` runs those three programs on a private Vulkan device. The caller passes a three-vertex mesh and a 1×1 texel. The smoke mesh uses tint `0.5` and must read back `(32, 64, 96, 255)`. A white tint on the same texel must read back `(64, 128, 192, 255)`. An empty program in the same list is reported and does not cancel the others. A bad mesh is rejected before a device is created. A missing device skips unless `afv.requireCompositeDevice=true`. This is not an FPS sample, and it does not replace Iris draws in the game.

Release builds of the existing Zink client no longer call `glGetError` per frame. `GlBlitScope` still queries scissor, sRGB, and the read buffer because Iris and the indexed-state probe can change them; those queries are correctness, not a missed optimization. Async present defaults on for the bridge. `--sync-present` or `-Dafv.asyncPresent=false` turns it off. Frame-pipeline depth above 1 stays off unless the frame-pipeline experiment is explicitly enabled.

## Remaining roadmap

Effort below is the size of the component, not a calendar estimate.

1. **Done in this change.** Scheduler, barriers, layout tracking, uniform ring, pipeline library, descriptor cache, headless composite execution, and the fallback flag.
2. **Next, and larger.** The fixed shadow/gbuffer program already draws a stand-in triangle on a private device. Capturing one pinned Iris version at pass boundaries is what would feed that program real meshes. Terrain draws may stay on GL. This is a hybrid: one shadow framebuffer, its program, and its barriers, with the rest of the frame still produced by Zink. It needs a stable Iris hook for that version only. Cancelling Iris's shadow pass without writing the same shadow map breaks the pack, so an absent hook keeps Zink.
3. **Largest capture.** Sodium and gbuffer draws. Retarget the draw to the graph's pipelines, or capture the draw inputs. This is not a per-call GL translator. It is the terrain vertex path, materials, and the Iris gbuffer programs.
4. **Composite and deferred chain.** Translate the pack's fullscreen GLSL with the shaderc dependency already in the build. The merger in this scheduler already groups consecutive fullscreen passes. Complementary's composite cost would move here, after the programs and their textures are real inputs.
5. **SSBOs, storage images, and compute dispatches.** Required for packs that leave the graphics-only composite shape. The graph's kinds and uses name them; the executor does not record them yet.
6. **Frames in flight for a whole graph, then multithreaded recording.** The wave list is the split point. Today the parallel clears are two command buffers and one submit on the calling thread. Worker recording comes after the single-thread chain matches a captured Iris frame.
7. **Default and install.** Only after a pinned Complementary scene matches the current path on the desktop GPU, and the harness shows a gap outside run-to-run noise, consider making the direct backend the default and dropping the Zink install requirement. That includes a MoltenVK path for macOS. This slice does not.

Until step 2 lands, `afv-direct` in the desktop harness is a label for the fallback, not a new renderer.
