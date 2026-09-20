#!/bin/bash
set -euo pipefail
repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
build_dir="$(mktemp -d "${TMPDIR:-/tmp}/media-portal-ios.XXXXXX")"
cd "$repo_dir"
xcodebuild -project ios/MediaPortal.xcodeproj -scheme MediaPortal \
  -configuration Release -destination 'generic/platform=iOS' \
  -derivedDataPath "$build_dir/build" CODE_SIGNING_ALLOWED=NO build
mkdir -p "$build_dir/package/Payload" dist
ditto "$build_dir/build/Build/Products/Release-iphoneos/MediaPortal.app" "$build_dir/package/Payload/MediaPortal.app"
version=$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$build_dir/package/Payload/MediaPortal.app/Info.plist")
ditto -c -k --norsrc --keepParent "$build_dir/package/Payload" "$repo_dir/dist/Folio-iOS-$version-unsigned.ipa"
shasum -a 256 "$repo_dir/dist/Folio-iOS-$version-unsigned.ipa"
