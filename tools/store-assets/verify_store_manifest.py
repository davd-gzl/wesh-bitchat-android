#!/usr/bin/env python3
"""Verify the packaged store-capture manifest, never just its source overlay."""

from __future__ import annotations

import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
ANDROID = "{http://schemas.android.com/apk/res/android}"
MERGED_ROOT = ROOT / "app/build/intermediates/merged_manifests/storeScreenshot"
EXPECTED_APPLICATION = "com.bitchat.android.store.StoreCaptureApplication"
EXPECTED_ACTIVITY = "com.bitchat.android.store.StoreScreenshotActivity"
FORBIDDEN_COMPONENTS = {
    "com.bitchat.android.MainActivity",
    "com.bitchat.android.service.MeshForegroundService",
    "com.bitchat.android.service.ConversationNotificationReceiver",
    "com.bitchat.android.service.BootCompletedReceiver",
}


def android_name(element: ET.Element) -> str:
    return element.attrib.get(f"{ANDROID}name", "")


def verify(path: Path) -> None:
    root = ET.parse(path).getroot()
    package_name = root.attrib.get("package", "")
    if not package_name.endswith(".storecapture"):
        raise SystemExit(f"{path}: capture package suffix is missing: {package_name!r}")

    declared_permissions = [
        android_name(element)
        for tag in ("permission", "uses-permission", "uses-permission-sdk-23")
        for element in root.findall(tag)
    ]
    if declared_permissions:
        raise SystemExit(f"{path}: capture APK must declare no permissions: {declared_permissions}")

    application = root.find("application")
    if application is None or android_name(application) != EXPECTED_APPLICATION:
        raise SystemExit(f"{path}: isolated StoreCaptureApplication is missing")
    if application.attrib.get(f"{ANDROID}debuggable") != "true":
        raise SystemExit(f"{path}: capture APK must remain debug-only")

    component_names = {
        android_name(element)
        for tag in ("activity", "activity-alias", "service", "receiver", "provider")
        for element in application.findall(tag)
    }
    leaked = sorted(component_names & FORBIDDEN_COMPONENTS)
    if leaked:
        raise SystemExit(f"{path}: product runtime component leaked into capture APK: {leaked}")
    if EXPECTED_ACTIVITY not in component_names:
        raise SystemExit(f"{path}: StoreScreenshotActivity is missing")


def main() -> None:
    manifests = sorted(MERGED_ROOT.glob("**/AndroidManifest.xml"))
    if not manifests:
        raise SystemExit(
            "merged storeScreenshot manifest not found; run ./gradlew assembleStoreScreenshot first"
        )
    for manifest in manifests:
        verify(manifest)
    print(f"verified {len(manifests)} merged storeScreenshot manifest(s)")


if __name__ == "__main__":
    try:
        main()
    except ET.ParseError as error:
        raise SystemExit(f"invalid merged manifest: {error}") from error
