package io.freemytv;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What any app is playing (title, artist or channel, the app), from the media session each app publishes for the remote's
 * play/pause and the Assistant: one way for music, videos, radio and live streams alike. Reading the sessions needs this
 * notification listener allowed (HomeMode does it while Home Settings' Now Playing widget is on); it never reads a
 * notification and asks the system to unbind it at once, so no process stays up for it.
 */
public class NowPlaying extends NotificationListenerService {

    @Override public void onListenerConnected() {
        requestUnbind();
    }

    private static final float[] STILL = {0.55f, 1, 0.75f}, PERIOD_MS = {170, 230, 200};

    /** The bars' width for text of this cap height (drawBars). */
    static float barsWidth(float cap) {
        return cap * 1.12f;
    }

    /** Three level bars before the title, standing on its baseline, `cap` tall: still, or bouncing like a level meter. */
    static void drawBars(Canvas c, float x, float base, float cap, Paint p, boolean moving, RectF r) {
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < 3; i++) {
            float level = moving ? 0.3f + 0.7f * Math.abs((float) Math.sin(t / PERIOD_MS[i] + i * 1.3f)) : STILL[i];
            float bx = x + i * cap * 0.42f;
            r.set(bx, base - cap * level, bx + cap * 0.28f, base);
            c.drawRoundRect(r, cap * 0.14f, cap * 0.14f, p);
        }
    }

    static ComponentName component(Context c) {
        return new ComponentName(c, NowPlaying.class);
    }

    /** Whether Android lets us read the sessions: our listener is allowed. */
    static boolean allowed(Context c) {
        try {
            c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c));
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean asked;

    /** Music apps: their sessions feed the home screen's music widget, and only one of them plays at a time. */
    static final java.util.Set<String> MUSIC = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.google.android.youtube.tvmusic", "com.spotify.tv.android", "deezer.android.tv", "deezer.android.app"));
    /** Video apps: opening one pauses the music first (their own sound would take over a moment later anyway). */
    static final java.util.Set<String> VIDEO = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.netflix.ninja", "com.nuvio.tv", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow", "com.globo.globotv",
            "com.apple.atve.androidtv.appletv", "com.crunchyroll.crunchyroid"));

    static final String YOUTUBE = "com.google.android.youtube.tv", YOUTUBE_MUSIC = "com.google.android.youtube.tvmusic";
    private static long musicCheckedAt;
    private static boolean youtubeIsMusic;

    /**
     * Whether YouTube is playing music: on this TV "YouTube Music" is only a shortcut that opens YouTube on its music
     * section, so it's YouTube's session either way. It counts as music when it was last opened from the YouTube
     * Music icon (from anywhere: the system's list of opened apps says so; the shortcut opens just before YouTube),
     * and as video once YouTube is opened by itself. Needs usage access (adb shell appops set io.freemytv
     * GET_USAGE_STATS allow); without it, YouTube is video.
     */
    static boolean youtubeIsMusic(Context c) {
        long now = System.currentTimeMillis();
        if (now - musicCheckedAt < 10_000) return youtubeIsMusic;
        musicCheckedAt = now;
        boolean music = false;
        try {
            android.app.usage.UsageStatsManager u = c.getSystemService(android.app.usage.UsageStatsManager.class);
            android.app.usage.UsageEvents events = u.queryEvents(now - 24 * 3600_000L, now);
            android.app.usage.UsageEvents.Event e = new android.app.usage.UsageEvents.Event();
            long shortcutAt = -1;
            while (events.hasNextEvent()) {
                events.getNextEvent(e);
                if (e.getEventType() != android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED) continue;
                if (YOUTUBE_MUSIC.equals(e.getPackageName())) {
                    shortcutAt = e.getTimeStamp();
                    music = true;
                } else if (YOUTUBE.equals(e.getPackageName()) && e.getClassName() != null
                        && e.getClassName().endsWith(".MainActivity") && e.getTimeStamp() - shortcutAt > 5000) {
                    music = false;   // YouTube came up without the shortcut just before: opened by itself
                }
            }
        } catch (RuntimeException e) {
            AppLog.w("music: can't read which app opened YouTube: " + e.getMessage());
        }
        if (music != youtubeIsMusic) AppLog.w("music: YouTube counts as " + (music ? "music" : "video"));
        youtubeIsMusic = music;
        return music;
    }

    /** A music app, or YouTube (Premium plays it on with the home screen in front, music and videos alike). */
    static boolean isMusic(Context c, String pkg) {
        return MUSIC.contains(pkg) || YOUTUBE.equals(pkg);
    }

    /**
     * Pauses whatever music app is playing (opening a video app), but not `opening` itself (opening YouTube while
     * YouTube plays goes back to it). Quietly nothing without the listener allowed.
     */
    static void pauseMusic(Context c, String opening) {
        musicCheckedAt = 0;
        try {
            for (MediaController mc : c.getSystemService(MediaSessionManager.class).getActiveSessions(component(c))) {
                PlaybackState s = mc.getPlaybackState();
                if (isMusic(c, mc.getPackageName()) && !mc.getPackageName().equals(opening) && s != null
                        && s.getState() == PlaybackState.STATE_PLAYING) {
                    AppLog.w("music: pausing " + mc.getPackageName() + " for a video app");
                    mc.getTransportControls().pause();
                }
            }
        } catch (SecurityException | NullPointerException e) {
            AppLog.w("music: can't pause: " + e.getMessage());
        }
    }

    /**
     * The music apps' playback (YouTube Music, Spotify, Deezer; not YouTube), for the home screen's music widget,
     * whatever Home Settings' Now Playing says: the song that plays, its title, artist, cover, length and where it is.
     * Paused or stopped, it stays only a few seconds (between songs and ads the apps stop for a moment), then goes. When one music app starts playing while
     * another plays, the other is paused: one never plays over the other. Needs the same listener allowed.
     */
    static final class Music implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final List<MediaController.Callback> callbacks = new ArrayList<>();
        private final Map<String, Long> startedAt = new HashMap<>();    // when each app last started playing
        private final Map<String, Boolean> wasPlaying = new HashMap<>();
        private MediaController controller;   // the one shown
        private boolean on;
        private long stoppedAt;   // when the music stopped playing (0 while it plays)
        private static final long LINGER_MS = 5000;
        private final Runnable recheck = this::update;   // harmless after stop(): nothing left to show
        String title, artist;
        android.graphics.Bitmap art;
        long duration;   // ms, 0 when unknown
        boolean playing, showing;

        Music(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on) return;
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                AppLog.w("music widget: not allowed to read what's playing");
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (int i = 0; i < controllers.size(); i++) controllers.get(i).unregisterCallback(callbacks.get(i));
            controllers.clear();
            callbacks.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    if (!MUSIC.contains(mc.getPackageName()) && !YOUTUBE.equals(mc.getPackageName())) continue;
                    MediaController.Callback cb = new MediaController.Callback() {
                        @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
                        @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
                        @Override public void onSessionDestroyed() { update(); }
                    };
                    mc.registerCallback(cb, handler);
                    controllers.add(mc);
                    callbacks.add(cb);
                }
            }
            update();
        }

        private static boolean isPlaying(MediaController mc) {
            PlaybackState s = mc.getPlaybackState();
            int state = s == null ? PlaybackState.STATE_NONE : s.getState();
            return state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                    || state == PlaybackState.STATE_CONNECTING;
        }

        /** Where the song is now, in ms (moving on from the last report while it plays). */
        long position() {
            PlaybackState s = controller == null ? null : controller.getPlaybackState();
            if (s == null) return 0;
            long p = s.getPosition();
            if (s.getState() == PlaybackState.STATE_PLAYING) {
                p += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            }
            return duration > 0 ? Math.max(0, Math.min(duration, p)) : Math.max(0, p);
        }

        private void update() {
            // Who just started playing, and the newest of those that play.
            long now = SystemClock.elapsedRealtime();
            MediaController newest = null, paused = null;
            for (MediaController mc : controllers) {
                String pkg = mc.getPackageName();
                boolean p = isPlaying(mc);
                if (!isMusic(context, pkg)) continue;
                if (p && !Boolean.TRUE.equals(wasPlaying.get(pkg))) startedAt.put(pkg, now);
                wasPlaying.put(pkg, p);
                if (p) {
                    Long at = startedAt.get(pkg);
                    if (newest == null || (at != null ? at : 0) > startedAt.getOrDefault(newest.getPackageName(), 0L)) newest = mc;
                } else if (paused == null && mc.getPlaybackState() != null
                        && mc.getPlaybackState().getState() == PlaybackState.STATE_PAUSED) {
                    paused = mc;   // the most recent paused one (the system lists the newest first)
                }
            }
            // Only one plays: the others that still play are paused.
            for (MediaController mc : controllers) {
                if (!isMusic(context, mc.getPackageName())) continue;
                if (mc != newest && isPlaying(mc) && newest != null && !mc.getPackageName().equals(newest.getPackageName())) {
                    AppLog.w("music: " + newest.getPackageName() + " started, pausing " + mc.getPackageName());
                    mc.getTransportControls().pause();
                }
            }
            controller = newest != null ? newest : paused;
            boolean nowPlaying = newest != null;
            MediaMetadata m = controller == null ? null : controller.getMetadata();
            String t = null, a = null;
            android.graphics.Bitmap b = null;
            long d = 0;
            if (m != null) {
                MediaDescription desc = m.getDescription();
                t = m.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (TextUtils.isEmpty(t) && desc != null && desc.getTitle() != null) t = desc.getTitle().toString();
                a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
                if (TextUtils.isEmpty(a) && desc != null && desc.getSubtitle() != null) a = desc.getSubtitle().toString();
                b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
                if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
                if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
                if (b == null && desc != null) b = desc.getIconBitmap();
                d = Math.max(0, m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            }
            if (nowPlaying) {
                stoppedAt = 0;
                handler.removeCallbacks(recheck);
            } else if (stoppedAt == 0 && (playing || showing)) {   // just stopped: it goes in a few seconds
                stoppedAt = now;
                handler.removeCallbacks(recheck);
                handler.postDelayed(recheck, LINGER_MS + 50);
            }
            boolean lingering = !nowPlaying && stoppedAt != 0 && now - stoppedAt < LINGER_MS;
            boolean show = controller != null && !TextUtils.isEmpty(t) && (nowPlaying || lingering);
            boolean same = show == showing && nowPlaying == playing && Objects.equals(t, title) && Objects.equals(a, artist)
                    && b == art && d == duration;
            showing = show;
            playing = nowPlaying;
            title = t;
            artist = a;
            art = b;
            duration = d;
            if (!same) changed.run();
        }
    }

    /** A screen's watch: start() when it shows, stop() when it's hidden. text is null while nothing plays. */
    static final class Watch implements MediaSessionManager.OnActiveSessionsChangedListener {
        private final Context context;
        private final Runnable changed;
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final List<MediaController> controllers = new ArrayList<>();
        private final Map<String, String> labels = new HashMap<>();   // some players report every second
        private final MediaController.Callback callback = new MediaController.Callback() {
            @Override public void onPlaybackStateChanged(PlaybackState state) { update(); }
            @Override public void onMetadataChanged(MediaMetadata metadata) { update(); }
            @Override public void onSessionDestroyed() { update(); }
        };
        private boolean on;
        volatile String text;

        Watch(Context c, Runnable changed) {
            context = c;
            this.changed = changed;
        }

        void start() {
            if (on || !Prefs.nowPlaying()) return;   // off in Home Settings: nothing watched at all
            MediaSessionManager m = context.getSystemService(MediaSessionManager.class);
            try {
                m.addOnActiveSessionsChangedListener(this, component(context), handler);
                on = true;
                onActiveSessionsChanged(m.getActiveSessions(component(context)));
            } catch (SecurityException e) {
                // On, but not allowed (it was on when Original or the watchdog took it back): asked once, as Free My TV
                // mode's switch itself never allows it. A TV that refused it shows the row unavailable instead.
                if (!asked && Capabilities.adb(context) && !Capabilities.refused(context, Capabilities.LISTENER)) {
                    asked = true;
                    HomeMode.nowPlayingAccess(context);
                }
            }
        }

        void stop() {
            if (!on) return;
            on = false;
            context.getSystemService(MediaSessionManager.class).removeOnActiveSessionsChangedListener(this);
            onActiveSessionsChanged(null);
        }

        @Override public void onActiveSessionsChanged(List<MediaController> sessions) {
            for (MediaController mc : controllers) mc.unregisterCallback(callback);
            controllers.clear();
            if (sessions != null && on) {
                for (MediaController mc : sessions) {
                    mc.registerCallback(callback, handler);
                    controllers.add(mc);
                }
            }
            update();
        }

        private void update() {
            String now = null;
            for (MediaController mc : controllers) {   // most recent first
                PlaybackState s = mc.getPlaybackState();
                int state = s == null ? PlaybackState.STATE_NONE : s.getState();
                // A stream that buffers is still playing: the line doesn't blink back to the prices.
                if (state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_BUFFERING
                        || state == PlaybackState.STATE_CONNECTING) {
                    now = describe(mc);
                    break;
                }
            }
            if (Objects.equals(now, text)) return;
            text = now;
            changed.run();
        }

        /** "Title · Artist · App": the parts the app gives, then its name; null when there's none of them. */
        private String describe(MediaController mc) {
            List<String> parts = new ArrayList<>();
            MediaMetadata m = mc.getMetadata();
            MediaDescription d = m == null ? null : m.getDescription();
            if (d != null && !TextUtils.isEmpty(d.getTitle())) parts.add(d.getTitle().toString().trim());
            if (d != null && !TextUtils.isEmpty(d.getSubtitle())) parts.add(d.getSubtitle().toString().trim());
            String app = labels.get(mc.getPackageName());
            if (app == null) {
                try {
                    PackageManager pm = context.getPackageManager();
                    app = pm.getApplicationLabel(pm.getApplicationInfo(mc.getPackageName(), 0)).toString();
                } catch (PackageManager.NameNotFoundException e) {
                    app = "";   // an app without a launcher icon is hidden from us (Android 11+): no name to show
                }
                labels.put(mc.getPackageName(), app);
            }
            if (!app.isEmpty() && !parts.contains(app)) parts.add(app);
            return parts.isEmpty() ? null : TextUtils.join(" · ", parts);
        }
    }
}
