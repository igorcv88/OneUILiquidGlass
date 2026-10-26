#!/bin/sh
# Builds the SurfaceFlinger binaries into sfhook/build/: the APK bundle (build/apk-assets, picked up by
# app/build.gradle) and the legacy KernelSU module zip (not published). Needs ANDROID_NDK (r29).
set -e
cd "$(dirname "$0")"
NDK="${ANDROID_NDK:?set ANDROID_NDK}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --target=aarch64-linux-android31"
rm -rf build && mkdir -p build/module/lib build/module/tools
$CC -O2 -Wall -Wextra -fPIC -shared -Wl,-Bsymbolic -o build/module/lib/liboulg_sf.so oulg_sf.c -llog -ldl
$CC -O2 -Wall -Wextra -static -o build/module/tools/dtneeded dtneeded.c
# Route A (no module, no mount): the injector, the same library and the root helper, bundled in the
# APK as assets/sfhook/ (docs/sf-live-app.md). live.prop is the manifest the app compares.
mkdir -p build/live
# The injector embeds the binary version, not the commit: rebuilt from unchanged sources it hashes the
# same, so an APK update alone does not ask for a reinstall and a userspace restart.
VERSION="$(sed -n 's/^version=//p' live/version.prop)"
COMMIT="$(git rev-parse --short HEAD 2>/dev/null || echo dev)"
$CC -O2 -Wall -Wextra -static -s -DOULG_VERSION="\"$VERSION\"" -o build/live/oulg_inject inject.c
cp build/module/lib/liboulg_sf.so live/oulg-live.sh live/allowed_fingerprints build/live/
{
  grep -E '^(version|versionCode)=' live/version.prop
  echo "commit=$COMMIT"
  echo "abi=arm64-v8a"
  echo "sha256_inject=$(sha256sum build/live/oulg_inject | cut -d ' ' -f 1)"
  echo "sha256_lib=$(sha256sum build/live/liboulg_sf.so | cut -d ' ' -f 1)"
  echo "sha256_helper=$(sha256sum build/live/oulg-live.sh | cut -d ' ' -f 1)"
} > build/live/live.prop
mkdir -p build/apk-assets/sfhook
cp build/live/* build/apk-assets/sfhook/
cp module/module.prop module/customize.sh module/service.sh module/post-fs-data.sh build/module/
(cd build/module && zip -qr ../oulg-sf.zip .)
echo "build/oulg-sf.zip build/apk-assets/sfhook/"
