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
python3 -m unittest discover -s tests -p test_modpack_profiles.py
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

Use the same JAR, mod profile, shader/options, GPU/driver, display configuration,
scene, camera, resolution, distances, VSync and frame limit. For a short sample,
add `--benchmark-ticks 400 --warmup-ticks 600 --uncapped` to a smoke scene run.
Do not enable `--final-output-probe` during timing: marker/readback work changes
measurement overhead. Run correctness diagnostics separately.

The launcher archives benchmark JSON and launch metadata inside the test instance.
Report average FPS and median/p95/p99 frame intervals, sample length and conditions.
These intervals include pacing and presentation; they are not GPU-only timings.
For asynchronous modes, also report completed Vulkan presentations, dropped jobs
and pending jobs. Successful `vkQueuePresentKHR` returns do not measure how many
distinct frames reached a compositor or monitor, or input-to-photon latency.

Compare experimental switches one at a time, including `--async-present`,
`--frame-pipeline --pipeline-depth 1|2|3` and `--direct-output`. Direct-output
handoff layouts can be compared with `--direct-output-layout general|attachment`.
A single A/B pair is a reference sample; repeated runs with controlled order are
needed for a performance claim. Verify the actual GPU renderer: a nested X server
may expose software OpenGL while Vulkan uses hardware, invalidating that comparison.

## Reporting and releases

Keep local paths, private modpack identities, raw logs and agent working notes in
ignored storage. Public issue reports and release notes should contain only the
information needed to reproduce the behavior: AFV version, platform, GPU/driver,
relevant mod versions, settings, scenario and sanitized diagnostics. Publish
performance claims with reproducible conditions and appropriate uncertainty.
