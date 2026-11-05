package io.freemytv;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Build;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Home Settings → Share performance, off until the person says yes: once a day, one anonymous line about how the home
 * screen ran, to a Google Form (no account, no server of ours). A random id tells the TVs apart and is forgotten when
 * it's turned off. Never the app on screen, the dates in perf.csv, the city or anything about what's watched. Each line
 * sent is also kept in perf_shared.csv, next to perf.csv, so anyone can read exactly what left the TV. log_ms is what
 * writing the log's last line took, so the log's own weight on the results can be seen.
 */
final class PerfShare {
    private PerfShare() {}

    /** The Form's formResponse address and its one question's field (entry.NNN); empty: nothing is sent. */
    private static final String FORM_URL = "https://docs.google.com/forms/d/e/1FAIpQLSfkf2712vT-N6GIJKehQ7A898zRfqi8OPAHX_4wxt3AXriCpQ/formResponse",
            FORM_FIELD = "entry.548541031";
    private static final long EVERY_MS = 24 * 3600_000L;
    private static final int MIN_LINES = 4;   // an hour of the TV on; less says nothing
    static final String HEADER = "format,id,brand,model,android,ram_mb,app_version,lines,frames,janky_pct,p50_ms,p95_ms,"
            + "mem_available_mb,home_mb,graphics_mb,cpu_pct,thermal_max,log_ms";

    /** After each line of perf.csv: sends what's new, once a day (adb shell setprop debug.freemytv.share 1: right now). */
    static void maybeSend(Context app, File log, long logMs) {
        if (!Prefs.sharePerf()) return;
        boolean forced = forced();
        long now = System.currentTimeMillis();
        if (!forced && now - Prefs.shareSentAt() < EVERY_MS && now >= Prefs.shareSentAt()) return;
        try {
            String line = summary(app, log, Prefs.shareSentAt(), forced ? 1 : MIN_LINES, logMs);
            if (line == null) return;
            if (!FORM_URL.isEmpty()) post(line);
            else if (!forced) return;
            File kept = new File(log.getParentFile(), "perf_shared.csv");
            boolean fresh = !kept.exists();
            try (FileWriter out = new FileWriter(kept, true)) {
                if (fresh) out.write(HEADER + "\n");
                out.write(line + "\n");
            }
            Prefs.setShareSentAt(now);
        } catch (IOException | RuntimeException e) {
            AppLog.w("perf share: " + e.getMessage());   // the next line of perf.csv tries again
        }
    }

    /** One line from perf.csv's lines since the last one sent; null with too few of them. */
    private static String summary(Context app, File log, long since, int minLines, long logMs) throws IOException {
        String after = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(since));
        int[] hist = new int[100];
        int lines = 0, thermal = -1;
        long frames = 0, janky = 0, mem = 0, home = 0, graphics = 0;
        double cpu = 0;
        try (BufferedReader in = new BufferedReader(new FileReader(log))) {
            in.readLine();
            for (String s; (s = in.readLine()) != null; ) {
                String[] c = s.split(",", -1);   // taken_at,period,screen_on,app,mem,home,graphics,cpu,thermal,frames,janky,hist
                if (c.length < 12 || c[0].compareTo(after) <= 0) continue;
                lines++;
                mem += Long.parseLong(c[4]);
                home += Long.parseLong(c[5]);
                graphics += Long.parseLong(c[6]);
                cpu += Double.parseDouble(c[7]);
                thermal = Math.max(thermal, Integer.parseInt(c[8]));
                frames += Long.parseLong(c[9]);
                janky += Long.parseLong(c[10]);
                for (String h : c[11].trim().isEmpty() ? new String[0] : c[11].trim().split(" ")) {
                    String[] b = h.split(":");
                    hist[Integer.parseInt(b[0])] += Integer.parseInt(b[1]);
                }
            }
        }
        if (lines < minLines) return null;
        ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
        ((ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
        String version = "";
        try {
            version = app.getPackageManager().getPackageInfo(app.getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        return String.format(Locale.US, "1,%s,%s,%s,%d,%d,%s,%d,%d,%.1f,%d,%d,%d,%d,%d,%.1f,%d,%d",
                Prefs.shareId(), clean(Build.MANUFACTURER), clean(Build.MODEL), Build.VERSION.SDK_INT,
                info.totalMem / 1_048_576, clean(version), lines, frames, frames == 0 ? 0 : 100.0 * janky / frames,
                percentile(hist, frames, 0.50), percentile(hist, frames, 0.95),
                mem / lines, home / lines, graphics / lines, cpu / lines, thermal, logMs);
    }

    private static int percentile(int[] hist, long frames, double share) {
        long seen = 0;
        for (int ms = 0; ms < hist.length; ms++) {
            seen += hist[ms];
            if (seen > 0 && seen >= frames * share) return ms;
        }
        return 0;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(',', ' ').trim();
    }

    private static void post(String line) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(FORM_URL).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(10_000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            try (OutputStream out = c.getOutputStream()) {
                out.write((FORM_FIELD + "=" + URLEncoder.encode(line, "UTF-8")).getBytes("UTF-8"));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("form answered " + code);
        } finally {
            c.disconnect();
        }
    }

    private static boolean forced() {
        try {
            Object v = Class.forName("android.os.SystemProperties").getMethod("get", String.class)
                    .invoke(null, "debug.freemytv.share");
            return "1".equals(String.valueOf(v).trim());
        } catch (Exception e) {
            return false;
        }
    }
}
