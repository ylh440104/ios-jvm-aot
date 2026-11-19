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
TARGET_NAME="${4:-X86_64}"
mkdir -p "$OUT"
sed \
    -e 's/@VERSION@/3.4-rc1/g' \
    -e "s/@TARGET@/$TARGET_NAME/g" \
    -e "s/@HAVE_LONG_DOUBLE@/$LDBL/g" \
    -e "s/@FFI_EXEC_TRAMPOLINE_TABLE@/$TRAMP/g" \
    "$ROOT/include/ffi.h.in" > "$OUT/ffi.h"
cp "$ROOT/include/ffi_common.h" "$OUT/ffi_common.h"
cp "$ROOT/include/ffi_cfi.h" "$OUT/ffi_cfi.h"
{
    printf '#ifndef AOT_FFICONFIG_H\n#define AOT_FFICONFIG_H\n'
    printf '#define EH_FRAME_FLAGS "a"\n'
    printf '#define HAVE_ALLOCA 1\n'
    printf '#define HAVE_ALLOCA_H 1\n'
    printf '#define HAVE_AS_CFI_PSEUDO_OP 1\n'
    printf '#define HAVE_DLFCN_H 1\n'
    printf '#define HAVE_HIDDEN_VISIBILITY_ATTRIBUTE 1\n'
    printf '#define HAVE_INTTYPES_H 1\n'
    printf '#define HAVE_MEMCPY 1\n'
    printf '#define HAVE_MKOSTEMP 1\n'
    printf '#define HAVE_MKSTEMP 1\n'
    printf '#define HAVE_MMAP 1\n'
    printf '#define HAVE_MMAP_ANON 1\n'
    printf '#define HAVE_MMAP_FILE 1\n'
    printf '#define HAVE_RO_EH_FRAME 1\n'
    printf '#define HAVE_STDINT_H 1\n'
    printf '#define HAVE_STDIO_H 1\n'
    printf '#define HAVE_STDLIB_H 1\n'
    printf '#define HAVE_STRING_H 1\n'
    printf '#define HAVE_STRINGS_H 1\n'
    printf '#define HAVE_SYS_MMAN_H 1\n'
    printf '#define HAVE_SYS_STAT_H 1\n'
    printf '#define HAVE_SYS_TYPES_H 1\n'
    printf '#define HAVE_UNISTD_H 1\n'
    printf '#define SIZEOF_DOUBLE 8\n'
    printf '#define SIZEOF_LONG_DOUBLE 8\n'
    printf '#define SIZEOF_SIZE_T 8\n'
    printf '#define STDC_HEADERS 1\n'
    printf '#define SYMBOL_UNDERSCORE 1\n'
    printf '#define PACKAGE "libffi"\n'
    printf '#define PACKAGE_VERSION "3.4-rc1"\n'
    printf '#define VERSION "3.4-rc1"\n'
    if [ "$TRAMP" = "1" ]; then
        printf '#define FFI_EXEC_TRAMPOLINE_TABLE 1\n'
    fi
    printf '#ifdef HAVE_HIDDEN_VISIBILITY_ATTRIBUTE\n'
    printf '#ifdef LIBFFI_ASM\n'
    printf '#ifdef __APPLE__\n'
    printf '#define FFI_HIDDEN(name) .private_extern name\n'
    printf '#else\n'
    printf '#define FFI_HIDDEN(name) .hidden name\n'
    printf '#endif\n'
    printf '#else\n'
    printf '#define FFI_HIDDEN __attribute__ ((visibility ("hidden")))\n'
    printf '#endif\n'
    printf '#else\n'
    printf '#ifdef LIBFFI_ASM\n'
    printf '#define FFI_HIDDEN(name)\n'
    printf '#else\n'
    printf '#define FFI_HIDDEN\n'
    printf '#endif\n'
    printf '#endif\n'
    printf '#endif\n'
} > "$OUT/fficonfig.h"
echo "generated $OUT/ffi.h + fficonfig.h tramp=$TRAMP ldbl=$LDBL"