#!/usr/bin/env python3
"""Validate the production NeoForge bundle without starting Minecraft."""
from io import BytesIO
from pathlib import Path
import json
import sys
import tomllib
from zipfile import ZipFile


def verify(path: Path) -> None:
    with ZipFile(path) as jar:
        names = set(jar.namelist())
        metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode())
        assert metadata['mods'][0]['modId'] == 'allforvulkan', 'Incorrect mod ID'
        assert '${version}' not in jar.read('META-INF/neoforge.mods.toml').decode(), 'Unexpanded version'
        assert 'fabric.mod.json' not in names, 'Fabric entrypoint in NeoForge artifact'
        assert not any(n.startswith('dev/allforvulkan/baseline/') for n in names), 'Comparison fixture leaked into production JAR'
        assert 'afvbaseline.mixins.json' not in names, 'Comparison mixins leaked into production JAR'
        assert 'META-INF/accesstransformer.cfg' in names
        assert 'LICENSE' in names and 'NOTICE.md' in names
        assert 'net/vulkanmod/vulkan/Vulkan.class' in names, 'Renderer missing'
        assert 'dev/allforvulkan/neoforge/AllForVulkan.class' in names, 'NeoForge entrypoint missing'
        for declaration in metadata['mixins']:
            config = json.loads(jar.read(declaration['config']))
            for mixin in config.get('client', []) + config.get('mixins', []):
                entry = config['package'].replace('.', '/') + '/' + mixin.replace('.', '/') + '.class'
                assert entry in names, f'Missing mixin {entry}'
        embedded = json.loads(jar.read('META-INF/jarjar/metadata.json'))['jars']
        ids = [(j['identifier']['group'], j['identifier']['artifact']) for j in embedded]
        assert len(ids) == len(set(ids)), 'Conflicting JarJar dependency IDs'
        paths = {j['path'] for j in embedded}
        for module in ('shaderc', 'vma'):
            for classifier in ('natives-linux', 'natives-windows', 'natives-macos', 'natives-macos-arm64'):
                assert f'META-INF/jarjar/lwjgl-{module}-3.3.3-{classifier}.jar' in paths
        assert 'META-INF/jarjar/lwjgl-vulkan-3.3.3.jar' in paths
        assert any('core-api-' in p for p in paths), 'Shared API missing'
        assert any('native-bridge-' in p for p in paths), 'Native bridge missing'
        assert 'META-INF/jarjar/lwjgl-egl-3.3.3.jar' in paths, 'EGL binding missing'
        for item in embedded:
            with ZipFile(BytesIO(jar.read(item['path']))) as nested:
                entries = nested.namelist()
                if '/lwjgl-' in item['path']:
                    assert not any(p == 'module-info.class' or p.endswith('/module-info.class') for p in entries), item['path']
        print(f'Validated {path}: NeoForge metadata, renderer, mixins, licenses, shared API, {len(embedded)} nested libraries')


if __name__ == '__main__':
    artifacts = [Path(p) for p in sys.argv[1:]] or sorted(Path('build/libs').glob('*.jar'))
    artifacts = [p for p in artifacts if not p.name.endswith('-sources.jar')]
    assert artifacts, 'No production JAR found; run ./gradlew build first'
    for artifact in artifacts:
        verify(artifact)
