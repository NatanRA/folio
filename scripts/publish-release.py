#!/usr/bin/env python3
"""Publish verified local Android and iOS packages as one GitHub release."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

ASSET_NAMES = (
    ('apk', 'Folio-Android.apk'),
    ('ipa', 'Folio-iOS.ipa'),
    # Preserve links shared before the Folio rename.
    ('apk', 'Media-Portal-Android.apk'),
    ('ipa', 'Media-Portal-iOS.ipa'),
)


def run(*args):
    return subprocess.check_output(args, text=True).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag')
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--ipa', required=True, type=Path)
    parser.add_argument('--notes', required=True, type=Path)
    args = parser.parse_args()
    if run('git', 'status', '--porcelain'):
        parser.error('Commit the working tree before publishing.')
    head = run('git', 'rev-parse', 'HEAD')
    remote = run('git', 'ls-remote', 'origin', 'refs/heads/main').split()[0]
    if head != remote:
        parser.error('Push the verified commit to origin/main before publishing.')
    args.notes.read_text()
    for path, required in [(args.apk, 'AndroidManifest.xml'), (args.ipa, 'Payload/MediaPortal.app/Info.plist')]:
        with zipfile.ZipFile(path) as archive:
            if required not in archive.namelist() or archive.testzip() is not None:
                parser.error(f'Invalid package: {path}')
    with tempfile.TemporaryDirectory(prefix='media-release-') as folder:
        folder = Path(folder)
        assets = []
        checksums = []
        expected_sizes = {}
        for kind, name in ASSET_NAMES:
            source = getattr(args, kind)
            destination = folder / name
            shutil.copyfile(source, destination)
            assets.append(str(destination))
            expected_sizes[name] = destination.stat().st_size
            checksums.append(f'{hashlib.sha256(destination.read_bytes()).hexdigest()}  {name}\n')
        checksum_file = folder / 'SHA256SUMS.txt'
        checksum_file.write_text(''.join(checksums))
        expected_sizes[checksum_file.name] = checksum_file.stat().st_size
        # Existing tags/releases are never overwritten. On upload failure, the draft
        # remains private and the previous latest release stays available.
        run('gh', 'release', 'create', args.tag, '--draft', '--target', head,
            '--title', args.tag, '--notes-file', str(args.notes), *assets, str(checksum_file))
        uploaded = json.loads(run('gh', 'release', 'view', args.tag, '--json', 'assets,isDraft'))
        actual_sizes = {asset['name']: asset['size'] for asset in uploaded['assets'] if asset['state'] == 'uploaded'}
        if not uploaded['isDraft'] or any(actual_sizes.get(name) != size for name, size in expected_sizes.items()):
            raise RuntimeError('Release upload verification failed. Draft retained; latest release was not changed.')
        run('gh', 'release', 'edit', args.tag, '--draft=false', '--latest')
        print(run('gh', 'release', 'view', args.tag, '--json', 'url', '--jq', '.url'))


if __name__ == '__main__':
    main()
