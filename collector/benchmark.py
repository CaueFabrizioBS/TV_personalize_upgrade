"""Benchmark the TV's current home screen, the same way every time, so versions can be compared.

    python collector/benchmark.py 192.168.1.50 "V5 current" [--enter-profiles]

It restarts the TV (so every version starts from the same state) and measures:
    boot_s         from the restart to Android saying it finished booting
    home_s         from the restart to the home screen on screen
    mem_*          3 minutes after boot, resting on the home screen: RAM free for apps, and the home screen's own
    open_ms        how long the home screen takes to come back from another app (median of 5, like pressing Home)
    frames         2 minutes moving around the home screen with the arrows: frame times and janky frames
    apps           Netflix, Nuvio and Spotify: cold launch time, RAM while open, frames in their menus, time back home
Rows go to datasets/private/benchmark.csv and the frame-time histogram to datasets/private/benchmark_frames.csv.
"""
import csv
import re
import statistics
import subprocess
import sys
import time
from datetime import datetime

import collect
from collect import ADB, OUT, number


def shell(serial, cmd):
    """collect.shell, but the TV's adb link drops now and then: a reading that came back empty reconnects and tries
    again (3 times). Actions (keys, starts, resets) run once: twice would press twice."""
    action = cmd.startswith(("input ", "am force-stop", "am start -a android.settings")) or cmd.endswith(" reset")
    for _ in range(1 if action else 3):
        try:
            out = collect.shell(serial, cmd)
        except subprocess.TimeoutExpired:
            out = ""
        if out.strip():
            return out
        subprocess.run([ADB, "disconnect", serial], capture_output=True, timeout=30)
        subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
        time.sleep(2)
    return out


def adb(serial, *args, timeout=30):
    try:
        return subprocess.run([ADB, "-s", serial, *args], capture_output=True, text=True, timeout=timeout).stdout
    except subprocess.TimeoutExpired:
        return ""


def home_activity(serial):
    return shell(serial, "cmd package resolve-activity --brief -c android.intent.category.HOME "
                         "-a android.intent.action.MAIN").strip().splitlines()[-1]


def resumed(serial):
    m = re.search(r"mResumedActivity: ActivityRecord\{\S+ \S+ (\S+)", shell(serial, "dumpsys activity activities"))
    return m.group(1) if m else ""


def processes(serial, pkg):
    return [p for p in shell(serial, "ps -A -o NAME").split() if p == pkg or p.startswith(pkg + ":")]


def reboot(serial, pkg):
    """Restart and time it: (boot_s, home_s)."""
    old_up = float(shell(serial, "cat /proc/uptime").split()[0])
    t0 = time.time()
    adb(serial, "reboot")
    time.sleep(15)
    boot = home = None
    while time.time() - t0 < 300:
        subprocess.run([ADB, "disconnect", serial], capture_output=True, timeout=30)   # the old link goes stale
        subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
        try:
            if adb(serial, "get-state").strip() == "device":
                up = (shell(serial, "cat /proc/uptime").split() or ["1e9"])[0]
                if float(up) >= old_up:
                    raise subprocess.TimeoutExpired("reboot", 0)   # not restarted yet: still the old boot
                if boot is None and shell(serial, "getprop sys.boot_completed").strip() == "1":
                    boot = time.time() - t0
                if boot is not None and resumed(serial).startswith(pkg + "/"):
                    home = time.time() - t0
                    break
        except subprocess.TimeoutExpired:
            pass
        time.sleep(1)
    return boot, home


def memory(serial, pkg):
    avail = number(r"MemAvailable:\s+(\d+)", shell(serial, "cat /proc/meminfo"))
    pss = sum(number(r"TOTAL PSS:\s+(\d+)", shell(serial, f"dumpsys meminfo {p}"), 0) for p in processes(serial, pkg))
    return avail // 1024, pss // 1024


def open_time(serial, activity):
    """Leave for Settings, come back with the Home intent: Android's own TotalTime, median of 5."""
    times = []
    for _ in range(5):
        shell(serial, "am start -a android.settings.SETTINGS")
        time.sleep(3)
        out = shell(serial, "am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
        t = number(r"TotalTime: (\d+)", out)
        if t:
            times.append(t)
        time.sleep(3)
    return statistics.median(times) if times else None


def frames(serial, pkg, seconds=120):
    """Reset the home screen's frame counters, move around with the arrows, read them back."""
    procs = processes(serial, pkg)
    for p in procs:
        shell(serial, f"dumpsys gfxinfo {p} reset")
    keys = ["DPAD_RIGHT"] * 4 + ["DPAD_LEFT"] * 4 + ["DPAD_DOWN", "DPAD_RIGHT", "DPAD_RIGHT", "DPAD_LEFT",
                                                     "DPAD_LEFT", "DPAD_UP"]
    t0, i = time.time(), 0
    while time.time() - t0 < seconds:
        shell(serial, f"input keyevent KEYCODE_{keys[i % len(keys)]}")
        i += 1
        time.sleep(0.8)
    best = ""
    for p in procs:
        gfx = shell(serial, f"dumpsys gfxinfo {p}")
        if number(r"Total frames rendered: (\d+)", gfx, 0) > number(r"Total frames rendered: (\d+)", best, 0):
            best = gfx
    hist = {}
    m = re.search(r"^HISTOGRAM: (.*)$", best, re.M)
    if m:
        hist = {int(ms): int(n) for ms, n in re.findall(r"(\d+)ms=(\d+)", m.group(1)) if int(n)}
    return {
        "frames": number(r"Total frames rendered: (\d+)", best),
        "janky_pct": number(r"Janky frames: \d+ \(([\d.]+)%\)", best, kind=float),
        "frame_p50_ms": number(r"50th percentile: (\d+)ms", best),
        "frame_p90_ms": number(r"90th percentile: (\d+)ms", best),
        "frame_p99_ms": number(r"99th percentile: (\d+)ms", best),
    }, hist


# Netflix and Nuvio stop at "Who's watching?". Only with --enter-profiles does the test press OK there, which leaves the
# app on the first profile: it can hide the "Continue watching" of whoever uses another one.
PROFILES = {"Netflix", "Nuvio"} if "--enter-profiles" in sys.argv else set()
APPS = {"Netflix": "com.netflix.ninja", "Nuvio": "com.nuvio.tv", "Spotify": "com.spotify.tv.android"}


def app_test(serial, name, pkg):
    """Open the app from cold, rest 20 s, move around its menus for 30 s, then come back home."""
    activity = shell(serial, f"cmd package resolve-activity --brief -c android.intent.category.LEANBACK_LAUNCHER "
                             f"-a android.intent.action.MAIN {pkg}").strip().splitlines()[-1]
    if "/" not in activity:
        return None, {}
    shell(serial, f"am force-stop {pkg}")
    time.sleep(3)
    launch = number(r"TotalTime: (\d+)", shell(serial, f"am start -W -n {activity}"))
    if name in PROFILES:   # "Who's watching?": the first profile
        time.sleep(8)
        shell(serial, "input keyevent KEYCODE_DPAD_CENTER")
    time.sleep(20)
    avail, pss = memory(serial, pkg)
    stats, hist = frames(serial, pkg, seconds=30)
    back = number(r"TotalTime: (\d+)", shell(serial, "am start -W -a android.intent.action.MAIN "
                                                       "-c android.intent.category.HOME"))
    time.sleep(3)
    return {"app": name, "launch_ms": launch, "mem_available_mb": avail, "app_mb": pss, "home_back_ms": back,
            **stats}, hist


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    serial = sys.argv[1] if ":" in sys.argv[1] else sys.argv[1] + ":5555"
    version = sys.argv[2]
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    activity = home_activity(serial)
    pkg = activity.split("/")[0]
    print(f"{version}: restarting the TV (home screen {pkg})")
    boot, home_s = reboot(serial, pkg)
    print(f"  boot {boot:.0f} s, home on screen {home_s:.0f} s")
    time.sleep(180)   # rest 3 minutes on the home screen
    avail, pss = memory(serial, pkg)
    print(f"  {avail} MB free, home screen {pss} MB")
    open_ms = open_time(serial, activity)
    print(f"  opens in {open_ms} ms")
    stats, hist = frames(serial, pkg)
    print(f"  frames {stats}")
    shell(serial, "am start -a android.intent.action.MAIN -c android.intent.category.HOME")
    apps = []
    for name, app in APPS.items():
        r, h = app_test(serial, name, app)
        if r:
            print(f"  {name}: {r}")
            apps.append((r, h))

    taken = datetime.now().isoformat(timespec="seconds")
    row = {"taken_at": taken, "version": version, "home_app": pkg, "boot_s": round(boot), "home_s": round(home_s),
           "mem_available_mb": avail, "home_mb": pss, "open_ms": open_ms, **stats}
    for name, header, rows in [
        ("benchmark.csv", list(row), [list(row.values())]),
        ("benchmark_frames.csv", ["taken_at", "version", "frame_ms", "frames"],
         [[taken, version, ms, n] for ms, n in sorted(hist.items())]),
        ("benchmark_apps.csv", ["taken_at", "version", *apps[0][0]] if apps else [],
         [[taken, version, *r.values()] for r, _ in apps]),
        ("benchmark_app_frames.csv", ["taken_at", "version", "app", "frame_ms", "frames"],
         [[taken, version, r["app"], ms, n] for r, h in apps for ms, n in sorted(h.items())]),
    ]:
        path = OUT / name
        new = not path.exists()
        with open(path, "a", newline="", encoding="utf-8") as f:
            w = csv.writer(f)
            if new:
                w.writerow(header)
            w.writerows(rows)


if __name__ == "__main__":
    main()
