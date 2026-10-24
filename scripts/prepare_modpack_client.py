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


def prepare(source, destination, mod_files=None, dependency_lock=None, neoforge=None):
    source, destination = source.resolve(), destination.resolve()
    if destination == source or destination.is_relative_to(source):
        raise ValueError('The client must be outside the source modpack')
    if not (source / 'mods').is_dir():
        raise ValueError('Source has no mods directory')
    available = sorted((source / 'mods').glob('*.jar'))
    locked = None
    if dependency_lock is not None:
        if mod_files is not None:
            raise ValueError('A dependency lock selects its exact mods; do not combine it with mod filenames')
        dependency_lock = dependency_lock.resolve()
        locked = json.loads(dependency_lock.read_text())
        if not locked.get('minecraft') or not locked.get('neoforge') or not locked.get('mods'):
            raise ValueError('Dependency lock must pin Minecraft, NeoForge and mods')
        mod_files = [entry['filename'] for entry in locked['mods']]
    elif neoforge is not None:
        raise ValueError('A runtime NeoForge override requires a dependency lock')
    selected = available
    if mod_files is not None:
        if not mod_files or len(set(mod_files)) != len(mod_files):
            raise ValueError('Selected mod filenames must be nonempty and unique')
        if any(Path(name).name != name or name not in {p.name for p in available} for name in mod_files):
            raise ValueError('Select exact JAR filenames from the source mods directory')
        selected = [source / 'mods' / name for name in sorted(mod_files)]
    if locked is not None:
        for entry in locked['mods']:
            jar = source / 'mods' / entry['filename']
            with jar.open('rb') as stream:
                checksum = hashlib.file_digest(stream, 'sha512').hexdigest()
            if checksum != entry['sha512']:
                raise ValueError('Dependency lock checksum mismatch: ' + entry['filename'])
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
        metadata = locked
        if metadata is None:
            metadata = json.loads((source / 'pack.json').read_text()) if (source / 'pack.json').exists() else {}
        manifest = {'source': str(source), 'client': str(destination), 'minecraft': metadata.get('minecraft'),
                    'neoforge': neoforge or metadata.get('neoforge'), 'pack_version': metadata.get('version'),
                    'jar_count': len(jars), 'jars': jars}
        manifest['profile'] = 'locked-mods' if locked is not None else 'selected-mods' if mod_files is not None else 'full-modpack'
        if locked is not None:
            manifest['dependency_lock'] = {'path': str(dependency_lock), 'sha256': sha256(dependency_lock),
                                           'minecraft': locked['minecraft'], 'neoforge': locked['neoforge'], 'mods': locked['mods']}
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
    parser.add_argument('--dependency-lock', type=Path, help='Select and verify the exact SHA-512-pinned mods from this lock, without modifying the source cache.')
    parser.add_argument('--neoforge', help='Explicit installed runtime version for a locked profile; the original lock version remains recorded.')
    args = parser.parse_args()
    prepare(args.source, args.destination, args.mod_file, args.dependency_lock, args.neoforge)
