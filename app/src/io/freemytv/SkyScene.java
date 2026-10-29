package io.freemytv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The background photo for a part of the day, alive. Its layers (tools/sky_layers.py, tools/tower.py,
 * app/assets/sky/<slot>/) are drawn back to front:
 *  - the empty sky, and at night the stars (turning very slowly around a pole above the screen, twinkling);
 *  - the loose clouds: by day drifting to the right across the whole sky and round again (the big near ones faster,
 *    ~10 pixels a second, the small far ones ~3); at night they stay;
 *  - with the weather: a deck of grey clouds when it's cloudy, more with rain, and the whole scene in a dimmer, softer
 *    light that keeps that part of the day's colors (warm at sunrise and sunset, blue at night);
 *  - the birds (by day, not in the rain): a small flock far away now and then;
 *  - the far rain, behind the forest;
 *  - in front, the cloud bank and the forest;
 *  - the lookout tower among the trees on the right, the hiker doing what that hour and weather call for, with the
 *    steam of his hot chocolate or the smoke of his stove; the near treetops over its legs; with music playing, by
 *    day he leans on the rail's left end with headphones, nodding along, and at night the windows blink in party
 *    colors that pass from window to window;
 *  - the near rain, over everything.
 * Everything follows the wall clock, so the home screen and the screensaver show the same sky. Drawn in 1920x1080
 * units, scaled to the screen.
 */
final class SkyScene {
    /** Frames for the motion: 10 a second, on purpose, like a hand-drawn cartoon (and light on the TV's GPU). */
    static final long FRAME_MS = 100;
    static final int CLEAR = 0, CLOUDY = 1, RAIN = 2;
    private static final float POLE_X = 1150, POLE_Y = -1500, STAR_SPIN = 0.0012f;    // radians a second: ~2 pixels a second on screen
    private static final double BIRD_PERIOD_S = 45;
    private static final int DECK = 12;           // grey clouds in the rain (8 when it's only cloudy)

    final int slot;
    private final boolean night;
    private final Bitmap bg, fg;
    private final int fgTop;
    private final List<Cloud> clouds = new ArrayList<>();
    private final Bitmap[] deck = new Bitmap[3];
    private final Paint image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint deckPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG), wing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint puff = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF dst = new RectF();
    private final Path path = new Path();
    // Stars, in polar coordinates around the pole: cos, sin, radius, size, phase and period of the twinkle.
    private float[] starCos, starSin, starR, starSize, starPhase, starPeriod;
    private int[] starColor;
    // The tower: its sprite for each state, the near trees in front of it, the mast, and where steam or smoke rise.
    private final Map<String, Bitmap> towers = new HashMap<>();
    private final Map<String, float[]> towerPuff = new HashMap<>();
    private Bitmap towerFront;
    private float towerX, towerY;
    private float[] mast;   // base x, y, top x, y
    private int mastColor;
    private float mastWidth;
    // Music playing: by day the hiker's head (nodding about its neck), at night the windows' colored lights and glows.
    private final Map<String, Bitmap> heads = new HashMap<>();
    private final Map<String, float[]> headAt = new HashMap<>();   // x, y, pivot x, pivot y
    private final List<float[]> panes = new ArrayList<>();
    private final List<Bitmap> glows = new ArrayList<>();          // alpha only, colored by the paint
    private final List<float[]> glowAt = new ArrayList<>();
    private final Paint party = new Paint(Paint.FILTER_BITMAP_FLAG);
    // The lights step 4 times a second through three patterns of 4 s (a chase of two lit windows, every other window,
    // all flashing together); each window's color moves on to the next window every other step.
    private static final float STEP_S = 0.25f;
    private static final int[] PARTY = {0xFFFF3CC8, 0xFF3CE6FF, 0xFFFFD23C, 0xFF6EFF6E, 0xFFA05CFF};
    private static final int[] ORDER = {3, 0, 1, 2};   // each window's place from the left (the side one is rightmost)
    private final int[] lightColor = new int[4];
    private final float[] lightLevel = new float[4];
    /** A music app is playing (HomeView and DreamView say so): the tower joins in. */
    static volatile boolean music;
    private static long musicCheckedAt;
    private static boolean musicForced;
    // The rain: three planes (far, middle, near), each its drops' x and y at time 0.
    // Each plane is a tile of streaks drawn once (alpha only, tinted by the paint), repeated over the screen and slid
    // down a little every frame: one rectangle a plane instead of hundreds of lines (those took ~70 ms of CPU a frame
    // on the TCL, and the titles' moves stuttered).
    private final Bitmap[] rainTile = new Bitmap[3];
    private final android.graphics.BitmapShader[] rainShader = new android.graphics.BitmapShader[3];
    private final Paint[] rainPaint = new Paint[3];
    private final android.graphics.Matrix rainMatrix = new android.graphics.Matrix();
    private static final int[] RAIN_W = {640, 960, 960}, RAIN_H = {540, 540, 1080}, RAIN_COUNT = {117, 95, 35};
    private static final float[] RAIN_LEN = {12, 24, 60}, RAIN_SPEED = {900, 1500, 2400}, RAIN_WIDTH = {1.2f, 1.8f, 3f};
    // Color filters: the weather's grey light, then the home screen's veil (drawer), made again when either changes.
    private ColorMatrix veilFor;
    private int weatherFor = -1;
    private ColorMatrixColorFilter sceneFilter, deckFilter, veilFilter;

    private static final class Cloud {
        final Bitmap image;
        final float x, y, speed;

        Cloud(Bitmap image, float x, float y) {
            this.image = image;
            this.x = x;
            this.y = y;
            // The bigger, the nearer, the faster (parallax): 3 pixels a second for the smallest, 10 for the biggest.
            speed = 3 + 7 * Ui.clamp((image.getWidth() - 150) / 600f);
        }
    }

    /**
     * What the sky looks like now, from what is happening rather than the forecast's summary code: rain when rain is
     * falling (0.1 mm or more in the last 15 minutes) or a storm; cloudy with 75% or more of the sky covered, fog or
     * snow. Without those (an older answer), the code alone.
     */
    static int weatherKind(HomeData.Weather w) {
        float forced = debugWeather();   // to see it: adb shell setprop debug.freemytv.weather 0..2
        if (forced >= 0) return (int) forced;
        int code = w.code;
        if (Double.isNaN(w.rain) || Double.isNaN(w.clouds)) {
            if (code >= 51 && code <= 67 || code >= 80 && code <= 82 || code >= 95) return RAIN;
            if (code == 3 || code == 45 || code == 48 || code >= 71 && code <= 77 || code == 85 || code == 86) return CLOUDY;
            return CLEAR;
        }
        if (w.rain >= 0.1 || code >= 95) return RAIN;
        if (w.clouds >= 75 || code == 45 || code == 48 || code >= 71 && code <= 77 || code == 85 || code == 86) return CLOUDY;
        return CLEAR;
    }

    private static float debugWeather() {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "debug.freemytv.weather");
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Reads the layers of `slot` (HomeData.skySlot). Blocking: call off the UI thread. */
    static SkyScene load(Context c, int slot) throws IOException {
        return new SkyScene(c, slot);
    }

    private SkyScene(Context c, int slot) throws IOException {
        this.slot = slot;
        night = slot == 0 || slot == 4;
        String dir = "sky/" + slot + "/";
        bg = decode(c, dir + "bg.jpg");
        Bitmap front = null;
        int top = 1080;
        for (String[] p : lines(c, dir + "layers.txt")) {
            if (p[0].equals("fg")) {
                top = Integer.parseInt(p[1]);
                front = decode(c, dir + "fg.png");
            } else if (p[0].equals("cloud")) {
                clouds.add(new Cloud(decode(c, dir + p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3])));
            }
        }
        fg = front;
        fgTop = top;
        for (String[] p : lines(c, dir + "tower.txt")) {
            if (p[0].equals("front")) {
                towerFront = decode(c, dir + p[1]);
                towerX = Float.parseFloat(p[2]);
                towerY = Float.parseFloat(p[3]);
            } else if (p[0].equals("antenna")) {
                mast = new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])};
                mastColor = 0xFF000000 | Integer.parseInt(p[5], 16);
                mastWidth = Float.parseFloat(p[6]);
            } else if (p[0].equals("head")) {
                heads.put(p[1], decode(c, dir + p[2]));
                headAt.put(p[1], new float[] {Float.parseFloat(p[3]), Float.parseFloat(p[4]), Float.parseFloat(p[5]), Float.parseFloat(p[6])});
            } else if (p[0].equals("pane")) {
                panes.add(new float[] {Float.parseFloat(p[1]), Float.parseFloat(p[2]), Float.parseFloat(p[3]), Float.parseFloat(p[4])});
            } else if (p[0].equals("glow")) {
                Bitmap g = decode(c, dir + p[1]);
                glows.add(g.extractAlpha());
                g.recycle();
                glowAt.add(new float[] {Float.parseFloat(p[2]), Float.parseFloat(p[3])});
            } else if (p[0].equals("tower")) {
                towers.put(p[1], decode(c, dir + p[2]));
                if (p.length > 7) towerPuff.put(p[1], new float[] {p[5].equals("smoke") ? 1 : 0, Float.parseFloat(p[6]), Float.parseFloat(p[7])});
            }
        }
        for (int i = 0; i < deck.length; i++) deck[i] = decode(c, "sky/deck/d" + i + ".png");
        dot.setStyle(Paint.Style.FILL);
        wing.setStyle(Paint.Style.STROKE);
        wing.setStrokeCap(Paint.Cap.ROUND);
        wing.setStrokeJoin(Paint.Join.ROUND);
        puff.setStyle(Paint.Style.FILL);
        if (night) makeStars();
        makeRain();
    }

    private static List<String[]> lines(Context c, String name) throws IOException {
        List<String[]> out = new ArrayList<>();
        try (BufferedReader in = new BufferedReader(new InputStreamReader(c.getAssets().open(name)))) {
            for (String line; (line = in.readLine()) != null; ) if (!line.trim().isEmpty()) out.add(line.trim().split(" "));
        }
        return out;
    }

    private static Bitmap decode(Context c, String name) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        try (InputStream in = c.getAssets().open(name)) {
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (b == null) throw new IOException("can't decode " + name);
            b.prepareToDraw();
            return b;
        }
    }

    /**
     * A sky full of stars around the pole, as dense as the painted ones (~140 over the sky above the trees), so the
     * screen keeps the same number as they turn; only those on screen are drawn.
     */
    private void makeStars() {
        Random r = new Random(42);
        float r0 = (float) Math.hypot(0, POLE_Y), r1 = (float) Math.hypot(1920 + Math.abs(POLE_X), fgTop - POLE_Y);
        double area = Math.PI * (r1 * r1 - r0 * r0), density = 140.0 / (1920.0 * fgTop);
        int n = (int) (area * density);
        starCos = new float[n];
        starSin = new float[n];
        starR = new float[n];
        starSize = new float[n];
        starPhase = new float[n];
        starPeriod = new float[n];
        starColor = new int[n];
        int[] colors = {0xFFFFFFFF, 0xFFFFF6DC, 0xFFE4ECFF};
        for (int i = 0; i < n; i++) {
            double a = r.nextDouble() * 2 * Math.PI;
            starCos[i] = (float) Math.cos(a);
            starSin[i] = (float) Math.sin(a);
            starR[i] = (float) Math.sqrt(r0 * r0 + r.nextDouble() * (r1 * r1 - r0 * r0));   // even over the area
            float s = r.nextFloat();
            starSize[i] = s < 0.1f ? 2.6f : s < 0.35f ? 1.8f : 1.2f;
            starPhase[i] = r.nextFloat() * 6.283f;
            starPeriod[i] = 2.5f + r.nextFloat() * 5;
            starColor[i] = colors[r.nextInt(colors.length)];
        }
    }

    private void makeRain() {
        Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setColor(0xFFFFFFFF);
        for (int p = 0; p < 3; p++) {
            int tw = RAIN_W[p], th = RAIN_H[p];
            Bitmap tile = Bitmap.createBitmap(tw, th, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(tile);
            Random r = new Random(1000 + p);
            float len = RAIN_LEN[p], slant = slant(p);
            line.setStrokeWidth(RAIN_WIDTH[p]);
            for (int i = 0; i < RAIN_COUNT[p]; i++) {
                float x = r.nextFloat() * tw, y = r.nextFloat() * th;
                // Also a tile away on each side: a streak that crosses the tile's edge goes on in the next one.
                for (int ox = -1; ox <= 1; ox++) {
                    for (int oy = -1; oy <= 1; oy++) {
                        float x0 = x + ox * tw, y0 = y + oy * th;
                        c.drawLine(x0, y0, x0 + len * slant, y0 + len, line);
                    }
                }
            }
            tile.prepareToDraw();
            rainTile[p] = tile;
            rainShader[p] = new android.graphics.BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
            rainPaint[p] = new Paint(Paint.FILTER_BITMAP_FLAG);
            rainPaint[p].setShader(rainShader[p]);
        }
    }

    private static float slant(int plane) {
        return 0.12f + plane * 0.03f;
    }

    /**
     * Draws the sky over the whole of (w, h), at `alpha`, through `veil` (the home screen's drawer veil, or null);
     * `now` is the wall clock and `weather` CLEAR, CLOUDY or RAIN.
     */
    void draw(Canvas c, int w, int h, long now, float alpha, ColorMatrix veil, int weather) {
        float k = w / 1920f;
        int a = Math.round(255 * Ui.clamp(alpha));
        if (a == 0) return;
        double t = (now % 86_400_000L) / 1000.0;   // seconds today: precise enough as a float's input
        filters(weather, veil);
        image.setColorFilter(sceneFilter);
        dot.setColorFilter(veilFilter);
        wing.setColorFilter(sceneFilter);
        puff.setColorFilter(veilFilter);
        image.setAlpha(a);
        dst.set(0, 0, w, h);
        c.drawBitmap(bg, null, dst, image);
        if (night && weather != RAIN) drawStars(c, k, t, Math.round(a * (weather == CLOUDY ? 0.35f : 1)));
        c.save();
        c.scale(k, k);
        for (Cloud cl : clouds) drawCloud(c, cl, t, a);
        if (weather != CLEAR) drawDeck(c, t, a, weather == RAIN ? DECK : 8);
        if (!night && weather != RAIN) drawBirds(c, t, a);
        if (weather == RAIN) drawRain(c, t, a, 0);
        image.setAlpha(a);
        if (fg != null) c.drawBitmap(fg, 0, fgTop, image);
        drawTower(c, t, a, weather, now);
        if (weather == RAIN) {
            drawRain(c, t, a, 1);
            drawRain(c, t, a, 2);
        }
        c.restore();
    }

    /**
     * The scene's color filters: the weather's light, then the veil. Cloudy or rainy, the scene is dimmer and softer
     * (less color) but keeps its part of the day's tint: warm at sunrise and sunset, golden in the morning, blue at
     * night, a little cool at midday. The deck's clouds take that part of the day's light first.
     */
    private void filters(int weather, ColorMatrix veil) {
        if (weather == weatherFor && veil == veilFor && sceneFilter != null) return;
        weatherFor = weather;
        veilFor = veil;
        ColorMatrix scene = new ColorMatrix();
        if (weather != CLEAR) {
            float g = weather == RAIN ? 0.5f : 0.35f, d = weather == RAIN ? 0.76f : 0.88f;
            float keep = 1 - g, s = g / 3;
            float[] tint = night ? new float[] {0.92f, 0.95f, 1.06f} : slot == 3 || slot == 5 ? new float[] {1.04f, 0.96f, 0.9f}
                    : slot == 1 ? new float[] {1.02f, 0.99f, 0.92f} : new float[] {0.94f, 0.97f, 1.02f};
            float[] m = {tint[0] * d, tint[1] * d, tint[2] * d};
            scene.set(new float[] {
                m[0] * (keep + s), m[0] * s, m[0] * s, 0, 0,
                m[1] * s, m[1] * (keep + s), m[1] * s, 0, 0,
                m[2] * s, m[2] * s, m[2] * (keep + s), 0, 0,
                0, 0, 0, 1, 0});
        }
        ColorMatrix deckM = new ColorMatrix();
        float[] light = night ? new float[] {0.42f, 0.45f, 0.78f} : slot == 3 || slot == 5 ? new float[] {1f, 0.86f, 0.82f}
                : slot == 1 ? new float[] {1f, 0.97f, 0.88f} : new float[] {1f, 1f, 1f};
        deckM.setScale(light[0], light[1], light[2], 1);
        deckM.postConcat(scene);
        if (veil != null) {
            scene.postConcat(veil);
            deckM.postConcat(veil);
        }
        sceneFilter = new ColorMatrixColorFilter(scene);
        deckFilter = new ColorMatrixColorFilter(deckM);
        veilFilter = veil == null ? null : new ColorMatrixColorFilter(veil);
        deckPaint.setColorFilter(deckFilter);
    }

    /** A loose cloud: by day crossing the whole sky to the right and round again; at night where it was painted. */
    private void drawCloud(Canvas c, Cloud cl, double t, int a) {
        float x = cl.x;
        if (!night) {
            float wdt = cl.image.getWidth(), span = 1920 + wdt + 300;
            x = (float) (((cl.x + wdt + cl.speed * t) % span + span) % span) - wdt;
        }
        image.setAlpha(a);
        c.drawBitmap(cl.image, x, cl.y, image);
    }

    /** The weather's grey clouds: the midday's own, up to 1.4 times their size, drifting right at 5 to 12 pixels a second. */
    private void drawDeck(Canvas c, double t, int a, int n) {
        deckPaint.setAlpha(a);
        Random r = new Random(77);
        for (int i = 0; i < DECK; i++) {
            Bitmap b = deck[i % deck.length];
            float scale = 1.0f + r.nextFloat() * 0.4f, y = -60 + r.nextFloat() * 480, x0 = r.nextFloat() * 2600;
            float speed = 5 + 7 * (scale - 1.0f) / 0.4f;
            if (i >= n) continue;
            float bw = b.getWidth() * scale, span = 1920 + bw + 400;
            float x = (float) (((x0 + speed * t) % span + span) % span) - bw;
            dst.set(x, y, x + bw, y + b.getHeight() * scale);
            c.drawBitmap(b, null, dst, deckPaint);
        }
    }

    // MARK: - The tower

    /** What the hiker is doing: the hour and the weather. */
    private String towerState(long now, int weather) {
        if (musicOn()) {
            String s = night ? "party" : weather == RAIN ? "music_rain" : "music";
            if (towers.containsKey(s)) return s;
        }
        if (weather == RAIN) return night ? "off" : "rain";           // asleep inside, or out in a raincoat
        if (weather == CLOUDY && !night) return "binoculars";
        switch (slot) {
            case 4: return HomeData.minutesOfDay(now) >= 19 * 60 + 31 ? "lit" : "off";   // cooking, or asleep at 3–5
            case 5: return "mug";
            case 1: return "reading";
            case 2: return "empty";
            case 3:   // at sunset, every other 3 minutes he turns his back to us, looking out at the sun
                return HomeData.minutesOfDay(now) / 3 % 2 == 1 && towers.containsKey("watching_back") ? "watching_back" : "watching";
            default: return "off";
        }
    }

    private void drawTower(Canvas c, double t, int a, int weather, long now) {
        String state = towerState(now, weather);
        Bitmap b = towers.get(state);
        if (b == null) return;
        if (mast != null) {   // the radio mast, behind the roof
            wing.setColor(mastColor);
            wing.setAlpha(a);
            wing.setStrokeWidth(mastWidth);
            c.drawLine(mast[0], mast[1], mast[2], mast[3], wing);
        }
        boolean lights = state.equals("party") && panes.size() == 4;
        if (lights) {   // behind the cut-out windows: lit ones flash bright and settle, unlit ones are nearly dark
            int step = (int) (t / STEP_S), pattern = (step / 16) % 3;
            float flash = 0.7f + 0.3f * (float) Math.exp(-5 * (t % STEP_S) / STEP_S);
            party.setColorFilter(veilFilter);
            for (int i = 0; i < 4; i++) {
                int pos = ORDER[i];
                boolean on = pattern == 0 ? Math.floorMod(step - pos, 4) < 2 : pattern == 1 ? (pos + step) % 2 == 0 : step % 2 == 0;
                lightColor[i] = PARTY[(pos + step / 2) % PARTY.length];
                lightLevel[i] = on ? flash : 0.07f;
                float[] r = panes.get(i);
                party.setColor(dim(lightColor[i], lightLevel[i]));
                party.setAlpha(a);
                c.drawRect(r[0], r[1], r[2], r[3], party);
            }
        }
        image.setAlpha(a);
        c.drawBitmap(b, towerX, towerY, image);
        Bitmap hd = heads.get(state);
        if (hd != null) {   // grooving, not counting: nods a little faster or slower, sometimes deeper, now and then calm
            float[] h = headAt.get(state);
            double phase = 2 * Math.PI * 1.8 * t + 0.9 * Math.sin(t * 0.7);
            double depth = 0.65 + 0.35 * Math.sin(t * 0.37 + 1.0);
            double calm = Math.max(0.55, Math.min(1, 1.6 * Math.sin(t * 0.21) + 1.2));
            double n = Math.pow((1 - Math.cos(phase)) / 2, 1.4) * depth * calm;
            c.save();
            c.translate((float) (-0.8 * n), (float) (2.6 * n));   // the head goes down and a little forward
            c.rotate((float) (-24 * n + 3 * Math.sin(t * 0.9)), h[2], h[3]);
            c.drawBitmap(hd, h[0], h[1], image);
            c.restore();
        }
        if (lights) {   // the light they throw on the wood around them
            for (int i = 0; i < glows.size() && i < 4; i++) {
                party.setColor(lightColor[i]);
                party.setAlpha(Math.round(a * Math.min(1f, 1.1f * lightLevel[i] * 200 / 255f)));
                c.drawBitmap(glows.get(i), glowAt.get(i)[0], glowAt.get(i)[1], party);
            }
        }
        if (towerFront != null) c.drawBitmap(towerFront, towerX, towerY, image);
        float[] p = towerPuff.get(state);
        if (p != null) drawPuffs(c, p[0] == 1, p[1], p[2], t, a, weather == RAIN);
    }

    private static int dim(int color, float f) {
        return 0xFF000000 | Math.round((color >> 16 & 0xFF) * f) << 16 | Math.round((color >> 8 & 0xFF) * f) << 8
                | Math.round((color & 0xFF) * f);
    }

    /** Music playing, or to see it without: adb shell setprop debug.freemytv.music 1 (read every 2 s). */
    private static boolean musicOn() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - musicCheckedAt > 2000) {
            musicCheckedAt = now;
            try {
                Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class).invoke(null, "debug.freemytv.music");
                musicForced = "1".equals(String.valueOf(v).trim());
            } catch (Exception e) {
                musicForced = false;
            }
        }
        return music || musicForced;
    }

    /**
     * Steam from the mug (small, white, rising ~20 pixels and fading in 2.6 s) or smoke from the stovepipe (bigger,
     * grey, rising ~45 pixels in 5 s and leaning away, more in the rain), a few wisps at a time, swaying as they rise.
     */
    private void drawPuffs(Canvas c, boolean smoke, float x, float y, double t, int a, boolean wind) {
        int n = smoke ? 5 : 4;
        float period = smoke ? 5f : 2.6f, rise = smoke ? 45 : 20, size = smoke ? 3.2f : 1.3f;
        for (int i = 0; i < n; i++) {
            float u = (float) ((t / period + i / (float) n) % 1.0);
            float px = x + (float) Math.sin(u * 5 + i * 1.7 + t * 0.8) * (smoke ? 3.5f : 1.6f) * u
                    + (smoke ? u * (wind ? 22 : 10) : 0);
            float py = y - u * rise;
            float fade = Ui.clamp(u / 0.15f) * (1 - u);
            puff.setColor(smoke ? 0xFFB9B4B0 : 0xFFFFFFFF);
            puff.setAlpha(Math.round(a * fade * (smoke ? 0.45f : 0.55f)));
            c.drawCircle(px, py, size * (0.7f + u * 1.4f), puff);
        }
    }

    // MARK: - Rain

    /**
     * One plane of rain: 0 far (thin, short, behind the forest), 1 middle, 2 near (few, long, fastest). Streaks
     * falling a little slanted, wrapping from the bottom back to the top.
     */
    private void drawRain(Canvas c, double t, int a, int plane) {
        // Down (and along the slant) by the distance fallen, within one tile: the tile repeats.
        double fall = (RAIN_SPEED[plane] * t) % (RAIN_H[plane] * (double) RAIN_W[plane]);
        float dy = (float) (fall % RAIN_H[plane]), dx = (float) ((fall * slant(plane)) % RAIN_W[plane]);
        rainMatrix.setTranslate(dx, dy);
        rainShader[plane].setLocalMatrix(rainMatrix);
        Paint p = rainPaint[plane];
        p.setColorFilter(veilFilter);
        p.setColor(night ? 0xFFAAB4D2 : 0xFFE1E8F5);   // an alpha-only tile takes the paint's color
        p.setAlpha(Math.round(a * (plane == 0 ? 0.24f : plane == 1 ? 0.33f : 0.27f)));
        c.drawRect(0, 0, 1920, 1080, p);
    }

    // MARK: - Stars and birds

    private void drawStars(Canvas c, float k, double t, int a) {
        double turn = (t * STAR_SPIN) % (2 * Math.PI);
        float cos = (float) Math.cos(turn), sin = (float) Math.sin(turn);
        float maxY = fgTop + 4;
        for (int i = 0; i < starR.length; i++) {
            // The star's angle plus the turn, without a cos and sin for each.
            float ca = starCos[i] * cos - starSin[i] * sin, sa = starSin[i] * cos + starCos[i] * sin;
            float x = POLE_X + starR[i] * ca, y = POLE_Y + starR[i] * sa;
            if (x < -4 || x > 1924 || y < -4 || y > maxY) continue;
            float tw = 0.58f + 0.42f * (float) Math.sin(t * 6.283 / starPeriod[i] + starPhase[i]);
            dot.setColor(starColor[i]);
            dot.setAlpha(Math.round(a * tw * (starSize[i] > 2 ? 1f : 0.85f)));
            float s = starSize[i] * k;
            c.drawCircle(x * k, y * k, s, dot);
            if (starSize[i] > 2) {   // the brightest: a four-pointed glint, as painted
                dot.setAlpha(Math.round(a * tw * 0.55f));
                c.drawRect(x * k - 4 * s, y * k - 0.3f * s, x * k + 4 * s, y * k + 0.3f * s, dot);
                c.drawRect(x * k - 0.3f * s, y * k - 4 * s, x * k + 0.3f * s, y * k + 4 * s, dot);
            }
        }
    }

    /**
     * Now and then (about half of every 45 s) a flock of 2 to 5 far away, crossing the sky at 85 to 115 pixels a second, in
     * a loose V, bobbing gently; each bird flaps at its own pace and sometimes glides.
     */
    private void drawBirds(Canvas c, double t, int a) {
        long n = (long) Math.floor(t / BIRD_PERIOD_S);
        for (long e = n - 1; e <= n; e++) {   // a flock may still be crossing when the next period starts
            Random r = new Random(mix(e * 8 + slot));
            if (r.nextFloat() < 0.15f) continue;   // some periods without birds
            float speed = 85 + r.nextFloat() * 30;
            float start = (float) (e * BIRD_PERIOD_S) + r.nextFloat() * 20;
            float dir = r.nextBoolean() ? 1 : -1;
            float baseY = 150 + r.nextFloat() * 260;
            float size = 13 + r.nextFloat() * 5;
            int count = 2 + r.nextInt(4);
            float s = (float) (t - start);
            if (s < 0) continue;
            float lead = -80 + s * speed;   // from just off one edge to past the other
            if (lead > 1920 + 260) continue;
            boolean warm = slot == 3 || slot == 5;   // dark brown against sunset and sunrise, as painted
            wing.setColor(warm ? 0xFF4A2C2E : 0xFF34404F);
            wing.setAlpha(Math.round(a * (warm ? 0.92f : 0.85f)));
            wing.setStrokeWidth(2.6f);
            for (int b = 0; b < count; b++) {
                // Loose V: each one a little behind and to the side of the one before.
                float back = b * (44 + r.nextFloat() * 18), side = (b % 2 == 0 ? 1 : -1) * ((b + 1) / 2) * (18 + r.nextFloat() * 10);
                float flapHz = 3.0f + r.nextFloat() * 1.0f, ph = r.nextFloat() * 6.283f, glide = r.nextFloat() * 6.283f;
                float bs = size * (0.85f + r.nextFloat() * 0.3f);
                float along = lead - back;
                float x = dir > 0 ? along : 1920 - along;
                float y = baseY + side + 9 * (float) Math.sin(s * 0.7f + b);
                // Flapping, easing into a glide (wings slightly up) for a few seconds now and then.
                float g = smooth(Ui.clamp((float) Math.sin(s * 0.45f + glide) * 2.5f - 1.2f));
                float flap = (float) Math.sin(s * 6.283f * flapHz + ph) * (1 - g) + 0.25f * g;
                drawBird(c, x, y, bs, flap);
            }
        }
    }

    /** A gull far away: two wings bowed up from the body, the tips moving with `flap` (-1 down to 1 up). */
    private void drawBird(Canvas c, float x, float y, float s, float flap) {
        float tipY = y - flap * s * 0.55f, midY = y - s * 0.32f - flap * s * 0.18f;
        path.reset();
        path.moveTo(x - s, tipY);
        path.quadTo(x - s * 0.45f, midY, x, y);
        path.quadTo(x + s * 0.45f, midY, x + s, tipY);
        c.drawPath(path, wing);
    }

    /**
     * Spreads consecutive numbers far apart (SplitMix64's finalizer): java.util.Random's first values for seeds 1, 2,
     * 3... are almost the same, which made every period skip its birds.
     */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private static float smooth(float x) {
        x = Ui.clamp(x);
        return x * x * (3 - 2 * x);
    }

    /** Frees the bitmaps now (they're large), instead of when the garbage collector gets to them. */
    void release() {
        bg.recycle();
        if (fg != null) fg.recycle();
        for (Cloud cl : clouds) cl.image.recycle();
        for (Bitmap b : deck) if (b != null) b.recycle();
        for (Bitmap b : towers.values()) b.recycle();
        for (Bitmap b : heads.values()) b.recycle();
        for (Bitmap b : glows) b.recycle();
        for (Bitmap b : rainTile) if (b != null) b.recycle();
        if (towerFront != null) towerFront.recycle();
    }
}
