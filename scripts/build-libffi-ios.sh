#!/bin/sh
set -e
if [ $# -lt 3 ]; then
    echo "usage: $0 <sdk:simulator|device> <ios-version> <out-dir>" >&2
    exit 1
fi
SDK_KIND="$1"
IOS_VER="$2"
OUT="$3"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FFI="$ROOT/vendor/libffi"

mkdir -p "$OUT"
HDR="$OUT/ffi-include"

if [ "$SDK_KIND" = "simulator" ]; then
    TARGET="arm64-apple-ios${IOS_VER}-simulator"
    SDKNAME="iphonesimulator"
    TRAMP=0
else
    TARGET="arm64-apple-ios${IOS_VER}"
    SDKNAME="iphoneos"
    TRAMP=1
fi

sh "$FFI/gen-headers.sh" "$HDR" "$TRAMP" 0 AARCH64

SDKPATH=$(xcrun --sdk "$SDKNAME" --show-sdk-path)
CFLAGS="-target $TARGET -isysroot $SDKPATH -O2 -fPIC -DFFI_EXEC_TRAMPOLINE_TABLE=$TRAMP"
INC="-I$FFI/src/aarch64 -I$HDR -I$FFI/include -I$FFI/src -I$FFI"

OBJS=""
for f in prep_cif closures debug types raw_api tramp java_raw_api; do
    o="$OUT/$f.o"
    xcrun --sdk "$SDKNAME" clang $CFLAGS $INC -c "$FFI/src/$f.c" -o "$o"
    OBJS="$OBJS $o"
done
for f in aarch64/ffi; do
    o="$OUT/aarch64-ffi.o"
    xcrun --sdk "$SDKNAME" clang $CFLAGS $INC -c "$FFI/src/$f.c" -o "$o"
    OBJS="$OBJS $o"
done
for f in aarch64/sysv aarch64/trampoline; do
    o="$OUT/$(echo "$f" | tr '/' '-').o"
    xcrun --sdk "$SDKNAME" clang $CFLAGS $INC -c "$FFI/src/$f.S" -o "$o"
    OBJS="$OBJS $o"
done

xcrun --sdk "$SDKNAME" ar rcs "$OUT/libffi.a" $OBJS
echo "built $OUT/libffi.a for $TARGET"
xcrun --sdk "$SDKNAME" lipo -info "$OUT/libffi.a"