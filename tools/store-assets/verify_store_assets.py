#!/usr/bin/env python3
"""Fail closed when a generated store asset or synthetic fixture drifts."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[2]
SCENES = ["home", "public", "private", "exchange", "settings", "connection", "about"]


def verify_png(relative: str, size: tuple[int, int], opaque: bool) -> None:
    path = ROOT / relative
    with Image.open(path) as image:
        if image.size != size:
            raise SystemExit(f"{relative}: expected {size}, got {image.size}")
        if image.mode != "RGBA":
            raise SystemExit(f"{relative}: expected 32-bit RGBA, got {image.mode}")
        if opaque and image.getchannel("A").getextrema() != (255, 255):
            raise SystemExit(f"{relative}: transparency is not allowed")
        if image.info:
            raise SystemExit(f"{relative}: metadata is not allowed: {sorted(image.info)}")


def verify_fixtures() -> None:
    relative = "app/src/storeScreenshot/assets/store_scenes.json"
    text = (ROOT / relative).read_text(encoding="utf-8")
    data = json.loads(text)
    if data.get("synthetic") is not True or data.get("sceneOrder") != SCENES:
        raise SystemExit(f"{relative}: missing synthetic declaration or canonical seven-scene order")
    if set(data) != {"schema", "synthetic", "provenance", "sceneOrder", "fr", "en"}:
        raise SystemExit(f"{relative}: unexpected top-level fixture data")
    forbidden = {
        "email": re.compile(r"[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}"),
        "phone": re.compile(r"(?:\+|00)\d[\d .-]{7,}"),
        "ipv4": re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b"),
        "url": re.compile(r"https?://", re.IGNORECASE),
        "device metadata": re.compile(r"\b(?:imei|serial|android[_ -]?id|ssid|bssid|mac address|peer[_ -]?id)\b", re.IGNORECASE),
    }
    for label, pattern in forbidden.items():
        if pattern.search(text):
            raise SystemExit(f"{relative}: possible {label} in synthetic fixtures")


def verify_sources() -> None:
    drawable_root = ROOT / "app/src/main/res/drawable"
    launchers = [
        drawable_root / "ic_launcher_foreground.xml",
        drawable_root / "ic_launcher_monochrome.xml",
        drawable_root / "wesh_launcher_foreground.xml",
    ]
    for source in launchers:
        text = source.read_text(encoding="utf-8")
        if "M31,39 L40,69 L54,45 L68,69 L77,39" not in text:
            raise SystemExit(f"{source.relative_to(ROOT)}: canonical Wesh vector is missing")
        if "M187.45493" in text:
            raise SystemExit(f"{source.relative_to(ROOT)}: stale BIT vector remains")


def main() -> None:
    subprocess.run([sys.executable, str(Path(__file__).with_name("generate_store_assets.py")), "--check"], cwd=ROOT, check=True)
    verify_png("store-assets/play/icon-512.png", (512, 512), opaque=True)
    verify_png("store-assets/play/fr-FR/feature-graphic.png", (1024, 500), opaque=True)
    verify_png("store-assets/play/en-US/feature-graphic.png", (1024, 500), opaque=True)
    verify_fixtures()
    verify_sources()
    print("store assets, Wesh launcher sources, and synthetic fixtures verified")


if __name__ == "__main__":
    main()
