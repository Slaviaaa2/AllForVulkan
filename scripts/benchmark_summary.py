#!/usr/bin/env python3
"""Pure summary helpers for the desktop AFV benchmark.

The CLI in benchmark_desktop.py launches clients. This module only rotates
routes, builds arguments, reads nvidia-smi dmon text, and writes the table.
"""
import datetime
import re
import statistics


DEFAULT_ROUTES = ("native-gl", "zink-gl", "afv-bridge", "afv-bridge-sync")
OPTIONAL_ROUTES = ("afv-native", "afv-direct")
ALL_ROUTES = DEFAULT_ROUTES + OPTIONAL_ROUTES
ZINK_ROUTES = ("zink-gl", "afv-bridge", "afv-bridge-sync", "afv-native", "afv-direct")


def selected_routes(routes, also):
    chosen = list(routes) if routes else list(DEFAULT_ROUTES)
    for route in chosen + list(also or []):
        if route not in ALL_ROUTES:
            raise ValueError("Unknown benchmark route: " + route)
    for extra in also or []:
        if extra not in chosen:
            chosen.append(extra)
    if not chosen:
        raise ValueError("No benchmark routes")
    return tuple(chosen)


def rotate(routes, round_index):
    routes = tuple(routes)
    if not routes:
        raise ValueError("No benchmark routes")
    shift = round_index % len(routes)
    return routes[shift:] + routes[:shift]


def nested_display(process_args):
    """True when Xephyr, Xnest, or Xvfb appears as a process. Xwayland is allowed."""
    pattern = re.compile(r"(^|[/\s])(Xephyr|Xnest|Xvfb)(\s|$)")
    return any(pattern.search(line) for line in process_args.splitlines())


def route_launch_args(route, *, width, height, render_distance, simulation_distance,
                      benchmark_ticks, warmup_ticks, camera, x11_no_modifiers, jvm_args):
    if route not in ALL_ROUTES:
        raise ValueError("Unknown benchmark route: " + route)
    args = [
        "--smoke", "--uncapped", "--scene", "--require-iris",
        "--benchmark-ticks", str(benchmark_ticks),
        "--warmup-ticks", str(warmup_ticks),
        "--width", str(width), "--height", str(height),
        "--render-distance", str(render_distance),
        "--simulation-distance", str(simulation_distance),
        "--benchmark-camera", camera,
    ]
    if route == "native-gl":
        args += ["--backend", "OPENGL"]
    elif route == "zink-gl":
        args += ["--zink"]
    elif route == "afv-bridge":
        args += ["--zink-client"]
    elif route == "afv-bridge-sync":
        args += ["--zink-client", "--sync-present"]
    elif route == "afv-native":
        args += ["--zink-client", "--presentation", "native"]
    elif route == "afv-direct":
        args += ["--zink-client", "--shader-backend", "direct"]
    if x11_no_modifiers:
        if route == "native-gl":
            raise ValueError("--x11-no-modifiers applies only to Zink routes")
        args += ["--x11-no-modifiers"]
    for arg in jvm_args or []:
        args += ["--jvm-arg", arg]
    if "--final-output-probe" in args:
        raise AssertionError("Timing runs must not read the framebuffer back")
    return args


def profile_jvm_args(profile, profiler_lib):
    """Return (jvm arguments, note). Spark is never attached automatically."""
    if profile in (None, "none"):
        return [], None
    if profile == "jfr":
        return ["-XX:StartFlightRecording=filename=afv-benchmark.jfr,dumponexit=true,settings=profile"], (
            "JFR is written as afv-benchmark.jfr in the private instance and copied next to each run. "
            "Inspect the render thread in that recording; this summary does not parse it.")
    if profile == "async-profiler":
        if not profiler_lib:
            raise ValueError("--profile async-profiler requires --profiler-lib")
        return [f"-agentpath:{profiler_lib}=start,event=cpu,file=afv-async-profiler.html"], (
            "async-profiler writes afv-async-profiler.html in the private instance. "
            "The summary copies it and does not interpret the stacks.")
    if profile == "spark":
        return [], (
            "Spark is not attached by this harness. Start it manually inside the client if you want a "
            "render-thread sample. This summary has no Spark profile.")
    raise ValueError("Unknown profile: " + profile)


def child_environment(base, display):
    env = dict(base)
    if display:
        env["DISPLAY"] = display
    return env


def parse_dmon(text):
    samples = []
    for line in text.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        date = time_text = None
        numeric = []
        for token in stripped.split():
            if time_text is None and re.fullmatch(r"\d{2}:\d{2}:\d{2}", token):
                time_text = token
            elif date is None and re.fullmatch(r"\d{4}[-/]?\d{2}[-/]?\d{2}", token):
                date = token
            else:
                try:
                    numeric.append(float(token))
                except ValueError:
                    continue
        if not numeric:
            continue
        samples.append({"sm": numeric[1] if len(numeric) >= 2 else numeric[0],
                        "time": time_text, "date": date, "index": len(samples)})
    return samples


def _stamp_ms(date, time_text, fallback_date):
    raw = (date or fallback_date).replace("/", "").replace("-", "")
    year, month, day = int(raw[0:4]), int(raw[4:6]), int(raw[6:8])
    hour, minute, second = (int(part) for part in time_text.split(":"))
    moment = datetime.datetime(year, month, day, hour, minute, second)
    return moment.timestamp() * 1000


def slice_utilization(text, begin_ms, end_ms, process_start_ms, interval_s=1.0, pad_s=1.0):
    samples = parse_dmon(text)
    if not samples or begin_ms is None or end_ms is None:
        return []
    has_time = any(sample["time"] for sample in samples)
    fallback_date = datetime.datetime.fromtimestamp(process_start_ms / 1000).strftime("%Y%m%d")
    window_start = begin_ms - pad_s * 1000
    window_end = end_ms + pad_s * 1000
    chosen = []
    for sample in samples:
        if has_time:
            if not sample["time"]:
                continue
            moment = _stamp_ms(sample["date"], sample["time"], fallback_date)
        else:
            moment = process_start_ms + sample["index"] * interval_s * 1000
        if window_start <= moment <= window_end:
            chosen.append({**sample, "wallMs": moment})
    return chosen


def classify(sm_values):
    values = [value for value in sm_values if isinstance(value, (int, float))]
    if not values:
        return "unknown"
    mean = statistics.fmean(values)
    if mean >= 90:
        return "gpu-bound"
    if mean <= 60:
        return "cpu-or-sync-bound"
    return "mixed"


def validate_run(route, report, *, client_passed, requested_width, requested_height):
    reasons = []
    warnings = []
    if not client_passed:
        reasons.append("client failed")
    if not report:
        reasons.append("missing benchmark")
        return reasons, warnings
    if report.get("vsync") is True:
        reasons.append("vsync enabled")
    limit = report.get("frameLimit")
    if isinstance(limit, (int, float)) and 0 < limit < 260:
        reasons.append(f"frame limit {int(limit)} is capped")
    renderer = str(report.get("renderer") or "")
    lowered = renderer.lower()
    if route == "native-gl":
        for token in ("llvmpipe", "softpipe", "swrast", "zink"):
            if token in lowered:
                reasons.append(f"native GL renderer is {renderer}")
                break
    elif route in ZINK_ROUTES and "zink" not in lowered:
        reasons.append(f"renderer does not report zink: {renderer}")
    if route == "afv-bridge":
        if report.get("asyncPresent") is not True:
            reasons.append("async present was not on")
        if report.get("pipelineDepth") != 0:
            reasons.append("pipeline depth was not 0")
    if route == "afv-bridge-sync" and report.get("asyncPresent") is not False:
        reasons.append("sync route did not disable async present")
    if route == "afv-direct" and str(report.get("shaderBackendRequested") or "").lower() != "direct":
        reasons.append("direct route did not request the direct backend")
    width, height = report.get("width"), report.get("height")
    if width != requested_width or height != requested_height:
        warnings.append(f"framebuffer {width}x{height} differs from requested {requested_width}x{requested_height}")
    fallback = report.get("shaderBackendFallback") or ""
    if route == "afv-direct" and fallback:
        warnings.append(f"fell back to {report.get('shaderBackendActive')}: {fallback}")
    return reasons, warnings


def apply_size_consistency(runs):
    """Invalidate a route whose own runs disagree. Differing valid routes invalidate the comparison."""
    by_route = {}
    for run in runs:
        if run.get("width") is None or run.get("height") is None:
            continue
        if "missing benchmark" in run["reasons"]:
            continue
        by_route.setdefault(run["route"], set()).add((run["width"], run["height"]))
    for route, sizes in by_route.items():
        if len(sizes) > 1:
            for run in runs:
                if run["route"] == route:
                    run["reasons"].append("framebuffer size changed between runs of this route")
                    run["valid"] = False
    sizes = {(run["width"], run["height"]) for run in runs if run["valid"]}
    return len(sizes) <= 1


def _mean(items, key):
    values = [item[key] for item in items if isinstance(item.get(key), (int, float))]
    return statistics.fmean(values) if values else None


def aggregate(runs):
    groups = {}
    for run in runs:
        if run["valid"]:
            groups.setdefault(run["route"], []).append(run)
    result = {}
    for route, items in groups.items():
        result[route] = {
            "runs": len(items),
            "averageFps": _mean(items, "averageFps"),
            "p1FrameMs": _mean(items, "p1FrameMs"),
            "p95FrameMs": _mean(items, "p95FrameMs"),
            "p99FrameMs": _mean(items, "p99FrameMs"),
            "smMean": _mean(items, "smMean"),
            "classification": classify([item.get("smMean") for item in items]),
            "fallback": next((item.get("shaderBackendFallback") or "" for item in items if item.get("shaderBackendFallback")), ""),
            "active": items[0].get("shaderBackendActive"),
            "coverage": items[0].get("shaderBackendCoverage") or "",
        }
    return result


def _gap(later, earlier):
    if not earlier or not later or not earlier.get("averageFps") or not later.get("averageFps"):
        return None
    return (later["averageFps"] - earlier["averageFps"]) / earlier["averageFps"]


def interpret(aggregates, comparison_valid):
    lines = []
    if not comparison_valid:
        lines.append("Framebuffer sizes are not consistent across routes, so FPS differences are not comparable.")
    zink, bridge, native = aggregates.get("zink-gl"), aggregates.get("afv-bridge"), aggregates.get("native-gl")
    direct, sync = aggregates.get("afv-direct"), aggregates.get("afv-bridge-sync")
    gap = _gap(bridge, zink)
    if gap is not None and comparison_valid:
        if abs(gap) <= 0.03:
            lines.append("AFV bridge average FPS is within 3% of GL-on-Zink. On this machine the presentation path is not the bottleneck.")
        else:
            direction = "higher" if gap > 0 else "lower"
            lines.append(
                f"AFV bridge average FPS is {abs(gap) * 100:.1f}% {direction} than GL-on-Zink. "
                "Repeat the harness before treating that gap as a gain; run-to-run noise can exceed a few percent.")
    if comparison_valid and native and zink and bridge and all(item.get("averageFps") for item in (native, zink, bridge)):
        if native["averageFps"] > zink["averageFps"] * 1.15 and native["averageFps"] > bridge["averageFps"] * 1.15:
            lines.append("Native GL is more than 15% faster than both GL-on-Zink and the AFV bridge. That gap is GL translation cost, which presentation changes do not remove.")
    if bridge and bridge.get("classification") == "gpu-bound":
        lines.append("AFV bridge GPU utilization averages at least 90%. The GPU is saturated, so overlapping presentation is unlikely to raise FPS.")
    elif bridge and bridge.get("classification") == "cpu-or-sync-bound":
        lines.append("AFV bridge GPU utilization averages at most 60%. Time is in CPU work or synchronization. This does not by itself say which side.")
    if direct:
        if direct.get("fallback"):
            lines.append(
                f"afv-direct requested the direct backend and fell back ({direct['fallback']}). "
                "Those runs are still the Zink client. A large FPS difference from zink-gl on this build would be a measurement bug.")
        coverage = direct.get("coverage") or ""
        if coverage.startswith("scheduled:"):
            lines.append(
                "afv-direct scheduled Iris programs (" + coverage.removeprefix("scheduled:") + "). "
                "World draws still run on Zink, so compare that route with afv-bridge. A large gap is a measurement bug.")
        direct_gap = _gap(direct, zink)
        if comparison_valid and direct_gap is not None and abs(direct_gap) <= 0.03:
            lines.append("afv-direct matches GL-on-Zink within 3%, which is the expected result until Iris passes run on the direct backend.")
    sync_gap = _gap(bridge, sync)
    if comparison_valid and sync_gap is not None:
        lines.append(f"Async presentation compared with the synchronous bridge is {sync_gap * 100:.1f}% on average FPS (positive means async is faster).")
    if not lines:
        lines.append("Not enough valid routes to classify a bottleneck.")
    return lines


def route_label(run):
    if run["route"] == "afv-direct" and run.get("shaderBackendFallback"):
        return f"afv-direct (fallback, still {run.get('shaderBackendActive') or 'zink'})"
    return run["route"]


def _number(value, digits=2):
    if not isinstance(value, (int, float)):
        return ""
    return f"{value:.{digits}f}"


def render_markdown(summary):
    lines = ["# AFV desktop benchmark", ""]
    meta = summary["meta"]
    lines.append(f"Display: {meta.get('display') or 'inherited'}")
    lines.append(
        f"Requested: {meta['width']}x{meta['height']}, render distance {meta['renderDistance']}, "
        f"simulation distance {meta['simulationDistance']}, rounds {meta['rounds']}, "
        f"benchmark ticks {meta['benchmarkTicks']}, warmup ticks {meta['warmupTicks']}, camera {meta['camera']}")
    lines.append("Nested display: " + ("allowed override" if meta.get("allowNestedDisplay") else "refused" if meta.get("nestedDisplay") else "not detected"))
    if meta.get("profileNote"):
        lines.append(meta["profileNote"])
    if meta.get("gpuNote"):
        lines.append(meta["gpuNote"])
    lines.append("")
    lines.append("Frame intervals are RenderFrameEvent.Post times. They include pacing and presentation, not GPU-only time.")
    lines.append("")
    lines.append("## Runs")
    lines.append("")
    lines.append("| Round | Route | Valid | FPS | p1 ms | p95 ms | p99 ms | SM% | Class | Notes |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
    for run in summary["runs"]:
        notes = "; ".join(run["reasons"] + run["warnings"])
        lines.append("| {round} | {route} | {valid} | {fps} | {p1} | {p95} | {p99} | {sm} | {klass} | {notes} |".format(
            round=run.get("round", ""), route=route_label(run), valid="yes" if run["valid"] else "no",
            fps=_number(run.get("averageFps")), p1=_number(run.get("p1FrameMs")),
            p95=_number(run.get("p95FrameMs")), p99=_number(run.get("p99FrameMs")),
            sm=_number(run.get("smMean"), 1), klass=run.get("classification") or "", notes=notes))
    lines.append("")
    lines.append("## Route means (valid runs)")
    lines.append("")
    lines.append("| Route | Runs | FPS | p1 ms | p95 ms | p99 ms | SM% | Class |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- | --- |")
    for route, item in summary["routes"].items():
        label = route
        if route == "afv-direct" and item.get("fallback"):
            label = f"afv-direct (fallback, still {item.get('active') or 'zink'})"
        lines.append("| {route} | {runs} | {fps} | {p1} | {p95} | {p99} | {sm} | {klass} |".format(
            route=label, runs=item["runs"], fps=_number(item.get("averageFps")),
            p1=_number(item.get("p1FrameMs")), p95=_number(item.get("p95FrameMs")),
            p99=_number(item.get("p99FrameMs")), sm=_number(item.get("smMean"), 1),
            klass=item.get("classification") or ""))
    lines.append("")
    lines.append("## Interpretation")
    lines.append("")
    for line in summary["interpretation"]:
        lines.append("- " + line)
    if not summary["comparisonValid"]:
        lines.append("- The comparison is invalidated. Do not read a route difference as an AFV gain.")
    lines.append("")
    return "\n".join(lines)


def assemble(runs, meta):
    prepared = []
    for run in runs:
        item = dict(run)
        item.setdefault("reasons", [])
        item.setdefault("warnings", [])
        item["valid"] = not item["reasons"]
        prepared.append(item)
    comparison_valid = apply_size_consistency(prepared)
    routes = aggregate(prepared)
    summary = {"meta": meta, "runs": prepared, "routes": routes,
               "comparisonValid": comparison_valid, "interpretation": interpret(routes, comparison_valid)}
    summary["markdown"] = render_markdown(summary)
    return summary
