#!/bin/sh
set -e
if [ $# -lt 1 ]; then
    echo "usage: $0 <out-dir> <tramp-table:0|1> [long-double:0|1]" >&2
    exit 1
fi
OUT="$1"
TRAMP="${2:-0}"
LDBL="${3:-1}"
ROOT="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$OUT"
sed \
    -e 's/@VERSION@/3.4-rc1/g' \
    -e 's/@TARGET@/AOT_LIBFFI_CONFIG/g' \
    -e "s/@HAVE_LONG_DOUBLE@/$LDBL/g" \
    -e "s/@FFI_EXEC_TRAMPOLINE_TABLE@/$TRAMP/g" \
    "$ROOT/include/ffi.h.in" > "$OUT/ffi.h"
cp "$ROOT/include/ffi_common.h" "$OUT/ffi_common.h"
cp "$ROOT/include/ffi_cfi.h" "$OUT/ffi_cfi.h"
echo "generated $OUT/ffi.h tramp=$TRAMP ldbl=$LDBL"