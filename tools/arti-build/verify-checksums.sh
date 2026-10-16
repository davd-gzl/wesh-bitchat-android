#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
# shellcheck source=provenance.sh
source "$SCRIPT_DIR/provenance.sh"
reject_unsupported_patches "$SCRIPT_DIR"
CHECKSUM_FILE="$SCRIPT_DIR/SHA256SUMS"
SOURCE_CHECKSUM_FILE="$SCRIPT_DIR/SOURCE_SHA256SUMS"

if [ ! -f "$CHECKSUM_FILE" ]; then
  echo "error: missing native checksum manifest: $CHECKSUM_FILE" >&2
  exit 1
fi

if [ ! -f "$SOURCE_CHECKSUM_FILE" ]; then
  echo "error: missing native source checksum manifest; rebuild all four Arti ABIs" >&2
  exit 1
fi

cd "$PROJECT_ROOT"

expected_sources=$(printf 'tools/arti-build/%s\n' "${NATIVE_SOURCE_INPUTS[@]}" | sort)
expected_libraries=$(printf 'app/src/main/jniLibs/%s/libarti_android.so\n' arm64-v8a armeabi-v7a x86 x86_64 | sort)
actual_sources=$(awk '{print $2}' "$SOURCE_CHECKSUM_FILE" | sort)
actual_libraries=$(awk '{print $2}' "$CHECKSUM_FILE" | sort)
if [ "$actual_sources" != "$expected_sources" ] || [ "$actual_libraries" != "$expected_libraries" ]; then
  echo "error: incomplete or unexpected native provenance entries; rebuild all four Arti ABIs" >&2
  exit 1
fi

if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c "$SOURCE_CHECKSUM_FILE"
  sha256sum -c "$CHECKSUM_FILE"
else
  shasum -a 256 -c "$SOURCE_CHECKSUM_FILE"
  shasum -a 256 -c "$CHECKSUM_FILE"
fi

for library in app/src/main/jniLibs/*/libarti_android.so; do
  assert_no_host_paths "$library"
done

echo "Native Arti libraries and build inputs match the pinned checksums and contain no host paths."
