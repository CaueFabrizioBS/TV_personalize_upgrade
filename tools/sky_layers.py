"""Splits each background photo (tools/sky_src/<slot>.jpg) into the layers SkyScene animates, in app/assets/sky/<slot>/:

  bg.jpg     the empty sky (clouds, stars and birds painted out), at half size: it's a smooth gradient
  c<i>.png   each loose cloud (and the moon), cut out; one cut by the frame is rounded off on that side, in the same
             style, so it can drift across the whole sky
  fg.png     what stands in front (the cloud bank, the hills, the trees), from its top row down
  layers.txt "fg <top>" and "cloud <file> <x> <y>", in 1920x1080 pixels

The sky is told apart from the rest by how far each pixel is from a smooth estimate of the sky around it. Slots 0 (deep
night) and 5 (sunrise) come from tools/sky_variants.py. Needs numpy, scipy and opencv-python-headless. Run from the repository's root:
    python tools/sky_layers.py
"""
import os

import cv2
import numpy as np
from scipy import ndimage as nd

SRC, OUT = "tools/sky_src", "app/assets/sky"


def sky_estimate(f):
    """The sky as if empty: a blur of the pixels that look like sky, refined 4 times (normalized convolution)."""
    m = np.ones(f.shape[:2], np.float32)
    for _ in range(4):
        num = cv2.GaussianBlur(f * m[..., None], (0, 0), 30)
        den = cv2.GaussianBlur(m, (0, 0), 30)[..., None]
        est = num / np.maximum(den, 1e-3)
        diff = np.abs(f - est).max(axis=2)
        m = (~nd.binary_dilation(diff > 12, iterations=3)).astype(np.float32)
    return est, diff


def complete(sprite):
    """
    Rounds off a cloud cut by the frame on its left: each run of cloud along the cut gets one or two puffs (half
    circles), filled with the cloud's own color along the cut and outlined in its darkest line color.
    Returns the wider sprite and how many pixels were added on the left.
    """
    SS = 4
    h, w = sprite.shape[:2]
    a = sprite[..., 3].astype(np.float32) / 255
    col = a[:, 0] > 0.5
    runs, y = [], 0
    while y < h:
        if col[y]:
            start = y
            while y < h and col[y]:
                y += 1
            if y - start >= 4:
                runs.append((start, y))
        y += 1
    if not runs:
        return sprite, 0
    puffs = []   # (center y, half height, half width), centered on the cut: a big round puff, a flatter one under it
    for ya, yb in runs:
        n = yb - ya
        if n > 60:
            puffs += [(ya + 0.40 * n, 0.40 * n, 0.55 * n), (ya + 0.80 * n, 0.20 * n, 0.62 * n)]
        else:
            puffs.append(((ya + yb) / 2, 0.5 * n, 0.9 * n))
    pad = int(max(rx for _, _, rx in puffs)) + 4
    big = np.zeros((h * SS, pad * SS), np.uint8)   # 4x, for clean edges
    for cy, ry, rx in puffs:
        cv2.ellipse(big, (pad * SS, int(cy * SS)), (int(rx * SS), int(ry * SS)), 0, 0, 360, 255, -1)
    big[:int(runs[0][0] * SS)] = 0                  # never above or below the cloud's own cut
    big[int(runs[-1][1] * SS):] = 0
    ring = big & ~cv2.erode(big, np.ones((2 * SS + 1, 2 * SS + 1), np.uint8))
    ring[:, -SS:] = 0   # no line along the seam
    shape = cv2.resize(big, (pad, h), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
    line = cv2.resize(ring, (pad, h), interpolation=cv2.INTER_AREA).astype(np.float32) / 255
    # Colors: each row the cloud's own color just inside the cut (no lines drawn on it), smoothed down the rows; the
    # line, its darkest outline color.
    solid = a > 0.9
    near = sprite[:, :24, :3].astype(np.float32)
    weight = solid[:, :24].astype(np.float32)
    mean = (near * weight[..., None]).sum(1) / np.maximum(weight.sum(1), 1)[:, None]
    body = np.median(sprite[:, :24, :3][solid[:, :24]].astype(np.float32), axis=0)
    mean[weight.sum(1) < 3] = body
    mean = cv2.GaussianBlur(mean[:, None, :], (0, 0), 6)[:, 0, :]
    fill = np.repeat(mean[:, None, :], pad, axis=1)
    lum = sprite[..., :3].astype(np.float32).mean(2)
    dark = sprite[..., :3][solid & (lum <= np.percentile(lum[solid], 2))].astype(np.float32).mean(0)
    dark = np.minimum(dark, body * 0.7)   # the art's outline: a deeper shade of the cloud
    rgb = fill * (1 - line[..., None]) + dark * line[..., None]
    cap = np.dstack([rgb, np.maximum(shape, line) * 255]).clip(0, 255).astype(np.uint8)
    # The cut side of the cloud eases into the flat color over 16 pixels, so no seam shows where they meet.
    out = sprite.copy()
    ramp = np.clip(np.arange(16, dtype=np.float32) / 16, 0, 1)[None, :, None]
    out[:, :16, :3] = (mean[:, None, :] * (1 - ramp) + sprite[:, :16, :3].astype(np.float32) * ramp).astype(np.uint8)
    return np.hstack([cap, out]), pad


def split(slot):
    im = cv2.imread(f"{SRC}/{slot}.jpg")
    H, W, _ = im.shape
    f = im.astype(np.float32)
    est, diff = sky_estimate(f)
    marks = nd.binary_opening(diff > 12) | (diff > 40)
    lab, n = nd.label(marks)
    sizes = nd.sum(np.ones_like(lab), lab, range(1, n + 1))
    objects = nd.find_objects(lab)

    # In front: whatever touches the bottom edge, and any big shape reaching the lower part; then every row below
    # the topmost of them in each column.
    front = set(np.unique(lab[-1])) - {0}
    for i, sl in enumerate(objects, 1):
        if sizes[i - 1] > 500 and sl[0].stop > 0.45 * H:
            front.add(i)
    fm = np.isin(lab, list(front))
    top = np.where(fm.any(0), fm.argmax(0), H)
    top = np.maximum(nd.minimum_filter1d(top, 3) - 1, 0)   # one row of sky over the edge, which never moves
    fg = np.arange(H)[:, None] >= top[None, :]
    fg_top = int(top.min())

    # Loose clouds (and the moon): big shapes above the front, holes filled. Small ones (stars, birds) are painted out.
    clouds = []
    gone = np.zeros((H, W), bool)
    for i, sl in enumerate(objects, 1):
        if i in front:
            continue
        sel = lab == i
        if not (sel & ~fg).any():
            continue
        if sizes[i - 1] > 500:
            # The cloud's inside, between the lines drawn on it (the frame closes a cloud it cuts).
            sel = nd.binary_fill_holes(np.pad(sel, ((1, 0), (1, 1)), constant_values=True))[1:, 1:-1]
            clouds.append(sel)
        gone |= sel
    # A shape inside another cloud's outline (a line drawn on it) is part of that cloud.
    clouds.sort(key=lambda m: -m.sum())
    kept = []
    for m in clouds:
        if not any((m & k).sum() > 0.5 * m.sum() for k in kept):
            kept.append(m)
    clouds = kept

    # The empty sky: the photo where it is clearly sky, elsewhere a wide blur of only that sky (so nothing of a
    # cloud's color bleeds in), blended over a few pixels.
    busy = nd.binary_dilation(gone | fg, iterations=6)
    m = (~nd.binary_dilation(busy, iterations=6)).astype(np.float32)
    est = None
    for sigma in (200, 40):   # the wide one where no sky is near (a corner a cloud fills)
        den = cv2.GaussianBlur(m, (0, 0), sigma)
        e = cv2.GaussianBlur(f * m[..., None], (0, 0), sigma) / np.maximum(den, 1e-6)[..., None]
        w = np.clip(den / 0.05, 0, 1)[..., None]
        est = e if est is None else e * w + est * (1 - w)
    keep = cv2.GaussianBlur((~busy).astype(np.float32), (0, 0), 3)[..., None]
    bg = f * keep + est * (1 - keep)
    os.makedirs(f"{OUT}/{slot}", exist_ok=True)
    cv2.imwrite(f"{OUT}/{slot}/bg.jpg", cv2.resize(np.clip(bg, 0, 255).astype(np.uint8), (W // 2, H // 2),
                                                   interpolation=cv2.INTER_AREA), [cv2.IMWRITE_JPEG_QUALITY, 92])

    lines = [f"fg {fg_top}"]
    rgba = cv2.cvtColor(im, cv2.COLOR_BGR2BGRA)
    a = (fg[fg_top:] * 255).astype(np.uint8)
    out = rgba[fg_top:].copy()
    out[..., 3] = a
    cv2.imwrite(f"{OUT}/{slot}/fg.png", out, [cv2.IMWRITE_PNG_COMPRESSION, 9])

    # How far from the empty sky: from the estimate, or from the row's own sky color (the estimate is poor in a corner
    # a cloud fills), whichever is closer.
    row_sky = np.array([np.median(f[y][~busy[y]], axis=0) if (~busy[y]).sum() > 50 else est[y].mean(0)
                        for y in range(H)], np.float32)[:, None, :]
    sky_diff = np.minimum(np.abs(f - bg).max(axis=2), np.abs(f - row_sky).max(axis=2))
    for j, mask in enumerate(clouds):
        ys, xs = np.where(nd.binary_dilation(mask, iterations=2))
        y0, y1, x0, x1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
        # The cloud is what differs from the empty sky (holes inside filled), not the sky the frame closes in.
        m = (sky_diff[y0:y1, x0:x1] > 12) & mask[y0:y1, x0:x1]
        m = nd.binary_fill_holes(nd.binary_closing(m, iterations=2))
        # 2 pixels of sky around the cloud: the sky is the same color along a row, so they never show as it drifts.
        grown = nd.binary_dilation(m, iterations=2)
        alpha = cv2.GaussianBlur(grown.astype(np.float32), (0, 0), 0.8)
        sprite = rgba[y0:y1, x0:x1].copy()
        sprite[..., 3] = np.clip(alpha * 255, 0, 255).astype(np.uint8)
        # Cut by the frame: rounded off on that side in the same style, so it can drift anywhere.
        if x0 == 0:
            sprite, pad = complete(sprite)
            x0 -= pad
        if x1 == W:
            sprite, _ = complete(sprite[:, ::-1])
            sprite = sprite[:, ::-1]
        cv2.imwrite(f"{OUT}/{slot}/c{j}.png", np.ascontiguousarray(sprite), [cv2.IMWRITE_PNG_COMPRESSION, 9])
        lines.append(f"cloud c{j}.png {x0} {y0}")
    with open(f"{OUT}/{slot}/layers.txt", "w") as fh:
        fh.write("\n".join(lines) + "\n")
    stars = sum(1 for i, sl in enumerate(objects, 1) if i not in front and sizes[i - 1] <= 500)
    print(f"slot {slot}: front from row {fg_top}, {len(clouds)} clouds, {stars} small marks painted out")


if __name__ == "__main__":
    for s in range(6):
        split(s)
