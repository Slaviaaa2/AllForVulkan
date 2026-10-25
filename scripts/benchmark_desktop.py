#!/usr/bin/env python3
"""Alternate desktop benchmark routes and write one summary.

Run this on the machine whose GPU you want to measure. A nested X server
(Xephyr, Xnest, Xvfb) makes present time dominate the sample; the harness
refuses those unless --allow-nested-display is set. It does not change
VK_ICD_FILENAMES. See docs/BENCHMARK.md.
"""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import time

import benchmark_summary as summary


LAUNCHER = Path(__file__).with_name("launch_modpack_client.py")
COPIES = ("afv-production-client.log", "afv-benchmark.json", "afv-benchmark.jfr", "afv-async-profiler.html")


def planned(args, routes, jvm_args):
    common = [
        "--instance", str(args.instance), "--install", str(args.install),
        "--java-home", str(args.java_home), "--afv-jar", str(args.afv_jar),
        "--heap", args.heap, "--timeout", str(args.timeout),
    ]
    if args.shader_pack:
        common += ["--shader-pack", str(args.shader_pack)]
    if args.local_xim:
        common += ["--local-xim"]
    commands = []
    for round_index in range(args.rounds):
        for route in summary.rotate(routes, round_index):
            commands.append([sys.executable, str(LAUNCHER), *common, *summary.route_launch_args(
                route, width=args.width, height=args.height, render_distance=args.render_distance,
                simulation_distance=args.simulation_distance, benchmark_ticks=args.benchmark_ticks,
                warmup_ticks=args.warmup_ticks, camera=args.benchmark_camera,
                x11_no_modifiers=args.x11_no_modifiers, jvm_args=jvm_args)])
    return commands


def start_dmon(path):
    if shutil.which("nvidia-smi") is None:
        path.write_text("")
        return None, None
    handle = path.open("w")
    process = subprocess.Popen(
        ["nvidia-smi", "dmon", "-s", "u", "-d", "1", "-o", "DT"],
        stdout=handle, stderr=subprocess.STDOUT, start_new_session=True)
    return process, handle


def stop_dmon(process, handle):
    if process is None:
        return
    try:
        os.killpg(process.pid, signal.SIGTERM)
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait()
    finally:
        if handle is not None:
            handle.close()


def one_run(args, route, round_index, command, output):
    run_dir = output / f"round-{round_index + 1}-{route}"
    run_dir.mkdir(parents=True)
    (run_dir / "command.txt").write_text(" ".join(command) + "\n")
    dmon_path = run_dir / "nvidia-smi-dmon.txt"
    dmon, dmon_handle = start_dmon(dmon_path)
    started = time.time() * 1000
    env = summary.child_environment(os.environ, args.display)
    completed = subprocess.run(command, env=env)
    stop_dmon(dmon, dmon_handle)
    for name in COPIES:
        source = args.instance / name
        if source.is_file():
            shutil.copy2(source, run_dir / name)
    report = None
    benchmark = run_dir / "afv-benchmark.json"
    if benchmark.is_file():
        report = json.loads(benchmark.read_text())
    samples = []
    if report and dmon_path.is_file():
        samples = summary.slice_utilization(
            dmon_path.read_text(errors="replace"), report.get("benchmarkBeginWallMs"),
            report.get("benchmarkEndWallMs"), started)
    reasons, warnings = summary.validate_run(
        route, report, client_passed=completed.returncode == 0,
        requested_width=args.width, requested_height=args.height)
    sm_values = [sample["sm"] for sample in samples]
    return {
        "round": round_index + 1, "route": route, "exitCode": completed.returncode,
        "reasons": reasons, "warnings": warnings,
        "averageFps": None if report is None else report.get("averageFps"),
        "p1FrameMs": None if report is None else report.get("p1FrameMs"),
        "medianFrameMs": None if report is None else report.get("medianFrameMs"),
        "p95FrameMs": None if report is None else report.get("p95FrameMs"),
        "p99FrameMs": None if report is None else report.get("p99FrameMs"),
        "frames": None if report is None else report.get("frames"),
        "width": None if report is None else report.get("width"),
        "height": None if report is None else report.get("height"),
        "renderer": None if report is None else report.get("renderer"),
        "asyncPresent": None if report is None else report.get("asyncPresent"),
        "pipelineDepth": None if report is None else report.get("pipelineDepth"),
        "shaderBackendRequested": None if report is None else report.get("shaderBackendRequested"),
        "shaderBackendActive": None if report is None else report.get("shaderBackendActive"),
        "shaderBackendFallback": None if report is None else report.get("shaderBackendFallback"),
        "smMean": statistics_mean(sm_values), "classification": summary.classify(sm_values),
        "gpuSamples": len(samples), "directory": str(run_dir),
    }


def statistics_mean(values):
    if not values:
        return None
    return sum(values) / len(values)


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--instance", type=Path)
    parser.add_argument("--install", type=Path)
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--afv-jar", type=Path)
    parser.add_argument("--shader-pack", type=Path)
    parser.add_argument("--display", help="DISPLAY for every client. The default inherits the current environment")
    parser.add_argument("--output", type=Path, default=Path("build/desktop-benchmark"))
    parser.add_argument("--rounds", type=int, default=3)
    parser.add_argument("--benchmark-ticks", type=int, default=600)
    parser.add_argument("--warmup-ticks", type=int, default=600)
    parser.add_argument("--width", type=int, default=1920)
    parser.add_argument("--height", type=int, default=1080)
    parser.add_argument("--render-distance", type=int, default=12)
    parser.add_argument("--simulation-distance", type=int, default=12)
    parser.add_argument("--benchmark-camera", choices=("fixed", "orbit"), default="orbit")
    parser.add_argument("--timeout", type=int, default=1800)
    parser.add_argument("--heap", default="6G")
    parser.add_argument("--route", action="append", dest="routes", choices=summary.ALL_ROUTES,
                        help="Replace the default route list. Repeat for each route, in rotation order")
    parser.add_argument("--also", action="append", choices=summary.OPTIONAL_ROUTES,
                        help="Add afv-native or afv-direct to the route list")
    parser.add_argument("--x11-no-modifiers", action="store_true",
                        help="Off by default. Set only if a real X server still needs Mesa's DRI2 Kopper workaround")
    parser.add_argument("--local-xim", action="store_true")
    parser.add_argument("--allow-nested-display", action="store_true",
                        help="Run even if Xephyr, Xnest, or Xvfb is visible. Those samples are not FPS evidence")
    parser.add_argument("--profile", choices=("none", "jfr", "async-profiler", "spark"), default="none")
    parser.add_argument("--profiler-lib", help="Path to libasyncProfiler.so when --profile async-profiler")
    parser.add_argument("--jvm-arg", action="append", default=[])
    parser.add_argument("--dry-run", action="store_true", help="Print the rotated commands and write no clients")
    args = parser.parse_args(argv)
    if args.rounds < 1 or args.benchmark_ticks < 1 or args.warmup_ticks < 0:
        raise ValueError("rounds and benchmark ticks must be positive")
    routes = summary.selected_routes(args.routes, args.also)
    profile_args, profile_note = summary.profile_jvm_args(args.profile, args.profiler_lib)
    jvm_args = [*profile_args, *args.jvm_arg]
    if args.dry_run:
        args.instance = args.instance or Path("<instance>")
        args.install = args.install or Path("<install>")
        args.java_home = args.java_home or Path("<java-home>")
        args.afv_jar = args.afv_jar or Path("<afv-jar>")
    commands = planned(args, routes, jvm_args)
    if args.dry_run:
        for command in commands:
            print(" ".join(command), flush=True)
        print(f"AFV_BENCHMARK_DRY_RUN: routes={','.join(routes)} commands={len(commands)}", flush=True)
        return 0
    for required in ("instance", "install", "java_home", "afv_jar"):
        if getattr(args, required) is None:
            raise ValueError("--" + required.replace("_", "-") + " is required unless --dry-run")
    listing = subprocess.check_output(["ps", "-eo", "args"], text=True, errors="replace")
    nested = summary.nested_display(listing)
    if nested and not args.allow_nested_display:
        print("Refusing to benchmark while Xephyr, Xnest, or Xvfb is running. "
              "A nested server makes present time the sample. Use the desktop DISPLAY, "
              "or pass --allow-nested-display only for a functional smoke.", file=sys.stderr)
        return 2
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    runs = []
    for index, command in enumerate(commands):
        round_index, route = divmod_route(index, routes, args.rounds)
        print(f"AFV_BENCHMARK_ROUTE: round={round_index + 1} route={route}", flush=True)
        runs.append(one_run(args, route, round_index, command, output))
    gpu_note = None if shutil.which("nvidia-smi") else "nvidia-smi is not installed, so GPU utilization was not recorded."
    meta = {
        "display": args.display or os.environ.get("DISPLAY"), "width": args.width, "height": args.height,
        "renderDistance": args.render_distance, "simulationDistance": args.simulation_distance,
        "rounds": args.rounds, "benchmarkTicks": args.benchmark_ticks, "warmupTicks": args.warmup_ticks,
        "camera": args.benchmark_camera, "routes": list(routes), "nestedDisplay": nested,
        "allowNestedDisplay": args.allow_nested_display, "profile": args.profile,
        "profileNote": profile_note, "gpuNote": gpu_note,
        "preservedVulkanIcd": "VK_ICD_FILENAMES" in os.environ,
    }
    report = summary.assemble(runs, meta)
    (output / "summary.json").write_text(json.dumps({key: value for key, value in report.items() if key != "markdown"}, indent=2) + "\n")
    (output / "summary.md").write_text(report["markdown"])
    print(report["markdown"], flush=True)
    print(f"AFV_BENCHMARK_SUMMARY: {output / 'summary.md'}", flush=True)
    return 0 if any(run["valid"] for run in report["runs"]) else 1


def divmod_route(index, routes, rounds):
    per_round = len(routes)
    round_index = index // per_round
    offset = index % per_round
    return round_index, summary.rotate(routes, round_index)[offset]


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except ValueError as failure:
        print("AFV_BENCHMARK_FAIL: " + str(failure), file=sys.stderr)
        raise SystemExit(2)
