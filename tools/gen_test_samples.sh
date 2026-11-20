#!/usr/bin/env bash
# Regenerates the ELF test samples used by :core:elf and :core:archive tests.
# Requires clang + lld (tested with Ubuntu clang 18.1.3). Output is base64 text so the repo stays text-only.
# NOTE: regenerating changes the SHA-256/Build-ID values asserted in ElfParserTest — update them afterwards.
set -euo pipefail
cd "$(dirname "$0")/samples"
OUT=../../core/elf/src/test/resources/elf
TMP=$(mktemp -d)
declare -A ABI=( [arm64]=aarch64-linux-android21 [armv7]=armv7a-linux-androideabi21 [x86_64]=x86_64-linux-android21 [x86]=i686-linux-android21 )
for name in "${!ABI[@]}"; do
  t=${ABI[$name]}
  clang --target=$t -fPIC -shared -nostdlib -fuse-ld=lld -Wl,-soname,libc.so stub.c -o $TMP/stub_$name.so
  clang --target=$t -O1 -fPIC -shared -nostdlib -fuse-ld=lld -Wl,--build-id=sha1 -Wl,-soname,libsample.so \
        -Wl,-z,max-page-size=16384 sample.c $TMP/stub_$name.so -o $TMP/libsample_$name.so
done
clang --target=aarch64-linux-android21 -O1 -fPIC -shared -nostdlib -fuse-ld=lld -Wl,--build-id=sha1 -Wl,-soname,libversioned.so \
      -Wl,--version-script=v.map sample.c $TMP/stub_arm64.so -o $TMP/libversioned_arm64.so
cp $TMP/libsample_arm64.so $TMP/libstripped_arm64.so && llvm-strip --strip-all $TMP/libstripped_arm64.so
for f in $TMP/lib*.so; do base64 -w 76 "$f" > "$OUT/$(basename "$f").b64"; done
sha256sum $TMP/lib*.so
rm -rf "$TMP"
