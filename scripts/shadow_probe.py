#!/usr/bin/env python3
"""Launch a minimal NeoForge client under Xvfb and read Iris shadowcolor0.

This is a correctness harness, not an FPS measurement. Xvfb plus llvmpipe is
expected to be slow. The Iris shadow hook stays off unless --iris-shadow is set.
"""
import argparse
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
MAGIC = 0x53564641


def write_image(path, width, height, rgba):
    path.write_bytes(struct.pack('<III', MAGIC, width, height) + rgba)


def read_image(path):
    data = Path(path).read_bytes()
    magic, width, height = struct.unpack_from('<III', data)
    if magic != MAGIC or len(data) != 12 + width * height * 4:
        raise ValueError('Not an AFV shadow image: ' + str(path))
    return width, height, data[12:]


def near_mismatches(left, right, width, height, tolerance):
    """A pixel matches when any texel within one step is inside the channel tolerance."""
    if len(left) != len(right) or len(left) != width * height * 4:
        raise ValueError('Shadow images differ in size')
    mismatches = 0
    for y in range(height):
        for x in range(width):
            index = (y * width + x) * 4
            if not any(
                0 <= x + dx < width and 0 <= y + dy < height and all(
                    abs(left[index + channel] - right[((y + dy) * width + x + dx) * 4 + channel]) <= tolerance
                    for channel in range(4))
                for dy in (-1, 0, 1) for dx in (-1, 0, 1)):
                mismatches += 1
    return mismatches


def center_mismatches(left, right, width, height, tolerance):
    if len(left) != len(right) or len(left) != width * height * 4:
        raise ValueError('Shadow images differ in size')
    x0, y0 = width // 4, height // 4
    x1, y1 = width - x0, height - y0
    mismatches = 0
    for y in range(y0, y1):
        for x in range(x0, x1):
            index = (y * width + x) * 4
            if any(abs(left[index + channel] - right[index + channel]) > tolerance for channel in range(4)):
                mismatches += 1
    return mismatches


def prepare(instance, mesh):
    pack_name = 'AFV-Shadow-Mesh' if mesh else 'AFV-Shadow'
    subprocess.run([sys.executable, str(ROOT / 'scripts/prepare_iris_baseline.py')], check=True, cwd=ROOT)
    mods = instance / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    for jar in (ROOT / 'run-iris-baseline' / 'mods').glob('*.jar'):
        shutil.copy2(jar, mods / jar.name)
    pack = instance / 'shaderpacks' / pack_name
    if pack.exists():
        shutil.rmtree(pack)
    shutil.copytree(ROOT / 'tests/shaderpacks' / pack_name, pack)
    legacy = instance / 'shaderpacks' / ('AFV-Shadow' if mesh else 'AFV-Shadow-Mesh')
    if legacy.exists():
        shutil.rmtree(legacy)
    config = instance / 'config'
    config.mkdir(exist_ok=True)
    (config / 'fml.toml').write_text('earlyWindowControl = false\n')
    (config / 'iris.properties').write_text('enableShaders=true\nshaderPack=' + pack_name + '\n')
    (instance / 'options.txt').write_text(
        'onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:8\n'
        'simulationDistance:5\nenableVsync:false\nmaxFps:20\n')
    marker = config / 'allforvulkan_renderer_starting'
    if marker.exists():
        marker.unlink()


def ensure_display():
    socket = Path('/tmp/.X11-unix/X99')
    if socket.exists():
        return None
    process = subprocess.Popen(
        ['Xvfb', ':99', '-screen', '0', '640x480x24', '-ac'],
        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    for _ in range(50):
        if socket.exists():
            return process
        if process.poll() is not None:
            raise RuntimeError('Xvfb exited before opening :99')
        time.sleep(0.1)
    raise RuntimeError('Xvfb did not open :99')


def launch(instance, iris_shadow, force_fallback, mesh, timeout):
    display = ensure_display()
    command = [
        './gradlew', 'runClient', '--console=plain',
        '-PafvGameDir=' + str(instance), '-PafvClientHeap=2G',
        '-Dafv.smokeTest=true', '-Dafv.smokeWorld=true', '-Dafv.smokeIris=true',
        '-Dafv.shadowProbe=true', '-Dafv.backend=OPENGL',
        '-Dafv.irisShadow=' + ('true' if iris_shadow else 'false'),
        '-Dafv.irisShadowForceFallback=' + ('true' if force_fallback else 'false'),
        '-Dafv.shadowMesh=' + ('true' if mesh else 'false'),
    ]
    environment = dict(**{key: value for key, value in __import__('os').environ.items()})
    environment.update({
        'DISPLAY': ':99',
        'LIBGL_ALWAYS_SOFTWARE': '1',
        'GALLIUM_DRIVER': 'llvmpipe',
        'VK_ICD_FILENAMES': '/usr/share/vulkan/icd.d/lvp_icd.json',
        'XDG_RUNTIME_DIR': '/tmp/runtime-afv',
    })
    Path('/tmp/runtime-afv').mkdir(exist_ok=True)
    log_name = 'shadow-fallback.log' if force_fallback else 'shadow-on.log' if iris_shadow else 'shadow-off.log'
    log = instance / log_name
    instance.mkdir(parents=True, exist_ok=True)
    try:
        with log.open('w') as handle:
            completed = subprocess.run(
                command, cwd=ROOT, env=environment, stdout=handle, stderr=subprocess.STDOUT,
                timeout=timeout, check=False)
    finally:
        if display is not None and display.poll() is None:
            display.terminate()
    text = log.read_text(errors='replace')
    passed = [line for line in text.splitlines() if 'AFV_SHADOW_PROBE_PASS' in line]
    if completed.returncode != 0 or not passed:
        tail = '\n'.join(text.splitlines()[-80:])
        raise SystemExit('Shadow probe failed with exit ' + str(completed.returncode) + '\n' + tail)
    print(passed[-1])
    if mesh:
        require_varied(passed[-1])
        if iris_shadow and not force_fallback and 'AFV_IRIS_SHADOW_MESH' not in text:
            raise SystemExit('Vulkan did not replay a Sodium shadow mesh')
    else:
        require_constant(passed[-1])
    if 'AFV_IRIS_SHADOW_HOOK' not in text:
        extra = '\n'.join(line for line in text.splitlines() if 'AFV_IRIS_SHADOW' in line)
        raise SystemExit('Iris renderShadows hook did not run\n' + extra)
    if force_fallback:
        if 'AFV_IRIS_SHADOW_FALLBACK' not in text or 'AFV_IRIS_SHADOW_PASS' in text:
            raise SystemExit('Forced fallback did not leave Iris\'s shadow image in place')
    elif iris_shadow and 'AFV_IRIS_SHADOW_PASS' not in text:
        raise SystemExit('Vulkan shadow pass did not run:\n' + '\n'.join(line for line in text.splitlines() if 'AFV_IRIS_SHADOW' in line))
    elif not iris_shadow and 'AFV_IRIS_SHADOW_PASS' in text:
        raise SystemExit('The shadow hook ran while afv.irisShadow was off')
    return log


def require_varied(line):
    """AFV-Shadow-Mesh is 32x32 and the wool columns make the shadow map more than one color."""
    import re
    size = re.search(r'width=(\d+) height=(\d+)', line)
    distinct = re.search(r'distinct=(\d+)', line)
    if not size or not distinct:
        raise SystemExit('Shadow probe pass line has no size or distinct count: ' + line)
    width, height = int(size.group(1)), int(size.group(2))
    if (width, height) != (32, 32):
        raise SystemExit('Shadow map is ' + str(width) + 'x' + str(height) + ', expected 32x32: ' + line)
    if int(distinct.group(1)) < 2:
        raise SystemExit('Shadow map is a constant color: ' + line)


def require_constant(line):
    """AFV-Shadow clears shadowcolor0 to 128,64,192,255 at 32x32. A white 1024 map is the Iris default."""
    import re
    size = re.search(r'width=(\d+) height=(\d+)', line)
    center = re.search(r'center=(\d+),(\d+),(\d+),(\d+)', line)
    if not size or not center:
        raise SystemExit('Shadow probe pass line has no size or center: ' + line)
    width, height = int(size.group(1)), int(size.group(2))
    if (width, height) != (32, 32):
        raise SystemExit('Shadow map is ' + str(width) + 'x' + str(height) + ', expected 32x32: ' + line)
    expected = (128, 64, 192, 255)
    actual = tuple(int(center.group(channel)) for channel in range(1, 5))
    if any(abs(actual[channel] - expected[channel]) > 8 for channel in range(4)):
        raise SystemExit('Shadow center ' + str(actual) + ' is outside tolerance of ' + str(expected))


def compare(off, on, tolerance, mesh):
    left_size = read_image(off)
    right_size = read_image(on)
    if left_size[0] != right_size[0] or left_size[1] != right_size[1]:
        raise SystemExit('Shadow resolutions differ: ' + str(left_size[:2]) + ' vs ' + str(right_size[:2]))
    if mesh:
        mismatches = near_mismatches(left_size[2], right_size[2], left_size[0], left_size[1], tolerance)
        total = left_size[0] * left_size[1]
    else:
        mismatches = center_mismatches(left_size[2], right_size[2], left_size[0], left_size[1], tolerance)
        total = (left_size[0] - left_size[0] // 4 * 2) * (left_size[1] - left_size[1] // 4 * 2)
    print(f'AFV_SHADOW_DIFF: mismatches={mismatches}/{total} tolerance={tolerance} mesh={mesh}')
    if mismatches:
        raise SystemExit('Shadow image differs by more than the tolerance')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('off', 'on', 'diff', 'fallback'), default='off')
    parser.add_argument('--pack', choices=('constant', 'mesh'), default='constant')
    parser.add_argument('--instance', type=Path)
    parser.add_argument('--timeout', type=int, default=2700)
    parser.add_argument('--tolerance', type=int, default=8)
    args = parser.parse_args()
    instance = args.instance or (ROOT / ('run-shadow-mesh' if args.pack == 'mesh' else 'run-shadow-probe'))
    instance = instance if instance.is_absolute() else ROOT / instance
    mesh = args.pack == 'mesh'
    if args.mode == 'diff':
        compare(instance / 'afv-shadow-off.rgba', instance / 'afv-shadow-on.rgba', args.tolerance, mesh)
        compare(instance / 'afv-shadow-off.rgba', instance / 'afv-shadow-fallback.rgba', args.tolerance, mesh)
        return
    prepare(instance, mesh)
    launch(instance, args.mode in ('on', 'fallback'), args.mode == 'fallback', mesh, args.timeout)


if __name__ == '__main__':
    main()
