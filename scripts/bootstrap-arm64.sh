#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/aot-arm64}"

mkdir -p "$WORK/out" "$WORK/cls" "$WORK/ffi-include"

if [ ! -f "$WORK/asm-9.7.1.jar" ]; then
    M2="${HOME}/.m2/repository/org/ow2/asm"
    for j in asm asm-tree asm-analysis; do
        src=$(find "$M2" -name "$j-9.7.1.jar" 2>/dev/null | head -1)
        if [ -z "$src" ]; then
            src=$(find / -name "$j-9.7.1.jar" 2>/dev/null | head -1)
        fi
        if [ -z "$src" ]; then
            echo "missing $j-9.7.1.jar" >&2
            exit 1
        fi
        cp "$src" "$WORK/"
    done
fi

ASM="$(ls "$WORK"/asm-*.jar | tr '\n' ':' | sed 's/:$//')"

javac -cp "$ASM" -d "$WORK/out" $(find "$ROOT/src/main/java" -name '*.java')
javac -d "$WORK/cls" $(find "$ROOT/testdata" -name '*.java')

sh "$ROOT/vendor/libffi/gen-headers.sh" "$WORK/ffi-include" 0 1

FFI="$ROOT/vendor/libffi"
INC="-I$FFI/src/aarch64 -I$WORK/ffi-include -I$FFI/include -I$FFI/src -I$FFI"
OBJS=""
for f in prep_cif closures types raw_api tramp; do
    clang -O1 -fPIC $INC -c "$FFI/src/$f.c" -o "$WORK/$f.o"
    OBJS="$OBJS $WORK/$f.o"
done
clang -O1 -fPIC $INC -c "$FFI/src/aarch64/ffi.c" -o "$WORK/aarch64-ffi.o"
clang -O1 -fPIC $INC -c "$FFI/src/aarch64/sysv.S" -o "$WORK/aarch64-sysv.o"
OBJS="$OBJS $WORK/aarch64-ffi.o $WORK/aarch64-sysv.o"
ar rcs "$WORK/libffi.a" $OBJS

echo "bootstrap done in $WORK"
echo "now run: $ROOT/scripts/verify-arm64.sh"