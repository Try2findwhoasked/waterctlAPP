# -*- coding: utf-8 -*-
"""Generate Android launcher icons from tools/icon/images.png.

Outputs
-------
mipmap-<dpi>/ic_launcher.png            legacy square icon (48..192 px)
mipmap-<dpi>/ic_launcher_round.png      legacy round icon (48..192 px)
mipmap-<dpi>/ic_launcher_foreground.png adaptive-icon foreground layer (108..432 px)

The adaptive-icon foreground keeps the artwork inside the central 72dp of the
108dp layer so that every launcher mask (circle, squircle, rounded square)
shows the whole picture.  The source artwork is fully opaque once flattened on
its own corner colour, which is also printed so colors.xml can be updated.
"""

import os

from PIL import Image, ImageDraw

# 源图不能放在 res/ 下：res 只接受已识别的资源类型与 .xml 文件
SOURCE = os.path.join("tools", "icon", "images.png")
RES = os.path.join("app", "src", "main", "res")

LEGACY_SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
LAYER_SIZES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

SAFE_FRACTION = 72 / 108  # content diameter inside the 108dp adaptive layer
SUPERSAMPLE = 4           # anti-aliasing factor for the round masks
LANCZOS = Image.Resampling.LANCZOS


def corner_colour(image):
    """Average colour of the top-left corner, ignoring the 1px feathered edge."""
    patch = image.crop((8, 8, 40, 40)).convert("RGB")
    pixels = list(patch.getdata())
    return tuple(round(sum(p[i] for p in pixels) / len(pixels)) for i in range(3))


def flatten(image, background):
    """Alpha-compose the artwork on its own background colour."""
    canvas = Image.new("RGBA", image.size, background + (255,))
    canvas.alpha_composite(image)
    return canvas.convert("RGB")


def main():
    source = Image.open(SOURCE).convert("RGBA")
    background = corner_colour(source)
    print("source     : %s %s" % (source.size, source.mode))
    print("background : #%02X%02X%02X %s" % (background + (background,)))

    artwork = flatten(source, background)

    for density, size in LEGACY_SIZES.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)

        square = artwork.resize((size, size), LANCZOS)
        square.save(os.path.join(folder, "ic_launcher.png"), optimize=True)

        big = size * SUPERSAMPLE
        enlarged = artwork.resize((big, big), LANCZOS).convert("RGBA")
        inset = SUPERSAMPLE
        mask = Image.new("L", (big, big), 0)
        ImageDraw.Draw(mask).ellipse((inset, inset, big - 1 - inset, big - 1 - inset), fill=255)
        round_icon = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        round_icon.paste(enlarged, (0, 0), mask)
        round_icon.resize((size, size), LANCZOS).save(
            os.path.join(folder, "ic_launcher_round.png"), optimize=True
        )

        print("mipmap-%-7s ic_launcher.png + ic_launcher_round.png (%dpx)" % (density, size))

    for density, size in LAYER_SIZES.items():
        folder = os.path.join(RES, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)

        content = round(size * SAFE_FRACTION)
        offset = (size - content) // 2
        layer = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        layer.paste(artwork.resize((content, content), LANCZOS), (offset, offset))
        layer.save(os.path.join(folder, "ic_launcher_foreground.png"), optimize=True)

        print("mipmap-%-7s ic_launcher_foreground.png (%dpx, art %dpx)" % (density, size, content))


if __name__ == "__main__":
    main()