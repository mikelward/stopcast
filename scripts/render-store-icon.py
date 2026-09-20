#!/usr/bin/env python3
"""Render the Play Store 512x512 icon from the adaptive-icon design.

Composites the adaptive icon (ink background + the three-route-lines foreground)
cropped to the 72dp safe zone so the mark fills the frame, opaque RGB. The
geometry mirrors app/src/main/res/drawable/ic_launcher_foreground.xml and the
background app/src/main/res/values/ic_launcher_background.xml; keep them in step.

Deterministic, so the committed docs/play-store/icon-512.png should match a fresh
run. Needs Pillow (`pip install Pillow`). Run from anywhere:

    python3 scripts/render-store-icon.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "docs", "play-store", "icon-512.png")

SIZE = 512
SS = 4
px = SIZE * SS

BG = "#12151C"
BARS = [(42, "#E32017"), (54, "#0098D4"), (66, "#FFD300")]  # centers, line colors
BAR_X1, BAR_X2, BAR_H, BAR_R = 26, 82, 7, 3.5
DOT_CX, DOT_R = 77, 4

# crop the inner 72dp safe zone (18..90 in the 108 canvas)
LO, SPAN = 18.0, 72.0
sc = px / SPAN
def X(x): return (x - LO) * sc
def Y(y): return (y - LO) * sc

img = Image.new("RGB", (px, px), BG)
d = ImageDraw.Draw(img)

for cy, col in BARS:
    d.rounded_rectangle(
        [X(BAR_X1), Y(cy - BAR_H / 2), X(BAR_X2), Y(cy + BAR_H / 2)],
        radius=BAR_R * sc, fill=col,
    )
for cy, _ in BARS:
    d.ellipse(
        [X(DOT_CX - DOT_R), Y(cy - DOT_R), X(DOT_CX + DOT_R), Y(cy + DOT_R)],
        fill="#FFFFFF",
    )

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT)
print("wrote", os.path.normpath(OUT))
