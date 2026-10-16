#!/usr/bin/env bash
# Run the untouched upstream phone baseline; keep caches and raw evidence local.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 21 installation}"
: "${ANDROID_HOME:?Set ANDROID_HOME to an SDK containing API 37 and Build Tools 37.0.0}"

validation_dir="$repo_root/.validation"
mkdir -p "$validation_dir/gradle-home" "$validation_dir/android-user-home" "$validation_dir/logs"
export GRADLE_USER_HOME="$validation_dir/gradle-home"
export ANDROID_USER_HOME="$validation_dir/android-user-home"
export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Prevent AGP from installing packages into a preexisting SDK implicitly.
# Additional explicit tasks may be supplied, e.g. :app:lintDebug.
if [[ "$#" -eq 0 ]]; then
    set -- :app:assembleDebug :app:testDebugUnitTest
fi
log_file="$validation_dir/logs/baseline-build-tests.log"
set +e
./gradlew --no-daemon --console=plain -Pandroid.builder.sdkDownload=false "$@" > "$log_file" 2>&1
result=$?
set -e
printf 'Gradle exit code: %s\nRaw log retained at .validation/logs/baseline-build-tests.log\n' "$result"
printf 'Review and redact raw logs before sharing externally.\n'
exit "$result"
