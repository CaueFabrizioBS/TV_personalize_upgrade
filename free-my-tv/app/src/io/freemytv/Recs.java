package io.freemytv;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Home screen picks ("This morning / afternoon / tonight"): what's trending this week plus recent releases, only from
 * the streaming apps installed on the TV (Netflix, Prime Video, Disney+, Max, Apple TV, Okko, Hulu, Paramount+, Peacock,
 * Globoplay...), via TMDB (an open movie database that knows what streams where in each country). Three per hour.
 * "Watch" opens the service's app.
 *
 * Each person uses their own free TMDB key (config.properties "tmdb_key", built into the app). It never goes to the log.
 */
final class Recs {
    static final class Pick {
        int id;
        String kind;            // "movie" or "tv"
        String title, backdrop, meta = "", app, pkg;
        String logo;            // TMDB's title logo (the name in the film's own lettering, transparent PNG), or null
        String poster;          // TMDB's poster (portrait), for the stack of posters on the home screen; null for games
        String link;            // opens the title in its app (looked up with Watchmode), or null: the app's home
        boolean detailed;
        boolean fresh;          // a new season or episode of one of your shows (favorites.json): Shows shows it first
        boolean digital;        // a movie just out of theaters on digital (rent, buy or streaming): Movies shows it first
        String where;           // where it's officially on, e.g. "Rent on Apple TV" (null when TMDB doesn't say)
        boolean allAges;        // rated for all ages in REGION (Brazil's "L", US "G" / "TV-Y"…): kids' fare, left out
        boolean horror;         // TMDB tags it horror or slasher (shows have no Horror genre): out when 27 is excluded
        boolean resume;         // the Continue tab (Trakt): something you're in the middle of, or a show's next episode
        float progress;         // how much of it you've watched (0 to 1), drawn as a bar on the card
        java.io.File photo;     // one of your own photos (Photos): no title, no caption
    }

    interface Listener { void onRecs(); }

    private static final String API = "https://api.themoviedb.org/3";
    /** The backdrops: on a 720p UI, TMDB's 1280-wide copy shows the same and is lighter to download and decode. */
    private static final String IMAGES = "https://image.tmdb.org/t/p/" + (Backdrop.W <= 1280 ? "w1280" : "original");
    /** TMDB's Animation genre (movies and shows: cartoons and anime), left out with hide_animation=true. */
    private static final int ANIMATION = 16;
    private static final long LIST_EVERY = 6 * 3600_000L;
    /** Bump when what is saved for each title changes (the saved list is discarded and fetched again). */
    private static final int CACHE_VERSION = 12;
    /** Nuvio (a Stremio-style player): with "source": "nuvio" in favorites.json every pick opens its page there. */
    static final String NUVIO = "com.nuvio.tv";
    private static final int MANHA = 0, TARDE = 1, NOITE = 2;
    /** Service name on TMDB (part of the name, lowercase) → TV app (HomeApps key, package). */
    private static final String[][] SERVICES = {
        {"netflix", "netflix", "com.netflix.ninja"},
        {"prime video", "prime", "com.amazon.amazonvideo.livingroom"},
        {"prime video", "prime", "com.amazon.firebat"},
        {"prime video", "prime", "com.amazon.avod"},
        {"disney", "disney", "com.disney.disneyplus"},
        {"hulu", "hulu", "com.hulu.livingroomplus"},
        {"hulu", "hulu", "com.hulu.plus"},
        {"paramount", "paramount", "com.cbs.ott"},
        {"paramount", "paramount", "com.cbs.ca"},      // Paramount+ outside the US (a Sony in the UK; Canada)
        {"peacock", "peacock", "com.peacocktv.peacockandroid"},
        {"peacock", "peacock", "com.peacock.peacockfiretv"},
        {"globoplay", "globoplay", "com.globo.globotv"},
        {"max", "max", "com.wbd.stream"},
        {"max", "max", "com.hbo.hbonow"},
        {"apple tv", "appletv", "com.apple.atve.androidtv.appletv"},
        {"apple tv", "appletv", "com.apple.atve.sony.appletv"},   // Sony's own build
        {"apple tv", "appletv", "com.apple.atve.amazon.appletv"},
        {"hotstar", "hotstar", "in.startv.hotstar"},   // TMDB: "JioHotstar" (earlier "Disney Plus Hotstar")
        {"crunchyroll", "crunchyroll", "com.crunchyroll.crunchyroid"},
        {"now tv", "now", "com.bskyb.nowtv.beta"},     // UK: Sky's NOW (TMDB: "Now TV", "Now TV Cinema")
        {"oneplay", "oneplay", "net.cme.voyo.cz.tvapp"},    // Czechia: Voyo and O2 TV became Oneplay, same app
        // Russia (TMDB's watch providers for RU)
        {"okko", "okko", "ru.more.play"},
        {"kinopoisk", "kinopoisk", "ru.kinopoisk.tv"},
        {"premier", "premier", "gpm.tnt_premier"},
        {"amediateka", "amediateka", "ru.amediateka"},
        {"more tv", "wink", "ru.rt.video.app.tv"},   // TMDB: "More TV" (more.tv is part of Rostelecom's Wink now)
    };
    private static final Map<String, String> SERVICE_NAME = new HashMap<>();
    static {
        SERVICE_NAME.put("netflix", "Netflix");
        SERVICE_NAME.put("prime", "Prime Video");
        SERVICE_NAME.put("disney", "Disney+");
        SERVICE_NAME.put("hulu", "Hulu");
        SERVICE_NAME.put("paramount", "Paramount+");
        SERVICE_NAME.put("peacock", "Peacock");
        SERVICE_NAME.put("globoplay", "Globoplay");
        SERVICE_NAME.put("max", "Max");
        SERVICE_NAME.put("appletv", "Apple TV");
        SERVICE_NAME.put("hotstar", "JioHotstar");
        SERVICE_NAME.put("okko", "Okko");
        SERVICE_NAME.put("crunchyroll", "Crunchyroll");
        SERVICE_NAME.put("now", "NOW");
        SERVICE_NAME.put("oneplay", "Oneplay");
        SERVICE_NAME.put("kinopoisk", "Кинопоиск");
        SERVICE_NAME.put("premier", "Premier");
        SERVICE_NAME.put("amediateka", "Амедиатека");
        SERVICE_NAME.put("wink", "Wink");
        SERVICE_NAME.put("nuvio", "Nuvio");
    }
    /** Country for "what streams where" (config.properties "tmdb_region", e.g. US, GB, BR). */
    static final String REGION = Place.region();
    /** The errors the settings say in words: TMDB lists none of the TV's apps in REGION, or the TV has none we know. */
    static final String NO_APPS = "none of the TV's streaming services are on TMDB";
    static final String NO_KNOWN_APPS = "none of the TV's streaming apps can give picks";

    private static Recs instance;

    static synchronized Recs get(Context c) {
        if (instance == null) instance = new Recs(c.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences cache;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    // This hour's, built and ready to draw; the least recently drawn goes first when it's full.
    private final Map<String, Backdrop> images = new LinkedHashMap<>(16, 0.75f, true);
    private final java.util.Set<String> pending = new java.util.HashSet<>();
    private volatile List<Pick> picks = new ArrayList<>();
    private volatile int period = -1;
    private volatile long listAt;
    private volatile boolean fetching;
    private volatile String error = "";
    private volatile boolean light;     // light design: builds only the cards (all three tabs' cards fit in memory)
    private volatile long failedAt, keyStamp, linkRetryAt;
    private volatile String listLanguage = "";   // language used for the saved titles, genres and texts

    private Recs(Context c) {
        context = c;
        cache = c.getSharedPreferences("recs", Context.MODE_PRIVATE);
        if (cache.getInt("version", 0) != CACHE_VERSION) cache.edit().clear().putInt("version", CACHE_VERSION).apply();
        try {
            String saved = cache.getString("picks", null);
            if (saved != null) picks = parse(new JSONArray(saved));
            period = cache.getInt("period", -1);
            listAt = cache.getLong("listAt", 0);
            listLanguage = cache.getString("language", cache.getBoolean("english", false) ? "en" : "pt");
            keyStamp = cache.getLong("keyStamp", 0);
        } catch (Exception ignored) {
        }
    }

    void addListener(Listener l) { if (!listeners.contains(l)) listeners.add(l); }
    void removeListener(Listener l) { listeners.remove(l); }

    static boolean hasKey(Context c) {
        return !Config.TMDB_KEY.trim().isEmpty();
    }

    /** Last fetch, for the settings screen: {count, error, at}. Written by the home screen (another process). */
    static JSONObject status(Context c) {
        File f = new File(c.getFilesDir(), "recs-status.json");
        if (!f.exists() || !hasKey(c)) return new JSONObject();
        try (FileInputStream in = new FileInputStream(f)) {
            return new JSONObject(read(in));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void writeStatus() {
        try (FileOutputStream out = new FileOutputStream(new File(context.getFilesDir(), "recs-status.json"))) {
            out.write(new JSONObject().put("count", picks.size()).put("error", error).put("at", System.currentTimeMillis())
                    .toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    String error() { return error; }

    static String periodLabel() {
        int p = period(System.currentTimeMillis());
        return p == MANHA ? Ui.tr("THIS MORNING") : p == TARDE ? Ui.tr("THIS AFTERNOON") : Ui.tr("TONIGHT");
    }

    private static int period(long now) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(now);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? MANHA : hour >= 12 && hour < 18 ? TARDE : NOITE;
    }

    /** This hour's picks (the first is the featured one; the arrows switch to the others). */
    List<Pick> now() {
        return now(null);
    }

    /** How many each tab shows: the stack of posters on the home screen (HomeView.MAX_CARDS). */
    static final int PER_TAB = 6;

    /** This hour's PER_TAB, movies only ("movie") or shows only ("tv"); null = both (the home screen tabs). */
    List<Pick> now(String kind) {
        if ("continue".equals(kind)) return continuing();
        List<Pick> out = surprised(kind);
        if (out == null) out = "game".equals(kind) ? games() : usual(kind);
        if (!out.isEmpty()) {
            out = new ArrayList<>(out);
            out.add(surpriseCard());
        }
        return out;
    }

    private List<Pick> continuing() {
        List<Pick> out = new ArrayList<>(watching);
        return out.size() > PER_TAB ? out.subList(0, PER_TAB) : out;
    }

    private List<Pick> usual(String kind) {
        List<Pick> list = new ArrayList<>();
        if (!hasKey(context)) return list;    // no TMDB key: no recommendations
        Map<String, Boolean> have = new HashMap<>();   // one look per app, not per title (this runs every 15 s)
        for (Pick p : picks) {
            if ((kind == null || kind.equals(p.kind))
                    && (!p.detailed || (p.pkg != null && have.computeIfAbsent(p.pkg, this::installed)))) list.add(p);   // app missing: skip
        }
        if (list.isEmpty()) return list;
        long hour = System.currentTimeMillis() / 3600_000L;
        // PER_TAB per hour, never two from the same franchise ("Law & Order" and "Law & Order: Special…"). Shows opens
        // with up to two new seasons or episodes of your shows (taking turns by the hour when there are more), then
        // recommendations; Movies has only recommendations (Explore, which had them, was removed). The rest takes turns by the hour.
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<Pick> fresh = new ArrayList<>();
        for (Pick p : list) if (p.fresh) fresh.add(p);
        list.removeIf(p -> p.fresh);
        for (int i = 0; "tv".equals(kind) && i < fresh.size() && out.size() < Math.min(2, fresh.size()); i++) {
            Pick p = fresh.get((int) ((hour * 2 + i) % fresh.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        // Movies opens with up to three that just left theaters for digital (taking turns by the hour).
        List<Pick> digital = new ArrayList<>();
        for (Pick p : list) if (p.digital) digital.add(p);
        if ("movie".equals(kind)) list.removeIf(p -> p.digital);
        for (int i = 0; "movie".equals(kind) && i < digital.size() && out.size() < Math.min(3, digital.size()); i++) {
            Pick p = digital.get((int) ((hour * 3 + i) % digital.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        if (list.isEmpty()) return out;
        for (int i = 0; i < list.size() && out.size() < PER_TAB; i++) {
            Pick p = list.get((int) ((hour * PER_TAB + i) % list.size()));
            if (seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    // MARK: - Surprise me

    /** The last card of Explore, Movies, Shows and Games: OK swaps the tab's titles for others, all highly rated. */
    static boolean surprise(Pick p) {
        return p != null && "surprise".equals(p.kind);
    }

    private Pick surpriseCard() {
        Pick p = new Pick();
        p.kind = "surprise";
        p.title = Ui.tr("Surprise me");
        p.meta = Ui.tr("Fresh picks, all highly rated");
        p.detailed = true;
        return p;
    }

    /** Each tab's surprise (null = Explore), shown for 6 hours or until the next one. */
    private final Map<String, List<Pick>> surprises = new HashMap<>();
    private final Map<String, Long> surprisedAt = new HashMap<>();
    private volatile boolean surprising;

    private List<Pick> surprised(String kind) {
        synchronized (surprises) {
            Long at = surprisedAt.get(String.valueOf(kind));
            if (at == null || System.currentTimeMillis() - at > 6 * 3600_000L) return null;
            return surprises.get(String.valueOf(kind));
        }
    }

    /**
     * New titles for the tab, in the background: well rated (7.2 or more on TMDB, with plenty of votes) and on a random
     * page of them, so each press brings others; games are shuffled. The listeners hear when they're in.
     */
    void surprise(String kind) {
        if (surprising) return;
        surprising = true;
        watchIo.execute(() -> {
            try {
                List<Pick> out = new ArrayList<>();
                if ("game".equals(kind)) {
                    if (gameList == null) games();
                    List<Pick> all = new ArrayList<>(gameList);
                    java.util.Collections.shuffle(all);
                    out.addAll(all.subList(0, Math.min(PER_TAB, all.size())));
                } else if (kind == null) {
                    List<Pick> movies = wellRated("movie", PER_TAB / 2), shows = wellRated("tv", PER_TAB - PER_TAB / 2);
                    alternate(out, new java.util.HashSet<>(), movies, shows, PER_TAB);
                } else {
                    out = wellRated(kind, PER_TAB);
                }
                if (!out.isEmpty()) {
                    synchronized (surprises) {
                        surprises.put(String.valueOf(kind), out);
                        surprisedAt.put(String.valueOf(kind), System.currentTimeMillis());
                    }
                }
            } catch (Exception e) {
                AppLog.w("surprise: " + e.getMessage());
            } finally {
                surprising = false;
                notifyListeners();
            }
        });
    }

    private List<Pick> wellRated(String kind, int count) throws Exception {
        JSONObject fav = favorites();
        Set<Integer> excluded = new HashSet<>();
        JSONArray ex = fav == null ? null : fav.optJSONArray("exclude_genres");
        for (int i = 0; ex != null && i < ex.length(); i++) excluded.add(ex.optInt(i));
        boolean noAnime = fav != null && fav.optBoolean("no_anime");
        StringBuilder without = new StringBuilder();
        for (int g : excluded) without.append(without.length() == 0 ? "" : ",").append(g);
        String query = "/discover/" + kind + "?language=" + lang() + "&sort_by=popularity.desc&vote_average.gte=7.2&vote_count.gte="
                + (kind.equals("movie") ? 1500 : 400) + "&include_adult=false"
                + (without.length() > 0 ? "&without_genres=" + URLEncoder.encode(without.toString(), "UTF-8") : "");
        int pages = Math.min(20, Math.max(1, get(query + "&page=1").optInt("total_pages", 1)));
        JSONArray results = get(query + "&page=" + (1 + new java.util.Random().nextInt(pages))).getJSONArray("results");
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < results.length(); i++) order.add(i);
        java.util.Collections.shuffle(order);
        List<Pick> out = new ArrayList<>();
        for (int i : order) {
            if (out.size() >= count) break;
            JSONObject o = results.getJSONObject(i);
            if (!allowed(o, kind, excluded, noAnime)) continue;
            List<Pick> one = new ArrayList<>();
            add(one, o, kind);
            if (one.isEmpty()) continue;
            Pick p = one.get(0);
            try {
                fetchDetails(p);
            } catch (Exception e) {
                continue;
            }
            if (p.pkg == null || p.allAges || (p.horror && excluded.contains(27))) continue;
            p.meta = Ui.trf("Rating {r}", "r", String.format(Ui.locale(), "%.1f", o.optDouble("vote_average"))) + " · " + p.meta;
            out.add(p);
        }
        return out;
    }

    // MARK: - Continue (Trakt)

    private static final String TRAKT = "https://api.trakt.tv";
    /** Every 3 minutes while the home screen runs, and again whenever it comes back (nudgeWatching). */
    private static final long WATCHING_EVERY = 3 * 60_000L;
    private volatile List<Pick> watching = new ArrayList<>();
    private volatile long watchingAt;
    private volatile boolean fetchingWatching;
    /** Its own thread: a long list fetch on `io` doesn't hold up the Continue tab. */
    private final ExecutorService watchIo = Executors.newSingleThreadExecutor();
    /** TMDB details of Continue titles (kind:id), so the frequent refresh only asks Trakt. */
    private final Map<String, Pick> resumeDetails = new HashMap<>();

    static boolean traktOn() {
        return !Config.TRAKT_CLIENT_ID.isEmpty() && !Config.TRAKT_REFRESH_TOKEN.isEmpty();
    }

    /** The home screen came back (you were probably watching something): refresh Continue soon. */
    void nudgeWatching() {
        if (System.currentTimeMillis() - watchingAt > 45_000L) watchingAt = 0;
        refreshWatching();
    }

    /** What you're in the middle of on Trakt (Nuvio scrobbles there), for the Continue tab. */
    private void refreshWatching() {
        long now = System.currentTimeMillis();
        if (!traktOn() || !hasKey(context) || fetchingWatching || (now - watchingAt < WATCHING_EVERY && now >= watchingAt)) return;
        fetchingWatching = true;
        watchingAt = now;
        watchIo.execute(() -> {
            try {
                watching = fetchWatching();
                notifyListeners();
            } catch (Exception e) {
                AppLog.w("trakt: " + e.getMessage());
                watchingAt = System.currentTimeMillis() - WATCHING_EVERY + 60_000L;   // again in a minute
            } finally {
                fetchingWatching = false;
            }
        });
    }

    private static final class Watched {
        long at;
        String kind, label;
        int tmdb, trakt;
        float progress;
        boolean next;   // a show whose last episode you finished: its next episode, if there is one
    }

    /**
     * The titles you watched most recently, newest first, one card per movie or show (up to PER_TAB): a movie or episode
     * you paused shows how far you got; a show whose episode you finished shows its next episode. Each opens in Nuvio.
     */
    private List<Pick> fetchWatching() throws Exception {
        Map<String, Watched> latest = new HashMap<>();
        JSONArray paused = new JSONArray(trakt("/sync/playback?limit=40"));
        for (int i = 0; i < paused.length(); i++) {
            JSONObject o = paused.getJSONObject(i);
            boolean movie = "movie".equals(o.optString("type"));
            JSONObject what = o.optJSONObject(movie ? "movie" : "show");
            int tmdb = what == null || what.optJSONObject("ids") == null ? 0 : what.getJSONObject("ids").optInt("tmdb");
            if (tmdb == 0) continue;
            Watched w = new Watched();
            w.at = time(o.optString("paused_at"));
            w.kind = movie ? "movie" : "tv";
            w.tmdb = tmdb;
            int pct = (int) Math.round(o.optDouble("progress"));
            w.progress = pct / 100f;
            if (movie) {
                w.label = Ui.trf("{p}% watched", "p", pct);
            } else {
                JSONObject ep = o.getJSONObject("episode");
                w.label = Ui.trf("S{s}:E{e}", "s", ep.optInt("season"), "e", ep.optInt("number")) + " · " + ep.optString("title") + " · " + pct + "%";
            }
            keepNewest(latest, w);
        }
        // Episodes you finished in the past 90 days: their show's next episode, unless you paused one of it later.
        JSONArray history = new JSONArray(trakt("/sync/history/episodes?limit=60&start_at=" + day(-90) + "T00:00:00.000Z"));
        for (int i = 0; i < history.length(); i++) {
            JSONObject h = history.getJSONObject(i);
            JSONObject show = h.optJSONObject("show");
            if (show == null || show.optJSONObject("ids") == null || show.getJSONObject("ids").optInt("tmdb") == 0) continue;
            Watched w = new Watched();
            w.at = time(h.optString("watched_at"));
            w.kind = "tv";
            w.tmdb = show.getJSONObject("ids").optInt("tmdb");
            w.trakt = show.getJSONObject("ids").optInt("trakt");
            w.next = true;
            keepNewest(latest, w);
        }
        AppLog.w("trakt: " + paused.length() + " paused, " + history.length() + " episodes in history");
        List<Watched> order = new ArrayList<>(latest.values());
        order.sort((a, b) -> Long.compare(b.at, a.at));
        List<Pick> out = new ArrayList<>();
        for (Watched w : order) {
            if (out.size() >= PER_TAB) break;
            if (w.next) {
                JSONObject next = new JSONObject(trakt("/shows/" + w.trakt + "/progress/watched")).optJSONObject("next_episode");
                if (next == null) continue;   // you've seen it all
                w.label = Ui.tr("Next") + ": " + Ui.trf("S{s}:E{e}", "s", next.optInt("season"), "e", next.optInt("number"))
                        + " · " + next.optString("title");
            }
            Pick p = resumePick(w.kind, w.tmdb, w.label, w.progress);
            if (p != null) out.add(p);
        }
        return out;
    }

    private static void keepNewest(Map<String, Watched> latest, Watched w) {
        Watched had = latest.get(w.kind + ":" + w.tmdb);
        if (had == null || w.at > had.at) latest.put(w.kind + ":" + w.tmdb, w);
    }

    /** Trakt's "2026-10-27T18:42:10.000Z" in ms (0 if unreadable). */
    private static long time(String iso) {
        try {
            return java.time.Instant.parse(iso).toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }

    /** A Continue card: the title's details, poster and backdrop from TMDB, opening in Nuvio; null if Nuvio can't. */
    private Pick resumePick(String kind, int tmdb, String label, float progress) {
        Pick base;
        synchronized (resumeDetails) {
            base = resumeDetails.get(kind + ":" + tmdb);
        }
        if (base == null) {
            try {
                JSONObject o = get("/" + kind + "/" + tmdb + "?language=" + lang());
                List<Pick> one = new ArrayList<>();
                add(one, o, kind);
                if (one.isEmpty()) return null;
                base = one.get(0);
                fetchDetails(base);
            } catch (Exception e) {
                return null;
            }
            synchronized (resumeDetails) {
                resumeDetails.put(kind + ":" + tmdb, base);
            }
        }
        if (base.pkg == null) return null;
        Pick p = new Pick();
        p.id = base.id;
        p.kind = base.kind;
        p.title = base.title;
        p.backdrop = base.backdrop;
        p.poster = base.poster;
        p.logo = base.logo;
        p.app = base.app;
        p.pkg = base.pkg;
        p.link = base.link;
        p.detailed = true;
        p.resume = true;
        p.progress = progress;
        p.meta = label + " · " + SERVICE_NAME.get(p.app);
        return p;
    }

    /** GET from Trakt as you, refreshing the sign-in when it ran out (Trakt's tokens last a week). */
    private String trakt(String path) throws IOException, org.json.JSONException {
        String token = traktToken(false);
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection c = (HttpURLConnection) new URL(TRAKT + path).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(12000);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("trakt-api-version", "2");
            c.setRequestProperty("trakt-api-key", Config.TRAKT_CLIENT_ID);
            c.setRequestProperty("Authorization", "Bearer " + token);
            try {
                int code = c.getResponseCode();
                if (code == 401 && attempt == 0) {
                    token = traktToken(true);
                    continue;
                }
                if (code != 200) throw new IOException("Trakt returned HTTP " + code);
                return read(c.getInputStream());
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Trakt refused the sign-in");
    }

    /**
     * The access token, renewed with the refresh token when it's about to run out (or `force`). Each renewal hands a new
     * refresh token, kept here; the one in config.properties only starts it (and a new one there starts it again).
     */
    private synchronized String traktToken(boolean force) throws IOException, org.json.JSONException {
        SharedPreferences t = context.getSharedPreferences("trakt", Context.MODE_PRIVATE);
        if (!Config.TRAKT_REFRESH_TOKEN.equals(t.getString("seed", ""))) {
            t.edit().clear().putString("seed", Config.TRAKT_REFRESH_TOKEN).putString("refresh", Config.TRAKT_REFRESH_TOKEN).apply();
        }
        String access = t.getString("access", "");
        long until = t.getLong("until", 0);
        if (!force && !access.isEmpty() && System.currentTimeMillis() < until - 3600_000L) return access;
        HttpURLConnection c = (HttpURLConnection) new URL(TRAKT + "/oauth/token").openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(12000);
        c.setDoOutput(true);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("trakt-api-version", "2");
        c.setRequestProperty("trakt-api-key", Config.TRAKT_CLIENT_ID);
        String body = new JSONObject().put("refresh_token", t.getString("refresh", "")).put("client_id", Config.TRAKT_CLIENT_ID)
                .put("redirect_uri", "http://localhost").put("grant_type", "refresh_token").toString();
        try {
            try (java.io.OutputStream o = c.getOutputStream()) {
                o.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Trakt sign-in renewal returned HTTP " + code);
            JSONObject r = new JSONObject(read(c.getInputStream()));
            access = r.getString("access_token");
            t.edit().putString("access", access).putString("refresh", r.getString("refresh_token"))
                    .putLong("until", System.currentTimeMillis() + r.optLong("expires_in", 86400) * 1000L).apply();
            return access;
        } finally {
            c.disconnect();
        }
    }

    // MARK: - Posters (the stack on the home screen)

    /** A poster or a title logo ready to draw: the bitmap and its shader (for rounded corners). */
    static final class Poster {
        final Bitmap bitmap;
        final android.graphics.BitmapShader shader;

        Poster(Bitmap b) {
            bitmap = b;
            shader = new android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
        }
    }

    private static final String POSTERS = "https://image.tmdb.org/t/p/w500";
    /** The last ones drawn (6 per tab; RGB_565, about 0.7 MB each): the rest are read again from disk. */
    private final Map<String, Poster> posters = new LinkedHashMap<String, Poster>(32, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Poster> e) { return size() > 24; }
    };
    private final java.util.Set<String> postersPending = new java.util.HashSet<>();
    private final ExecutorService imgIo = Executors.newSingleThreadExecutor();

    /** The pick's poster if it's ready, or null (it's fetched in the background and the listeners hear when). */
    Poster poster(Pick p) {
        return p == null || p.poster == null ? null : image(p.poster, POSTERS + p.poster, false, p.title);
    }

    /**
     * The closed card's picture: the same photo as the open card (TMDB's w780 copy of it; a game's own image), kept
     * still, so a title looks the same closed or open.
     */
    Poster still(Pick p) {
        if (p == null || p.backdrop == null) return null;
        String url = "game".equals(p.kind) ? "asset:games/" + p.id + ".jpg" : "https://image.tmdb.org/t/p/w780" + p.backdrop;
        return image("still" + p.backdrop, url, false, p.title);
    }

    /** The pick's title logo (transparent) if it's ready, or null (fetched like the poster). */
    Poster logo(Pick p) {
        return p == null || p.logo == null ? null : image(p.logo, POSTERS + p.logo, true, p.title);
    }

    /** An image by `key` from `url` (or "asset:<path>" in the app), kept on disk and the last ones in memory. */
    private Poster image(String key, String url, boolean alpha, String title) {
        final String path = key;
        synchronized (posters) {
            Poster ready = posters.get(path);
            if (ready != null) return ready;
            if (!postersPending.add(path)) return null;
        }
        imgIo.execute(() -> {
            try {
                File f = new File(new File(context.getCacheDir(), "posters"), path.replace("/", ""));
                if (url.startsWith("asset:")) {
                    BitmapFactory.Options small = new BitmapFactory.Options();
                    small.inSampleSize = 2;
                    small.inPreferredConfig = Bitmap.Config.RGB_565;
                    Bitmap a;
                    try (InputStream in = context.getAssets().open(url.substring("asset:".length()))) {
                        a = BitmapFactory.decodeStream(in, null, small);
                    }
                    if (a == null) throw new IOException("unreadable image");
                    synchronized (posters) {
                        posters.put(path, new Poster(a));
                    }
                    notifyListeners();
                    return;
                }
                if (!f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.getParentFile().mkdirs();
                    File part = new File(f.getPath() + ".part");
                    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(20000);
                    try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
                        byte[] buffer = new byte[16384];
                        int n;
                        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
                    } finally {
                        c.disconnect();
                    }
                    if (!part.renameTo(f)) throw new IOException("couldn't save the poster");
                }
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inPreferredConfig = alpha ? Bitmap.Config.ARGB_8888 : Bitmap.Config.RGB_565;
                Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
                if (b == null) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                    throw new IOException("unreadable poster");
                }
                synchronized (posters) {
                    posters.put(path, new Poster(b));
                }
                notifyListeners();
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("image “" + title + "”: " + e.getMessage());
            } finally {
                synchronized (posters) {
                    postersPending.remove(path);
                }
            }
        });
        return null;
    }

    /** Each title's spine color (kind:id), worked out once from a tiny copy of its poster. */
    private final Map<String, Integer> spineColors = new HashMap<>();
    private final java.util.Set<String> spinesPending = new java.util.HashSet<>();

    /** The title's spine color if it's ready, or null (worked out in the background; the listeners hear when). */
    Integer spineColor(Pick p) {
        if (p == null) return null;
        final String key = p.kind + ":" + p.id;
        synchronized (spineColors) {
            Integer ready = spineColors.get(key);
            if (ready != null || !spinesPending.add(key)) return ready;
        }
        final String poster = p.poster;
        final boolean game = "game".equals(p.kind);
        final int id = p.id;
        imgIo.execute(() -> {
            Bitmap b = null;
            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = 8;
                if (game) {
                    try (InputStream in = context.getAssets().open("games/" + id + ".jpg")) {
                        b = BitmapFactory.decodeStream(in, null, opts);
                    }
                } else if (poster != null) {
                    HttpURLConnection c = (HttpURLConnection) new URL("https://image.tmdb.org/t/p/w92" + poster).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(12000);
                    try (InputStream in = c.getInputStream()) {
                        b = BitmapFactory.decodeStream(in);
                    } finally {
                        c.disconnect();
                    }
                }
                int color = 0xFF2A2D3A;
                if (b != null) {
                    Bitmap one = Bitmap.createScaledBitmap(b, 1, 1, true);
                    float[] hsv = new float[3];
                    android.graphics.Color.colorToHSV(one.getPixel(0, 0), hsv);
                    hsv[1] = Math.min(0.75f, hsv[1] * 1.25f);   // its own hue, a little richer
                    hsv[2] = 0.30f + 0.12f * hsv[2];             // and dark enough for white lettering
                    color = android.graphics.Color.HSVToColor(hsv);
                }
                synchronized (spineColors) {
                    spineColors.put(key, color);
                }
                notifyListeners();
            } catch (Exception | OutOfMemoryError e) {
                AppLog.w("spine color: " + e.getMessage());
            } finally {
                synchronized (spineColors) {
                    spinesPending.remove(key);
                }
            }
        });
        return null;
    }

    static final String NETFLIX = "com.netflix.ninja";
    private List<Pick> gameList;

    /**
     * The Games tab: three Netflix games that play on the TV (app/assets/games.json), taking turns by the hour. Each
     * opens its page in the Netflix app; its image is in app/assets/games. None without Netflix on the TV.
     */
    private List<Pick> games() {
        List<Pick> out = new ArrayList<>();
        if (!installed(NETFLIX)) return out;
        if (gameList == null) {
            List<Pick> all = new ArrayList<>();
            try (InputStream in = context.getAssets().open("games.json")) {
                JSONArray a = new JSONObject(read(in)).getJSONArray("games");
                for (int i = 0; i < a.length(); i++) {
                    Pick p = new Pick();
                    p.id = a.getJSONObject(i).getInt("id");
                    p.kind = "game";
                    p.title = a.getJSONObject(i).getString("name");
                    p.backdrop = "game-" + p.id + ".jpg";
                    p.app = "netflix";
                    p.pkg = NETFLIX;
                    // A fresh task: Netflix already open on another title would just come back to it.
                    p.link = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.netflix.com/title/" + p.id))
                            .setComponent(new android.content.ComponentName(NETFLIX, NETFLIX + ".MainActivity"))
                            .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            .putExtra("source", "30").toUri(android.content.Intent.URI_INTENT_SCHEME);
                    p.meta = Ui.tr("Game") + " · Netflix · " + Ui.tr("Use your phone as the controller");
                    p.detailed = true;
                    all.add(p);
                }
            } catch (Exception e) {
                AppLog.w("games: " + e.getMessage());
            }
            gameList = all;
        }
        if (gameList.isEmpty()) return out;
        long hour = System.currentTimeMillis() / 3600_000L;
        for (int i = 0; i < PER_TAB && i < gameList.size(); i++) out.add(gameList.get((int) ((hour * PER_TAB + i) % gameList.size())));
        return out;
    }

    /** "Law & Order: Special Victims Unit" → "law & order" (what comes before ":" or " - "). */
    private static String franchise(String title) {
        String t = title == null ? "" : title.toLowerCase(Locale.ROOT);
        int cut = t.indexOf(':');
        if (cut < 0) cut = t.indexOf(" - ");
        return (cut > 0 ? t.substring(0, cut) : t).trim();
    }

    /** Name of the service the pick is on ("Netflix"), or empty. */
    static String serviceName(Pick p) {
        return p == null || p.app == null ? "" : SERVICE_NAME.get(p.app);
    }

    /** The pick's ready backdrop (or null: builds it in the background and notifies when it's ready). */
    Backdrop backdrop(Pick p) {
        if (p == null || p.backdrop == null) return null;
        final String key = p.backdrop;    // the details may swap the image midway
        synchronized (images) {
            Backdrop b = images.get(key);
            if (b != null) return b;
            if (!pending.add(key)) return null;
        }
        io.execute(() -> {
            try {
                loadImage(p);
            } finally {
                synchronized (images) { pending.remove(key); }
            }
        });
        return null;
    }

    private boolean hasImage(Pick p) {
        return p.backdrop != null && imageFile(p).exists();
    }

    private File imageFile(Pick p) {
        return new File(new File(context.getCacheDir(), "recs"), p.backdrop.replace("/", ""));
    }

    /** With a key: fetches the list if stale or the time of day (morning/afternoon/night) changed; then the details. */
    void refreshIfStale() {
        if (!hasKey(context) || fetching) return;
        long now = System.currentTimeMillis();
        long stamp = sourceStamp();   // a new key, country, animation choice or installed streaming-app set refetches
        boolean newKey = stamp != keyStamp;
        long sinceFail = now - failedAt;    // negative = the clock went back: don't wait
        if (!newKey && failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        int p = period(now);
        boolean stale = newKey || p != period || now - listAt > LIST_EVERY || now < listAt || picks.isEmpty()
                || !listLanguage.equals(Ui.language);
        boolean missing = false;
        for (Pick pick : wanted()) missing |= !pick.detailed || !hasImage(pick) || needsLink(pick);
        if (!stale && !missing) return;
        keyStamp = stamp;
        fetching = true;
        io.execute(() -> {
            try {
                if (stale) fetchList(p);
                // One failing title doesn't stop the others; whatever has no app on the TV is not downloaded.
                int failures = 0, tried = 0;
                String last = "";
                for (Pick pick : wanted()) {
                    tried++;
                    try {
                        if (!pick.detailed) fetchDetails(pick);
                        if (pick.pkg != null) download(pick);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                    findLink(pick);
                }
                save();
                if (tried > 0 && failures == tried) throw new IOException(last);
                error = "";
                failedAt = 0;
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                failedAt = System.currentTimeMillis();
                AppLog.w("recommendations: " + error);
            } finally {
                fetching = false;
                writeStatus();
                notifyListeners();
            }
        });
    }

    /** Home screen design: the ready images are rebuilt for it (the light design uses cards, not the whole photo). */
    void setLight(boolean value) {
        if (value == light) return;
        light = value;
        synchronized (images) {
            images.clear();
        }
    }

    /**
     * Screensaver: the picks with an app on the TV and an image already on disk, starting with this hour's (the
     * featured one first), never two from the same franchise.
     */
    List<Pick> showcase() {
        List<Pick> out = new ArrayList<>();
        if (!hasKey(context)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick p : wanted()) if (hasImage(p) && seen.add(franchise(p.title))) out.add(p);
        Map<String, Boolean> have = new HashMap<>();
        for (Pick p : picks) {
            if (p.detailed && p.pkg != null && have.computeIfAbsent(p.pkg, this::installed) && hasImage(p)
                    && seen.add(franchise(p.title))) out.add(p);
        }
        return out;
    }

    /** The pick's original image if it's already on disk (the screensaver loads it in its own way), or null. */
    File readyImage(Pick p) {
        return hasImage(p) ? imageFile(p) : null;
    }

    /**
     * While the screensaver is showing: details and image for every pick in the list (not just this hour's), one at a
     * time in the background, so it has more photos. Not used otherwise.
     */
    void prefetchAll() {
        if (!hasKey(context) || fetching) return;
        long sinceFail = System.currentTimeMillis() - failedAt;
        if (failedAt != 0 && sinceFail >= 0 && sinceFail < 10 * 60_000L) return;
        List<Pick> todo = new ArrayList<>();
        for (Pick p : picks) if (!p.detailed || (p.pkg != null && !hasImage(p))) todo.add(p);
        if (todo.isEmpty()) return;
        fetching = true;
        io.execute(() -> {
            int failures = 0;
            String last = "";
            try {
                // One failing title (gone from TMDB, an image that won't download) doesn't stop the others.
                for (Pick p : todo) {
                    try {
                        if (!p.detailed) fetchDetails(p);
                        if (p.pkg != null) download(p);
                    } catch (Exception e) {
                        failures++;
                        last = e.getMessage() == null ? e.toString() : e.getMessage();
                    }
                }
                save();
                if (failures == todo.size()) {
                    error = last;
                    failedAt = System.currentTimeMillis();
                    AppLog.w("recommendations (screensaver): " + last);
                }
            } finally {
                fetching = false;
                notifyListeners();
            }
        });
    }

    /** This hour's picks for the three tabs (Explore, Movies, Shows), without repeats. */
    List<Pick> wanted() {
        List<Pick> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (String kind : new String[] {"movie", "tv"}) {
            for (Pick p : now(kind)) if (!"surprise".equals(p.kind) && seen.add(key(p))) out.add(p);
        }
        return out;
    }

    /** Movies and shows are numbered separately on TMDB: a pick is identified by type + number. */
    static String key(Pick p) {
        return p.kind + ":" + p.id;
    }

    private void notifyListeners() {
        main.post(() -> { for (Listener l : new ArrayList<>(listeners)) l.onRecs(); });
    }

    // MARK: - TMDB

    private void fetchList(int p) throws Exception {
        if (nuvioMode()) {
            if (!installed(NUVIO)) throw new IOException("Nuvio isn't installed");
            fetchForNuvio(p, favorites());
            return;
        }
        // None of the services we know is on the TV (or hide_services hides them all): no region would change that.
        boolean any = false;
        for (String[] s : SERVICES) any |= installed(s[2]);
        if (!any) throw new IOException(NO_KNOWN_APPS);
        // The services the TV has, by their TMDB ids in your region.
        Map<Integer, String[]> services = new HashMap<>();
        JSONArray providers = get("/watch/providers/movie?language=" + lang() + "&watch_region=" + REGION).getJSONArray("results");
        for (int i = 0; i < providers.length(); i++) {
            JSONObject o = providers.getJSONObject(i);
            String[] s = service(o.optString("provider_name"), o.optInt("provider_id"));
            if (s != null && installed(s[2])) services.put(o.getInt("provider_id"), s);
        }
        if (services.isEmpty()) throw new IOException(NO_APPS);
        JSONObject favorites = favorites();
        if (favorites != null) {
            fetchFromFavorites(p, favorites);
            return;
        }
        StringBuilder ids = new StringBuilder();
        for (int id : services.keySet()) ids.append(ids.length() == 0 ? "" : "|").append(id);
        String common = "&language=" + lang() + "&watch_region=" + REGION + "&with_watch_monetization_types=flatrate&include_adult=false"
                + "&sort_by=popularity.desc&with_watch_providers=" + URLEncoder.encode(ids.toString(), "UTF-8")
                + (Config.HIDE_ANIMATION ? "&without_genres=" + ANIMATION : "");
        // 1. Trending this week (TMDB goes by what people are watching and searching for right now), only what is
        //    on one of the TV's services: the details say where (and are then ready).
        List<Pick> hotMovies = new ArrayList<>(), hotShows = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray hot = get("/trending/" + kind + "/week?language=" + lang()).getJSONArray("results");
            List<Pick> candidates = new ArrayList<>();
            for (int i = 0; i < hot.length() && i < 20; i++) add(candidates, hot.getJSONObject(i), kind);
            for (Pick pick : candidates) {
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg != null) (kind.equals("movie") ? hotMovies : hotShows).add(pick);
            }
        }
        // 2. Recent releases on the TV's services: movies from the past year and shows with a new episode in the
        //    past 3 months.
        JSONArray movies = get("/discover/movie?vote_count.gte=30&primary_release_date.gte=" + day(-365)
                + "&primary_release_date.lte=" + day(0) + common).getJSONArray("results");
        JSONArray shows = get("/discover/tv?vote_count.gte=20&air_date.gte=" + day(-90) + "&air_date.lte=" + day(0)
                + common).getJSONArray("results");
        List<Pick> newMovies = new ArrayList<>(), newShows = new ArrayList<>();
        for (int i = 0; i < movies.length(); i++) add(newMovies, movies.getJSONObject(i), "movie");
        for (int i = 0; i < shows.length(); i++) add(newShows, shows.getJSONObject(i), "tv");
        // Trending first, then the new releases; movies and shows alternating, without repeats, up to 24.
        List<Pick> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        alternate(list, seen, hotMovies, hotShows, 24);
        alternate(list, seen, newMovies, newShows, 24);
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** favorites.json says "source": "nuvio": the picks are what's trending, and all open in Nuvio. */
    private boolean nuvioMode() {
        JSONObject f = favorites();
        return f != null && "nuvio".equals(f.optString("source"));
    }

    /**
     * Nuvio mode: this week's trending movies and shows on TMDB (what Nuvio's own "Trending" rows show), whatever service
     * has them, since Nuvio plays them all; Explore first shows new seasons and episodes of your shows. The genres in
     * exclude_genres, anime, all-ages titles and anything not out yet stay out.
     */
    private void fetchForNuvio(int p, JSONObject fav) throws Exception {
        Set<Integer> excluded = new HashSet<>();
        JSONArray ex = fav.optJSONArray("exclude_genres");
        for (int i = 0; ex != null && i < ex.length(); i++) excluded.add(ex.optInt(i));
        boolean noAnime = fav.optBoolean("no_anime");
        List<Pick> fresh = freshEpisodes(fav);
        fresh.addAll(newOnDigital(excluded, noAnime));
        List<Pick> movies = new ArrayList<>(), tv = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            List<Pick> out = kind.equals("movie") ? movies : tv;
            for (int page = 1; page <= 2 && out.size() < 12; page++) {
                JSONArray hot = get("/trending/" + kind + "/week?language=" + lang() + "&page=" + page).getJSONArray("results");
                for (int i = 0; i < hot.length() && out.size() < 12; i++) {
                    JSONObject o = hot.getJSONObject(i);
                    if (!allowed(o, kind, excluded, noAnime)) continue;
                    List<Pick> one = new ArrayList<>();
                    add(one, o, kind);
                    if (one.isEmpty()) continue;
                    try {
                        fetchDetails(one.get(0));
                    } catch (Exception e) {
                        continue;
                    }
                    if (one.get(0).pkg != null && !one.get(0).allAges && !(one.get(0).horror && excluded.contains(27))) out.add(one.get(0));
                }
            }
        }
        List<Pick> list = new ArrayList<>(fresh);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick f : fresh) seen.add(key(f));
        alternate(list, seen, movies, tv, 24 + fresh.size());
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /**
     * Movies released on digital in your region in the past 45 days (TMDB's release type 4: rent, buy or streaming),
     * most popular first, up to 8; each says where it's on.
     */
    private List<Pick> newOnDigital(Set<Integer> excluded, boolean noAnime) {
        List<Pick> out = new ArrayList<>();
        try {
            JSONArray a = get("/discover/movie?language=" + lang() + "&region=" + REGION + "&with_release_type=4"
                    + "&release_date.gte=" + day(-45) + "&release_date.lte=" + day(0)
                    + "&sort_by=popularity.desc&include_adult=false&vote_count.gte=20").getJSONArray("results");
            for (int i = 0; i < a.length() && out.size() < 8; i++) {
                JSONObject o = a.getJSONObject(i);
                if (!allowed(o, "movie", excluded, noAnime)) continue;
                List<Pick> one = new ArrayList<>();
                add(one, o, "movie");
                if (one.isEmpty()) continue;
                Pick pick = one.get(0);
                try {
                    fetchDetails(pick);
                } catch (Exception e) {
                    continue;
                }
                if (pick.pkg == null || pick.allAges || (pick.horror && excluded.contains(27))) continue;
                pick.digital = true;
                String genre = pick.meta.contains(" · ") ? pick.meta.substring(0, pick.meta.indexOf(" · ")) : pick.meta;
                pick.meta = Ui.tr("New on digital") + (genre.isEmpty() ? "" : " · " + genre) + (pick.where != null ? " · " + pick.where : "");
                out.add(pick);
            }
        } catch (Exception e) {
            AppLog.w("new on digital: " + e.getMessage());
        }
        return out;
    }

    /** New seasons and episodes of your shows (favorites.json), aired in the past 60 days, marked fresh. */
    private List<Pick> freshEpisodes(JSONObject fav) {
        List<Pick> fresh = new ArrayList<>();
        JSONArray shows = fav.optJSONArray("shows");
        String since = day(-60), today = day(0);
        for (int i = 0; shows != null && i < shows.length(); i++) {
            try {
                JSONObject o = get("/tv/" + shows.getJSONObject(i).optInt("id") + "?language=" + lang());
                JSONObject last = o.optJSONObject("last_episode_to_air");
                String aired = last == null ? "" : last.optString("air_date");
                if (aired.compareTo(since) < 0 || aired.compareTo(today) > 0) continue;
                List<Pick> one = new ArrayList<>();
                add(one, o, "tv");
                if (one.isEmpty()) continue;
                Pick pick = one.get(0);
                fetchDetails(pick);
                if (pick.pkg == null) continue;
                pick.fresh = true;
                String label = last.optInt("episode_number") == 1 ? Ui.tr("New season") : Ui.tr("New episode");
                pick.meta = label + " · " + pick.meta;
                fresh.add(pick);
            } catch (Exception ignored) {
            }
        }
        return fresh;
    }

    /** app/assets/favorites.json: the shows and movies you like (TMDB ids), or null without it. */
    private JSONObject favorites() {
        try (InputStream in = context.getAssets().open("favorites.json")) {
            JSONObject o = new JSONObject(read(in));
            return o.optJSONArray("shows") == null && o.optJSONArray("movies") == null ? null : o;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Picks from your favorites instead of what's trending: first the new seasons and episodes of your shows (aired in
     * the past 60 days), then TMDB's recommendations for each favorite, ranked by how many of them recommend it and how
     * high. Only what streams on one of the TV's apps; never the favorites themselves, the genres in exclude_genres or
     * anime.
     */
    private void fetchFromFavorites(int p, JSONObject fav) throws Exception {
        Set<Integer> excluded = new HashSet<>();
        JSONArray ex = fav.optJSONArray("exclude_genres");
        for (int i = 0; ex != null && i < ex.length(); i++) excluded.add(ex.optInt(i));
        boolean noAnime = fav.optBoolean("no_anime");
        Set<String> mine = new HashSet<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray a = fav.optJSONArray(kind.equals("movie") ? "movies" : "shows");
            for (int i = 0; a != null && i < a.length(); i++) mine.add(kind + ":" + a.getJSONObject(i).optInt("id"));
        }
        // 1. New seasons and episodes of your shows.
        List<Pick> fresh = new ArrayList<>();
        JSONArray shows = fav.optJSONArray("shows");
        String since = day(-60), today = day(0);
        for (int i = 0; shows != null && i < shows.length(); i++) {
            try {
                JSONObject o = get("/tv/" + shows.getJSONObject(i).optInt("id") + "?language=" + lang());
                JSONObject last = o.optJSONObject("last_episode_to_air");
                String aired = last == null ? "" : last.optString("air_date");
                if (aired.compareTo(since) < 0 || aired.compareTo(today) > 0) continue;
                List<Pick> one = new ArrayList<>();
                add(one, o, "tv");
                if (one.isEmpty()) continue;
                Pick pick = one.get(0);
                fetchDetails(pick);
                if (pick.pkg == null) continue;
                pick.fresh = true;
                String label = last.optInt("episode_number") == 1 ? Ui.tr("New season") : Ui.tr("New episode");
                pick.meta = label + " · " + pick.meta;
                fresh.add(pick);
            } catch (Exception ignored) {
            }
        }
        // 2. Recommendations from each favorite, scored: one point per favorite that recommends it, more near the top.
        List<Pick> movies = new ArrayList<>(), tv = new ArrayList<>();
        for (String kind : new String[] {"movie", "tv"}) {
            JSONArray seeds = fav.optJSONArray(kind.equals("movie") ? "movies" : "shows");
            Map<Integer, Double> score = new HashMap<>();
            Map<Integer, JSONObject> found = new HashMap<>();
            for (int i = 0; seeds != null && i < seeds.length(); i++) {
                JSONArray r;
                try {
                    r = get("/" + kind + "/" + seeds.getJSONObject(i).optInt("id") + "/recommendations?language=" + lang())
                            .getJSONArray("results");
                } catch (Exception e) {
                    continue;
                }
                for (int j = 0; j < r.length(); j++) {
                    JSONObject o = r.getJSONObject(j);
                    int id = o.optInt("id");
                    if (mine.contains(kind + ":" + id) || !allowed(o, kind, excluded, noAnime)) continue;
                    score.merge(id, 1 + (r.length() - j) / (double) Math.max(1, r.length()), Double::sum);
                    found.put(id, o);
                }
            }
            List<Integer> ranked = new ArrayList<>(score.keySet());
            ranked.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
            List<Pick> out = kind.equals("movie") ? movies : tv;
            for (int i = 0; i < ranked.size() && i < 30 && out.size() < 12; i++) {
                List<Pick> one = new ArrayList<>();
                add(one, found.get(ranked.get(i)), kind);
                if (one.isEmpty()) continue;
                try {
                    fetchDetails(one.get(0));
                } catch (Exception e) {
                    continue;
                }
                if (one.get(0).pkg != null && !one.get(0).allAges && !(one.get(0).horror && excluded.contains(27))) out.add(one.get(0));
            }
        }
        List<Pick> list = new ArrayList<>(fresh);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Pick f : fresh) seen.add(key(f));
        alternate(list, seen, movies, tv, 24 + fresh.size());
        if (list.isEmpty()) throw new IOException("TMDB returned no recommendations");
        picks = list;
        period = p;
        listAt = System.currentTimeMillis();
        listLanguage = Ui.language;
    }

    /** The title's logo: in the TV's language, else English, else language-free; the best rated among those. */
    private static String bestLogo(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("logos");
        String best = null;
        double bestScore = -1;
        String lang = Ui.language.replaceAll("-.*", "");
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject l = list.optJSONObject(i);
            if (l == null || l.optString("file_path", "").isEmpty()) continue;
            String iso = l.isNull("iso_639_1") ? "" : l.optString("iso_639_1");
            double score = (iso.equals(lang) ? 3e6 : iso.equals("en") ? 2e6 : iso.isEmpty() ? 1e6 : 0) + l.optDouble("vote_average", 0) * 1000
                    + Math.min(l.optInt("width"), 1000);
            if (score < 1e6) continue;
            if (score > bestScore) {
                bestScore = score;
                best = l.optString("file_path");
            }
        }
        return best != null && best.endsWith(".svg") ? null : best;
    }

    /** TMDB's keywords say horror (movies list them in "keywords", shows in "results"). */
    private static boolean horror(JSONObject o) {
        JSONObject k = o.optJSONObject("keywords");
        JSONArray list = k == null ? null : k.has("keywords") ? k.optJSONArray("keywords") : k.optJSONArray("results");
        for (int i = 0; list != null && i < list.length(); i++) {
            String name = list.optJSONObject(i).optString("name").toLowerCase(Locale.ROOT);
            if (name.contains("horror") || name.contains("slasher")) return true;
        }
        return false;
    }

    /** Rated for all ages in REGION (or the US when REGION has no rating): "L", "G", "TV-Y", "TV-G", "TV-Y7". */
    private static boolean allAges(JSONObject o, String kind) {
        JSONObject wrap = o.optJSONObject(kind.equals("movie") ? "release_dates" : "content_ratings");
        JSONArray results = wrap == null ? null : wrap.optJSONArray("results");
        String region = null, us = null;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.optJSONObject(i);
            String rating = "";
            if (kind.equals("movie")) {
                JSONArray dates = r.optJSONArray("release_dates");
                for (int j = 0; dates != null && j < dates.length() && rating.isEmpty(); j++) rating = dates.optJSONObject(j).optString("certification");
            } else {
                rating = r.optString("rating");
            }
            if (rating.isEmpty()) continue;
            if (REGION.equals(r.optString("iso_3166_1"))) region = rating;
            if ("US".equals(r.optString("iso_3166_1"))) us = rating;
        }
        String rating = region != null ? region : us;
        return rating != null && Arrays.asList("L", "G", "TV-Y", "TV-Y7", "TV-G").contains(rating.trim().toUpperCase(Locale.ROOT));
    }

    /** Not an excluded genre, not anime (animation from Japan), already out (movies) or on the air (shows). */
    private static boolean allowed(JSONObject o, String kind, Set<Integer> excluded, boolean noAnime) {
        JSONArray genres = o.optJSONArray("genre_ids");
        boolean animation = false;
        for (int i = 0; genres != null && i < genres.length(); i++) {
            if (excluded.contains(genres.optInt(i))) return false;
            animation |= genres.optInt(i) == ANIMATION;
        }
        if (noAnime && animation && "ja".equals(o.optString("original_language"))) return false;
        String date = o.optString(kind.equals("movie") ? "release_date" : "first_air_date");
        return !date.isEmpty() && date.compareTo(day(0)) <= 0;
    }

    /** Language of titles and genres (TMDB's "fr-FR", "zh-CN"…); the services come from the region. */
    private static String lang() {
        String lang = Ui.language;
        if (lang.equals("en")) return "en-US";
        if (lang.equals("pt")) return "pt-BR";
        if (lang.equals("es")) return "ES".equals(java.util.Locale.getDefault().getCountry()) ? "es-ES" : "es-MX";
        if (lang.equals("de")) return "de-DE";   // TMDB's de-AT and de-CH have few translations of their own
        if (lang.indexOf('-') > 0) return lang;
        return lang + "-" + Ui.locale().getCountry();
    }

    private static void alternate(List<Pick> out, java.util.Set<String> seen, List<Pick> a, List<Pick> b, int max) {
        for (int i = 0; i < Math.max(a.size(), b.size()) && out.size() < max; i++) {
            if (i < a.size() && seen.add(key(a.get(i)))) out.add(a.get(i));
            if (i < b.size() && out.size() < max && seen.add(key(b.get(i)))) out.add(b.get(i));
        }
    }

    /** The date `offset` days from today, as "2026-10-19" (negative = in the past). */
    private static String day(int offset) {
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_YEAR, offset);
        return String.format(Locale.US, "%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH));
    }

    private static void add(List<Pick> list, JSONObject o, String kind) {
        String backdrop = o.optString("backdrop_path", "");
        if (backdrop.isEmpty() || "null".equals(backdrop)) return;
        if (Config.HIDE_ANIMATION && animated(o)) return;
        Pick p = new Pick();
        p.id = o.optInt("id");
        p.kind = kind;
        p.title = o.optString(kind.equals("movie") ? "title" : "name");
        p.backdrop = backdrop;
        String poster = o.optString("poster_path", "");
        p.poster = poster.isEmpty() || "null".equals(poster) ? null : poster;
        list.add(p);
    }

    private static boolean animated(JSONObject o) {
        JSONArray genres = o.optJSONArray("genre_ids");
        for (int i = 0; genres != null && i < genres.length(); i++) if (genres.optInt(i) == ANIMATION) return true;
        return false;
    }

    /** Genre, runtime (or seasons) and the service it's on, among the ones the TV has. */
    private void fetchDetails(Pick p) throws Exception {
        JSONObject o = get("/" + p.kind + "/" + p.id
                + "?language=" + lang() + "&append_to_response=watch/providers,images,external_ids,keywords," + (p.kind.equals("movie") ? "release_dates" : "content_ratings")
                + "&include_image_language=null," + Ui.language.replaceAll("-.*", "") + ",en");
        p.allAges = allAges(o, p.kind);
        p.horror = horror(o);
        String poster = o.optString("poster_path", "");
        if (!poster.isEmpty() && !"null".equals(poster)) p.poster = poster;
        p.logo = bestLogo(o.optJSONObject("images"));
        p.title = title(p, o);
        String best = bestBackdrop(o.optJSONObject("images"));
        if (best != null) p.backdrop = best;
        List<String> parts = new ArrayList<>();
        JSONArray genres = o.optJSONArray("genres");
        if (p.kind.equals("tv")) parts.add(Ui.tr("Series"));
        if (genres != null && genres.length() > 0) {
            String name = genres.getJSONObject(0).optString("name");
            parts.add(genre(name));
        }
        if (p.kind.equals("movie")) {
            int minutes = o.optInt("runtime");
            if (minutes > 0) parts.add(minutes >= 60 ? Ui.trf("{h}h {m}m", "h", minutes / 60, "m", minutes % 60) : Ui.trf("{m}m", "m", minutes));
        } else {
            int seasons = o.optInt("number_of_seasons");
            if (seasons > 0) parts.add(Ui.plural("plural|one:{n} season|other:{n} seasons", seasons));
        }
        JSONObject wp = o.optJSONObject("watch/providers");
        JSONObject br = wp == null ? null : wp.optJSONObject("results") == null ? null : wp.optJSONObject("results").optJSONObject(REGION);
        JSONArray flat = br == null ? null : br.optJSONArray("flatrate");
        p.where = where(br);
        // On Netflix here and Netflix is on the TV: it opens there, on the title's page when Wikidata knows its Netflix
        // number, else on Netflix's home; everything else opens in Nuvio.
        boolean onNetflix = false;
        for (int i = 0; flat != null && i < flat.length(); i++) onNetflix |= flat.getJSONObject(i).optInt("provider_id") == 8;
        if (nuvioMode() && onNetflix && installed(NETFLIX)) {
            p.app = "netflix";
            p.pkg = NETFLIX;
            String id = netflixId(p);
            if (id != null) {
                p.link = new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.netflix.com/title/" + id))
                        .setComponent(new android.content.ComponentName(NETFLIX, NETFLIX + ".MainActivity"))
                        .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("source", "30").toUri(android.content.Intent.URI_INTENT_SCHEME);
            }
            flat = null;
        } else if (nuvioMode()) {
            // Everything opens in Nuvio, on the title's page (it finds titles by their IMDb id); none without one.
            JSONObject ext = o.optJSONObject("external_ids");
            String imdb = o.optString("imdb_id", ext == null ? "" : ext.optString("imdb_id"));
            if (imdb.startsWith("tt") && installed(NUVIO)) {
                p.app = "nuvio";
                p.pkg = NUVIO;
                // A fresh task: Nuvio reads the title only when it starts, so one already open would just come back.
                p.link = new android.content.Intent().setComponent(new android.content.ComponentName(NUVIO, NUVIO + ".MainActivity"))
                        .setFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        .putExtra("contentId", imdb).putExtra("contentType", p.kind.equals("movie") ? "movie" : "series")
                        .putExtra("name", p.title).toUri(android.content.Intent.URI_INTENT_SCHEME);
            }
            flat = null;
        }
        for (int i = 0; flat != null && i < flat.length() && p.app == null; i++) {
            String[] s = service(flat.getJSONObject(i).optString("provider_name"), flat.getJSONObject(i).optInt("provider_id"));
            if (s != null && installed(s[2])) {
                p.app = s[1];
                p.pkg = s[2];
            }
        }
        if (p.app != null) parts.add(SERVICE_NAME.get(p.app));
        p.meta = String.join(" · ", parts);
        p.detailed = true;
    }

    /**
     * The title's Netflix number from Wikidata (free, no key: its TMDB id → Netflix ID), remembered; a title it doesn't
     * know yet is asked again after 3 days.
     */
    private String netflixId(Pick p) {
        String key = "nf:" + p.kind + p.id, saved = cache.getString(key, null);
        long now = System.currentTimeMillis();
        if (saved != null && !saved.startsWith("|")) return saved;
        if (saved != null && now - Long.parseLong(saved.substring(1)) < 3 * 86_400_000L) return null;
        try {
            String q = "SELECT ?n WHERE { ?i wdt:" + (p.kind.equals("movie") ? "P4947" : "P4983") + " \"" + p.id + "\"; wdt:P1874 ?n }";
            HttpURLConnection c = (HttpURLConnection) new URL("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(q, "UTF-8")).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setRequestProperty("User-Agent", "FreeMyTV/1.0 (home screen)");
            c.setRequestProperty("Accept", "application/sparql-results+json");
            try (InputStream in = c.getInputStream()) {
                JSONArray b = new JSONObject(read(in)).getJSONObject("results").getJSONArray("bindings");
                String id = b.length() == 0 ? "" : b.getJSONObject(0).getJSONObject("n").optString("value");
                if (!id.matches("\\d+")) id = "";
                cache.edit().putString(key, id.isEmpty() ? "|" + now : id).apply();
                return id.isEmpty() ? null : id;
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            AppLog.w("wikidata: " + e.getMessage());
            return null;
        }
    }

    /** Where TMDB says the title is officially on in your region: streaming first, then to rent, then to buy. */
    private static String where(JSONObject br) {
        if (br == null) return null;
        String[][] ways = {{"flatrate", "On {service}"}, {"rent", "Rent on {service}"}, {"buy", "Buy on {service}"}};
        for (String[] w : ways) {
            JSONArray a = br.optJSONArray(w[0]);
            String name = a == null || a.length() == 0 ? "" : a.optJSONObject(0).optString("provider_name");
            if (!name.isEmpty()) return Ui.trf(w[1], "service", name.replace("Google Play Movies", "Google Play"));
        }
        return null;
    }

    /**
     * Without a title in the TV's language TMDB answers with the original one, so a Korean series on a German TV reads
     * "닥터X: 하얀 마피아의 시대". Then the English title, when TMDB has one (one more request, only for those).
     */
    private String title(Pick p, JSONObject o) {
        String field = p.kind.equals("movie") ? "title" : "name";
        String own = o.optString(field, p.title);
        String original = o.optString("original_language");
        String lang = Ui.language.replaceAll("-.*", "");   // TMDB's original_language has no country: "zh", not "zh-CN"
        if (lang.equals("en") || own.isEmpty() || !own.equals(o.optString("original_" + field))
                || lang.equals(original) || "en".equals(original)) return own;
        try {
            String english = get("/" + p.kind + "/" + p.id + "?language=en-US").optString(field);
            return english.isEmpty() ? own : english;
        } catch (Exception e) {
            return own;
        }
    }

    /** TV genres that TMDB returns in English even in other languages (pt-BR, es, de…). */
    private static String genre(String name) {
        switch (name) {
            case "Action & Adventure": return Ui.tr("genre|Action & Adventure");
            case "Sci-Fi & Fantasy": return Ui.tr("genre|Sci-Fi & Fantasy");
            case "War & Politics": return Ui.tr("genre|War & Politics");
            case "Kids": return Ui.tr("genre|Kids");
            case "News": return Ui.tr("genre|News");
            case "Soap": return Ui.tr("genre|Soap");
            case "Talk": return Ui.tr("genre|Talk");
            default: return name;
        }
    }

    /**
     * The title's best backdrop: no text written over it (our own text goes on top of it), the closest to 1920 wide
     * (larger only costs download, disk and decoding) and, on a tie, the highest rated.
     */
    private static String bestBackdrop(JSONObject images) {
        JSONArray list = images == null ? null : images.optJSONArray("backdrops");
        String best = null;
        double bestScore = -1;
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject b = list.optJSONObject(i);
            if (b == null || b.optString("file_path", "").isEmpty()) continue;
            boolean textless = b.isNull("iso_639_1");
            int width = b.optInt("width");
            double score = (textless ? 1e7 : 0) + Math.min(width, 1920) * 100 - Math.max(0, width - 1920) + b.optDouble("vote_average", 0);
            if (score > bestScore) {
                bestScore = score;
                best = b.optString("file_path");
            }
        }
        return best;
    }

    /** The SERVICES row for a TMDB name: the installed one first, when a service has more than one package. */
    private String[] service(String name, int providerId) {
        String n = name.toLowerCase(Locale.ROOT);
        String[] first = null;
        // "HBO Max Amazon Channel", "Apple TV Amazon Channel"...: a subscription sold inside another app, not this one.
        if (n.contains(" channel")) return null;
        for (String[] s : SERVICES) {
            // Apple TV: only the streaming service (TMDB provider 350); provider 2 is the Apple TV Store (rent or buy).
            if (s[1].equals("appletv") && providerId != 350) continue;
            if (!(s[0].equals("max") ? n.matches(".*\\bmax\\b.*") : n.contains(s[0]))) continue;
            if (installed(s[2])) return s;
            if (first == null) first = s;
        }
        return first;
    }

    /** Services named in hide_services (config.properties), trimmed and lowercased: never a source of picks. */
    private static final Set<String> HIDDEN = new HashSet<>();
    static {
        for (String s : Config.HIDE_SERVICES.split(",")) {
            String key = s.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) HIDDEN.add(key);
        }
    }

    /** The service's app is on the TV, and the service isn't hidden (an app the TV ships without a subscription, say). */
    private boolean installed(String pkg) {
        if (!HIDDEN.isEmpty()) for (String[] s : SERVICES) if (s[2].equals(pkg) && HIDDEN.contains(s[1])) return false;
        return HomeApps.launchIntent(context.getPackageManager(), pkg) != null;
    }

    private long sourceStamp() {
        long stamp = (Config.TMDB_KEY.hashCode() * 31L + REGION.hashCode()) * 31L + (Config.HIDE_ANIMATION ? 1 : 0);
        for (String[] service : SERVICES) stamp = stamp * 31L + (installed(service[2]) ? service[2].hashCode() : 0);
        return stamp;
    }

    // MARK: - Watchmode (the title's own page in its app; the free plan's web addresses are what the apps open)

    private static String linkKey(Pick p) {
        return "wm2:" + p.kind + p.id + p.app;   // wm2: links found before the channel check are looked up again
    }

    private boolean needsLink(Pick p) {
        return linksOn() && p.pkg != null && p.link == null
                && cache.getString(linkKey(p), null) == null && System.currentTimeMillis() >= linkRetryAt;
    }

    /** A key is set and Watchmode hasn't refused it (a refused key isn't tried again until it changes). */
    private boolean linksOn() {
        String key = Config.WATCHMODE_KEY.trim();
        return !key.isEmpty() && cache.getInt("wm:refused", 0) != key.hashCode();
    }

    /** Sets p.link from what was looked up before, or looks it up (2 requests of the free plan's 2,500 a month). */
    private void findLink(Pick p) {
        if (!linksOn() || p.pkg == null || p.link != null) return;
        String saved = cache.getString(linkKey(p), null);
        if (saved != null) {
            p.link = saved.isEmpty() ? null : saved;
            return;
        }
        if (System.currentTimeMillis() < linkRetryAt) return;
        try {
            String link = "";
            JSONArray found = new JSONObject(watchmode("/search/?search_field=tmdb_" + p.kind + "_id&search_value=" + p.id))
                    .optJSONArray("title_results");
            if (found != null && found.length() > 0) {
                JSONArray sources = new JSONArray(watchmode("/title/" + found.getJSONObject(0).getInt("id") + "/sources/?regions=" + REGION));
                String[] service = null;
                for (String[] s : SERVICES) if (s[1].equals(p.app)) service = s;
                for (int i = 0; service != null && i < sources.length() && link.isEmpty(); i++) {
                    JSONObject s = sources.getJSONObject(i);
                    String name = s.optString("name").toLowerCase(Locale.ROOT).replace(" ", "");
                    String want = service[0].replace(" ", "");
                    // Not a channel sold inside another app ("AppleTV+ Amazon Channel", "Paramount+ (Via Amazon Prime)"):
                    // that link opens Amazon or Apple's app, not the one the pick is for.
                    boolean resold = name.contains("channel") || name.contains("(via");
                    boolean same = !resold && (want.equals("max") ? name.equals("max") : name.contains(want));
                    if (same && s.optString("type").equals("sub") && s.optString("web_url").startsWith("https://")) link = s.optString("web_url");
                }
            }
            cache.edit().putString(linkKey(p), link).apply();
            p.link = link.isEmpty() ? null : link;
        } catch (Exception e) {
            if ("Watchmode returned HTTP 401".equals(e.getMessage())) {
                cache.edit().putInt("wm:refused", Config.WATCHMODE_KEY.trim().hashCode()).apply();
            }
            linkRetryAt = System.currentTimeMillis() + 10 * 60_000L;   // quota or network: not again right away
            AppLog.w("watchmode: " + e.getMessage());
        }
    }

    private static String watchmode(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("https://api.watchmode.com/v1" + path + (path.contains("?") ? "&" : "?")
                + "apiKey=" + URLEncoder.encode(Config.WATCHMODE_KEY.trim(), "UTF-8")).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(10000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("Watchmode returned HTTP " + code);
            return read(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private JSONObject get(String path) throws IOException, org.json.JSONException {
        String key = readKey();
        if (key == null) throw new IOException("no TMDB key");
        boolean bearer = key.startsWith("eyJ");   // read access token (v4) or API key (v3)
        String url = API + path + (bearer ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + URLEncoder.encode(key, "UTF-8"));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("Accept", "application/json");
        if (bearer) c.setRequestProperty("Authorization", "Bearer " + key);
        try {
            int code = c.getResponseCode();
            if (code == 401) throw new IOException("TMDB rejected the key");
            if (code != 200) throw new IOException("TMDB returned HTTP " + code);
            return new JSONObject(read(c.getInputStream()));
        } finally {
            c.disconnect();
        }
    }

    private String readKey() {
        String key = Config.TMDB_KEY.trim();
        return key.isEmpty() ? null : key;
    }

    // MARK: - Images (TMDB originals on disk, up to 24 so the whole list fits; this hour's kept ready in memory)

    private void download(Pick p) throws IOException {
        File f = imageFile(p);
        if (f.exists()) return;
        //noinspection ResultOfMethodCallIgnored
        f.getParentFile().mkdirs();
        File part = new File(f.getPath() + ".part");
        if ("game".equals(p.kind)) {
            // A game's image ships with the app (app/assets/games).
            try (InputStream in = context.getAssets().open("games/" + p.id + ".jpg"); FileOutputStream out = new FileOutputStream(part)) {
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
            if (!part.renameTo(f)) throw new IOException("couldn't save the image");
            return;
        }
        HttpURLConnection c = (HttpURLConnection) new URL(IMAGES + p.backdrop).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(20000);
        try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
            byte[] buffer = new byte[16384];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
            throw e;
        } finally {
            c.disconnect();
        }
        if (!part.renameTo(f)) throw new IOException("couldn't save the image");
        prune(f.getParentFile());
    }

    /** Reads the photo (≥ the UI's width) and builds the backdrop (Backdrop: cards plus frosted and glass versions). */
    private void loadImage(Pick p) {
        synchronized (images) {
            if (images.containsKey(p.backdrop)) return;
        }
        File f = imageFile(p);
        try {
            download(p);
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getPath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = 1;
            while (bounds.outWidth / (opts.inSampleSize * 2) >= Backdrop.W) opts.inSampleSize *= 2;
            Bitmap b = BitmapFactory.decodeFile(f.getPath(), opts);
            if (b == null) throw new IOException("unreadable image");
            AppLog.w("backdrop \u201c" + p.title + "\u201d: " + bounds.outWidth + "×" + bounds.outHeight + " → " + Backdrop.W + "×" + Backdrop.H);
            boolean forLight = light;
            Backdrop backdrop = Backdrop.make(b, forLight);
            if (forLight != light) return;   // the design changed midway: this one is stale
            synchronized (images) {
                images.put(p.backdrop, backdrop);
                while (images.size() > (forLight ? 10 : 4)) images.remove(images.keySet().iterator().next());
            }
            notifyListeners();
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            AppLog.w("recommendation image: " + e.getMessage());
        }
    }

    void trim() {
        // Explore's picks stay: the home screen opens on that tab, so its cards are never gray when you come back.
        java.util.Set<String> keep = new java.util.HashSet<>();
        for (Pick p : now("movie")) if (p.backdrop != null) keep.add(p.backdrop);
        synchronized (images) {
            images.keySet().retainAll(keep);
        }
        // The posters of the two tabs the home screen opens on (Continue, or Explore without it).
        java.util.Set<String> keepPosters = new java.util.HashSet<>();
        for (String kind : new String[] {"movie", "tv"}) for (Pick p : now(kind)) {
            if (p.poster != null) keepPosters.add(p.poster);
            if (p.logo != null) keepPosters.add(p.logo);
        }
        synchronized (posters) {
            posters.keySet().retainAll(keepPosters);
        }
    }

    private static void prune(File dir) {
        // A download cut off when the system stopped the app leaves its .part behind.
        long stale = System.currentTimeMillis() - 10 * 60_000L;
        File[] parts = dir.listFiles((d, name) -> name.endsWith(".part"));
        for (int i = 0; parts != null && i < parts.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            if (parts[i].lastModified() < stale) parts[i].delete();
        }
        File[] files = dir.listFiles((d, name) -> !name.endsWith(".part"));
        if (files == null || files.length <= 24) return;
        Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 24; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }

    // MARK: - List cache

    private void save() {
        try {
            JSONArray a = new JSONArray();
            for (Pick p : picks) {
                a.put(new JSONObject().put("id", p.id).put("kind", p.kind).put("title", p.title).put("backdrop", p.backdrop).put("link", p.link == null ? "" : p.link)
                        .put("meta", p.meta).put("app", p.app == null ? "" : p.app).put("pkg", p.pkg == null ? "" : p.pkg)
                        .put("detailed", p.detailed).put("fresh", p.fresh).put("poster", p.poster == null ? "" : p.poster).put("logo", p.logo == null ? "" : p.logo));
            }
            SharedPreferences.Editor e = cache.edit().putString("picks", a.toString()).putInt("period", period).putLong("listAt", listAt)
                    .putString("language", listLanguage)
                    .putLong("keyStamp", keyStamp).putInt("version", CACHE_VERSION);
            // Watchmode links of titles no longer in the list go (the refused-key mark stays).
            Set<String> keep = new HashSet<>();
            for (Pick p : picks) keep.add(linkKey(p));
            for (String k : cache.getAll().keySet()) if ((k.startsWith("wm:") || k.startsWith("wm2:")) && !k.equals("wm:refused") && !keep.contains(k)) e.remove(k);
            e.apply();
        } catch (Exception ignored) {
        }
    }

    private static List<Pick> parse(JSONArray a) {
        List<Pick> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o == null) continue;
            Pick p = new Pick();
            p.id = o.optInt("id");
            p.kind = o.optString("kind");
            p.title = o.optString("title");
            p.backdrop = o.optString("backdrop");
            p.link = o.optString("link").isEmpty() ? null : o.optString("link");
            p.meta = o.optString("meta");
            p.app = o.optString("app").isEmpty() ? null : o.optString("app");
            p.pkg = o.optString("pkg").isEmpty() ? null : o.optString("pkg");
            p.detailed = o.optBoolean("detailed");
            p.fresh = o.optBoolean("fresh");
            p.poster = o.optString("poster").isEmpty() ? null : o.optString("poster");
            p.logo = o.optString("logo").isEmpty() ? null : o.optString("logo");
            out.add(p);
        }
        return out;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int n;
        while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
