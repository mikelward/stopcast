#!/usr/bin/env python3
"""Render the Play Store 512x512 icon from the adaptive-icon design.

Composites the adaptive icon (white background + the three-route-lines foreground,
the blue line converging into a black arrow) cropped to the 72dp safe zone so the
mark fills the frame, opaque RGB. The geometry mirrors
app/src/main/res/drawable/ic_launcher_foreground.xml and the background
app/src/main/res/values/ic_launcher_background.xml; keep them in step.

Deterministic, so the committed docs/play-store/icon-512.png should match a fresh
run. Needs Pillow (`pip install Pillow`). Run from anywhere:

    python3 scripts/render-store-icon.py
"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, os.pardir, "docs", "play-store", "icon-512.png")

SIZE, SS = 512, 4
px = SIZE * SS
BG = "#FFFFFF"
BARS = [(43, "#E32017"), (54, "#0098D4"), (65, "#FFD300")]  # centers, line colors
BX1, BX2, BH, BR = 22, 66, 7, 3.5
ARROW = "#000000"  # black, to read against the white background

# crop the inner 72dp safe zone (18..90 in the 108 canvas)
LO, SPAN = 18.0, 72.0
sc = px / SPAN
def X(x): return (x - LO) * sc
def Y(y): return (y - LO) * sc

img = Image.new("RGB", (px, px), BG)
d = ImageDraw.Draw(img)

for cy, col in BARS:
    d.rounded_rectangle([X(BX1), Y(cy - BH / 2), X(BX2), Y(cy + BH / 2)], radius=BR * sc, fill=col)

w = int(round(7 * sc))                       # tail height == line height
d.line([X(64), Y(54), X(84), Y(54)], fill=ARROW, width=w)                          # shaft (flat ends)
d.line([X(77), Y(47), X(84), Y(54), X(77), Y(61)], fill=ARROW, width=w, joint="curve")  # head
for (x, y) in [(77, 47), (77, 61), (64, 54)]:                                      # round head tips + tail
    r = w / 2
    d.ellipse([X(x) - r, Y(y) - r, X(x) + r, Y(y) + r], fill=ARROW)

os.makedirs(os.path.dirname(OUT), exist_ok=True)
img.resize((SIZE, SIZE), Image.LANCZOS).save(OUT)
print("wrote", os.path.normpath(OUT))
