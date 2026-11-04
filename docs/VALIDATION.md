# Validation guide

AFV includes opt-in diagnostics for startup, graphics interoperability, Iris
lifecycle and presentation. A successful check applies to its exact artifact,
driver, mod versions and scenario; it does not establish universal compatibility.
Build and launch instructions are in [DEVELOPMENT.md](DEVELOPMENT.md).

## Build and distribution

Use a full JDK 21:

```sh
./gradlew build compileBaselineJava
python3 scripts/verify_artifact.py
python3 -m unittest discover -s tests -p 'test_*.py'
```

The artifact validator checks distribution metadata, required classes, nested
libraries and native resources. It does not replace a production-client run.

## Isolated client runs

Prepare a new test instance with `scripts/prepare_modpack_client.py`, then launch
the built AFV JAR with `scripts/launch_modpack_client.py`. See each helper's `--help`
and the development guide for required installation and Java paths. Preparation
copies independent files and records source hashes; launch rejects missing,
changed or unexpected mod JARs. Never use an existing gameplay save for destructive
scene or dimension diagnostics. Third-party mods and shader packs are supplied
separately and are not included with AFV.

Use `--smoke --require-iris --zink-client --expect-backend Vulkan` to require an
actual Iris pipeline and AFV Vulkan presentation. Backend fallback alone does
not satisfy this check. Add the following diagnostics as needed:

| Option | Required behavior |
| --- | --- |
| `--scene` | Fixed test scene in a fresh smoke-test world |
| `--iris-cycle` | Shader disable, enable and reload with real pipeline changes |
| `--window-cycle` | Iconify/restore and VSync transitions with a real window manager |
| `--dimension-cycle` | Overworld, Nether and End transitions with active Iris pipelines |
| `--final-output-probe` | Small GPU output marker checked after presentation transfer |
| `--direct-output` | Shared render target with direct-frame activity assertions |

Require the launcher's success markers and normal exit; process survival alone
is insufficient. Preserve the launch manifest and logs with artifact and input
checksums. GPU marker probes verify bounded output regions, not full-image
identity. Native driver faults, arbitrary GL extensions and untested shader
features need their own coverage.

## Performance checkpoints

Use [BENCHMARK.md](BENCHMARK.md) for a claim. It alternates native GL, GL-on-Zink,
and the AFV bridge on a real desktop display, rotates the starting route, and
writes one `summary.md` with average FPS, p1/p95/p99 frame time, and GPU
utilization. A nested X server (Xephyr, Xnest, Xvfb) is refused because present
time dominates that sample. Those runs are not evidence of an AFV gain.

For a single diagnostic sample, add `--benchmark-ticks 400 --warmup-ticks 600 --uncapped`
to a smoke scene run. Do not enable `--final-output-probe` during timing:
marker/readback work changes measurement overhead. Run correctness diagnostics
separately.

The launcher archives benchmark JSON and launch metadata inside the test instance.
Report average FPS and p1/median/p95/p99 frame intervals, sample length and conditions.
These intervals include pacing and presentation; they are not GPU-only timings.
For asynchronous modes, also report completed Vulkan presentations, dropped jobs
and pending jobs. Successful `vkQueuePresentKHR` returns do not measure how many
distinct frames reached a compositor or monitor, or input-to-photon latency.

### Render ceiling

A window system can cost more than the renderer. On a nested X server the GPU
driver may present through a slow foreign-server path, which caps every route near
the same frame rate and hides real differences. Check this before trusting a
comparison: if `vkQueuePresentKHR` dominates or the GPU is far from fully loaded,
the sample measures presentation.

`--render-ceiling` replaces swapchains with offscreen images through a Vulkan layer
built from `validation/native/`. Rendering, submission, synchronization and a
three-frame GPU throttle remain; nothing reaches the window. The resulting FPS is
what the renderer can produce on that GPU, not what a player sees.

`--gl-reference VENDOR_JSON` is the same measurement for the vendor's OpenGL driver:
GL renders on the GPU's EGL device into a pbuffer, so a nested X server can host a
same-hardware reference. Use it with `--backend OPENGL --expect-backend OpenGL
--benchmark-only` and the vendor's glvnd JSON file. Compare it only with
`--render-ceiling` results from the same scene.

`--render-probe PATH` records per-second Vulkan blocking times and draw statistics
to `PATH.<pid>`, with or without `--render-ceiling`. Layer-level times are CPU
blocking times of those entrypoints, not GPU execution times.
Add `--render-probe-gpu` for GPU timestamps around render passes, blits and copies.
Group a frame by pass position when comparing runs: two passes with the same size
and attachments can be different work.

The flat diagnostic scene is too light to represent play. For a representative
load add `--terrain --benchmark-only --width 1920 --height 1080`, which uses a fixed
viewpoint over generated terrain. Use the real display for the final measurement
of what players see, since neither mode includes the compositor or scanout.

Compare experimental switches one at a time, including `--sync-present` against
the async default, `--frame-pipeline --pipeline-depth 1|2|3`, and `--direct-output`.
Direct-output handoff layouts can be compared with `--direct-output-layout general|attachment`.
Pipeline depth above 1 stays off unless that experiment is selected. A single A/B
pair is a reference sample. Verify the actual GPU renderer: a nested X server may
expose software OpenGL while Vulkan uses hardware, invalidating that comparison.

## Reporting and releases

Keep local paths, private modpack identities, raw logs and agent working notes in
ignored storage. Public issue reports and release notes should contain only the
information needed to reproduce the behavior: AFV version, platform, GPU/driver,
relevant mod versions, settings, scenario and sanitized diagnostics. Publish
performance claims with reproducible conditions and appropriate uncertainty.
