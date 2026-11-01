#!/usr/bin/env python3
"""Download Complementary Reimagined r5.9.3 from the Modrinth API and cache it outside the repo.

The pack is not copied into the git tree. Its license does not allow that.
"""
import hashlib
import json
from pathlib import Path
import urllib.request

PROJECT = 'complementary-reimagined'
VERSION = 'r5.9.3'
API = 'https://api.modrinth.com/v2/project/' + PROJECT + '/version'
CACHE = Path.home() / '.cache' / 'allforvulkan' / 'shaderpacks'
USER_AGENT = 'AllForVulkan/shadow-probe (github.com/Slaviaaa2/AllForVulkan)'


def find_release(versions):
    matches = [version for version in versions if version.get('version_number') == VERSION]
    if len(matches) != 1:
        raise SystemExit('Modrinth did not return exactly one ' + VERSION + ' release')
    files = [item for item in matches[0].get('files', []) if item.get('primary')]
    if len(files) != 1:
        raise SystemExit(VERSION + ' has no single primary file')
    file = files[0]
    hashes = file.get('hashes') or {}
    if not hashes.get('sha512') or not file.get('url') or not file.get('filename'):
        raise SystemExit(VERSION + ' is missing a sha512, url, or filename')
    return file['filename'], file['url'], hashes['sha512']


def cached_pack():
    request = urllib.request.Request(API, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(request, timeout=60) as response:
        versions = json.load(response)
    filename, url, sha512 = find_release(versions)
    CACHE.mkdir(parents=True, exist_ok=True)
    output = CACHE / filename
    if output.exists() and hashlib.sha512(output.read_bytes()).hexdigest() == sha512:
        print('Cached ' + filename + ': SHA-512 matches the Modrinth API')
        return output
    download = urllib.request.Request(url, headers={'User-Agent': USER_AGENT})
    with urllib.request.urlopen(download, timeout=120) as response:
        data = response.read()
    actual = hashlib.sha512(data).hexdigest()
    if actual != sha512:
        raise SystemExit('Checksum mismatch for ' + filename)
    output.write_bytes(data)
    print('Downloaded ' + filename + ': SHA-512 matches the Modrinth API')
    return output


if __name__ == '__main__':
    print(cached_pack())
