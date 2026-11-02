"""Regenerate the Android TV launcher banner (requires Pillow).

Run from any directory. Pass a bold TrueType font path as the first argument
on platforms without Segoe UI. The app name is a non-translatable brand,
matching values/strings.xml, so all locales share this banner.
"""

from pathlib import Path
import sys

from PIL import Image, ImageDraw, ImageFont


SCALE = 4
ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "MiracastReceiver/app/src/main/res/drawable-xhdpi/tv_banner.png"
FONT = sys.argv[1] if len(sys.argv) > 1 else "C:/Windows/Fonts/segoeuib.ttf"


def main():
    image = Image.new("RGB", (320 * SCALE, 180 * SCALE), "#1A73E8")
    draw = ImageDraw.Draw(image)

    def box(coords):
        return tuple(round(n * SCALE) for n in coords)

    # The existing launcher icon's blue field, green display and white waves.
    draw.rounded_rectangle(box((24, 48, 116, 116)), radius=10 * SCALE, fill="#00C853")
    draw.rectangle(box((33, 57, 107, 107)), fill="#1A73E8")
    for radius in (12, 24, 36):
        draw.arc(box((36 - radius, 103 - radius, 36 + radius, 103 + radius)),
                 270, 360, fill="white", width=6 * SCALE)
    draw.ellipse(box((33, 100, 39, 106)), fill="white")

    font = ImageFont.truetype(FONT, 27 * SCALE)
    for text, y in (("Miracast", 55), ("Receiver", 90)):
        draw.text(box((136, y)), text, font=font, fill="white", anchor="lt")

    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    image.resize((320, 180), Image.Resampling.LANCZOS).save(OUTPUT, optimize=True)
    print(OUTPUT)


if __name__ == "__main__":
    main()
