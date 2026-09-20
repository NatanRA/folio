#!/bin/bash
# Official TDLib, pinned for reproducible Android arm64 and iOS arm64 builds.
# Requires Xcode, cmake, ninja, gperf, git, OpenSSL (host), and Android NDK r27c.
# Usage: bash scripts/build-telegram-native.sh /absolute/build/workspace android|ios|simulator
set -eo pipefail
mkdir -p "$1"
ROOT="$(cd "$1" && pwd)"
PLATFORM="$2"
case "$PLATFORM" in android|ios|simulator) ;; *) echo 'Choose android, ios, or simulator.' >&2; exit 1;; esac
if [[ ! -d "$ROOT/tdlib/.git" ]]; then
  git clone https://github.com/tdlib/td.git "$ROOT/tdlib"
  git -C "$ROOT/tdlib" checkout d1085f9cebc5a62379991ae1652673954f229c1f
fi
[[ "$(git -C "$ROOT/tdlib" rev-parse HEAD)" == d1085f9cebc5a62379991ae1652673954f229c1f ]]
if [[ ! -d "$ROOT/telegram-openssl/.git" ]]; then
  git clone --depth 1 --branch openssl-3.5.2 https://github.com/openssl/openssl.git "$ROOT/telegram-openssl"
fi
if [[ ! -f "$ROOT/tdlib/td/generate/auto/td/telegram/td_api.cpp" ]]; then
  cmake -S "$ROOT/tdlib" -B "$ROOT/tdlib-native" -G Ninja -DTD_GENERATE_SOURCE_FILES=ON -DCMAKE_BUILD_TYPE=Release -DOPENSSL_ROOT_DIR="$(brew --prefix openssl@3)"
  cmake --build "$ROOT/tdlib-native" -j3
fi
PREFIX="$ROOT/openssl-$PLATFORM"
mkdir -p "$PREFIX/build"
cd "$PREFIX/build"
if [[ "$PLATFORM" == android ]]; then
  export ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}/ndk/27.2.12479018}"
  export PATH="$ANDROID_NDK_ROOT/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
  TARGET=android-arm64
  EXTRA=(-D__ANDROID_API__=26)
else
  TARGET=ios64-xcrun
  [[ "$PLATFORM" == simulator ]] && TARGET=iossimulator-arm64-xcrun
  EXTRA=(-miphoneos-version-min=17.0)
  [[ "$PLATFORM" == simulator ]] && EXTRA=(-mios-simulator-version-min=17.0)
fi
if [[ ! -f "$PREFIX/lib/libcrypto.a" ]]; then
  "$ROOT/telegram-openssl/Configure" "$TARGET" no-shared no-tests --prefix="$PREFIX" --libdir=lib "${EXTRA[@]}"
  make -j3 build_libs
  make install_dev
fi
OPENSSL=(-DOPENSSL_ROOT_DIR="$PREFIX" -DOPENSSL_USE_STATIC_LIBS=ON -DOPENSSL_INCLUDE_DIR="$PREFIX/include" -DOPENSSL_CRYPTO_LIBRARY="$PREFIX/lib/libcrypto.a" -DOPENSSL_SSL_LIBRARY="$PREFIX/lib/libssl.a")
if [[ "$PLATFORM" == android ]]; then
  cmake -S "$ROOT/tdlib/example/android" -B "$ROOT/tdlib-android" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$ANDROID_NDK_ROOT/build/cmake/android.toolchain.cmake" \
    -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 -DANDROID_STL=c++_static -DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON \
    -DTD_ANDROID_JSON_JAVA=ON -DCMAKE_BUILD_TYPE=Release "${OPENSSL[@]}"
  cmake --build "$ROOT/tdlib-android" --target tdjni -j3
else
  SDK=iphoneos
  [[ "$PLATFORM" == simulator ]] && SDK=iphonesimulator
  cmake -S "$ROOT/tdlib" -B "$ROOT/tdlib-$PLATFORM" -G Ninja \
    -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT="$(xcrun --sdk "$SDK" --show-sdk-path)" \
    -DCMAKE_OSX_ARCHITECTURES=arm64 -DCMAKE_OSX_DEPLOYMENT_TARGET=17.0 \
    -DCMAKE_BUILD_TYPE=Release "${OPENSSL[@]}"
  cmake --build "$ROOT/tdlib-$PLATFORM" --target tdjson -j3
fi
