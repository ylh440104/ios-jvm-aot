libffi vendored from https://github.com/apple-oss-distributions/libffi

Apple fork of libffi 3.4-rc1, kept unmodified except for added gen-headers.sh.
The darwin/include/fficonfig_arm64.h in the upstream tree documents the iOS
constraint: FFI_EXEC_TRAMPOLINE_TABLE is required because PROT_EXEC is not
available on that target.

Headers are generated at build time by scripts/build-libffi-ios.sh, which
substitutes the autoconf placeholders in include/ffi.h.in for the requested
platform. No configure run is needed.
