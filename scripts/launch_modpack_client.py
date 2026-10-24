#!/usr/bin/env python3
"""Launch a verified private copy using installed production Minecraft/NeoForge.
Reads version/library/assets metadata only; never reads launcher accounts.
"""
import argparse
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


def launch(args):
    game, install = args.instance.resolve(), args.install.resolve()
    manifest_file = game / '.afv-validation-source.json'
    manifest = json.loads(manifest_file.read_text())
    if (game / '.afv-copy-incomplete').exists() or game.is_relative_to(Path(manifest['source']).resolve()):
        raise ValueError('Refusing an incomplete client or the source pack')
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
    environment = os.environ.copy()
    if args.local_xim:
        environment['XMODIFIERS'] = '@im=none'
    if args.zink:
        # Explicit GL comparison route; do not call this AFV's native renderer.
        command += ['-Dafv.backend=OPENGL', '-Dafv.validationEgl=true', '-Dafv.expectZink=true']
        environment.update(MESA_LOADER_DRIVER_OVERRIDE='zink', GALLIUM_DRIVER='zink',
                           __EGL_VENDOR_LIBRARY_FILENAMES=args.mesa_egl_vendor)
    command += expand(vanilla['arguments']['jvm'] + forge['arguments']['jvm'], values)
    command += [forge['mainClass']] + expand(vanilla['arguments']['game'] + forge['arguments']['game'], values)
    command += ['--width', '960', '--height', '540']
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
    parser.add_argument('--local-xim', action='store_true')
    parser.add_argument('--zink', action='store_true', help='OpenGL comparison through real Zink, AFV native renderer disabled')
    parser.add_argument('--mesa-egl-vendor', default='/usr/share/glvnd/egl_vendor.d/50_mesa.json')
    raise SystemExit(launch(parser.parse_args()))
