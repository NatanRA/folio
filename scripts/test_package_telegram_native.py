import contextlib
import io
from pathlib import Path
import plistlib
import runpy
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch


class PackageTelegramNativeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.repo = self.root / 'repo'
        self.work = self.root / 'work'
        self.script = self.repo / 'scripts/package-telegram-native.py'
        self.script.parent.mkdir(parents=True)
        shutil.copy2(Path(__file__).with_name('package-telegram-native.py'), self.script)

    def write(self, relative, data=b'fixture'):
        path = self.work / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def ios_inputs(self):
        self.write('tdlib/td/telegram/td_json_client.h', b'#include "td/telegram/tdjson_export.h"')
        for platform in ('ios', 'simulator'):
            self.write(f'tdlib-{platform}/libtdjson.dylib', platform.encode())
            self.write(f'tdlib-{platform}/td/telegram/tdjson_export.h')

    def invoke(self, platform=None):
        argv = [str(self.script), str(self.work)]
        if platform:
            argv.extend(['--platform', platform])
        with patch.object(sys, 'argv', argv), contextlib.redirect_stdout(io.StringIO()):
            runpy.run_path(str(self.script), run_name='__main__')

    @patch('subprocess.run')
    def test_ios_packages_both_slices_without_android(self, command):
        self.ios_inputs()
        self.invoke('ios')
        self.assertFalse((self.repo / 'app').exists())
        for platform, supported in [('ios', 'iPhoneOS'), ('simulator', 'iPhoneSimulator')]:
            framework = self.work / f'framework-{platform}/TDLib.framework'
            self.assertEqual((framework / 'TDLib').read_bytes(), platform.encode())
            info = plistlib.loads((framework / 'Info.plist').read_bytes())
            self.assertEqual(info['CFBundleSupportedPlatforms'], [supported])
            self.assertIn('"tdjson_export.h"', (framework / 'Headers/td_json_client.h').read_text())
        args = command.call_args.args[0]
        self.assertEqual(args[:2], ['xcodebuild', '-create-xcframework'])
        self.assertEqual(args.count('-framework'), 2)

    @patch('subprocess.run')
    def test_android_does_not_require_xcode_or_ios_outputs(self, command):
        self.write('tdlib-android/libtdjsonjava.so', b'android')
        self.invoke('android')
        self.assertEqual((self.repo / 'app/src/main/jniLibs/arm64-v8a/libtdjsonjava.so').read_bytes(), b'android')
        command.assert_not_called()
        self.assertFalse((self.repo / 'ios').exists())

    @patch('subprocess.run')
    def test_missing_simulator_fails_before_copying_android(self, command):
        self.ios_inputs()
        self.write('tdlib-android/libtdjsonjava.so')
        (self.work / 'tdlib-simulator/libtdjson.dylib').unlink()
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr), self.assertRaises(SystemExit) as error:
            self.invoke()
        self.assertEqual(error.exception.code, 2)
        self.assertIn('tdlib-simulator/libtdjson.dylib', stderr.getvalue())
        self.assertFalse((self.repo / 'app').exists())
        command.assert_not_called()

    @patch('subprocess.run')
    def test_default_still_packages_android_and_ios(self, command):
        self.ios_inputs()
        self.write('tdlib-android/libtdjsonjava.so', b'android')
        self.invoke()
        self.assertEqual((self.repo / 'app/src/main/jniLibs/arm64-v8a/libtdjsonjava.so').read_bytes(), b'android')
        self.assertEqual(command.call_args.args[0].count('-framework'), 2)


if __name__ == '__main__':
    unittest.main()
