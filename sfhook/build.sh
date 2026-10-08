#!/bin/sh
# Builds the KernelSU module zip into sfhook/build/. Needs ANDROID_NDK (r29).
set -e
cd "$(dirname "$0")"
NDK="${ANDROID_NDK:?set ANDROID_NDK}"
CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/clang --target=aarch64-linux-android31"
rm -rf build && mkdir -p build/module/lib build/module/tools
$CC -O2 -Wall -Wextra -fPIC -shared -o build/module/lib/liboulg_sf.so oulg_sf.c -llog -ldl
$CC -O2 -Wall -Wextra -static -o build/module/tools/dtneeded dtneeded.c
cp module/module.prop module/customize.sh module/service.sh build/module/
(cd build/module && zip -qr ../oulg-sf-phase1.zip .)
echo "build/oulg-sf-phase1.zip"
