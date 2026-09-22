import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

spec = importlib.util.spec_from_file_location('publish_release', Path(__file__).with_name('publish-release.py'))
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class PublishReleaseTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk, self.ipa, self.notes = [self.root / name for name in ('app.apk', 'app.ipa', 'notes.md')]
        for path, entry in [(self.apk, 'AndroidManifest.xml'), (self.ipa, 'Payload/MediaPortal.app/Info.plist')]:
            with zipfile.ZipFile(path, 'w') as archive:
                archive.writestr(entry, 'fixture')
        self.notes.write_text('Release notes')
        self.argv = ['publish-release.py', 'v-test', '--apk', str(self.apk), '--ipa', str(self.ipa), '--notes', str(self.notes)]
        self.calls, self.uploads = [], {}
        self.incomplete = False
        self.signer = release.ANDROID_CERTIFICATE_SHA256

    def run_command(self, *args):
        self.calls.append(args)
        if args[0] == 'apksigner':
            return f'Signer #1 certificate SHA-256 digest: {self.signer}'
        if args == ('git', 'status', '--porcelain'):
            return ''
        if args == ('git', 'rev-parse', 'HEAD'):
            return 'abc123'
        if args[:2] == ('git', 'ls-remote'):
            return 'abc123\trefs/heads/main'
        if args[:3] == ('gh', 'release', 'create'):
            self.assertIn('--draft', args)
            for value in args:
                path = Path(value)
                if path.name in {'Folio-Android.apk', 'Folio-iOS.ipa', 'Media-Portal-Android.apk', 'Media-Portal-iOS.ipa', 'SHA256SUMS.txt'}:
                    self.uploads[path.name] = path.read_bytes()
            return ''
        if args[-1] == 'assets,isDraft':
            assets = [{'name': name, 'size': len(data), 'state': 'uploaded'} for name, data in self.uploads.items()]
            if self.incomplete:
                assets = [asset for asset in assets if asset['name'] != 'Folio-iOS.ipa']
            return json.dumps({'isDraft': True, 'assets': assets})
        if args[:3] == ('gh', 'release', 'edit'):
            return ''
        if args[-1] == '.url':
            return 'https://example.test/release'
        raise AssertionError(args)

    def invoke(self):
        with patch.object(sys, 'argv', self.argv), patch.object(release, 'run', self.run_command), patch.object(release, 'apksigner', lambda: 'apksigner'), contextlib.redirect_stdout(io.StringIO()):
            release.main()

    def test_paired_release_preserves_current_and_legacy_links(self):
        self.invoke()
        for name in ('Folio-Android.apk', 'Media-Portal-Android.apk'):
            self.assertEqual(self.uploads[name], self.apk.read_bytes())
        for name in ('Folio-iOS.ipa', 'Media-Portal-iOS.ipa'):
            self.assertEqual(self.uploads[name], self.ipa.read_bytes())
        for line in self.uploads['SHA256SUMS.txt'].decode().splitlines():
            digest, name = line.split('  ')
            self.assertEqual(digest, hashlib.sha256(self.uploads[name]).hexdigest())
        verify = next(i for i, call in enumerate(self.calls) if call[-1] == 'assets,isDraft')
        publish = next(i for i, call in enumerate(self.calls) if call[:3] == ('gh', 'release', 'edit'))
        self.assertLess(verify, publish)

    def test_missing_ipa_upload_does_not_change_latest(self):
        self.incomplete = True
        with self.assertRaisesRegex(RuntimeError, 'latest release was not changed'):
            self.invoke()
        self.assertFalse(any(call[:3] == ('gh', 'release', 'edit') for call in self.calls))

    def test_invalid_ipa_is_rejected_before_creating_release(self):
        self.ipa.write_bytes(b'not an archive')
        with self.assertRaises(zipfile.BadZipFile):
            self.invoke()
        self.assertFalse(any(call[0] == 'gh' for call in self.calls))

    def test_packages_carrying_telegram_credentials_are_refused_before_upload(self):
        for package, entry in [(self.apk, 'assets/telegram-private.json'), (self.ipa, 'Payload/MediaPortal.app/telegram-private.json')]:
            with self.subTest(entry=entry):
                self.calls.clear()
                original = package.read_bytes()
                with zipfile.ZipFile(package, 'a') as archive:
                    archive.writestr(entry, '{"api_id": 1, "api_hash": "0123456789abcdef0123456789abcdef"}')
                with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
                    self.invoke()
                self.assertFalse(any(call[0] == 'gh' for call in self.calls))
                package.write_bytes(original)

    def test_apk_signed_with_another_key_is_refused_before_upload(self):
        # The 0.9.2 APK's debug certificate: an update signed like that will not install over Folio.
        self.signer = 'e3f69267602b6f92d7964589f14fc4330ca190162d2764f6e1dab0f22bf695dd'
        with self.assertRaises(SystemExit), contextlib.redirect_stderr(io.StringIO()):
            self.invoke()
        self.assertFalse(any(call[0] == 'gh' for call in self.calls))

    def test_readme_links_match_uploaded_asset_names(self):
        readme = Path(__file__).resolve().parents[1].joinpath('README.md').read_text()
        for name in ('Folio-Android.apk', 'Folio-iOS.ipa'):
            self.assertIn('/releases/latest/download/' + name, readme)


if __name__ == '__main__':
    unittest.main()
