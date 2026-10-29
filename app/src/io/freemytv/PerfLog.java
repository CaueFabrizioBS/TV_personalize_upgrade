package io.freemytv;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.view.FrameMetrics;
import android.view.Window;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A quiet performance log for tv-cleaner-analysis: the home screen's frame times, the RAM and the app on screen, one
 * line every 15 minutes while the TV is on, in Android/data/io.freemytv/files/perf.csv (the PC reads it with
 * adb pull, whenever it's on). Nothing on screen, nothing sent anywhere; the file stays under 2 MB.
 */
final class PerfLog {
    private PerfLog() {}

    private static final long EVERY_MS = 15 * 60_000L;
    private static final long MAX_BYTES = 2_000_000;
    private static final String[] SLOT = {"deep night", "morning", "midday", "sunset", "night", "sunrise"};
    private static final int BUCKETS = 100;   // 0..98 ms one by one, 99 = 99 ms or more

    private static final int[] hist = new int[BUCKETS];
    private static Handler handler;
    private static Context app;
    private static long since;

    /** Counts this window's frames, and starts the 15-minute lines (once per process). */
    static synchronized void watch(Window w, Context c) {
        if (handler == null) {
            HandlerThread t = new HandlerThread("perf");
            t.start();
            handler = new Handler(t.getLooper());
            app = c.getApplicationContext();
            since = System.currentTimeMillis();
            handler.postDelayed(PerfLog::sample, EVERY_MS);
        }
        w.addOnFrameMetricsAvailableListener((window, metrics, dropped) -> {
            int ms = (int) Math.min(BUCKETS - 1, metrics.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000);
            synchronized (hist) {
                hist[ms]++;
            }
        }, handler);
    }

    private static void sample() {
        handler.postDelayed(PerfLog::sample, EVERY_MS);
        long now = System.currentTimeMillis();
        int frames = 0, janky = 0;
        StringBuilder h = new StringBuilder();
        synchronized (hist) {
            for (int ms = 0; ms < BUCKETS; ms++) {
                if (hist[ms] == 0) continue;
                frames += hist[ms];
                if (ms > 16) janky += hist[ms];
                h.append(h.length() > 0 ? " " : "").append(ms).append(':').append(hist[ms]);
                hist[ms] = 0;
            }
        }
        boolean on = ((PowerManager) app.getSystemService(Context.POWER_SERVICE)).isInteractive();
        if (!on && frames == 0) {
            since = now;
            return;   // the TV was off: nothing to say
        }
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mem);
        String line = String.format(Locale.US, "%s,%s,%s,%s,%d,%d,%d,%d,%s%n",
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(now)),
                SLOT[HomeData.skySlot(now)], on ? "yes" : "no", foreground(since, now),
                mem.availMem / 1_048_576, Debug.getPss() / 1024, frames, janky, h);
        since = now;
        File f = new File(app.getExternalFilesDir(null), "perf.csv");
        try {
            if (f.length() > MAX_BYTES) f.renameTo(new File(f.getParentFile(), "perf.old.csv"));
            boolean fresh = !f.exists();
            try (FileWriter out = new FileWriter(f, true)) {
                if (fresh) out.write("taken_at,period,screen_on,app_on_screen,mem_available_mb,home_mb,frames,janky,histogram\n");
                out.write(line);
            }
        } catch (IOException | RuntimeException e) {
            AppLog.w("perf log: " + e.getMessage());
        }
    }

    /** The app that was on screen last in [from, to] (usage access, granted with appops GET_USAGE_STATS). */
    private static String foreground(long from, long to) {
        try {
            UsageEvents ev = ((UsageStatsManager) app.getSystemService(Context.USAGE_STATS_SERVICE)).queryEvents(from, to);
            UsageEvents.Event e = new UsageEvents.Event();
            String last = "";
            while (ev.hasNextEvent()) {
                ev.getNextEvent(e);
                if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) last = e.getPackageName();
            }
            return last;
        } catch (RuntimeException e) {
            return "";
        }
    }
}
