#!/usr/bin/env python3
"""Launch a verified private copy using installed production Minecraft/NeoForge.
Reads version/library/assets metadata only; never reads launcher accounts.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import zipfile


def allowed(rules):
    result = not rules
    for rule in rules or []:
        system = rule.get('os', {})
        if system.get('name', 'linux') != 'linux':
            continue
        if system.get('arch', 'x86_64') not in ('amd64', 'x86_64'):
            continue
        if 'version' in system and not re.search(system['version'], os.uname().release):
            continue
        if any(value is not False for value in rule.get('features', {}).values()):
            continue
        result = rule['action'] == 'allow'
    return result


def expand(items, values):
    result = []
    for item in items:
        if isinstance(item, dict):
            if not allowed(item.get('rules')):
                continue
            item = item['value']
        for argument in item if isinstance(item, list) else [item]:
            for key, value in values.items():
                argument = argument.replace('${' + key + '}', str(value))
            if '${' in argument:
                raise ValueError('Unknown installed launcher argument: ' + argument)
            result.append(argument)
    return result


def verify_client_mods(game, manifest):
    expected = {entry['file']: entry for entry in manifest['jars']}
    if len(expected) != len(manifest['jars']):
        raise ValueError('Duplicate source mod names in validation manifest')
    for name, entry in expected.items():
        if Path(name).name != name or not name.endswith('.jar'):
            raise ValueError('Invalid source mod filename in validation manifest')
        jar = game / 'mods' / name
        if not jar.is_file():
            raise ValueError('Verified client mod changed or disappeared: ' + name)
        with jar.open('rb') as stream:
            checksum = hashlib.file_digest(stream, 'sha256').hexdigest()
        if checksum != entry['sha256']:
            raise ValueError('Verified client mod changed or disappeared: ' + name)
    unknown = {p.name for p in (game / 'mods').glob('*.jar')} - expected.keys() - {'AllForVulkan-validation.jar'}
    if unknown:
        raise ValueError('Unrecorded client mods would invalidate the selected profile: ' + ', '.join(sorted(unknown)))


def launch(args):
    if args.iris_cycle and not (args.smoke and args.require_iris):
        raise ValueError('--iris-cycle requires --smoke --require-iris')
    if args.window_cycle and not (args.smoke and args.zink_client):
        raise ValueError('--window-cycle requires --smoke --zink-client')
    if args.dimension_cycle and not (args.smoke and args.require_iris and args.zink_client):
        raise ValueError('--dimension-cycle requires --smoke --require-iris --zink-client')
    if args.dimension_inspect_ticks < 0 or (args.dimension_inspect_ticks and not args.dimension_cycle):
        raise ValueError('--dimension-inspect-ticks requires --dimension-cycle and a nonnegative value')
    game, install = args.instance.resolve(), args.install.resolve()
    manifest_file = game / '.afv-validation-source.json'
    manifest = json.loads(manifest_file.read_text())
    if (game / '.afv-copy-incomplete').exists() or game.is_relative_to(Path(manifest['source']).resolve()):
        raise ValueError('Refusing an incomplete client or the source pack')
    verify_client_mods(game, manifest)
    minecraft, neoforge = manifest['minecraft'], manifest['neoforge']
    vanilla = json.loads((install / f'versions/{minecraft}/{minecraft}.json').read_text())
    profile_name = f'neoforge-{neoforge}'
    forge = json.loads((install / f'versions/{profile_name}/{profile_name}.json').read_text())
    roots = [install / 'libraries', Path.home() / '.minecraft/libraries']

    def library(relative):
        for root in roots:
            candidate = root / relative
            if candidate.is_file():
                return candidate
        raise FileNotFoundError('Installed library missing: ' + relative)

    artifacts = {}
    for profile in (vanilla, forge):
        for lib in profile['libraries']:
            if not allowed(lib.get('rules')):
                continue
            artifact = lib.get('downloads', {}).get('artifact')
            if artifact:
                coordinate = lib['name'].split(':')
                key = ':'.join(coordinate[:2] + coordinate[3:])
                artifacts[key] = library(artifact['path'])
    # These must be located by NeoForge's ProductionClientProvider, not the
    # bootstrap classpath, to avoid duplicate Minecraft module packages.
    for relative in (
        f'net/neoforged/neoforge/{neoforge}/neoforge-{neoforge}-universal.jar',
        f'net/neoforged/neoforge/{neoforge}/neoforge-{neoforge}-client.jar',
    ):
        library(relative)
    natives = game / '.afv-natives'
    natives.mkdir(exist_ok=True)
    for archive in artifacts.values():
        if 'natives-linux' in archive.name:
            with zipfile.ZipFile(archive) as jar:
                for entry in jar.infolist():
                    if entry.filename.endswith('.so'):
                        (natives / Path(entry.filename).name).write_bytes(jar.read(entry))
    assets = install / 'assets'
    index = vanilla['assetIndex']['id']
    if not (assets / 'indexes' / (index + '.json')).is_file():
        assets = Path.home() / '.minecraft/assets'
    if not (assets / 'indexes' / (index + '.json')).is_file():
        raise FileNotFoundError('Installed asset index missing: ' + index)
    jar = args.afv_jar.resolve()
    with zipfile.ZipFile(jar) as archive:
        if b'modId="allforvulkan"' not in archive.read('META-INF/neoforge.mods.toml'):
            raise ValueError('Not an AFV production artifact')
    target = game / 'mods/AllForVulkan-validation.jar'
    if target.name in {item['file'] for item in manifest['jars']}:
        raise ValueError('Refusing to replace a source mod')
    shutil.copy2(jar, target)
    shader_source = args.shader_pack or args.shader_fixture
    if shader_source:
        fixture = shader_source.resolve()
        shaders = game / 'shaderpacks'
        shaders.mkdir(exist_ok=True)
        if fixture.is_dir():
            if not (fixture / 'shaders').is_dir():
                raise ValueError('Fixture must contain shaders/')
            shutil.copytree(fixture, shaders / fixture.name, dirs_exist_ok=True)
        else:
            with zipfile.ZipFile(fixture) as archive:
                if not any(name.startswith('shaders/') for name in archive.namelist()):
                    raise ValueError('Shader ZIP must contain shaders/')
            shutil.copy2(fixture, shaders / fixture.name)
            shader_options = Path(str(fixture) + '.txt')
            if shader_options.is_file():
                shutil.copy2(shader_options, shaders / shader_options.name)
        iris = game / 'config/iris.properties'
        iris.parent.mkdir(parents=True, exist_ok=True)
        content = iris.read_text() if iris.exists() else ''
        backup = game / '.afv-original-iris.properties'
        if not backup.exists():
            backup.write_text(content)
        for key, value in [('enableShaders', 'true'), ('shaderPack', fixture.name)]:
            content = re.sub(r'(?m)^' + key + r'=.*$', key + '=' + value, content)
            if not re.search(r'(?m)^' + key + '=', content):
                content += '\n' + key + '=' + value + '\n'
        iris.write_text(content)
    if args.smoke:
        config = game / 'config/fml.toml'
        config.parent.mkdir(exist_ok=True)
        content = config.read_text() if config.exists() else ''
        content = re.sub(r'(?m)^earlyWindowControl\s*=.*$', 'earlyWindowControl = false', content)
        if not re.search(r'(?m)^earlyWindowControl\s*=', content):
            content += '\nearlyWindowControl = false\n'
        config.write_text(content)
        options = game / 'options.txt'
        lines = options.read_text().splitlines() if options.exists() else []
        lines = [line for line in lines if not line.startswith(('onboardAccessibility:', 'pauseOnLostFocus:'))]
        if args.uncapped:
            lines = [line for line in lines if not line.startswith(('enableVsync:', 'maxFps:'))]
            lines += ['enableVsync:false', 'maxFps:260']
        options.write_text('\n'.join(lines + ['onboardAccessibility:false', 'pauseOnLostFocus:false']) + '\n')
    values = dict(auth_player_name='AFV_QA', version_name=profile_name,
                  game_directory=game, assets_root=assets, assets_index_name=index,
                  auth_uuid='00000000000000000000000000000001', auth_access_token='0',
                  clientid='', auth_xuid='', user_type='legacy', version_type='release',
                  library_directory=install / 'libraries', natives_directory=natives,
                  classpath=os.pathsep.join(map(str, artifacts.values())),
                  classpath_separator=os.pathsep, launcher_name='AFV private validation', launcher_version='1')
    command = [str(args.java_home.resolve() / 'bin/java'), '-Xmx' + args.heap]
    if args.smoke:
        command += ['-Dafv.smokeTest=true', '-Dafv.smokeWorld=true']
    command += [f'-Dafv.smokeInspectTicks={args.inspect_ticks}', f'-Dafv.benchmarkTicks={args.benchmark_ticks}']
    command += [f'-Dafv.benchmarkWarmupTicks={args.warmup_ticks}']
    if args.require_iris:
        command += ['-Dafv.smokeIris=true']
    if args.iris_cycle:
        command += ['-Dafv.smokeIrisCycle=true']
    if args.window_cycle:
        command += ['-Dafv.smokeWindowCycle=true']
    if args.dimension_cycle:
        command += ['-Dafv.smokeDimensions=true']
        command += ['-Dafv.smokeDimensionInspectTicks=' + str(args.dimension_inspect_ticks)]
    if args.scene:
        command += ['-Dafv.smokeScene=true']
    if args.final_output_probe:
        command += ['-Dafv.smokeFinalOutput=true']
    if args.debug_gl:
        command += ['-Dafv.debugGlClient=true']
    if args.mesa_egl_vendor:
        command += ['-Dafv.mesaEglVendor=' + args.mesa_egl_vendor]
    environment = os.environ.copy()
    if args.local_xim:
        environment['XMODIFIERS'] = '@im=none'
    if args.zink:
        if args.backend and args.backend != 'OPENGL':
            raise ValueError('--zink comparison requires OpenGL selection')
        # Explicit GL comparison route; do not call this AFV's native renderer.
        command += ['-Dafv.backend=OPENGL', '-Dafv.validationEgl=true', '-Dafv.expectZink=true']
        environment.update(MESA_LOADER_DRIVER_OVERRIDE='zink', GALLIUM_DRIVER='zink',
                           __EGL_VENDOR_LIBRARY_FILENAMES=args.mesa_egl_vendor or '/usr/share/glvnd/egl_vendor.d/50_mesa.json')
    if args.zink_client:
        if args.backend == 'OPENGL':
            raise ValueError('--zink-client cannot use OpenGL selection')
        command += ['-Dafv.backend=' + (args.backend or 'VULKAN'), '-Dafv.expectZinkClient=true', '-Dafv.expectZink=true']
        # With Iris installed, exercise the same selection as the Vulkan setting.
        # The force flag remains useful for an otherwise vanilla GL client.
        if not args.require_iris:
            command += ['-Dafv.zinkClient=true']
    elif args.backend and not args.zink:
        command += ['-Dafv.backend=' + args.backend]
    if args.x11_no_modifiers:
        if not args.zink:
            raise ValueError('--x11-no-modifiers requires --zink')
        environment['LIBGL_KOPPER_DRI2'] = 'true'
    command += expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'], values)
    command += [forge['mainClass']] + expand(vanilla['arguments']['game'] + forge['arguments']['game'], values)
    command += ['--width', '960', '--height', '540']
    record = {'instance': str(game), 'packVersion': manifest['pack_version'],
              'neoforge': neoforge, 'minecraft': minecraft, 'sourceMods': manifest['jar_count'],
              'profile': manifest.get('profile', 'full-modpack'),
              'mods': [{'file': m['file'], 'sha256': m['sha256']} for m in manifest['jars']],
              'afvSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'route': 'AFV Vulkan presentation with Zink GL client' if args.zink_client else 'OpenGL via Zink comparison' if args.zink else 'Auto; see AFV_BACKEND',
              'benchmarkTicks': args.benchmark_ticks, 'warmupTicks': args.warmup_ticks,
              'uncapped': args.uncapped, 'requireIris': args.require_iris, 'validationScene': args.scene}
    record['debugGlErrors'] = args.debug_gl
    record['finalOutputProbe'] = args.final_output_probe
    record['irisCycle'] = args.iris_cycle
    record['windowCycle'] = args.window_cycle
    record['dimensionCycle'] = args.dimension_cycle
    record['dimensionInspectTicks'] = args.dimension_inspect_ticks
    record['mesaEglVendorOverride'] = args.mesa_egl_vendor
    record['expectedBackend'] = args.expect_backend
    record['requestedBackend'] = args.backend or ('VULKAN' if args.zink_client else 'OPENGL' if args.zink else 'saved setting')
    record['x11NoModifiers'] = args.x11_no_modifiers
    if shader_source:
        record['shaderPack'] = fixture.name
        if fixture.is_file():
            record['shaderSha256'] = hashlib.sha256(fixture.read_bytes()).hexdigest()
            if shader_options.is_file():
                record['shaderOptionsSha256'] = hashlib.sha256(shader_options.read_bytes()).hexdigest()
    (game / '.afv-launch-manifest.json').write_text(json.dumps(record, indent=2) + '\n')
    log = game / 'afv-production-client.log'
    print(f'Production NeoForge {neoforge}, verified copy {game}, log {log}', flush=True)
    with log.open('w') as output:
        with subprocess.Popen(command, cwd=game, env=environment, stdout=output, stderr=subprocess.STDOUT) as process:
            try:
                code = process.wait(timeout=args.timeout)
            except subprocess.TimeoutExpired:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                print('AFV_PRODUCTION_FAIL: timeout', flush=True)
                return 1
    text = log.read_text(errors='replace')
    passed = code == 0 and (not args.smoke or 'AFV_SMOKE_PASS' in text)
    passed = passed and 'Caught error loading resourcepacks' not in text and 'AFV_SMOKE_FAIL' not in text
    if args.zink_client:
        passed = passed and 'AFV_ZINK_CLIENT_PRESENT_PASS' in text
    if args.iris_cycle:
        passed = passed and 'AFV_IRIS_CYCLE_PASS' in text
    if args.window_cycle:
        passed = passed and 'AFV_WINDOW_CYCLE_PASS' in text
    if args.dimension_cycle:
        passed = passed and 'AFV_DIMENSION_CYCLE_PASS' in text
    if args.expect_backend:
        passed = passed and bool(re.search(r'AFV_BACKEND: requested=[A-Z]+, active=' + args.expect_backend + r',', text))
    print(('AFV_PRODUCTION_PASS' if passed else 'AFV_PRODUCTION_FAIL') + f': exit={code} log={log}', flush=True)
    return 0 if passed else 1


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--instance', required=True, type=Path)
    parser.add_argument('--install', required=True, type=Path)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--afv-jar', required=True, type=Path)
    parser.add_argument('--heap', default='6G')
    parser.add_argument('--timeout', type=int, default=600)
    parser.add_argument('--smoke', action='store_true')
    parser.add_argument('--inspect-ticks', type=int, default=0)
    parser.add_argument('--benchmark-ticks', type=int, default=0)
    parser.add_argument('--warmup-ticks', type=int, default=900)
    parser.add_argument('--uncapped', action='store_true', help='Disable VSync and the frame cap in this private smoke client')
    parser.add_argument('--require-iris', action='store_true')
    parser.add_argument('--iris-cycle', action='store_true', help='Disable/re-enable/reload real Iris shaders with an additional resize in the same world')
    parser.add_argument('--window-cycle', action='store_true', help='Iconify/restore the private Vulkan window and switch VSync; requires a real window manager')
    parser.add_argument('--dimension-cycle', action='store_true', help='Transfer the fresh QA world through Nether/End and back with real Iris pipelines')
    parser.add_argument('--dimension-inspect-ticks', type=int, default=0, help='Hold each validated dimension for scoped visual inspection')
    parser.add_argument('--scene', action='store_true', help='Build a shader validation scene in the new smoke world')
    parser.add_argument('--final-output-probe', action='store_true', help='Verify a last default-framebuffer draw reaches the Vulkan shared image')
    parser.add_argument('--debug-gl', action='store_true', help='Log bounded synchronous GL errors for client-bridge diagnosis')
    shader = parser.add_mutually_exclusive_group()
    shader.add_argument('--shader-fixture', type=Path)
    shader.add_argument('--shader-pack', type=Path, help='Shader directory or ZIP; adjacent ZIP.txt options are copied too')
    parser.add_argument('--local-xim', action='store_true')
    renderer = parser.add_mutually_exclusive_group()
    renderer.add_argument('--zink', action='store_true', help='OpenGL comparison through real Zink, AFV native renderer disabled')
    renderer.add_argument('--zink-client', action='store_true', help='Zink GL frontend with AFV-owned Vulkan presentation')
    parser.add_argument('--x11-no-modifiers', action='store_true', help='Zink presentation fallback for nested X11 servers without explicit modifiers')
    parser.add_argument('--mesa-egl-vendor', help='Override the EGL vendor; otherwise AFV discovers installed Mesa metadata')
    parser.add_argument('--expect-backend', choices=['Vulkan', 'OpenGL'], help='Require the actual selected backend, including explicit fallback tests')
    parser.add_argument('--backend', choices=['AUTO', 'VULKAN', 'OPENGL'], help='Exercise an explicit AFV backend selection')
    raise SystemExit(launch(parser.parse_args()))
