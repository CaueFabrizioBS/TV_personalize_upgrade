package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.IOException;

/**
 * Keeps the TV on while the music widget is on screen. The screen-on flag (HomeView, MusicSaver) holds the screen, but
 * Android 11+ also has an inactivity timer that ignores it (attentive_timeout: "turn off after X without a key, even
 * while watching"), and TCL turns the TV off with it in the middle of the music. While the widget shows, both timers
 * are switched off over the app's own ADB; when it goes, they get back exactly what they had. The values saved stay
 * on disk, so a crash or a power cut in the middle is undone at the next start or boot (restore()).
 */
final class StayAwake {
    private StayAwake() {}

    private static final String[] TIMERS = {"attentive_timeout", "sleep_timeout"};
    private static final long SETTLE_MS = 3_000;   // the home screen and MusicSaver hand over: only the last word counts
    private static Handler worker;

    /** Called by the screens whenever the music widget's state changes; applied once it settles. */
    static synchronized void want(Context c, boolean on) {
        Context app = c.getApplicationContext();
        if (worker == null) {
            HandlerThread t = new HandlerThread("stay-awake");
            t.start();
            worker = new Handler(t.getLooper());
        }
        worker.removeCallbacksAndMessages(null);
        worker.postDelayed(() -> apply(app, on), SETTLE_MS);
    }

    /** After a boot: gives the timers back if the TV went off while the music held them. */
    static void restore(Context c) {
        new Thread(() -> apply(c.getApplicationContext(), false)).start();
    }

    private static synchronized void apply(Context app, boolean on) {
        SharedPreferences p = app.getSharedPreferences("stay_awake", Context.MODE_PRIVATE);
        boolean held = p.getBoolean("held", false);
        if (on == held) return;
        try (Adb adb = Adb.connect(app, 10_000, null)) {
            if (on) {
                SharedPreferences.Editor e = p.edit();
                for (String t : TIMERS) e.putString(t, adb.shell("settings get secure " + t).trim());
                e.putBoolean("held", true).commit();   // saved before touching anything
                for (String t : TIMERS) adb.shell("settings put secure " + t + " -1");
            } else {
                for (String t : TIMERS) {
                    String v = p.getString(t, "null");
                    adb.shell(v.matches("-?\\d+") ? "settings put secure " + t + " " + v : "settings delete secure " + t);
                }
                p.edit().clear().commit();
            }
            AppLog.w("stay awake: " + (on ? "timers off while the music widget shows" : "timers back"));
        } catch (IOException e) {
            AppLog.w("stay awake: " + e.getMessage());   // ADB didn't answer: tried again at the next change
        }
    }
}
