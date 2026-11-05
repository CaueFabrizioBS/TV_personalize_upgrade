package io.freemytv;

import android.app.ActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.os.Build;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.view.FrameMetrics;
import android.view.Window;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A quiet performance log for tv-cleaner-analysis: the home screen's frame times, the RAM (the TV's free and the home
 * screen's own, GPU included), its CPU, the chip's temperature state and the app on screen, one line every 15 minutes
 * while the TV is on, in Android/data/io.freemytv/files/perf.csv (the PC reads it with adb pull, whenever it's on).
 * The app on screen only with log_apps=true (this project's own TV; off in the public APK). Nothing on screen, and
 * nothing leaves the TV unless Home Settings → Share performance is on (PerfShare); the file stays under 2 MB.
 */
final class PerfLog {
    private PerfLog() {}

    private static final long EVERY_MS = 15 * 60_000L;
    private static final long MAX_BYTES = 2_000_000;
    private static final String[] SLOT = {"deep night", "morning", "midday", "sunset", "night", "sunrise"};
    private static final int BUCKETS = 100;   // 0..98 ms one by one, 99 = 99 ms or more
    private static final String HEADER = "taken_at,period,screen_on,app_on_screen,mem_available_mb,home_mb,graphics_mb,"
            + "cpu_pct,thermal,frames,janky,histogram";

    private static final int[] hist = new int[BUCKETS];
    private static Handler handler;
    private static Context app;
    private static long since, cpuAt, wallAt;

    /** Counts this window's frames, and starts the 15-minute lines (once per process). */
    static synchronized void watch(Window w, Context c) {
        if (handler == null) {
            HandlerThread t = new HandlerThread("perf");
            t.start();
            handler = new Handler(t.getLooper());
            app = c.getApplicationContext();
            since = System.currentTimeMillis();
            cpuAt = Process.getElapsedCpuTime();
            wallAt = SystemClock.elapsedRealtime();
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
        long began = SystemClock.elapsedRealtime();
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
        // This process's share of one CPU core since the last line.
        long cpu = Process.getElapsedCpuTime(), wall = SystemClock.elapsedRealtime();
        float cpuPct = wall > wallAt ? 100f * (cpu - cpuAt) / (wall - wallAt) : 0;
        cpuAt = cpu;
        wallAt = wall;
        PowerManager power = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
        boolean on = power.isInteractive();
        if (!on && frames == 0) {
            since = now;
            return;   // the TV was off: nothing to say
        }
        ActivityManager.MemoryInfo mem = new ActivityManager.MemoryInfo();
        ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(mem);
        Debug.MemoryInfo own = new Debug.MemoryInfo();
        Debug.getMemoryInfo(own);
        String graphics = own.getMemoryStat("summary.graphics");   // GPU textures and layers, in kB
        int thermal = Build.VERSION.SDK_INT >= 29 ? power.getCurrentThermalStatus() : -1;
        String line = String.format(Locale.US, "%s,%s,%s,%s,%d,%d,%d,%.1f,%d,%d,%d,%s%n",
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(now)),
                SLOT[HomeData.skySlot(now)], on ? "yes" : "no", Config.LOG_APPS ? foreground(since, now) : "",
                mem.availMem / 1_048_576, own.getTotalPss() / 1024,
                graphics == null ? 0 : Integer.parseInt(graphics) / 1024, cpuPct, thermal, frames, janky, h);
        since = now;
        File f = new File(app.getExternalFilesDir(null), "perf.csv");
        try {
            // Full, or written with other columns by an older version: start a new file, keep the last one.
            if (f.length() > MAX_BYTES || f.exists() && !HEADER.equals(firstLine(f))) {
                f.renameTo(new File(f.getParentFile(), "perf.old.csv"));
            }
            boolean fresh = !f.exists();
            try (FileWriter out = new FileWriter(f, true)) {
                if (fresh) out.write(HEADER + "\n");
                out.write(line);
            }
            PerfShare.maybeSend(app, f, SystemClock.elapsedRealtime() - began);
        } catch (IOException | RuntimeException e) {
            AppLog.w("perf log: " + e.getMessage());
        }
    }

    private static String firstLine(File f) throws IOException {
        try (BufferedReader in = new BufferedReader(new FileReader(f))) {
            return in.readLine();
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
