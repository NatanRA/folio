#!/usr/bin/env python3
"""Install this build's Telegram application credentials for Android and iOS.

Telegram issues an api_id and api_hash to the developer of a client rather than to each
person using it, so they belong to the build. Create an application at
https://my.telegram.org/apps, then run this script. It writes the two untracked files the
apps read at runtime; neither is ever committed.

The values are read from a prompt rather than the command line so they stay out of shell
history. Pass --api-id when scripting a build machine.
"""
import argparse
import getpass
import json
from pathlib import Path
import re
import sys

TARGETS = (
    Path('ios/Resources/telegram-private.json'),
    Path('app/src/main/assets/telegram-private.json'),
)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--api-id', type=int, help='Numeric api_id from my.telegram.org/apps.')
    parser.add_argument('--remove', action='store_true', help='Delete the credentials from this checkout.')
    args = parser.parse_args()

    root = Path(__file__).resolve().parent.parent
    if args.remove:
        for target in TARGETS:
            path = root / target
            if path.exists():
                path.unlink()
                print(f'removed {target}')
        print('Re-run `xcodegen generate --spec ios/project.yml` so the iOS project drops the resource.')
        return 0

    api_id = args.api_id if args.api_id is not None else input('api_id: ').strip()
    try:
        api_id = int(api_id)
    except ValueError:
        parser.error('api_id must be the number shown on my.telegram.org/apps.')
    if api_id <= 0:
        parser.error('api_id must be positive.')

    api_hash = getpass.getpass('api_hash (hidden): ').strip()
    if not re.fullmatch(r'[a-fA-F0-9]{32}', api_hash):
        parser.error('api_hash must be the 32-character hexadecimal value from my.telegram.org/apps.')

    body = json.dumps({'api_id': api_id, 'api_hash': api_hash}, indent=2) + '\n'
    for target in TARGETS:
        path = root / target
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
        path.chmod(0o600)
        print(f'wrote {target}')

    print('\nBoth files are listed in .gitignore and must stay untracked.')
    print('Run `xcodegen generate --spec ios/project.yml` before building iOS,')
    print('and do not commit the resulting project.pbxproj: it will reference the file,')
    print('which breaks a checkout that does not have it.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
