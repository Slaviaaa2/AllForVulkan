#!/usr/bin/env python3
"""Prepare an isolated, checksum-pinned Iris comparison; never alter AFV's run/."""
import hashlib
import json
from pathlib import Path
import shutil
import urllib.request

root = Path(__file__).resolve().parents[1]
lock = json.loads((root / 'compatibility/iris-baseline.lock.json').read_text())
instance = root / 'run-iris-baseline'
mods = instance / 'mods'
mods.mkdir(parents=True, exist_ok=True)
expected = {m['filename'] for m in lock['mods']}
unknown = [p.name for p in mods.glob('*.jar') if p.name not in expected]
if unknown:
    raise SystemExit(f'Unexpected mods in isolated Iris baseline: {unknown}; move them before running this fixed comparison')
for entry in lock['mods']:
    output = mods / entry['filename']
    if not output.exists() or hashlib.sha512(output.read_bytes()).hexdigest() != entry['sha512']:
        with urllib.request.urlopen(entry['url'], timeout=60) as response:
            data = response.read()
        if hashlib.sha512(data).hexdigest() != entry['sha512']:
            raise SystemExit(f'Checksum mismatch: {entry["filename"]}')
        output.write_bytes(data)
    print(f'Pinned {entry["filename"]}: SHA-512 verified')
config = instance / 'config'
config.mkdir(exist_ok=True)
(config / 'fml.toml').write_text('earlyWindowControl = false\n')
(config / 'iris.properties').write_text('enableShaders=true\nshaderPack=AFV-Smoke\n')
(instance / 'options.txt').write_text('onboardAccessibility:false\npauseOnLostFocus:false\nrenderDistance:6\nsimulationDistance:5\nenableVsync:false\nmaxFps:260\n')
shutil.copytree(root / 'tests/shaderpacks/AFV-Smoke', instance / 'shaderpacks/AFV-Smoke', dirs_exist_ok=True)
