#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Generate the full Bellgram icon set from the user supplied artwork.

Source: a 1920x1920 JPEG containing a blue rounded-square background, a white
speech bubble with a small tail at the lower left, and a blue bell inside.

Outputs (all replaced in place):
  res/mipmap-<density>/app_logo.png              legacy launcher icon (48dp)
  res/mipmap-<density>/app_logo_round.png        round launcher icon
  res/mipmap-<density>/app_logo_background.png   adaptive icon background (108dp)
  res/mipmap-<density>/app_logo_foreground.png   adaptive icon foreground (108dp)
  res/drawable/app_logo.webp                    in-app brand image (512px)
  res/drawable-night/onboarding.webp            dark theme onboarding backdrop
  docs/app-icon.png                             README illustration (512px)

The adaptive foreground keeps only the bubble + bell on transparency so that
launchers applying a circular / squircle mask do not clip the blue square.
"""
from PIL import Image, ImageDraw, ImageFilter, ImageChops
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
DOCS = os.path.join(ROOT, "docs")
# modified: 用户提供的原图随仓库放在 docs/app-icon-source.jpg，也支持命令行传入自定义素材。
# 早期版本写死本机剪贴板绝对路径，换机器或分享仓库后无法复现整套图标。
SRC = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    DOCS, "app-icon-source.jpg")

DENSITY_SCALE = {
    "mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0,
}
LEGACY_DP = 48
ADAPTIVE_DP = 108
MASTER_SIZE = 1024

TOP_BLUE = (55, 166, 255)      # sampled near the top of the artwork
BOTTOM_BLUE = (3, 105, 231)    # sampled near the bottom of the artwork


def build_master():
    """Crop the white margin and make the rounded-square corners transparent."""
    src = Image.open(SRC).convert("RGB")
    w, h = src.size
    crop = src.crop((16, 16, w - 16, h - 16)).resize(
        (MASTER_SIZE, MASTER_SIZE), Image.LANCZOS)

    corner_radius = int(250 * MASTER_SIZE / (w - 32))
    rounded = Image.new("L", (MASTER_SIZE, MASTER_SIZE), 0)
    ImageDraw.Draw(rounded).rounded_rectangle(
        (0, 0, MASTER_SIZE - 1, MASTER_SIZE - 1),
        radius=corner_radius, fill=255)
    rounded = rounded.filter(ImageFilter.GaussianBlur(1.0))

    # Flood fill from the four corners removes the remaining white margin
    # outside the rounded square; the white bubble is NOT connected to it.
    white = Image.new("L", (MASTER_SIZE, MASTER_SIZE), 0)
    wp, cp = white.load(), crop.load()
    for y in range(MASTER_SIZE):
        for x in range(MASTER_SIZE):
            if min(cp[x, y]) >= 225:
                wp[x, y] = 255
    for corner in ((0, 0), (MASTER_SIZE - 1, 0),
                   (0, MASTER_SIZE - 1), (MASTER_SIZE - 1, MASTER_SIZE - 1)):
        if white.getpixel(corner) == 255:
            ImageDraw.floodfill(white, corner, 128)
    outer_white = white.point(lambda v: 255 if v == 128 else 0)

    alpha = ImageChops.subtract(rounded, outer_white)
    out = crop.convert("RGBA")
    out.putalpha(alpha)
    return out


def legacy_icon(master, size, round_icon=False):
    im = master.resize((size, size), Image.LANCZOS)
    if round_icon:
        ss = size * 4
        mask = Image.new("L", (ss, ss), 0)
        ImageDraw.Draw(mask).ellipse((0, 0, ss - 1, ss - 1), fill=255)
        mask = mask.resize((size, size), Image.LANCZOS)
        im.putalpha(ImageChops.multiply(im.getchannel("A"), mask))
    return im


def build_foreground():
    """Keep the bubble + bell (with its soft shadow) on a transparent canvas."""
    src = Image.open(SRC).convert("RGB")
    box = (350, 420, 1570, 1500)
    fg = src.crop(box).convert("RGBA")
    mask = Image.new("L", fg.size, 0)
    d = ImageDraw.Draw(mask)
    d.rounded_rectangle((25, 30, 1200, 980), radius=420, fill=255)
    d.polygon([(210, 1050), (250, 860), (470, 945)], fill=255)
    fg.putalpha(mask.filter(ImageFilter.GaussianBlur(2.0)))
    return fg


def adaptive_background(size):
    im = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(im)
    for y in range(size):
        t = y / float(max(1, size - 1))
        c = tuple(int(TOP_BLUE[i] * (1 - t) + BOTTOM_BLUE[i] * t)
                  for i in range(3))
        d.line((0, y, size, y), fill=c)
    return im.convert("RGBA")


def adaptive_foreground(fg, size):
    target_w = int(size * 0.56)  # keep the artwork inside the 66dp safe zone
    target_h = int(fg.height * target_w / float(fg.width))
    scaled = fg.resize((target_w, target_h), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    canvas.alpha_composite(scaled,
                           ((size - target_w) // 2, (size - target_h) // 2))
    return canvas


def dark_onboarding(size=512):
    """Deep-blue gradient used as the onboarding backdrop in dark theme."""
    im = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(im)
    top, bottom = (18, 32, 60), (10, 18, 34)
    for y in range(size):
        t = y / float(size - 1)
        c = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
        d.line((0, y, size, y), fill=c)
    return im


def main():
    if not os.path.isfile(SRC):
        raise SystemExit("source artwork not found: " + SRC)
    master = build_master()
    fg = build_foreground()

    for density, scale in DENSITY_SCALE.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        legacy = int(LEGACY_DP * scale)
        adaptive = int(ADAPTIVE_DP * scale)
        legacy_icon(master, legacy).save(
            os.path.join(folder, "app_logo.png"))
        legacy_icon(master, legacy, round_icon=True).save(
            os.path.join(folder, "app_logo_round.png"))
        adaptive_background(adaptive).save(
            os.path.join(folder, "app_logo_background.png"))
        adaptive_foreground(fg, adaptive).save(
            os.path.join(folder, "app_logo_foreground.png"))
        print("wrote mipmap-%s (%d / %d px)" % (density, legacy, adaptive))

    brand = master.resize((512, 512), Image.LANCZOS)
    brand.save(os.path.join(RES, "drawable", "app_logo.webp"),
               "WEBP", quality=95, method=6)
    os.makedirs(os.path.join(RES, "drawable-night"), exist_ok=True)
    dark_onboarding().save(
        os.path.join(RES, "drawable-night", "onboarding.webp"),
        "WEBP", quality=92, method=6)
    os.makedirs(DOCS, exist_ok=True)
    brand.convert("RGB").save(os.path.join(DOCS, "app-icon.png"))
    print("wrote drawable/app_logo.webp, drawable-night/onboarding.webp, docs/app-icon.png")


if __name__ == "__main__":
    main()
