# Desktop benchmark

この手順は、実デスクトップの GPU で経路を交互に測るためのものです。Xephyr、Xnest、Xvfb の中で出た FPS は、表示待ちがサンプルを支配するため、AFV の効果の根拠にしません。このリポジトリの VM では NVIDIA GPU を使えないので、ここにある数値はフレームタイムの主張ではありません。

The harness compares one private client, one JAR, and one shader pack across routes. It rotates which route starts each round, refuses a nested X server, and writes `summary.md` plus `summary.json`.

## What is held constant

- Fresh smoke world, seed `123456789`, flat preset, validation scene.
- Camera: `orbit` (radius 3, 80-tick period, pitch 12) after 120 settle ticks, including warmup. `fixed` keeps the single spawn view.
- VSync off and `maxFps` 260. Minecraft treats 260 as unlimited. A frame limit strictly between 0 and 260 invalidates the run.
- Same width, height, render distance, and simulation distance on every route. A HiDPI framebuffer that differs from the request is a warning when every run of a route agrees. If valid routes disagree with each other, the comparison is invalidated.
- No `--final-output-probe`. Readback is not part of a timing run.
- `--x11-no-modifiers` stays off. Turn it on only when a real X server still fails Zink without Mesa's DRI2 workaround, and treat that as part of the condition.
- The harness does not set or clear `VK_ICD_FILENAMES`.

Frame intervals are `RenderFrameEvent.Post` times. They include pacing and presentation. They are not GPU-only times. `nvidia-smi dmon -s u -d 1 -o DT` is sliced to the benchmark window with one second of padding. Mean SM at or above 90% is `gpu-bound`, at or below 60% is `cpu-or-sync-bound`, and anything between is `mixed`. Missing `nvidia-smi` leaves the class `unknown`.

## Routes

| Route | What it launches | Validity |
| --- | --- | --- |
| `native-gl` | `--backend OPENGL` with the desktop GL driver. Zink is not forced | Renderer must not contain llvmpipe, softpipe, swrast, or zink |
| `zink-gl` | OpenGL through Mesa Zink, AFV native renderer off | Renderer must contain zink |
| `afv-bridge` | Zink GL client, AFV Vulkan presentation, async present at the default | `asyncPresent=true`, `pipelineDepth=0` |
| `afv-bridge-sync` | Same bridge with `--sync-present` | `asyncPresent=false` |
| `afv-native` | Optional. Zink's own window, no AFV bridge | Renderer must contain zink |
| `afv-direct` | Optional. Requests the direct frame graph | `shaderBackendRequested=direct`. A recorded fallback is valid. `scheduled:...` names recognized Iris programs. `compiled:...` names the fixed shadow/gbuffer programs that produced SPIR-V. World draws still run on Zink |

Default routes are the first four. Add the optional ones with `--also afv-native` or `--also afv-direct`.

## Run it

Prepare a private client first (`scripts/prepare_modpack_client.py`). Then, from the repository:

```sh
python3 scripts/benchmark_desktop.py \
  --instance /path/to/private-client \
  --install /path/to/CurseForge/Install \
  --java-home /path/to/jdk21 \
  --afv-jar build/libs/AllForVulkan-1.21.1-neoforge-0.1.0-alpha.1.jar \
  --shader-pack /path/to/ComplementaryReimagined_r5.9.3.zip \
  --display :0 \
  --output ~/afv-bench
```

That uses the short defaults: 3 rounds, 600 measured ticks, 600 warmup ticks, 1920×1080, render and simulation distance 12, orbit camera, 1800 second timeout per client. For a longer sample:

```sh
python3 scripts/benchmark_desktop.py \
  --instance /path/to/private-client \
  --install /path/to/CurseForge/Install \
  --java-home /path/to/jdk21 \
  --afv-jar build/libs/AllForVulkan-1.21.1-neoforge-0.1.0-alpha.1.jar \
  --shader-pack /path/to/ComplementaryReimagined_r5.9.3.zip \
  --display :0 \
  --rounds 5 --benchmark-ticks 1200 --warmup-ticks 1200 \
  --width 1920 --height 1080 \
  --output ~/afv-bench
```

Preview the rotated commands without launching:

```sh
python3 scripts/benchmark_desktop.py --dry-run
```

Each client log and `afv-benchmark.json` is copied into `round-<n>-<route>/` before the next client overwrites the instance copies. The single table is `summary.md`.

## How to read the summary

- AFV bridge average FPS within 3% of `zink-gl`: presentation is not the bottleneck on that machine.
- Native GL more than 15% faster than both `zink-gl` and `afv-bridge`: the gap is GL translation, which the presentation path does not remove.
- `afv-direct` matching `afv-bridge`: expected. Coverage `scheduled:...` means those pack programs were recognized. Coverage `compiled:...` means those fixed shadow and gbuffer programs produced SPIR-V and were not submitted. World draws, and every program listed as unsupported or not compiled, still run on Zink. A large "direct" win in this build is a measurement bug.

A/B the direct request against the current bridge without the other routes:

```sh
python3 scripts/benchmark_desktop.py \
  --instance /path/to/private-client \
  --install /path/to/CurseForge/Install \
  --java-home /path/to/jdk21 \
  --afv-jar build/libs/AllForVulkan-1.21.1-neoforge-0.1.0-alpha.1.jar \
  --shader-pack /path/to/ComplementaryReimagined_r5.9.3.zip \
  --display :0 \
  --route afv-bridge --route afv-direct \
  --output ~/afv-bench
```

`--route` replaces the default list. Omit `--route` to keep native GL, Zink, the bridge, and the synchronous bridge. `--also afv-direct` adds the direct request to those four. The same JAR is used for every route. `afv-bridge` does not pass `--shader-backend`. `afv-direct` passes `--shader-backend direct`.
- Async versus sync is printed as a signed average-FPS gap. Treat it as a gain only when it repeats and p95 does not regress. If the bridge class is `gpu-bound`, overlap has little room to raise FPS.
- A run is dropped when the client fails, the benchmark file is missing, VSync is on, the frame limit is capped, the renderer does not match the route, or a route changes framebuffer size between its own runs.
- An invalidated comparison is labeled in the summary. Do not quote a route difference from that file as an AFV gain.

## Optional profiles

| Flag | Result |
| --- | --- |
| `--profile jfr` | `-XX:StartFlightRecording=...` and a copied `afv-benchmark.jfr`. The summary does not parse it |
| `--profile async-profiler --profiler-lib /path/libasyncProfiler.so` | CPU profile HTML copied from the instance |
| `--profile spark` | Not attached. The summary says so. Start Spark yourself if you want that sample |

Use a profile on a separate invocation. Attaching one during the comparison changes the timing.

## Headless composite check

This does not measure FPS. It runs the direct composite chain on whatever Vulkan device the process already sees:

```sh
./gradlew :native-bridge:verifyFrameGraph :native-bridge:verifyCompositeChain
```

Add `-Dafv.requireCompositeDevice=true` to fail instead of skip when no suitable device exists. `-Dafv.compositeDevice=NVIDIA` keeps the first matching device name. Do not point `VK_ICD_FILENAMES` at lavapipe when the machine has the desktop GPU; the task will not override an ICD you already exported.
