#!/bin/sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="${WORK:-/tmp/aot-arm64}"
CP="$WORK/out"
CLS="$WORK/cls"
ASM="$(ls "$WORK"/asm-*.jar 2>/dev/null | tr '\n' ':' | sed 's/:$//')"

if [ ! -d "$CLS" ]; then
    echo "missing $CLS, run bootstrap first" >&2
    exit 1
fi

FFIHDR="${FFIHDR:-$WORK/ffi-include}"
FFILIB="${FFILIB:-$WORK/libffi.a}"

PASS=0
FAIL=""

run_case() {
    t="$1"
    exp="$2"
    java -cp "$CP:$ASM" dev.cmc.aot.Main -cp "$CLS" -entry "$t" -o "$WORK/$t.ll" >/dev/null 2>&1
    clang -O1 "$WORK/$t.ll" "$ROOT/src/main/c/runtime.c" "$FFILIB" -I"$FFIHDR" \
        -o "$WORK/$t.bin" -lpthread -ldl 2>/dev/null
    timeout 40 "$WORK/$t.bin" > "$WORK/$t.out" 2>&1 || true
    if [ -n "$exp" ]; then
        printf '%s' "$exp" | tr ' ' '\n' > "$WORK/$t.exp"
        if diff -q "$WORK/$t.exp" "$WORK/$t.out" >/dev/null 2>&1; then
            PASS=$((PASS + 1))
        else
            FAIL="$FAIL $t"
        fi
        return
    fi
    (cd "$CLS" && timeout 40 java "$t") > "$WORK/$t.jvm" 2>&1 || true
    if diff -q "$WORK/$t.jvm" "$WORK/$t.out" >/dev/null 2>&1; then
        PASS=$((PASS + 1))
    else
        FAIL="$FAIL $t"
    fi
}

for t in Hello ObjTest ArraysTest StaticsTest RefsTest NumericTest TypesTest ExcTest \
         RangeTest EdgeTest CheckTest GcTest StrTest SbTest CollTest ThreadTest \
         ThreadGc IndyTest LambdaTest ReflectTest Reflect2 Reflect3 Reflect4 \
         Reflect5 Reflect6 Reflect7 Reflect8 Reflect9 Reflect10 Reflect11 Str2 \
         Arr6 Fmt1 Wait1 Wait2 Type1; do
    run_case "$t" ""
done

run_case Buf1 "1 32 0 32 32 12345 -7 0 1 2 65 66 2 0 2 2 0 4 2 0 done"
run_case Buf5 "1 1 wrote 1 16 3 4 3 0 done"
run_case Ffi2 "1 1 1 123 456 1 0"
run_case Ffi3 "1 1 1 1 0 4.000 1024.000 2.000 3.000 -56 4464 done"
run_case Buf9 "1 1 1 5 10 20 30 40 50 done"
run_case Mem1 "1 32 1 1234567890 42 -7 65535 done"

echo "ARM64 PASS=$PASS FAIL=${FAIL:-none}"
[ -z "$FAIL" ]