"""The clouds of a cloudy or rainy sky (SkyScene's weather), in app/assets/sky/deck/: the midday photo's loose clouds
(whole, as tools/sky_layers.py completes them), in grey, their lines softened (SkyScene draws them bigger, which
would thicken the lines); SkyScene tints them with the part of the day's light. Run from
the repository's root, after tools/sky_layers.py:
    python tools/sky_deck.py
"""
import os

import numpy as np
from PIL import Image, ImageFilter

SRC, OUT = "app/assets/sky/2", "app/assets/sky/deck"

os.makedirs(OUT, exist_ok=True)
n = 0
for line in open(f"{SRC}/layers.txt"):
    p = line.split()
    if p[0] != "cloud":
        continue
    a = np.asarray(Image.open(f"{SRC}/{p[1]}").convert("RGBA")).astype(np.float32)
    lum = a[..., :3] @ np.array([0.3, 0.55, 0.15], np.float32)
    # The lines: pixels much darker than the cloud around them, pulled most of the way to it.
    body = np.asarray(Image.fromarray(np.clip(lum, 0, 255).astype(np.uint8)).filter(ImageFilter.MaxFilter(7))
                      .filter(ImageFilter.MedianFilter(5))).astype(np.float32)
    line = np.clip((body - lum - 12) / 30, 0, 1)
    lum = lum + (body - lum) * line * 0.6
    a[..., :3] = (lum * 0.86)[..., None] + np.array([0, 2, 8], np.float32)   # grey, a touch cool
    Image.fromarray(np.clip(a, 0, 255).astype(np.uint8)).save(f"{OUT}/d{n}.png", optimize=True)
    n += 1
print(n, "deck clouds")
