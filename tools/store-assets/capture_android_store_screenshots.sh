#!/usr/bin/env bash
set -euo pipefail

root_dir="$(git rev-parse --show-toplevel)"
cd "$root_dir"

if ! command -v adb >/dev/null 2>&1; then
  echo "adb is unavailable; no screenshots were created." >&2
  exit 2
fi

serial="${ANDROID_STORE_CAPTURE_SERIAL:-}"
if [[ -z "$serial" ]]; then
  online_serials=()
  while IFS= read -r online_serial; do
    online_serials+=("$online_serial")
  done < <(adb devices | awk 'NR > 1 && $2 == "device" { print $1 }')
  if [[ "${#online_serials[@]}" -ne 1 ]]; then
    echo "Set ANDROID_STORE_CAPTURE_SERIAL to one dedicated emulator serial." >&2
    exit 2
  fi
  serial="${online_serials[0]}"
fi

if [[ "$(adb -s "$serial" shell getprop ro.kernel.qemu | tr -d '\r')" != "1" ]]; then
  echo "Refusing capture: $serial is not an emulator. Physical devices and their data are out of scope." >&2
  exit 3
fi

size_line="$(adb -s "$serial" shell wm size | tr -d '\r')"
if [[ "$size_line" != *"1080x1920"* ]]; then
  echo "Use a dedicated 1080x1920 portrait emulator; current wm size: $size_line" >&2
  exit 3
fi

# A fresh emulator otherwise draws Android's immersive-mode tutorial over the
# first captures. A store screenshot with system coaching is invalid, so make
# the isolated emulator acknowledge it before the app is launched.
adb -s "$serial" shell settings put secure immersive_mode_confirmations confirmed >/dev/null

./gradlew :app:assembleStoreScreenshot
apk="app/build/outputs/apk/storeScreenshot/app-universal-storeScreenshot.apk"
if [[ ! -f "$apk" ]]; then
  echo "Store-capture APK not found at $apk" >&2
  exit 4
fi

package_name="org.samouraiworld.wesh.android.storecapture"
activity_name="com.bitchat.android.store.StoreScreenshotActivity"
adb -s "$serial" install -r "$apk" >/dev/null

temporary_dir="$(mktemp -d)"
trap 'rm -rf "$temporary_dir"' EXIT
scenes=(home public private exchange settings connection about)

for language in fr en; do
  if [[ "$language" == "fr" ]]; then
    locale_dir="fr-FR"
  else
    locale_dir="en-US"
  fi
  index=1
  for scene in "${scenes[@]}"; do
    adb -s "$serial" shell am force-stop "$package_name"
    adb -s "$serial" shell am start -W \
      -n "$package_name/$activity_name" \
      --es scene "$scene" \
      --es language "$language" >/dev/null
    sleep 1
    raw="$temporary_dir/$language-$scene.png"
    destination="store-assets/play/$locale_dir/phoneScreenshots/$(printf '%02d' "$index")-$scene.png"
    adb -s "$serial" exec-out screencap -p > "$raw"
    python3 tools/store-assets/sanitize_store_screenshot.py "$raw" "$destination"
    index=$((index + 1))
  done
done

echo "Captured 14 synthetic screenshots from the isolated emulator-only variant."
