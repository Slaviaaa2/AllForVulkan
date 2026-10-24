#!/usr/bin/env python3
"""Create an isolated, auditable client from a modpack's mods/ and overrides/.
Never edits the source, existing clients, saves or launcher/account data.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def sha256(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def prepare(source, destination, mod_files=None):
    source, destination = source.resolve(), destination.resolve()
    if destination == source or destination.is_relative_to(source):
        raise ValueError('The client must be outside the source modpack')
    if not (source / 'mods').is_dir():
        raise ValueError('Source has no mods directory')
    available = sorted((source / 'mods').glob('*.jar'))
    selected = available
    if mod_files is not None:
        if not mod_files or len(set(mod_files)) != len(mod_files):
            raise ValueError('Selected mod filenames must be nonempty and unique')
        if any(Path(name).name != name or name not in {p.name for p in available} for name in mod_files):
            raise ValueError('Select exact JAR filenames from the source mods directory')
        selected = [source / 'mods' / name for name in sorted(mod_files)]
    destination.mkdir(parents=True, exist_ok=False)
    jars = []
    try:
        if mod_files is None:
            shutil.copytree(source / 'mods', destination / 'mods')
        else:
            (destination / 'mods').mkdir()
            for jar in selected:
                shutil.copy2(jar, destination / 'mods' / jar.name)
        if mod_files is None and (source / 'overrides').is_dir():
            # Resolve symlinks into independent copies; never share writable files.
            shutil.copytree(source / 'overrides', destination, dirs_exist_ok=True)
        for jar in selected:
            checksum = sha256(jar)
            if checksum != sha256(destination / 'mods' / jar.name):
                raise ValueError('Copied mod differs: ' + jar.name)
            jars.append({'file': jar.name, 'bytes': jar.stat().st_size, 'sha256': checksum})
        metadata = json.loads((source / 'pack.json').read_text()) if (source / 'pack.json').exists() else {}
        manifest = {'source': str(source), 'client': str(destination), 'minecraft': metadata.get('minecraft'),
                    'neoforge': metadata.get('neoforge'), 'pack_version': metadata.get('version'),
                    'jar_count': len(jars), 'jars': jars}
        manifest['profile'] = 'selected-mods' if mod_files is not None else 'full-modpack'
        manifest['source_jar_count'] = len(available)
        manifest['overrides_copied'] = mod_files is None and (source / 'overrides').is_dir()
        (destination / '.afv-validation-source.json').write_text(json.dumps(manifest, indent=2) + '\n')
        print(json.dumps({k: v for k, v in manifest.items() if k != 'jars'}, ensure_ascii=False))
    except Exception:
        # Preserve a partial copy for diagnosis, and forbid it from being mistaken for verified.
        (destination / '.afv-copy-incomplete').touch()
        raise


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--source', required=True, type=Path)
    parser.add_argument('--destination', required=True, type=Path)
    parser.add_argument('--mod-file', action='append', help='Create a minimal profile from these exact source JAR filenames; repeat for dependencies. Omits pack overrides.')
    args = parser.parse_args()
    prepare(args.source, args.destination, args.mod_file)
