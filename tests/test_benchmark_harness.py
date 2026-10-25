import importlib.util
import sys
from pathlib import Path
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "scripts"))


def load(name):
    path = Path(__file__).resolve().parents[1] / "scripts" / (name + ".py")
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


summary = load("benchmark_summary")
desktop = load("benchmark_desktop")
launcher = load("launch_modpack_client")


def report(**overrides):
    base = {
        "vsync": False, "frameLimit": 260, "renderer": "zink", "asyncPresent": True,
        "pipelineDepth": 0, "width": 1920, "height": 1080, "shaderBackendRequested": "zink",
        "shaderBackendActive": "zink", "shaderBackendFallback": "", "averageFps": 100,
        "p1FrameMs": 8, "p95FrameMs": 12, "p99FrameMs": 18,
    }
    base.update(overrides)
    return base


class HarnessChecks(unittest.TestCase):
    def test_rotation_changes_the_first_route_every_round(self):
        routes = summary.DEFAULT_ROUTES
        self.assertEqual(summary.rotate(routes, 0)[0], "native-gl")
        self.assertEqual(summary.rotate(routes, 1)[0], "zink-gl")
        self.assertEqual(summary.rotate(routes, 4), routes)
        self.assertEqual(summary.selected_routes(None, ["afv-direct"])[-1], "afv-direct")

    def test_route_commands_keep_native_gl_off_zink_and_skip_readback(self):
        common = dict(width=1920, height=1080, render_distance=12, simulation_distance=12,
                      benchmark_ticks=600, warmup_ticks=600, camera="orbit",
                      x11_no_modifiers=False, jvm_args=[])
        native = summary.route_launch_args("native-gl", **common)
        bridge = summary.route_launch_args("afv-bridge", **common)
        sync = summary.route_launch_args("afv-bridge-sync", **common)
        direct = summary.route_launch_args("afv-direct", **common)
        self.assertIn("--backend", native)
        self.assertNotIn("--zink", native)
        self.assertNotIn("--zink-client", native)
        self.assertNotIn("--x11-no-modifiers", bridge)
        self.assertNotIn("--sync-present", bridge)
        self.assertIn("--sync-present", sync)
        self.assertEqual(direct[direct.index("--shader-backend") + 1], "direct")
        for route in summary.ALL_ROUTES:
            args = summary.route_launch_args(route, **common)
            self.assertNotIn("--final-output-probe", args)
            self.assertNotIn("VK_ICD_FILENAMES", " ".join(args))

    def test_async_manifest_defaults_on_and_explicit_flags_win(self):
        self.assertEqual(launcher.async_manifest(False, False, False), "default-on")
        self.assertIs(launcher.async_manifest(True, False, False), False)
        self.assertIs(launcher.async_manifest(False, True, False), True)
        self.assertIs(launcher.async_manifest(False, False, True), True)
        with self.assertRaisesRegex(ValueError, "cannot be combined"):
            launcher.async_manifest(True, True, False)

    def test_nested_servers_are_rejected_and_xwayland_is_not(self):
        self.assertTrue(summary.nested_display("1234 /usr/bin/Xephyr :97 -screen 960x540\n"))
        self.assertTrue(summary.nested_display("Xvfb :99 -screen 0 1920x1080x24"))
        self.assertTrue(summary.nested_display("/usr/bin/Xnest :8"))
        self.assertFalse(summary.nested_display("/usr/bin/Xwayland :0\n/usr/lib/xorg/Xorg :1"))

    def test_dmon_slice_uses_timestamps_and_pads_one_second(self):
        text = "\n".join([
            "# gpu Date Time sm mem",
            "0 2026/10/25 10:00:00 10 1",
            "0 2026/10/25 10:00:01 40 1",
            "0 2026/10/25 10:00:02 80 1",
            "0 2026/10/25 10:00:04 95 1",
        ])
        begin = int(summary._stamp_ms("20261025", "10:00:02", "20261025"))
        end = begin
        chosen = summary.slice_utilization(text, begin, end, begin - 5000)
        self.assertEqual([sample["sm"] for sample in chosen], [40, 80])
        untimed = "0 10 1\n0 20 1\n0 30 1\n"
        indexed = summary.slice_utilization(untimed, 1500, 2500, 0, interval_s=1, pad_s=0)
        self.assertEqual([sample["sm"] for sample in indexed], [30])

    def test_validation_rejects_software_gl_caps_and_present_mismatches(self):
        reasons, warnings = summary.validate_run(
            "native-gl", report(renderer="llvmpipe (LLVM 20.1.2)"), client_passed=True,
            requested_width=1920, requested_height=1080)
        self.assertTrue(any("llvmpipe" in reason for reason in reasons))
        reasons, _ = summary.validate_run(
            "zink-gl", report(renderer="NVIDIA GeForce RTX 3060"), client_passed=True,
            requested_width=1920, requested_height=1080)
        self.assertTrue(any("zink" in reason for reason in reasons))
        reasons, _ = summary.validate_run(
            "afv-bridge", report(vsync=True, frameLimit=80, asyncPresent=False, pipelineDepth=2),
            client_passed=True, requested_width=1920, requested_height=1080)
        self.assertTrue(any("vsync" in reason for reason in reasons))
        self.assertTrue(any("capped" in reason for reason in reasons))
        self.assertTrue(any("async" in reason for reason in reasons))
        self.assertTrue(any("pipeline depth" in reason for reason in reasons))
        reasons, _ = summary.validate_run(
            "afv-bridge-sync", report(asyncPresent=False), client_passed=True,
            requested_width=1920, requested_height=1080)
        self.assertEqual(reasons, [])
        reasons, warnings = summary.validate_run(
            "afv-direct", report(shaderBackendRequested="direct", shaderBackendFallback="Iris stays on Zink"),
            client_passed=False, requested_width=1920, requested_height=1080)
        self.assertEqual(reasons, ["client failed"])
        self.assertTrue(any("fell back" in warning for warning in warnings))
        reasons, warnings = summary.validate_run(
            "native-gl", report(renderer="NVIDIA GeForce RTX 3060/PCIe/SSE2", width=3840, height=2160),
            client_passed=True, requested_width=1920, requested_height=1080)
        self.assertEqual(reasons, [])
        self.assertTrue(warnings)

    def test_cross_route_sizes_invalidate_the_comparison_only(self):
        runs = [
            {"route": "native-gl", "width": 1920, "height": 1080, "reasons": [], "averageFps": 140,
             "p1FrameMs": 5, "p95FrameMs": 9, "p99FrameMs": 12, "smMean": 70},
            {"route": "zink-gl", "width": 960, "height": 540, "reasons": [], "averageFps": 80,
             "p1FrameMs": 10, "p95FrameMs": 16, "p99FrameMs": 20, "smMean": 40},
        ]
        assembled = summary.assemble(runs, self.meta())
        self.assertFalse(assembled["comparisonValid"])
        self.assertTrue(assembled["runs"][0]["valid"])
        self.assertTrue(any("not comparable" in line for line in assembled["interpretation"]))

    def test_interpretation_is_conditional_on_the_measured_gap(self):
        close = self.assemble_pair(100, 102, 140)
        self.assertTrue(any("within 3%" in line for line in close["interpretation"]))
        self.assertTrue(any("more than 15%" in line for line in close["interpretation"]))
        direct = summary.assemble([
            self.sample("zink-gl", 100),
            self.sample("afv-direct", 101, shaderBackendFallback="Iris passes stay on Zink", shaderBackendActive="zink"),
        ], self.meta())
        self.assertTrue(any("measurement bug" in line for line in direct["interpretation"]))
        self.assertTrue(any("expected result" in line for line in direct["interpretation"]))

    def test_environment_keeps_the_vulkan_icd_and_profiles_are_explicit(self):
        env = summary.child_environment({"VK_ICD_FILENAMES": "/usr/share/vulkan/icd.d/nvidia_icd.json", "DISPLAY": ":0"}, ":1")
        self.assertEqual(env["VK_ICD_FILENAMES"], "/usr/share/vulkan/icd.d/nvidia_icd.json")
        self.assertEqual(env["DISPLAY"], ":1")
        args, note = summary.profile_jvm_args("spark", None)
        self.assertEqual(args, [])
        self.assertIn("not attached", note)
        with self.assertRaisesRegex(ValueError, "profiler-lib"):
            summary.profile_jvm_args("async-profiler", None)
        jfr, _ = summary.profile_jvm_args("jfr", None)
        self.assertTrue(jfr[0].startswith("-XX:StartFlightRecording="))

    def test_dry_run_prints_a_rotated_plan_without_launching(self):
        commands = desktop.planned(self.args(), summary.DEFAULT_ROUTES, [])
        self.assertEqual(len(commands), 8)
        self.assertIn("--backend", commands[0])
        self.assertIn("OPENGL", commands[0])
        self.assertIn("--zink", commands[1])
        self.assertIn("--sync-present", commands[3])
        self.assertIn("--zink", commands[4])
        self.assertNotIn("--final-output-probe", commands[4])
        self.assertTrue(all("VK_ICD_FILENAMES" not in " ".join(command) for command in commands))

    def meta(self):
        return {"display": ":0", "width": 1920, "height": 1080, "renderDistance": 12,
                "simulationDistance": 12, "rounds": 1, "benchmarkTicks": 600, "warmupTicks": 600,
                "camera": "orbit", "allowNestedDisplay": False, "nestedDisplay": False}

    def sample(self, route, fps, **extra):
        item = {"route": route, "width": 1920, "height": 1080, "reasons": [], "averageFps": fps,
                "p1FrameMs": 8, "p95FrameMs": 12, "p99FrameMs": 18, "smMean": 40,
                "shaderBackendFallback": "", "shaderBackendActive": "zink"}
        item.update(extra)
        return item

    def assemble_pair(self, zink_fps, bridge_fps, native_fps):
        return summary.assemble([
            self.sample("native-gl", native_fps, smMean=70),
            self.sample("zink-gl", zink_fps),
            self.sample("afv-bridge", bridge_fps, smMean=95),
        ], self.meta())

    def args(self):
        return desktop.argparse.Namespace(
            instance=Path("instance"), install=Path("install"), java_home=Path("java"),
            afv_jar=Path("afv.jar"), shader_pack=None, heap="6G", timeout=1800, local_xim=False,
            width=1920, height=1080, render_distance=12, simulation_distance=12,
            benchmark_ticks=600, warmup_ticks=600, benchmark_camera="orbit", rounds=2,
            x11_no_modifiers=False)


if __name__ == "__main__":
    unittest.main()
