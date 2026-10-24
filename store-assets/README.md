# Android store assets (candidate 104)

This directory contains only reproducible Wesh artwork and synthetic store fixtures. It contains no
physical-device screenshot, account, contact, message, identifier, network detail, or location.

## Canonical artwork

`source/WeshAppIcon.svg` is the byte-for-byte local copy of the public iOS Wesh artwork, SHA-256
`bbebcedf6e040de562a0a616bd9864136c6d10e289feeba2942905b84becec91`. The generator validates that
digest before rendering anything. It produces:

- `play/icon-512.png`: opaque 32-bit 512 x 512 Google Play icon;
- `play/fr-FR/feature-graphic.png`: opaque 32-bit 1024 x 500 French feature graphic;
- `play/en-US/feature-graphic.png`: opaque 32-bit 1024 x 500 English feature graphic;
- every legacy launcher PNG and adaptive compatibility PNG under `app/src/main/res/mipmap-*`.

The adaptive XML launchers use the same W geometry. The prior BIT vectors are not retained.

```sh
python3 -m pip install -r tools/store-assets/requirements.txt
python3 tools/store-assets/generate_store_assets.py
python3 tools/store-assets/verify_store_assets.py
```

## Synthetic screenshots

The `storeScreenshot` build type is debug-signed, uses the `.storecapture` application ID suffix,
and loads only `app/src/storeScreenshot/assets/store_scenes.json`. Its dedicated Application does
not initialize identity, mesh, Bluetooth, or Internet. Its manifest removes all data-bearing
device/network permissions, notification and foreground-service permissions, AndroidX's generated
receiver permission, and every product runtime component. Release still applies `FLAG_SECURE`;
only the source-set-exclusive capture Activity can clear it. The merged-manifest gate fails if any
permission or product runtime component reaches the capture APK.

The seven deterministic scenes are, in upload order:

1. home and nearby devices;
2. public local room;
3. verified private chat;
4. QR exchange completion;
5. settings and erase safety;
6. Bluetooth/Internet connection state;
7. about and privacy access.

Capture is deliberately restricted to a dedicated 1080 x 1920 emulator. The script refuses a
physical device and strips ancillary PNG metadata before writing 14 localized screenshots.

Build the isolated APK and validate the manifest produced by Android's merger before capture:

```sh
./gradlew assembleStoreScreenshot
python3 tools/store-assets/verify_store_manifest.py
```

```sh
ANDROID_STORE_CAPTURE_SERIAL=emulator-5554 tools/store-assets/capture_android_store_screenshots.sh
```

If no suitable emulator is available, do not fabricate screenshots. Leave the localized
`phoneScreenshots` directories absent and run the script later on a dedicated emulator.
