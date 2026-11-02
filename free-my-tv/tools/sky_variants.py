"""Makes the two backgrounds drawn from others, in tools/sky_src (then run tools/sky_layers.py):

  0.jpg  deep night (0:00 to 2:59): the night (4.jpg) darker, the moon as bright as before
  5.jpg  sunrise (5:00 to 6:59): the sunset (3.jpg) in the softer, paler
         pinks and golds of dawn under a sky still blue-violet at the top

Run from the repository's root:  python tools/sky_variants.py
"""
import cv2
import numpy as np

SRC = "tools/sky_src"


def lin(im):
    return (im.astype(np.float32) / 255) ** 2.2


def srgb(x):
    return (np.clip(x, 0, 1) ** (1 / 2.2) * 255).round().astype(np.uint8)


def deep_night():
    im = cv2.imread(f"{SRC}/4.jpg")
    x = lin(im)
    lum = x[..., 0] * 0.07 + x[..., 1] * 0.72 + x[..., 2] * 0.21
    keep = np.clip((lum - 0.45) / 0.25, 0, 1)[..., None]   # the moon (and bright stars) stay
    keep = cv2.GaussianBlur(keep, (0, 0), 2)[..., None] if keep.ndim == 2 else cv2.GaussianBlur(keep[..., 0], (0, 0), 2)[..., None]
    dark = x * np.array([0.42, 0.40, 0.50], np.float32)   # BGR: a little bluer as it darkens
    cv2.imwrite(f"{SRC}/0.jpg", srgb(dark * (1 - keep) + x * keep), [cv2.IMWRITE_JPEG_QUALITY, 95])


def sunrise():
    im = cv2.imread(f"{SRC}/3.jpg")
    x = lin(im)
    H = x.shape[0]
    y = np.linspace(0, 1, H, dtype=np.float32)[:, None, None]
    # Dawn light: the top a touch more violet, the warm glow toward pink and pale gold, everything a little softer.
    top = np.array([1.18, 0.95, 1.08], np.float32)       # BGR
    low = np.array([1.05, 0.82, 1.00], np.float32)
    grade = top * (1 - y) + low * y
    x = x * grade
    lum = (x[..., 0] * 0.07 + x[..., 1] * 0.72 + x[..., 2] * 0.21)[..., None]
    x = x * 0.75 + lum * 0.25                              # 25% less saturated
    x = x + np.array([0.035, 0.020, 0.045], np.float32) * (1 - y) # a faint haze, pinkish violet
    cv2.imwrite(f"{SRC}/5.jpg", srgb(x * 1.06), [cv2.IMWRITE_JPEG_QUALITY, 95])


if __name__ == "__main__":
    deep_night()
    sunrise()
