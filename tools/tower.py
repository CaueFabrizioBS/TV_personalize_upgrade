"""Draws the lookout tower on the right of every background, with the hiker who lives in it, in the backgrounds' style
(flat colors, dark outlines), 4x larger and then reduced for clean edges. One sprite per part of the day and what the
hiker is doing, lit like that photo (each photo's forest color next to the day's), with the near treetops of that very
photo drawn over the tower's legs, so it stands among the trees.

  app/assets/sky/<slot>/tower_<state>.png, tower_front.png (the photo's near trees, drawn over the tower) and
  tower.txt: "front <file> <x> <y>", "antenna <base x y> <top x y> <color> <width>" (SkyScene draws the mast, which
  sways in the wind) and "tower <state> <file> <x> <y> [steam|smoke <x> <y>]", in 1920x1080 pixels

States: off (asleep, dark windows), lit (evening, someone cooking inside), mug (sunrise, hot chocolate at the rail),
reading (morning, sitting against the cabin), empty (midday, out walking), watching (sunset, at the rail), rain (in a
raincoat), binoculars (cloudy). With music playing: music and music_rain by day (at the rail with headphones; the head
is its own sprite, "head <state> <file> <x> <y> <pivot x> <pivot y>", which SkyScene nods as he enjoys it) and party
at night (the windows cut out: "pane <x0> <y0> <x1> <y1>" and "glow <file> <x> <y>" for each, colored lights that
SkyScene blinks and passes from window to window).
    python tools/tower.py            the sprites
    python tools/tower.py preview    and a contact sheet of every state on its photo (tools/tower_preview.png)
"""
import sys

import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFilter
from scipy import ndimage as nd

SRC, OUT = "tools/sky_src", "app/assets/sky"
SS = 4                       # drawn this much larger
X0, Y0, W, H = 1640, 690, 280, 390   # the sprite's box on the 1920x1080 photo
CX = 1780                    # the tower's middle
# The tower is drawn in these units, then scaled by K around its floor, which stands at FLOOR_AT: in front of the
# forest of small crowns, its legs going down behind the big near trees at the bottom right (their tips about y 900 to
# 960); near, so in the same clear light as they are (not the far forest's haze).
FLOOR, RAIL, CABIN_TOP, EAVE, APEX = 778, 767, 742, 744, 716
K, FLOOR_AT = 2.3, 902
NEAR_FROM = 870              # the big trees' tips are looked for from here down (on the photo)
NEAR_ALL = 1010              # and from here down it's only big trees, in every photo

OUTLINE = (43, 31, 24)
WOOD, WOOD_MID, WOOD_DARK = (176, 128, 82), (142, 98, 60), (98, 66, 40)
ROOF, ROOF_LIGHT = (74, 90, 78), (98, 118, 100)
PANE_DAY, PANE_OFF, PANE_LIT = (186, 210, 226), (40, 48, 70), (255, 206, 120)
PANE_PARTY = (0, 0, 0, 0)    # cut out: SkyScene's colored lights show through
SKIN, SKIN_DARK, HAIR = (110, 70, 48), (80, 50, 34), (30, 22, 18)
JACKET, JACKET_DARK = (233, 163, 42), (192, 127, 26)
JEANS, PACK = (62, 95, 143), (46, 60, 92)
RAINCOAT, RAINCOAT_DARK = (64, 122, 92), (44, 92, 68)
BOOK, MUG, METAL = (176, 52, 48), (236, 236, 232), (70, 70, 72)

DAY_MUSIC = ["music", "music_rain"]
STATES = {0: ["off", "party"], 4: ["off", "lit", "party"], 5: ["mug", "rain", "binoculars"] + DAY_MUSIC,
          1: ["reading", "rain", "binoculars"] + DAY_MUSIC, 2: ["empty", "rain", "binoculars"] + DAY_MUSIC,
          3: ["watching", "watching_back", "rain", "binoculars"] + DAY_MUSIC}


def at(x, y):
    """The tower's units to the photo's pixels."""
    return (CX + (x - CX) * K, FLOOR_AT + (y - FLOOR) * K)


def P(x, y):
    """The tower's units to the sprite's enlarged canvas."""
    x, y = at(x, y)
    return ((x - X0) * SS, (y - Y0) * SS)


def Q(x, y):
    """The photo's pixels to the sprite's enlarged canvas."""
    return ((x - X0) * SS, (y - Y0) * SS)


def shade(color, f=0.58):
    """The art's outline for a fill: a deeper shade of the same color, never black."""
    return tuple(int(c * f) for c in color[:3])


def wobble(x, y, amount=0.28):
    """A small, steady offset for a point (the same point always moves the same way): lines drawn by hand."""
    h = (int(round(x * 8)) * 73856093) ^ (int(round(y * 8)) * 19349663)
    rng = np.random.default_rng(h & 0xFFFFFFFF)
    dx, dy = rng.normal(0, amount, 2)
    return x + dx, y + dy


class Pen:
    def __init__(self):
        self.im = Image.new("RGBA", (W * SS, H * SS), (0, 0, 0, 0))
        self.d = ImageDraw.Draw(self.im)

    def poly(self, pts, fill, outline=OUTLINE, width=1.1):
        outline = shade(fill) if outline is OUTLINE else outline
        self.d.polygon([P(*wobble(*p)) for p in pts], fill=fill, outline=outline,
                       width=max(1, round(width * K * SS)) if outline else 0)

    def rect(self, x0, y0, x1, y1, fill, outline=OUTLINE, width=1.1):
        self.poly([(x0, y0), (x1, y0), (x1, y1), (x0, y1)], fill, outline, width)

    def line(self, pts, fill, width=1.3):
        self.d.line([P(*wobble(*p)) for p in pts], fill=fill, width=max(1, round(width * K * SS)), joint="curve")

    def circle(self, x, y, r, fill, outline=OUTLINE, width=0.9):
        outline = shade(fill) if outline is OUTLINE else outline
        a, b = P(x - r, y - r), P(x + r, y + r)
        self.d.ellipse([a, b], fill=fill, outline=outline, width=max(1, round(width * K * SS)) if outline else 0)


# MARK: - The tower, seen a little from the side (its right side going away from us)

L, R = CX - 28, CX + 22            # the cabin's front wall
EYE = 758                          # eye level: about the cabin's middle (the photos' horizon is near it)
CABIN_DEPTH, DECK_DEPTH = 20, 28   # how deep the cabin and the balcony are
LEG_DROP = 150                     # the legs, from the floor down (hidden in the trees well before their feet)


def proj(x, y, depth):
    """A point `depth` units behind (x, y) on the front: to the right, and toward eye level (above it lines going
    away slope down, below it up), so nothing looks tilted."""
    return (x + depth * 0.72, y + (EYE - y) * depth * 0.006)


def leg_at(name, t):
    """A point of a leg, t from 0 (the floor) to 1 (its foot): the four spread out evenly as they go down."""
    spread = 1 + 0.30 * t
    mid = (L + R) / 2
    half = (R - L) / 2 + 3
    x = mid + (-half if name[1] == "l" else half) * spread
    depth = DECK_DEPTH * 0.5 + (-1 if name[0] == "f" else 1) * DECK_DEPTH * 0.42 * spread
    return proj(x, FLOOR + 4 + LEG_DROP * t, depth)


def legs(p):
    levels = [0, 0.13, 0.42, 0.66, 0.92]   # the first girt just above the trees, the rest down among them
    # The far legs first, darker; their side's braces; the staircase up the right side; then the near legs.
    for name in ("bl", "br"):
        a, b = leg_at(name, 0), leg_at(name, 1)
        p.poly([(a[0] - 1.1, a[1]), (a[0] + 1.1, a[1]), (b[0] + 1.6, b[1]), (b[0] - 1.6, b[1])], WOOD_DARK)
    for t0, t1 in zip(levels, levels[1:]):   # the right side: girts and crossed braces, in shade
        p.line([leg_at("fr", t0), leg_at("br", t1)], WOOD_DARK, 1.0)
        p.line([leg_at("br", t0), leg_at("fr", t1)], WOOD_DARK, 1.0)
        p.line([leg_at("fr", t1), leg_at("br", t1)], WOOD_DARK, 1.1)

    def side(u, t):
        a, b = leg_at("fr", t), leg_at("br", t)
        return (a[0] + (b[0] - a[0]) * u, a[1] + (b[1] - a[1]) * u - 1.5)

    for i, (t0, t1) in enumerate(zip(levels, levels[1:])):   # the stairs: a flight per bay, turning at each girt
        u0, u1 = (0.82, 0.18) if i % 2 == 0 else (0.18, 0.82)
        lo, hi = side(u0, t1), side(u1, t0)
        p.line([lo, hi], WOOD_MID, 1.0)
        for k in range(1, 7):   # treads
            f = k / 7
            x, y = lo[0] + (hi[0] - lo[0]) * f, lo[1] + (hi[1] - lo[1]) * f
            p.line([(x - 1.6, y), (x + 1.6, y)], WOOD_MID, 0.7)
    for t0, t1 in zip(levels, levels[1:]):   # the front: crossed braces and girts
        p.line([leg_at("fl", t0), leg_at("fr", t1)], WOOD_MID, 1.2)
        p.line([leg_at("fr", t0), leg_at("fl", t1)], WOOD_MID, 1.2)
        p.line([leg_at("fl", t1), leg_at("fr", t1)], WOOD_MID, 1.3)
    for name in ("fl", "fr"):
        a, b = leg_at(name, 0), leg_at(name, 1)
        p.poly([(a[0] - 1.5, a[1]), (a[0] + 1.5, a[1]), (b[0] + 2.1, b[1]), (b[0] - 2.1, b[1])], WOOD)


def cabin(p, panes, lit_figure=False):
    """The balcony's floor, the walls (the side in shade) with their windows, the roof. Returns the stovepipe's base."""
    global STOVEPIPE
    d0, d1 = (DECK_DEPTH - CABIN_DEPTH) / 2, (DECK_DEPTH + CABIN_DEPTH) / 2   # the cabin sits mid-balcony
    # The balcony's floor: its top (seen a little from above) and its front and side edges.
    p.poly([proj(L - 7, FLOOR, 0), proj(R + 7, FLOOR, 0), proj(R + 7, FLOOR, DECK_DEPTH), proj(L - 7, FLOOR, DECK_DEPTH)],
           WOOD_MID)
    p.poly([proj(R + 7, FLOOR, 0), proj(R + 7, FLOOR, DECK_DEPTH), proj(R + 7, FLOOR + 4, DECK_DEPTH),
            proj(R + 7, FLOOR + 4, 0)], WOOD_DARK)
    p.rect(L - 7, FLOOR, R + 7, FLOOR + 4, WOOD_MID)
    # Walls: the side (in shade), then the front, both set back d0 from the balcony's edge.
    p.poly([proj(R, CABIN_TOP, d0), proj(R, CABIN_TOP, d1), proj(R, FLOOR, d1), proj(R, FLOOR, d0)], WOOD_MID)
    front = [proj(L, CABIN_TOP, d0), proj(R, CABIN_TOP, d0), proj(R, FLOOR, d0), proj(L, FLOOR, d0)]
    p.poly(front, WOOD)
    ox = front[0][0] - L   # the front wall's shift to the right (set back)
    p.line([(L + ox + 1, FLOOR - 7), (R + ox - 1, FLOOR - 7)], shade(WOOD, 0.8), 0.7)   # a board under the windows
    global PANES   # the windows' boxes, in the tower's units (the party's lights go behind them)
    side = [proj(R, CABIN_TOP + 4, d0 + 3), proj(R, CABIN_TOP + 4, d1 - 3), proj(R, FLOOR - 11, d1 - 3),
            proj(R, FLOOR - 11, d0 + 3)]
    PANES = [(min(x for x, _ in side), min(y for _, y in side), max(x for x, _ in side), max(y for _, y in side))]
    p.poly(side, panes if panes in (PANE_LIT, PANE_PARTY) else shade(panes, 0.85), shade(WOOD_MID, 0.6), 0.9)
    for i in range(3):
        x0 = L + ox + 3 + i * 15
        p.rect(x0, CABIN_TOP + 5, x0 + 13, FLOOR - 11, panes, shade(WOOD, 0.55), 0.9)
        PANES.append((x0, CABIN_TOP + 5, x0 + 13, FLOOR - 11))
        if panes == PANE_DAY:   # a glint of sky on the glass
            p.line([(x0 + 3, CABIN_TOP + 15), (x0 + 7.5, CABIN_TOP + 8.5)], (232, 242, 250), 0.9)
    if lit_figure:   # someone at the stove, a shadow in the middle window
        x = L + ox + 3 + 15 + 6.5
        p.circle(x, CABIN_TOP + 11.5, 2.5, (150, 96, 50), None)
        p.poly([(x - 4.2, FLOOR - 11), (x - 3.3, CABIN_TOP + 15), (x + 3.3, CABIN_TOP + 15), (x + 4.2, FLOOR - 11)],
               (150, 96, 50), None)
    # Roof: hipped, wide eaves all round; the front slope lit, the right one in shade; stovepipe and radio mast.
    e0, e1 = d0 - 6, d1 + 6
    fl, fr = proj(L - 7, EAVE + 1, e0), proj(R + 7, EAVE + 1, e0)
    br = proj(R + 7, EAVE + 1, e1)
    apex = proj((L + R) / 2, APEX, (d0 + d1) / 2)
    pipe = STOVEPIPE = proj(R - 2, EAVE - 7, d0 + CABIN_DEPTH * 0.6)
    p.rect(pipe[0] - 1.8, pipe[1] - 10, pipe[0] + 1.8, pipe[1] + 2, METAL)
    p.rect(pipe[0] - 2.6, pipe[1] - 12, pipe[0] + 2.6, pipe[1] - 9.5, METAL)
    global MAST   # the radio mast is SkyScene's (it sways in the wind): from inside the roof, up past the top
    base = proj((L + R) / 2 - 8, APEX + 12, (d0 + d1) / 2 + 4)
    MAST = (base, (base[0] + 0.6, apex[1] - 16))
    p.poly([fr, br, apex], shade(ROOF, 0.78))
    p.poly([fl, fr, apex], ROOF)
    p.poly([(fl[0] + 4, fl[1] - 1), (apex[0] - 2, apex[1] + 3), (apex[0] - 8, fl[1] - 1)], ROOF_LIGHT, None)
    p.poly([fl, fr, (fr[0], fr[1] + 2.4), (fl[0], fl[1] + 2.4)], shade(ROOF, 0.7))      # the eave's edge
    p.poly([fr, br, (br[0], br[1] + 2.4), (fr[0], fr[1] + 2.4)], shade(ROOF, 0.55))
    return pipe


def side_rail(p):
    """The balcony's railing along the right side (in front of the side wall)."""
    x0 = R + 7
    for f in (0.0, 0.33, 0.66, 1.0):
        bx, by = proj(x0, FLOOR, DECK_DEPTH * f)
        tx, ty = proj(x0, RAIL, DECK_DEPTH * f)
        p.poly([(tx - 0.8, ty), (tx + 0.8, ty), (bx + 0.8, by), (bx - 0.8, by)], WOOD_MID)
    p.line([proj(x0, RAIL, 0), proj(x0, RAIL, DECK_DEPTH)], WOOD_MID, 1.5)
    p.line([proj(x0, RAIL + 5.5, 0), proj(x0, RAIL + 5.5, DECK_DEPTH)], shade(WOOD_MID, 0.85), 1.0)


def floor_and_rail(p, front=True):
    if not front:
        return
    # The railing in front: posts and two rails, open, so who sits behind shows; spacing not quite even.
    for x in (L - 7, L - 0.5, L + 7.5, L + 15, L + 22.5, L + 30, L + 37.5, L + 44.5, R + 7):
        p.rect(x - 1.0, RAIL, x + 1.0, FLOOR, WOOD)
    p.rect(L - 8, RAIL - 1.5, R + 8, RAIL + 1.2, WOOD)
    p.line([(L - 7, RAIL + 5.5), (R + 7, RAIL + 5.5)], WOOD_MID, 1.1)


# MARK: - The hiker (about 26 units tall): the reference's yellow hoodie with a white hood and pocket, jeans rolled at
# the ankle, white sneakers, a navy backpack with red stripes, short curly hair and a goatee. Whenever he's out on the
# balcony he leans on the rail's left end, seen from the side, facing left (out over the forest).

HOODIE, HOODIE_DARK = (236, 178, 40), (196, 138, 22)
HOOD = (238, 236, 230)
SNEAKER = (240, 240, 236)
STRIPE = (200, 56, 52)
PHONES, PHONES_DARK = (150, 152, 158), (96, 98, 104)
BIN = (34, 34, 38)

HX = L - 2          # his hips, at the left end of the balcony
LEAN = -1.8         # the shoulders, a little forward toward the rail
TOP, HEM = FLOOR - 20, FLOOR - 8
HEAD_Y = FLOOR - 23.0


def body(p):
    """Legs, backpack and hoodie (the head and the near arm are drawn apart, the arm over the rail)."""
    x, feet = HX, FLOOR - 0.5
    for dx, color in ((1.8, shade(JEANS, 0.85)), (0, JEANS)):   # the far leg, then the near one
        p.rect(x - 1.4 + dx, HEM - 1.5, x + 1.4 + dx, feet - 1.6, color, OUTLINE, 0.8)
        p.rect(x - 1.5 + dx, feet - 2.4, x + 1.5 + dx, feet - 1.4, shade(JEANS, 1.25), OUTLINE, 0.5)   # the cuff
        p.poly([(x - 3.0 + dx, feet), (x - 2.6 + dx, feet - 1.6), (x + 1.5 + dx, feet - 1.6), (x + 1.6 + dx, feet)],
               SNEAKER, OUTLINE, 0.7)
    # The backpack, on his back: navy, a top flap, two red stripes down it.
    bx = x + 3.2
    p.poly([(bx, TOP + 0.5), (bx + 3.8, TOP + 1.2), (bx + 4.2, HEM - 2), (bx + 0.4, HEM - 1.5)], PACK, OUTLINE, 0.9)
    p.poly([(bx, TOP + 0.5), (bx + 3.8, TOP + 1.2), (bx + 3.9, TOP + 4.2), (bx + 0.1, TOP + 3.8)], shade(PACK, 1.25),
           OUTLINE, 0.6)
    for f in (0.3, 0.75):
        sx = bx + 3.8 * f
        p.line([(sx, TOP + 1.2), (sx + 0.3, HEM - 1.8)], STRIPE, 0.55)
    # The hoodie, leaning forward a little; the white pocket in front, the strap over the shoulder.
    p.poly([(x - 3.0, HEM), (x - 3.4 + LEAN, TOP + 2), (x - 1.4 + LEAN, TOP), (x + 2.4 + LEAN, TOP),
            (x + 3.8 + LEAN * 0.6, TOP + 2), (x + 3.6, HEM)], HOODIE, OUTLINE, 1.0)
    p.rect(x - 3.0, HEM - 0.9, x + 3.6, HEM, HOODIE_DARK, None)                         # the ribbed hem
    p.poly([(x - 3.1, HEM - 1.1), (x - 3.2, HEM - 5.0), (x + 0.4, HEM - 4.6), (x + 0.6, HEM - 1.1)], HOOD,
           OUTLINE, 0.6)
    p.line([(x + 2.0 + LEAN, TOP + 0.3), (x + 3.2, TOP + 6), (x + 3.4, HEM - 2)], shade(PACK, 0.9), 0.7)


def hood_down(p, hx, hy):
    """The white hood lying on his shoulders, behind the neck."""
    p.circle(hx + 2.6, hy + 3.6, 2.0, HOOD, OUTLINE, 0.7)


def face(p, hx, hy, hood=False, phones=False, down=0.0):
    """The head in profile, facing left: curly hair on top and at the back, ear, nose, goatee; the hood up over it,
    or headphones (over the hood too). `down` lowers the face toward a book."""
    ny = hy + down
    if hood:
        p.circle(hx + 1.4, hy - 0.4, 3.9, HOOD, OUTLINE, 0.8)   # open in front: the face covers its front edge
    p.circle(hx, ny, 3.1, SKIN, OUTLINE, 0.9)
    if not hood:   # the hair: a cap on top and at the back, its edge in little curls
        p.d.chord([P(hx - 3.25, ny - 3.4), P(hx + 3.25, ny + 3.1)], 192, 395, fill=HAIR)
        for a in np.linspace(195, 385, 9):
            r = np.radians(a)
            p.circle(hx + 3.05 * np.cos(r), ny + 3.05 * np.sin(r) - 0.15, 0.75, HAIR, None)
        p.circle(hx + 0.7, ny + 0.5, 0.85, SKIN, shade(SKIN), 0.5)   # the ear
    else:          # a few curls over the forehead
        for dx in (-2.2, -1.2):
            p.circle(hx + dx, ny - 2.4, 0.7, HAIR, None)
    p.circle(hx - 3.0, ny + 0.5, 0.8, SKIN, None)                     # the nose
    p.poly([(hx - 2.6, ny + 2.0), (hx - 1.4, ny + 2.2), (hx - 1.6, ny + 3.2), (hx - 2.6, ny + 3.0)], HAIR, None)   # goatee
    if phones:
        r = 4.3 if hood else 3.5
        p.d.arc([P(hx - r + 0.8, ny - r - 0.4), P(hx + r + 0.8, ny + r - 0.4)], 200, 340, fill=PHONES_DARK,
                width=max(1, round(0.9 * K * SS)))
        cx = hx + (1.6 if hood else 0.8)
        p.circle(cx, ny + 0.4, 1.55, PHONES, PHONES_DARK, 0.7)
        p.circle(cx, ny + 0.4, 0.7, PHONES_DARK, None)


def arm_on_rail(p):
    """The near arm: shoulder, elbow on the rail, the hand over its end."""
    sx, sy = HX - 0.6 + LEAN, TOP + 2.5
    p.line([(sx, sy), (HX - 2.5, RAIL - 1.2), (HX - 7.0, RAIL - 1.6)], HOODIE, 2.1)
    p.line([(sx, sy), (HX - 2.5, RAIL - 1.2)], HOODIE_DARK, 0.6)
    p.circle(HX - 7.6, RAIL - 1.7, 1.1, SKIN, None)


# The poses. Each draws what goes behind the front rail; `over` what goes over it (arms, mug, book). The head is
# HX - 2.6 across, HEAD_Y down, a little lower with the book.

def pose_head():
    return HX - 2.6, HEAD_Y


def watching(p):
    """Sunset: at the rail, looking out at the sun."""
    body(p)
    hood_down(p, *pose_head())
    face(p, *pose_head())


def watching_over(p):
    arm_on_rail(p)


def mug(p):
    body(p)
    hood_down(p, *pose_head())
    face(p, *pose_head())


def mug_over(p):
    """Sunrise: the near hand brings the hot chocolate to his face. Returns where the steam starts."""
    hx, hy = pose_head()
    sx, sy = HX - 0.6 + LEAN, TOP + 2.5
    p.line([(sx, sy), (HX - 2.0, RAIL - 2.0), (hx - 4.2, hy + 4.4)], HOODIE, 2.1)
    p.rect(hx - 6.6, hy + 1.6, hx - 3.4, hy + 5.4, MUG, OUTLINE, 0.8)
    p.rect(hx - 6.6, hy + 1.6, hx - 3.4, hy + 2.5, (92, 52, 32), None)   # chocolate
    p.circle(hx - 3.8, hy + 4.4, 1.0, SKIN, None)
    return at(hx - 5.0, hy + 1.2)


def binoculars(p):
    body(p)
    hood_down(p, *pose_head())
    face(p, *pose_head())


def binoculars_over(p):
    """Cloudy: looking far off through binoculars, elbow on the rail."""
    hx, hy = pose_head()
    sx, sy = HX - 0.6 + LEAN, TOP + 2.5
    p.line([(sx, sy), (HX - 2.6, RAIL - 1.4), (hx - 3.6, hy + 1.6)], HOODIE, 2.1)
    p.rect(hx - 6.4, hy - 1.3, hx - 2.6, hy + 1.4, BIN, OUTLINE, 0.7)
    p.rect(hx - 6.9, hy - 1.0, hx - 6.1, hy + 1.1, (70, 74, 82), None)   # the lens' rim
    p.circle(hx - 3.6, hy + 1.8, 1.0, SKIN, None)


def rain(p):
    """Rain by day: at the rail with the hood up, watching it fall."""
    body(p)
    face(p, *pose_head(), hood=True)


def rain_over(p):
    arm_on_rail(p)


def reading(p):
    """Morning: the book open on the rail, reading, head bent over it."""
    body(p)
    hx, hy = pose_head()
    hood_down(p, hx, hy)
    face(p, hx - 0.4, hy, down=1.2)


def reading_over(p):
    hx, hy = pose_head()
    sx, sy = HX - 0.6 + LEAN, TOP + 2.5
    p.line([(sx, sy), (HX - 2.0, RAIL - 1.0), (HX - 5.6, RAIL - 1.8)], HOODIE, 2.1)
    # The open book on the rail's top: two pages and its red cover's edge.
    p.poly([(HX - 9.6, RAIL - 1.6), (HX - 7.0, RAIL - 3.6), (HX - 6.4, RAIL - 1.4)], (244, 238, 222), OUTLINE, 0.6)
    p.poly([(HX - 6.4, RAIL - 1.4), (HX - 5.6, RAIL - 3.8), (HX - 3.2, RAIL - 1.8)], (232, 226, 208), OUTLINE, 0.6)
    p.line([(HX - 9.8, RAIL - 1.3), (HX - 3.0, RAIL - 1.5)], BOOK, 0.8)
    p.circle(HX - 5.2, RAIL - 1.6, 1.0, SKIN, None)


def music(p, hood=False):
    """Music playing: at the rail, enjoying it; the head is drawn apart (music_head), so SkyScene can nod it."""
    body(p)
    if not hood:
        hood_down(p, *pose_head())


def music_over(p, hood=False):
    arm_on_rail(p)


def music_head(p, hood=False):
    """The head with the headphones on (over the hood in the rain). Returns its pivot: the neck."""
    hx, hy = pose_head()
    face(p, hx, hy, hood=hood, phones=True)
    return hx + 1.2, FLOOR - 20


def watching_back(p):
    """Sunset, now and then: his back to us, leaning on the rail and looking out at the sun: the backpack on his back,
    the hood bunched above it, the back of his curly head, elbows out to the sides."""
    x, feet = HX - 1.0, FLOOR - 0.5
    for dx in (-1.6, 1.6):   # legs, heels toward us
        p.rect(x + dx - 1.4, HEM - 1.5, x + dx + 1.4, feet - 1.6, JEANS, OUTLINE, 0.8)
        p.rect(x + dx - 1.5, feet - 2.4, x + dx + 1.5, feet - 1.4, shade(JEANS, 1.25), OUTLINE, 0.5)
        p.rect(x + dx - 1.4, feet - 1.6, x + dx + 1.4, feet, SNEAKER, OUTLINE, 0.7)
    for side in (-1, 1):     # the sleeves, elbows out, forearms going forward to the rail
        p.line([(x + side * 4.0, TOP + 2.0), (x + side * 6.0, TOP + 7.0), (x + side * 4.6, TOP + 9.5)], HOODIE, 2.1)
    p.poly([(x - 4.4, HEM), (x - 4.8, TOP + 2), (x - 2.2, TOP), (x + 2.2, TOP), (x + 4.8, TOP + 2), (x + 4.4, HEM)],
           HOODIE, OUTLINE, 1.0)
    p.rect(x - 4.4, HEM - 0.9, x + 4.4, HEM, HOODIE_DARK, None)
    # The backpack, the reference's back view: navy, a lighter top flap, red stripes down it, two grey buckles.
    p.poly([(x - 3.4, TOP + 1.6), (x + 3.4, TOP + 1.6), (x + 3.7, HEM - 1.4), (x - 3.7, HEM - 1.4)], PACK, OUTLINE, 0.9)
    p.poly([(x - 3.4, TOP + 1.6), (x + 3.4, TOP + 1.6), (x + 3.3, TOP + 5.2), (x - 3.3, TOP + 5.2)], shade(PACK, 1.25),
           OUTLINE, 0.6)
    for dx in (-1.6, 1.6):
        p.line([(x + dx, TOP + 1.8), (x + dx, HEM - 1.6)], STRIPE, 0.6)
        p.rect(x + dx - 0.6, TOP + 5.0, x + dx + 0.6, TOP + 6.2, (176, 176, 180), None)
    p.poly([(x - 3.6, TOP + 2.6), (x - 3.2, TOP + 0.2), (x - 1.2, TOP - 0.6), (x + 1.2, TOP - 0.6), (x + 3.2, TOP + 0.2),
            (x + 3.6, TOP + 2.6)], HOOD, OUTLINE, 0.7)          # the hood, lying flat across the shoulders
    hx, hy = x, HEAD_Y
    for side in (-1, 1):
        p.circle(hx + side * 3.0, hy + 0.6, 0.85, SKIN, shade(SKIN), 0.5)   # ears
    p.circle(hx, hy, 3.2, HAIR, OUTLINE, 0.9)                 # the back of the head: all hair, little curls
    for a in np.linspace(180, 360, 8):
        r = np.radians(a)
        p.circle(hx + 3.1 * np.cos(r), hy + 3.1 * np.sin(r) - 0.1, 0.75, HAIR, None)
    p.rect(hx - 1.2, hy + 2.6, hx + 1.2, hy + 3.3, SKIN, None)   # the nape


POSES = {"watching": (watching, watching_over), "watching_back": (watching_back, lambda p: None), "mug": (mug, mug_over), "binoculars": (binoculars, binoculars_over),
         "rain": (rain, rain_over), "reading": (reading, reading_over),
         "music": (music, music_over), "music_rain": (lambda p: music(p, True), lambda p: music_over(p, True))}


# MARK: - Light and the trees in front

def tint_for(slot):
    """The light of a photo: its forest color next to the midday's, channel by channel."""
    def forest(s):
        a = np.asarray(Image.open(f"{SRC}/{s}.jpg").convert("RGB")).astype(np.float32)
        return a[960:1060, 1700:1880].reshape(-1, 3).mean(0)
    return np.clip(forest(slot) / forest(2), 0.12, 1.35)


def light(im, tint, keep=None):
    a = np.asarray(im).astype(np.float32)
    rgb = a[..., :3] * tint
    if keep is not None:   # lit windows keep their own light
        rgb = rgb * (1 - keep[..., None]) + a[..., :3] * keep[..., None]
    a[..., :3] = np.clip(rgb, 0, 255)
    return Image.fromarray(a.astype(np.uint8))


def treetops(slot):
    """
    The photo's big near trees (the darker, larger crowns at the bottom, in front of the forest of small ones), pixel
    by pixel: they go in front of the tower, whose legs go down among them (seen in the gaps between them, hidden
    behind them, never cut off in the air). Never over the balcony, so the hiker always shows.
    """
    im = cv2.imread(f"{SRC}/{slot}.jpg")
    lab = cv2.cvtColor(im, cv2.COLOR_BGR2LAB).astype(np.float32)[Y0:Y0 + H, X0:X0 + W]
    # How near each pixel looks: from the small crowns' color (just under the hazy band) to the big trees' (at the
    # bottom); medians, so a few odd pixels don't count.
    mid = np.median(lab[895 - Y0:915 - Y0].reshape(-1, 3), 0)
    near = np.median(lab[H - 30:H - 2].reshape(-1, 3), 0)
    d = near - mid
    score = cv2.GaussianBlur(((lab - mid) @ d) / (d @ d), (0, 0), 1.5)
    m = nd.binary_closing(nd.binary_opening(score > 0.55, np.ones((5, 3))), np.ones((5, 5)))
    m[:NEAR_FROM - Y0] = False
    m[NEAR_ALL - Y0:] = True                       # the bottom is all big trees in every photo
    lab_, n = nd.label(m)                          # only the trees: what joins the bottom, holes filled
    keep = np.unique(lab_[-1][lab_[-1] > 0])
    m = nd.binary_fill_holes(np.isin(lab_, keep))
    m = nd.binary_dilation(m, iterations=1)        # their dark outlines too
    # Under the balcony: nothing above just below its floor (the deck's edge shows).
    (dx0, dy), (dx1, _) = at(L - 9, FLOOR + 6), at(R + 7 + DECK_DEPTH * 0.72 + 2, FLOOR + 6)
    xs = np.arange(W) + X0
    m[:int(dy - Y0), (xs >= dx0) & (xs <= dx1)] = False
    a = cv2.GaussianBlur(m.astype(np.float32), (0, 0), 0.6)   # a soft pixel of edge
    photo = Image.open(f"{SRC}/{slot}.jpg").convert("RGBA").crop((X0, Y0, X0 + W, Y0 + H))
    photo.putalpha(Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8)))
    return photo


def sprite(slot, state):
    p = Pen()
    lit = state == "lit"
    panes = (PANE_LIT if lit else PANE_PARTY if state == "party" else PANE_OFF if state == "off" or slot in (0, 4)
             else PANE_DAY)
    legs(p)
    pipe = cabin(p, panes)   # lit: the light alone, no one at the window
    side_rail(p)
    mark = None
    floor_and_rail(p)
    pose = POSES.get(state)
    if pose:   # out on the balcony's left end: behind the front rail, the rail again over his hips, then his arms
        pose[0](p)
        floor_and_rail(p)
        mark = pose[1](p)
    im = p.im.resize((W, H), Image.LANCZOS)
    keep = None
    if lit:
        a = np.asarray(im).astype(np.float32)
        keep = ((np.abs(a[..., 0] - PANE_LIT[0]) < 30) & (np.abs(a[..., 1] - PANE_LIT[1]) < 40) &
                (np.abs(a[..., 2] - PANE_LIT[2]) < 50)).astype(np.float32)
    im = light(im, tint_for(slot), keep)
    if lit:   # a soft glow around the windows
        glow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        gd = ImageDraw.Draw(glow)
        (gx0, gy0), (gx1, gy1) = at(L - 2, CABIN_TOP + 2), at(R + CABIN_DEPTH, FLOOR - 8)
        gd.rectangle([gx0 - X0, gy0 - Y0, gx1 - X0, gy1 - Y0], fill=(255, 190, 100, 120))
        glow = glow.filter(ImageFilter.GaussianBlur(12))
        im = Image.alpha_composite(glow, im)
    return im, mark


def head_sprite(slot, state):
    """The music head, lit like its tower, and its pivot on the photo."""
    p = Pen()
    pivot = music_head(p, state == "music_rain")
    return light(p.im.resize((W, H), Image.LANCZOS), tint_for(slot)), at(*pivot)


def pane_boxes():
    """The windows on the photo, in pixels."""
    return [at(x0, y0) + at(x1, y1) for x0, y0, x1, y1 in PANES]


def glows():
    """For each window, the soft light it throws on the wood around it: a blurred alpha mask, cropped."""
    out = []
    for a, b, c, d in pane_boxes():
        m = Image.new("L", (W, H), 0)
        ImageDraw.Draw(m).rectangle([a - X0, b - Y0, c - X0, d - Y0], fill=200)
        m = m.filter(ImageFilter.GaussianBlur(9))
        box = m.getbbox()
        out.append((m.crop(box), X0 + box[0], Y0 + box[1]))
    return out


# What SkyScene does, for the previews: the lights step 4 times a second through three patterns of 4 seconds each
# (a chase of two lit windows, every other window, all flashing together); each window's color moves on to the next
# window every other step, and a lit one flashes bright and settles; an unlit one is nearly dark.
STEP = 0.25
PARTY = [(255, 60, 200), (60, 230, 255), (255, 210, 60), (110, 255, 110), (160, 92, 255)]
ORDER = [3, 0, 1, 2]   # each window's place from the left (the side window is the rightmost)


def lights(t):
    """Each window's (color, brightness 0..1) at t."""
    step, ph = int(t // STEP), (t % STEP) / STEP
    pattern = (step // 16) % 3
    out = []
    for i in range(4):
        pos = ORDER[i]
        on = ((step - pos) % 4 < 2 if pattern == 0 else (pos + step) % 2 == 0 if pattern == 1 else step % 2 == 0)
        col = PARTY[(pos + step // 2) % len(PARTY)]
        out.append((col, (0.7 + 0.3 * np.exp(-ph * 5)) if on else 0.07))
    return out


def party_frame(im, t):
    frame = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    gd = ImageDraw.Draw(frame)
    now = lights(t)
    for (a, b, c, d), (col, f) in zip(pane_boxes(), now):
        gd.rectangle([a - X0 - 1, b - Y0 - 1, c - X0 + 1, d - Y0 + 1], fill=tuple(int(v * f) for v in col) + (255,))
    frame.alpha_composite(im)
    for (m, gx, gy), (col, f) in zip(glows(), now):
        layer = Image.new("RGBA", m.size, col + (0,))
        layer.putalpha(m.point(lambda v: int(min(255, v * 1.1 * f))))
        frame.alpha_composite(layer, (gx - X0, gy - Y0))
    return frame


def nod(t):
    """
    The head's offset (pixels down) and tilt (degrees, forward is negative) at t: grooving, not counting: a nod a
    little faster or slower as it goes, sometimes deeper, and now and then a few calm seconds.
    """
    phase = 2 * np.pi * 1.8 * t + 0.9 * np.sin(t * 0.7)
    depth = 0.65 + 0.35 * np.sin(t * 0.37 + 1.0)
    calm = np.clip(1.6 * np.sin(t * 0.21) + 1.2, 0.55, 1)
    n = ((1 - np.cos(phase)) / 2) ** 1.4 * depth * calm
    return 2.6 * n, -24 * n + 3.0 * np.sin(t * 0.9)


def build(preview=False):
    sheets = []
    for slot, states in STATES.items():
        front = treetops(slot)
        front.save(f"{OUT}/{slot}/tower_front.png", optimize=True)
        tint = tint_for(slot)
        lines = [f"front tower_front.png {X0} {Y0}"]
        for state in states:
            im, mark = sprite(slot, state)
            im.save(f"{OUT}/{slot}/tower_{state}.png", optimize=True)
            extra = " steam %.1f %.1f" % mark if mark else ""
            if state == "lit":
                extra = " smoke %.1f %.1f" % at(STOVEPIPE[0], STOVEPIPE[1] - 12)   # the stovepipe's top
            lines.append(f"tower {state} tower_{state}.png {X0} {Y0}{extra}")
            if state.startswith("music"):
                hd, (px, py) = head_sprite(slot, state)
                box = hd.getbbox()   # only the head: a small bitmap to draw every frame
                box = (box[0] - 2, box[1] - 2, box[2] + 2, box[3] + 2)
                hd.crop(box).save(f"{OUT}/{slot}/tower_{state}_head.png", optimize=True)
                lines.append(f"head {state} tower_{state}_head.png {X0 + box[0]} {Y0 + box[1]} {px:.1f} {py:.1f}")
            if state == "party":
                for i, ((a, b, c, d), (m, gx, gy)) in enumerate(zip(pane_boxes(), glows())):
                    white = Image.new("L", m.size, 255)   # the alpha is what counts (SkyScene colors it)
                    Image.merge("LA", (white, m)).save(f"{OUT}/{slot}/tower_glow{i}.png", optimize=True)
                    lines.append(f"pane {a - 1:.1f} {b - 1:.1f} {c + 1:.1f} {d + 1:.1f}")
                    lines.append(f"glow tower_glow{i}.png {gx} {gy}")
            if preview:   # as SkyScene draws it: the mast, the tower over it, the near trees over both
                photo = Image.open(f"{SRC}/{slot}.jpg").convert("RGBA")
                color = tuple(int(c) for c in np.clip(np.array(shade(METAL, 0.7)) * tint, 0, 255))
                ImageDraw.Draw(photo).line([at(*MAST[0]), at(*MAST[1])], fill=color, width=2)
                photo.alpha_composite(im, (X0, Y0))
                photo.alpha_composite(front, (X0, Y0))
                sheets.append((slot, state, photo))
        (bx, by), (tx, ty) = at(*MAST[0]), at(*MAST[1])
        r, g, b = (int(c) for c in np.clip(np.array(shade(METAL, 0.7)) * tint, 0, 255))
        lines.insert(1, "antenna %.1f %.1f %.1f %.1f %02X%02X%02X %.1f" % (bx, by, tx, ty, r, g, b, 1.5))
        with open(f"{OUT}/{slot}/tower.txt", "w") as fh:
            fh.write("\n".join(lines) + "\n")
    return sheets


if __name__ == "__main__":
    out = build(preview=len(sys.argv) > 1)
    print(f"{sum(len(s) for s in STATES.values())} sprites")
