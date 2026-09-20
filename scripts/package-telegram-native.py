#!/usr/bin/env python3
"""Package locally compiled official TDLib binaries; never reads credentials."""
import argparse
import plistlib
import shutil
import subprocess
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument("work", type=Path, help="TDLib build workspace")
args = parser.parse_args()
work = args.work.resolve()
repo = Path(__file__).resolve().parents[1]
android = repo / "app/src/main/jniLibs/arm64-v8a"
android.mkdir(parents=True, exist_ok=True)
shutil.copy2(work / "tdlib-android/libtdjsonjava.so", android / "libtdjsonjava.so")
frameworks = []
for platform in ("ios", "simulator"):
    build = work / f"tdlib-{platform}"
    framework = work / f"framework-{platform}/TDLib.framework"
    (framework / "Headers").mkdir(parents=True, exist_ok=True)
    (framework / "Modules").mkdir(exist_ok=True)
    shutil.copy2(build / "libtdjson.dylib", framework / "TDLib")
    subprocess.run(["install_name_tool", "-id", "@rpath/TDLib.framework/TDLib", str(framework / "TDLib")], check=True)
    header = (work / "tdlib/td/telegram/td_json_client.h").read_text().replace('"td/telegram/tdjson_export.h"', '"tdjson_export.h"')
    (framework / "Headers/td_json_client.h").write_text(header)
    shutil.copy2(build / "td/telegram/tdjson_export.h", framework / "Headers/tdjson_export.h")
    (framework / "Modules/module.modulemap").write_text('framework module TDLib { header "td_json_client.h"\n export *\n}\n')
    info = dict(CFBundleIdentifier="org.drinkless.TDLib", CFBundleName="TDLib", CFBundleExecutable="TDLib", CFBundlePackageType="FMWK", CFBundleVersion="1", CFBundleShortVersionString="1.8.0", MinimumOSVersion="17.0", CFBundleSupportedPlatforms=["iPhoneOS" if platform == "ios" else "iPhoneSimulator"])
    (framework / "Info.plist").write_bytes(plistlib.dumps(info))
    frameworks.extend(["-framework", str(framework)])
destination = repo / "ios/Frameworks/TDLib.xcframework"
if destination.exists():
    parser.error("TDLib.xcframework already exists; move it aside before repackaging.")
destination.parent.mkdir(parents=True, exist_ok=True)
subprocess.run(["xcodebuild", "-create-xcframework", *frameworks, "-output", str(destination)], check=True)
print("Packaged Android arm64 and iOS arm64 device/simulator libraries. No credentials included.")
