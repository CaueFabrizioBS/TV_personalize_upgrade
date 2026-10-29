"""Build the public, anonymized datasets in datasets/ from the private ones in datasets/private/.

    python collector/build_datasets.py

What stays private: the TV's address, exact dates and times, and hours of watching. In the public files a date is
"day N" (day 1 = the first record), and screen time is each app's share of that day's total, in %.

Inputs (datasets/private/, never committed):
    usage_before.csv     the usage report taken before any change (Package, Enabled, Type, LastUsed, Screen, Launches)
    system.csv, packages.csv, usage.csv     from collect.py, one snapshot per run
"""
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
PRIVATE, OUT = ROOT / "datasets" / "private", ROOT / "datasets"
# Day 1 = the day the cleanup started: datasets/private/day_one.txt (YYYY-MM-DD), else the first snapshot's day.
_first = PRIVATE / "day_one.txt"
DAY_ONE = pd.Timestamp(_first.read_text().strip() if _first.exists()
                       else pd.read_csv(PRIVATE / "system.csv")["taken_at"].min()).normalize()

# What each package is, for the charts. Anything not listed is "System" (Android, Google or TCL internals).
APPS = {
    # The apps people watch
    "com.netflix.ninja": ("Netflix", "Streaming"), "com.google.android.youtube.tv": ("YouTube", "Streaming"),
    "com.amazon.amazonvideo.livingroom": ("Prime Video", "Streaming"), "com.disney.disneyplus": ("Disney+", "Streaming"),
    "com.wbd.stream": ("Max", "Streaming"), "com.globo.globotv": ("Globoplay", "Streaming"),
    "com.apple.atve.androidtv.appletv": ("Apple TV", "Streaming"), "com.stremio.one": ("Stremio", "Streaming"),
    "com.nuvio.tv": ("Nuvio", "Streaming"), "com.mercadolibre.android.mplay_tv": ("Mercado Play", "Streaming"),
    "com.spotify.tv.android": ("Spotify", "Music"), "com.google.android.youtube.tvmusic": ("YouTube Music", "Music"),
    "com.valvesoftware.steamlink": ("Steam Link", "Games"), "gamepass.fire.tv": ("Game Pass", "Games"),
    "com.google.android.play.games": ("Play Games", "Games"),
    # Home screen, screensaver and settings
    "com.google.android.apps.tv.launcherx": ("Google TV home", "Home screen"),
    "io.freemytv": ("Free My TV home", "Home screen"),
    "com.google.android.apps.tv.dreamx": ("Ambient mode", "Home screen"),
    "com.tcl.waterfall.overseas": ("TCL content feed", "Home screen"),
    "com.google.android.tungsten.setupwraith": ("Google TV setup", "Home screen"),
    "com.android.tv.settings": ("Settings", "Settings"),
    # Tools and extras
    "com.tcl.browser": ("BrowseHere browser", "Tools"), "com.esaba.downloader": ("Downloader", "Tools"),
    "com.android.vending": ("Play Store", "Tools"), "com.google.android.katniss": ("Google Assistant", "Tools"),
    "com.tcl.miracast": ("Screen mirroring", "Tools"), "com.boost.airplay.receiver": ("AirPlay (Boost)", "Tools"),
    "com.google.android.apps.mediashell": ("Chromecast built-in", "Tools"), "com.tcl.hearaid": ("Hearing aid", "Tools"),
    "com.tcl.tv": ("TV inputs", "Tools"),
    # Pre-installed extras nobody used
    "com.google.android.feedback": ("Google Feedback", "Bloat"), "com.android.printspooler": ("Print spooler", "Bloat"),
    "com.google.android.syncadapters.calendar": ("Calendar sync", "Bloat"),
    "android.autoinstalls.config.google.gtvpai": ("Partner app auto-installer", "Bloat"),
    "com.google.android.marvin.talkback": ("TalkBack", "Bloat"), "com.tcl.esticker": ("TCL store stickers", "Bloat"),
    "com.tcl.exhibit": ("TCL store demo", "Bloat"), "com.tcl.hotelmenu": ("TCL hotel menu", "Bloat"),
    "com.tcl.logkit": ("TCL logging", "Bloat"), "com.tcl.messagebox": ("TCL messages", "Bloat"),
    "com.tcl.ocean.instructions": ("TCL e-manual", "Bloat"), "com.tcl.repairguide": ("TCL repair guide", "Bloat"),
    "com.tcl.channelplus": ("TCL Channel", "Bloat"), "com.tcl.usercenter": ("TCL account center", "Bloat"),
    "com.tcl.magiconnectfree": ("TCL MagiConnect", "Bloat"),
}


def describe(pkg):
    return APPS.get(pkg, (pkg.split(".")[-1], "System"))


def day(ts):
    return (pd.Timestamp(ts).normalize() - DAY_ONE).days + 1


def seconds(hms):
    if not isinstance(hms, str) or not hms:
        return 0
    total = 0
    for part in hms.split(":"):
        total = total * 60 + int(part)
    return total


def shares(df, value, by):
    """Each row's `value` as % of its group's total."""
    return (100 * df[value] / df.groupby(by)[value].transform("sum")).round(2)


def apps():
    """Every package ever seen, with its name and category."""
    seen = set()
    if (PRIVATE / "usage_before.csv").exists():
        seen |= set(pd.read_csv(PRIVATE / "usage_before.csv")["Package"])
    if (PRIVATE / "packages.csv").exists():
        seen |= set(pd.read_csv(PRIVATE / "packages.csv")["package"])
    rows = [(p, *describe(p)) for p in sorted(seen | set(APPS))]
    pd.DataFrame(rows, columns=["package", "name", "category"]).to_csv(OUT / "apps.csv", index=False)


def usage_before():
    """How the TV was used before anything changed: share of screen time and of launches per app."""
    if not (PRIVATE / "usage_before.csv").exists():
        return
    u = pd.read_csv(PRIVATE / "usage_before.csv")
    u["screen"] = u["Screen"].map(seconds)
    u = u[(u["screen"] > 0) | (u["Launches"] > 0)].copy()
    u["day"] = 0   # the report covers the days before day 1
    out = pd.DataFrame({
        "package": u["Package"],
        "enabled": u["Enabled"].str.lower().map({"yes": "yes", "disabled": "no"}),
        "type": u["Type"].replace({"any": "system"}),
        "screen_share_pct": shares(u, "screen", "day"),
        "launch_share_pct": shares(u, "Launches", "day"),
    }).sort_values("screen_share_pct", ascending=False)
    out.to_csv(OUT / "usage_before.csv", index=False)


def snapshots():
    """The collector's snapshots, with days instead of dates and shares instead of hours."""
    if not (PRIVATE / "system.csv").exists():
        return
    s = pd.read_csv(PRIVATE / "system.csv")
    s.insert(0, "day", s.pop("taken_at").map(day))
    s["mem_available_mb"] = (s.pop("mem_available_kb") / 1024).round()
    s["mem_total_mb"] = (s.pop("mem_total_kb") / 1024).round()
    s["home_mb"] = (s.pop("home_pss_kb") / 1024).round()
    s.to_csv(OUT / "snapshots_system.csv", index=False)

    p = pd.read_csv(PRIVATE / "packages.csv")
    p.insert(0, "day", p.pop("taken_at").map(day))
    p.drop_duplicates(["day", "package"], keep="last").to_csv(OUT / "snapshots_packages.csv", index=False)

    u = pd.read_csv(PRIVATE / "usage.csv")
    u["day"] = u["taken_at"].map(day)
    u = u.sort_values("taken_at").drop_duplicates(["day", "package"], keep="last")   # the day's latest count
    out = pd.DataFrame({"day": u["day"], "package": u["package"],
                        "screen_share_pct": shares(u, "screen_seconds_today", "day"),
                        "launches": u["launches_today"]})
    out.sort_values(["day", "screen_share_pct"], ascending=[True, False]).to_csv(OUT / "snapshots_usage.csv",
                                                                                 index=False)


if __name__ == "__main__":
    apps()
    usage_before()
    snapshots()
    for f in sorted(OUT.glob("*.csv")):
        print(f"{f.name}: {len(pd.read_csv(f))} rows")
