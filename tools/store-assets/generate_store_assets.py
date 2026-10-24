#!/usr/bin/env python3
"""Render Wesh assets inside the pinned canonical Linux toolchain."""

from __future__ import annotations

import argparse
import functools
import hashlib
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[2]
CANONICAL_SVG = ROOT / "store-assets/source/WeshAppIcon.svg"
CANONICAL_SHA256 = "bbebcedf6e040de562a0a616bd9864136c6d10e289feeba2942905b84becec91"
ARCHIVO = ROOT / "app/src/main/res/font/archivo_extracondensed_black.ttf"
GEIST_BOLD = ROOT / "app/src/main/res/font/geist_mono_bold.ttf"

LEGACY_SIZES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
ADAPTIVE_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}
FEATURE_COPY = {
    "fr-FR": "MESSAGES SANS RÉSEAU MOBILE",
    "en-US": "MESSAGING WITHOUT A MOBILE NETWORK",
}
SILVER_STOPS = (
    (0.0, (182, 182, 182)),
    (0.32, (255, 255, 255)),
    (0.52, (207, 207, 207)),
    (0.70, (245, 245, 245)),
    (1.0, (182, 182, 182)),
)


def _validate_canonical_source() -> None:
    digest = hashlib.sha256(CANONICAL_SVG.read_bytes()).hexdigest()
    if digest != CANONICAL_SHA256:
        raise SystemExit(f"canonical Wesh SVG changed unexpectedly: {digest}")


def _interpolate_silver(value: float) -> tuple[int, int, int]:
    value = min(1.0, max(0.0, value))
    for index in range(1, len(SILVER_STOPS)):
        right_t, right = SILVER_STOPS[index]
        left_t, left = SILVER_STOPS[index - 1]
        if value <= right_t:
            ratio = (value - left_t) / (right_t - left_t)
            return tuple(round(a + (b - a) * ratio) for a, b in zip(left, right))
    return SILVER_STOPS[-1][1]


@functools.lru_cache(maxsize=None)
def _silver_gradient(size: int = 1024) -> Image.Image:
    # Same user-space vector as the SVG: (220, 300) -> (804, 730).
    start_x, start_y = 220.0, 300.0
    delta_x, delta_y = 584.0, 430.0
    length_squared = delta_x * delta_x + delta_y * delta_y
    scale = 1024.0 / size
    pixels = []
    for y in range(size):
        source_y = (y + 0.5) * scale
        for x in range(size):
            source_x = (x + 0.5) * scale
            projection = ((source_x - start_x) * delta_x + (source_y - start_y) * delta_y) / length_squared
            pixels.append(_interpolate_silver(projection))
    gradient = Image.new("RGB", (size, size))
    gradient.putdata(pixels)
    return gradient


@functools.lru_cache(maxsize=None)
def _mark_mask(size: int = 1024) -> Image.Image:
    scale = size / 1024.0
    points = [(round(x * scale), round(y * scale)) for x, y in ((220, 300), (356, 730), (512, 430), (668, 730), (804, 300))]
    width = max(1, round(106 * scale))
    radius = width / 2.0
    mask = Image.new("L", (size, size), 0)
    draw = ImageDraw.Draw(mask)
    draw.line(points, fill=255, width=width, joint="curve")
    for x, y in points:
        draw.ellipse((round(x - radius), round(y - radius), round(x + radius), round(y + radius)), fill=255)
    return mask


@functools.lru_cache(maxsize=None)
def _mark_rgba(size: int = 1024) -> Image.Image:
    rgb = _silver_gradient(size).copy()
    rgb.putalpha(_mark_mask(size))
    return rgb


def _icon_rgba(size: int) -> Image.Image:
    icon = Image.new("RGBA", (1024, 1024), (0, 0, 0, 255))
    icon.alpha_composite(_mark_rgba())
    return icon.resize((size, size), Image.Resampling.LANCZOS)


def _adaptive_foreground(size: int) -> Image.Image:
    # Android's adaptive safe zone is tighter than the square store icon.
    mark = _mark_rgba()
    mark = mark.resize((round(size * 0.74), round(size * 0.74)), Image.Resampling.LANCZOS)
    foreground = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    offset = ((size - mark.width) // 2, (size - mark.height) // 2)
    foreground.alpha_composite(mark, offset)
    return foreground


def _feature_graphic(copy: str) -> Image.Image:
    image = Image.new("RGBA", (1024, 500), (11, 11, 10, 255))
    draw = ImageDraw.Draw(image)
    draw.rectangle((0, 486, 1024, 500), fill=(228, 255, 26, 255))

    mark = _mark_rgba().crop(_mark_mask().getbbox())
    mark.thumbnail((300, 250), Image.Resampling.LANCZOS)
    image.alpha_composite(mark, (64 + (300 - mark.width) // 2, (500 - mark.height) // 2 - 4))

    title_font = ImageFont.truetype(ARCHIVO, 132)
    draw.text((405, 100), "WESH", font=title_font, fill=(244, 243, 238, 255), anchor="la")

    size = 31
    while size >= 20:
        copy_font = ImageFont.truetype(GEIST_BOLD, size)
        if draw.textbbox((0, 0), copy, font=copy_font)[2] <= 555:
            break
        size -= 1
    draw.text((410, 286), copy, font=copy_font, fill=(228, 255, 26, 255), anchor="la")
    return image


def _save_png(image: Image.Image, target: Path) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    image.convert("RGBA").save(target, "PNG", optimize=False, compress_level=9)


def generate(destination_root: Path) -> list[Path]:
    _validate_canonical_source()
    outputs: list[Path] = []

    def write(relative: str, image: Image.Image) -> None:
        target = destination_root / relative
        _save_png(image, target)
        outputs.append(Path(relative))

    write("store-assets/play/icon-512.png", _icon_rgba(512))
    for locale, copy in FEATURE_COPY.items():
        write(f"store-assets/play/{locale}/feature-graphic.png", _feature_graphic(copy))
    for density, size in LEGACY_SIZES.items():
        write(f"app/src/main/res/mipmap-{density}/ic_launcher.png", _icon_rgba(size))
    for density, size in ADAPTIVE_SIZES.items():
        write(f"app/src/main/res/mipmap-{density}/ic_launcher_adaptive_back.png", Image.new("RGBA", (size, size), (0, 0, 0, 255)))
        write(f"app/src/main/res/mipmap-{density}/ic_launcher_adaptive_fore.png", _adaptive_foreground(size))
    return outputs


def check() -> None:
    with tempfile.TemporaryDirectory(prefix="wesh-store-assets-") as directory:
        temporary_root = Path(directory)
        outputs = generate(temporary_root)
        mismatches = [relative for relative in outputs if not (ROOT / relative).is_file() or (ROOT / relative).read_bytes() != (temporary_root / relative).read_bytes()]
    if mismatches:
        raise SystemExit("generated assets are missing or stale:\n" + "\n".join(f"  {path}" for path in mismatches))
    print(f"verified {len(outputs)} generated Wesh assets")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="regenerate in a temporary directory and compare byte-for-byte")
    args = parser.parse_args()
    if args.check:
        check()
    else:
        outputs = generate(ROOT)
        print(f"generated {len(outputs)} Wesh assets")


if __name__ == "__main__":
    main()
