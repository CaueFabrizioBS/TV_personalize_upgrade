package io.freemytv;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * The welcome tour: five glass cards over the living home screen, the first time it opens (HomeView, "tourSeen"). The
 * sky keeps moving behind them; the weather card shows the rain and the tower card the music scene, whatever the real
 * weather and music are (SkyScene.tourWeather, tourMusic). Next / Skip with the remote; Back skips too.
 */
final class Tour {
    interface Host {
        Scene scene();
        float k();
        RectF shelf();          // the shelf's glass, in screen pixels
        PointF tower();         // the tower's middle, in screen pixels (null while the sky loads)
        void step(int step);    // what the home screen shows behind each card
        void finished();
        void redraw();
    }

    static final int SKY = 0, WEATHER = 1, TOWER = 2, APPS = 3, READY = 4;
    private static final String[][] CARDS = {
        {"A sky that follows the clock", "Six scenes through the day: clouds drift by, birds fly and, at night, the stars turn."},
        {"The real weather", "Every 15 minutes the sky checks the weather where you live: clouds when it's cloudy, rain when it rains."},
        {"Someone lives in the tower", "He reads in the morning, goes hiking at midday and sleeps at night. With music, by day he nods along and, at night, the cabin throws a party."},
        {"Your apps, no ads", "Only the apps you use, in the order you like. Nothing sponsored, nothing playing by itself."},
        {"All set", "To go back to the original home screen: the Free My TV app › Home Screen › Original. Nothing was uninstalled from your TV."},
    };
    private static final int[] SLOTS = {0, 5, 1, 2, 3, 4};   // the day's order
    private static final String[] SLOT_NAMES = {"tour|Deep night", "tour|Sunrise", "tour|Morning", "tour|Midday", "tour|Sunset", "tour|Night"};
    private static final int TINT = 0xFF111113;

    private final Context context;
    private final Host host;
    private final Bitmap[] thumbs = new Bitmap[SLOTS.length];
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), image = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final RectF card = new RectF(), tmp = new RectF();
    private final Path path = new Path();
    private TextPaint titlePaint, bodyPaint, smallPaint, buttonPaint;
    private float paintK;
    private ValueAnimator anim;
    private boolean active;
    private int step, focus;   // focus: 0 = Next / Start, 1 = Skip

    Tour(Context c, Host host) {
        context = c;
        this.host = host;
    }

    boolean active() {
        return active;
    }

    void start() {
        if (active) return;
        active = true;
        new Thread(this::loadThumbs, "tour-thumbs").start();
        go(SKY);
    }

    private void go(int s) {
        step = s;
        focus = 0;
        host.step(s);
        if (anim != null) anim.cancel();
        anim = ValueAnimator.ofFloat(0, 1).setDuration(450);
        anim.addUpdateListener(a -> host.redraw());
        anim.start();
    }

    private void finish() {
        if (!active) return;
        active = false;
        if (anim != null) anim.cancel();
        host.finished();
        host.redraw();
    }

    /** The remote while the tour shows: everything stays here except the media and volume keys. */
    boolean key(KeyEvent e) {
        if (!active) return false;
        int code = e.getKeyCode();
        if (KeyEvent.isMediaSessionKey(code) || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE || code == KeyEvent.KEYCODE_HOME) return false;
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN, up = e.getAction() == KeyEvent.ACTION_UP;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down && step != READY) {
                    focus = code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
                    host.redraw();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (up) {   // on release, so the key never reaches the home screen behind
                    if (focus == 1 || step == READY) finish();
                    else go(step + 1);
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (up) finish();
                return true;
            default:
                return true;
        }
    }

    // MARK: - Drawing (the whole screen, in its own layer)

    void draw(Canvas c, int w, int h) {
        if (!active) return;
        float k = host.k();
        makePaints(k);
        float a = anim == null ? 1 : anim.getAnimatedFraction();   // the animator's own last frame is exactly 1
        a = 1 - (1 - a) * (1 - a);
        if (step == TOWER) spotlight(c, w, h, k);
        if (step == APPS) shelfLight(c, w, h, k);

        float width = 900 * k, pad = 56 * k, inner = width - 2 * pad;
        StaticLayout title = layout(Ui.tr(CARDS[step][0]), titlePaint, inner, 1f);
        StaticLayout body = layout(Ui.tr(CARDS[step][1]), bodyPaint, inner, 1.22f);
        float strip = step == SKY ? 63 * k + 52 * k : 0;
        float height = 86 * k + strip + title.getHeight() + 18 * k + body.getHeight() + 40 * k + 68 * k + 48 * k;
        float left = 96 * k, top = step == APPS ? 250 * k : 960 * k - height;
        top += (1 - a) * 18 * k;
        card.set(left, top, left + width, top + height);
        int save = c.saveLayerAlpha(0, 0, w, h, Math.round(255 * a));
        host.scene().glass(c, card, 36 * k, TINT, 0.62f, 0, 0, 0.02f, 1);
        fill.setStyle(Paint.Style.STROKE);
        fill.setStrokeWidth(2 * k);
        fill.setColor(0x66FFFFFF);
        c.drawRoundRect(card, 36 * k, 36 * k, fill);
        fill.setStyle(Paint.Style.FILL);

        for (int i = 0; i < CARDS.length; i++) {   // where we are
            fill.setColor(i == step ? 0xFFFFFFFF : 0x70FFFFFF);
            c.drawCircle(left + pad + i * 30 * k, top + 52 * k, (i == step ? 7 : 5) * k, fill);
        }
        float y = top + 86 * k;
        if (step == SKY) {
            drawStrip(c, left + pad, y, k);
            y += strip;
        }
        c.save();
        c.translate(left + pad, y);
        title.draw(c);
        c.translate(0, title.getHeight() + 18 * k);
        body.draw(c);
        c.restore();
        float by = card.bottom - 48 * k - 68 * k;
        float x = button(c, left + pad, by, step == READY ? Ui.tr("tour|Start") : Ui.tr("tour|Next") + "  ›", focus == 0, k);
        if (step != READY) button(c, x + 20 * k, by, Ui.tr("tour|Skip"), focus == 1, k);
        c.restoreToCount(save);
    }

    private static StaticLayout layout(String s, TextPaint p, float width, float spacing) {
        return StaticLayout.Builder.obtain(s, 0, s.length(), p, Math.max(1, Math.round(width)))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, spacing).setIncludePad(false).build();
    }

    private void makePaints(float k) {
        if (titlePaint != null && paintK == k) return;
        paintK = k;
        titlePaint = new TextPaint(Ui.text(Ui.semibold, 54 * k, 0xFFFFFFFF));
        bodyPaint = new TextPaint(Ui.text(Ui.regular, 30 * k, 0xEBFFFFFF));
        smallPaint = new TextPaint(Ui.text(Ui.medium, 19 * k, 0xD9FFFFFF));
        buttonPaint = new TextPaint(Ui.text(Ui.semibold, 30 * k, 0xFFFFFFFF));
    }

    /** A pill button; returns its right edge. Focused: white, with a soft glow (like the shelf's focus). */
    private float button(Canvas c, float x, float y, String label, boolean focused, float k) {
        float w = buttonPaint.measureText(label) + 72 * k, h = 68 * k;
        tmp.set(x, y, x + w, y + h);
        if (focused) {
            fill.setColor(0xFFF5F5F7);
            fill.setShadowLayer(18 * k, 0, 0, 0x99FFFFFF);
            c.drawRoundRect(tmp, h / 2, h / 2, fill);
            fill.clearShadowLayer();
            buttonPaint.setColor(0xFF1C1C1E);
        } else {
            fill.setColor(0x26FFFFFF);
            c.drawRoundRect(tmp, h / 2, h / 2, fill);
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth(2 * k);
            fill.setColor(0x80FFFFFF);
            c.drawRoundRect(tmp, h / 2, h / 2, fill);
            fill.setStyle(Paint.Style.FILL);
            buttonPaint.setColor(0xEBFFFFFF);
        }
        Paint.FontMetrics m = buttonPaint.getFontMetrics();
        c.drawText(label, x + 36 * k, tmp.centerY() - (m.ascent + m.descent) / 2, buttonPaint);
        return x + w;
    }

    /** The six scenes of the day, small, the one on screen now outlined. */
    private void drawStrip(Canvas c, float x, float y, float k) {
        int now = HomeData.skySlot(System.currentTimeMillis());
        float cw = 112 * k, ch = 63 * k, gap = 8 * k, r = 12 * k;
        for (int i = 0; i < SLOTS.length; i++) {
            tmp.set(x + i * (cw + gap), y, x + i * (cw + gap) + cw, y + ch);
            Bitmap b = thumbs[i];
            if (b != null) {
                c.save();
                path.reset();
                path.addRoundRect(tmp, r, r, Path.Direction.CW);
                c.clipPath(path);
                c.drawBitmap(b, null, tmp, image);
                c.restore();
            }
            fill.setStyle(Paint.Style.STROKE);
            fill.setStrokeWidth((SLOTS[i] == now ? 3 : 1) * k);
            fill.setColor(SLOTS[i] == now ? 0xE6FFFFFF : 0x50FFFFFF);
            c.drawRoundRect(tmp, r, r, fill);
            fill.setStyle(Paint.Style.FILL);
            c.drawText(Ui.tr(SLOT_NAMES[i]), tmp.left + 4 * k, tmp.bottom + 26 * k, smallPaint);
        }
    }

    /** Everything a little darker except around the tower. */
    private void spotlight(Canvas c, int w, int h, float k) {
        PointF t = host.tower();
        if (t == null) return;
        float r = 520 * k;
        fill.setShader(new RadialGradient(t.x, t.y, r, new int[] {0x00000000, 0x00000000, 0x80000000},
                new float[] {0, 0.42f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, fill);
        fill.setShader(null);
    }

    /** The screen darker except the shelf. */
    private void shelfLight(Canvas c, int w, int h, float k) {
        RectF s = host.shelf();
        path.reset();
        path.setFillType(Path.FillType.EVEN_ODD);
        path.addRect(0, 0, w, h, Path.Direction.CW);
        if (s != null && !s.isEmpty()) {
            tmp.set(s);
            tmp.inset(-12 * k, -12 * k);
            path.addRoundRect(tmp, 60 * k, 60 * k, Path.Direction.CW);
        }
        fill.setColor(0x8C000000);
        c.drawPath(path, fill);
        path.setFillType(Path.FillType.WINDING);
    }

    /** The day's scenes, small: each slot's sky with its forest band (off the UI thread). */
    private void loadThumbs() {
        for (int i = 0; i < SLOTS.length; i++) {
            String dir = "sky/" + SLOTS[i] + "/";
            try {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                Bitmap bg, fg;
                try (InputStream in = context.getAssets().open(dir + "bg.jpg")) { bg = BitmapFactory.decodeStream(in, null, o); }
                o.inSampleSize = 8;
                try (InputStream in = context.getAssets().open(dir + "fg.png")) { fg = BitmapFactory.decodeStream(in, null, o); }
                int fgTop = 543;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(context.getAssets().open(dir + "layers.txt")))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        String[] p = line.trim().split("\\s+");
                        if (p.length > 1 && p[0].equals("fg")) fgTop = Integer.parseInt(p[1]);
                    }
                }
                Bitmap t = Bitmap.createBitmap(240, 135, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(t);
                c.drawBitmap(bg, null, new Rect(0, 0, 240, 135), image);
                if (fg != null) c.drawBitmap(fg, null, new RectF(0, fgTop / 8f, 240, fgTop / 8f + fg.getHeight() * 240f / fg.getWidth()), image);
                thumbs[i] = t;
            } catch (Exception e) {
                AppLog.w("tour: " + dir + " " + e.getMessage());
            }
        }
        host.redraw();
    }
}
