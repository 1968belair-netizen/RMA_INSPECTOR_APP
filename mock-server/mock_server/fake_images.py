"""Fake JPEG generator for the demo dataset (mock only). Pillow draws a coloured background with a label."""
from __future__ import annotations

import io

from PIL import Image, ImageDraw, ImageFont


def _font(size: int):
    try:
        return ImageFont.load_default(size=size)   # Pillow >= 10.1
    except TypeError:
        return ImageFont.load_default()


def make_fake_jpeg(label: str, rgb: tuple[int, int, int], size: tuple[int, int] = (640, 480)) -> bytes:
    """A small valid JPEG: solid `rgb` background, darker border, `label` text centred (word-wrapped)."""
    img = Image.new("RGB", size, rgb)
    draw = ImageDraw.Draw(img)
    dark = tuple(max(0, c - 70) for c in rgb)
    draw.rectangle([6, 6, size[0] - 7, size[1] - 7], outline=dark, width=6)
    font = _font(max(18, size[0] // 18))
    words, lines, cur = label.split(), [], ""
    for w in words:
        trial = (cur + " " + w).strip()
        if draw.textlength(trial, font=font) > size[0] - 60 and cur:
            lines.append(cur)
            cur = w
        else:
            cur = trial
    if cur:
        lines.append(cur)
    line_h = font.size + 8 if hasattr(font, "size") else 24
    y = (size[1] - line_h * len(lines)) // 2
    light = sum(rgb) / 3 > 140
    ink = (20, 20, 20) if light else (245, 245, 245)
    for line in lines:
        draw.text(((size[0] - draw.textlength(line, font=font)) / 2, y), line, fill=ink, font=font)
        y += line_h
    out = io.BytesIO()
    img.save(out, format="JPEG", quality=70)
    return out.getvalue()
