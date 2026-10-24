import contextlib
import importlib.util
import io
import hashlib
import json
from pathlib import Path
import tempfile
import unittest


def load(name):
    path = Path(__file__).resolve().parents[1] / 'scripts' / (name + '.py')
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


prepare = load('prepare_modpack_client').prepare
verify = load('launch_modpack_client').verify_client_mods


class ProfileChecks(unittest.TestCase):
    def test_locked_profile_pins_dependencies_and_records_runtime_override(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, client = root / 'source', root / 'client'
            (source / 'mods').mkdir(parents=True)
            (source / 'mods/a.jar').write_bytes(b'pinned renderer')
            (source / 'mods/unselected.jar').write_bytes(b'cache entry')
            (source / 'overrides/config').mkdir(parents=True)
            (source / 'overrides/config/unwanted.json').write_text('{}')
            lock = root / 'renderer.lock.json'
            contents = {'minecraft': '1.21.1', 'neoforge': '21.1.228', 'mods': [
                {'filename': 'a.jar', 'version': 'fixture', 'sha512': hashlib.sha512(b'pinned renderer').hexdigest()}]}
            lock.write_text(json.dumps(contents))
            with contextlib.redirect_stdout(io.StringIO()):
                prepare(source, client, dependency_lock=lock, neoforge='21.1.251')
            manifest = json.loads((client / '.afv-validation-source.json').read_text())
            self.assertEqual(manifest['profile'], 'locked-mods')
            self.assertEqual(manifest['neoforge'], '21.1.251')
            self.assertEqual(manifest['dependency_lock']['neoforge'], '21.1.228')
            self.assertEqual(manifest['dependency_lock']['mods'], contents['mods'])
            self.assertFalse(manifest['overrides_copied'])
            self.assertFalse((client / 'config').exists())
            self.assertEqual({p.name for p in (client / 'mods').iterdir()}, {'a.jar'})
            verify(client, manifest)
            (source / 'mods/a.jar').write_bytes(b'changed cache')
            rejected = root / 'rejected'
            with self.assertRaisesRegex(ValueError, 'checksum mismatch'):
                prepare(source, rejected, dependency_lock=lock)
            self.assertFalse(rejected.exists())
            with self.assertRaisesRegex(ValueError, 'do not combine'):
                prepare(source, rejected, ['a.jar'], dependency_lock=lock)
            with self.assertRaisesRegex(ValueError, 'requires a dependency lock'):
                prepare(source, rejected, neoforge='21.1.251')

    def test_selected_profile_is_independent_and_excludes_pack_overrides(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, client = root / 'source', root / 'client'
            (source / 'mods').mkdir(parents=True)
            (source / 'mods/a.jar').write_bytes(b'first mod')
            (source / 'mods/b.jar').write_bytes(b'second mod')
            (source / 'overrides/config').mkdir(parents=True)
            (source / 'overrides/config/pack-specific.json').write_text('{}')
            (source / 'pack.json').write_text('{"minecraft":"1.21.1","neoforge":"21.1.251","version":"fixture"}')
            with contextlib.redirect_stdout(io.StringIO()):
                prepare(source, client, ['a.jar'])
            manifest = json.loads((client / '.afv-validation-source.json').read_text())
            self.assertEqual({p.name for p in (client / 'mods').iterdir()}, {'a.jar'})
            self.assertFalse((client / 'config').exists())
            self.assertEqual((manifest['profile'], manifest['source_jar_count'], manifest['jar_count']), ('selected-mods', 2, 1))
            verify(client, manifest)
            (client / 'mods/AllForVulkan-validation.jar').write_bytes(b'owned artifact')
            verify(client, manifest)
            (client / 'mods/extra.jar').write_bytes(b'unrecorded mod')
            with self.assertRaisesRegex(ValueError, 'Unrecorded'):
                verify(client, manifest)
            (client / 'mods/extra.jar').unlink()
            (client / 'mods/a.jar').write_bytes(b'edited private mod')
            self.assertEqual((source / 'mods/a.jar').read_bytes(), b'first mod')
            with self.assertRaisesRegex(ValueError, 'changed'):
                verify(client, manifest)
            for names in (['../mods/a.jar'], ['missing.jar'], ['a.jar', 'a.jar'], []):
                rejected = root / 'rejected'
                with self.assertRaises(ValueError):
                    prepare(source, rejected, names)
                self.assertFalse(rejected.exists())
            with self.assertRaises(ValueError):
                prepare(source, source / 'forbidden', ['a.jar'])

    def test_full_profile_keeps_overrides_and_rejects_missing_mods(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, client = root / 'source', root / 'client'
            (source / 'mods').mkdir(parents=True)
            (source / 'mods/a.jar').write_bytes(b'first mod')
            (source / 'overrides/config').mkdir(parents=True)
            (source / 'overrides/config/pack-specific.json').write_text('{}')
            with contextlib.redirect_stdout(io.StringIO()):
                prepare(source, client)
            manifest = json.loads((client / '.afv-validation-source.json').read_text())
            self.assertEqual(manifest['profile'], 'full-modpack')
            self.assertTrue(manifest['overrides_copied'])
            self.assertTrue((client / 'config/pack-specific.json').is_file())
            verify(client, manifest)
            (client / 'mods/a.jar').unlink()
            with self.assertRaisesRegex(ValueError, 'disappeared'):
                verify(client, manifest)
            with self.assertRaises(FileExistsError):
                prepare(source, client)


if __name__ == '__main__':
    unittest.main()
