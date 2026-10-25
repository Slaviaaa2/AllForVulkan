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


def prepare(instance):
    subprocess.run([sys.executable, str(ROOT / 'scripts/prepare_iris_baseline.py')], check=True, cwd=ROOT)
    mods = instance / 'mods'
    mods.mkdir(parents=True, exist_ok=True)
    for jar in (ROOT / 'run-iris-baseline' / 'mods').glob('*.jar'):
        shutil.copy2(jar, mods / jar.name)
    pack = instance / 'shaderpacks' / 'AFV-Shadow'
    if pack.exists():
        shutil.rmtree(pack)
    shutil.copytree(ROOT / 'tests/shaderpacks/AFV-Shadow', pack)
    config = instance / 'config'
    config.mkdir(exist_ok=True)
    (config / 'fml.toml').write_text('earlyWindowControl = false\n')
    (config / 'iris.properties').write_text('enableShaders=true\nshaderPack=AFV-Shadow\n')
    (instance / 'options.txt').write_text(
        'onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:2\n'
        'simulationDistance:2\nenableVsync:false\nmaxFps:20\n')
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


def launch(instance, iris_shadow, timeout):
    display = ensure_display()
    command = [
        './gradlew', 'runClient', '--console=plain',
        '-PafvGameDir=' + str(instance), '-PafvClientHeap=2G',
        '-Dafv.smokeTest=true', '-Dafv.smokeWorld=true', '-Dafv.smokeIris=true',
        '-Dafv.shadowProbe=true', '-Dafv.backend=OPENGL',
        '-Dafv.irisShadow=' + ('true' if iris_shadow else 'false'),
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
    log = instance / ('shadow-on.log' if iris_shadow else 'shadow-off.log')
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
    require_constant(passed[-1])
    return log


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


def compare(off, on, tolerance):
    left_size = read_image(off)
    right_size = read_image(on)
    if left_size[0] != right_size[0] or left_size[1] != right_size[1]:
        raise SystemExit('Shadow resolutions differ: ' + str(left_size[:2]) + ' vs ' + str(right_size[:2]))
    mismatches = center_mismatches(left_size[2], right_size[2], left_size[0], left_size[1], tolerance)
    center = (left_size[0] - left_size[0] // 4 * 2) * (left_size[1] - left_size[1] // 4 * 2)
    print(f'AFV_SHADOW_DIFF: mismatches={mismatches}/{center} tolerance={tolerance}')
    if mismatches:
        raise SystemExit('Shadow center differs by more than the tolerance')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=('off', 'on', 'diff'), default='off')
    parser.add_argument('--instance', type=Path, default=ROOT / 'run-shadow-probe')
    parser.add_argument('--timeout', type=int, default=2700)
    parser.add_argument('--tolerance', type=int, default=8)
    args = parser.parse_args()
    instance = args.instance if args.instance.is_absolute() else ROOT / args.instance
    if args.mode == 'diff':
        compare(instance / 'afv-shadow-off.rgba', instance / 'afv-shadow-on.rgba', args.tolerance)
        return
    prepare(instance)
    launch(instance, args.mode == 'on', args.timeout)


if __name__ == '__main__':
    main()
