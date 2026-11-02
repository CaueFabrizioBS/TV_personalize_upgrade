"""Take one snapshot of a Google TV / Android TV over adb and append it to datasets/private/.

Read-only: it only runs dumpsys, pm list and reads /proc/meminfo. Nothing on the TV changes and nothing shows on screen.

    python collector/collect.py 192.168.1.50            # the TV's IP (port 5555 is added)
    ADB=/path/to/adb python collector/collect.py 192.168.1.50

Three CSVs grow by one snapshot each run (the same `taken_at` ties them together):
    system.csv     memory, storage, the home screen app and its frame times
    packages.csv   every package: enabled or not, system or user
    usage.csv      today's screen time and launches per app (Android's usage stats)
"""
import csv
import os
import re
import shutil
import subprocess
import sys
from datetime import datetime
from pathlib import Path

OUT = Path(__file__).resolve().parent.parent / "datasets" / "private"


def find_adb():
    if os.environ.get("ADB"):
        return os.environ["ADB"]
    local = Path(os.environ.get("LOCALAPPDATA", "")) / "Android" / "platform-tools" / "adb.exe"
    return str(local) if local.exists() else shutil.which("adb") or "adb"


ADB = find_adb()


def shell(serial, cmd):
    r = subprocess.run([ADB, "-s", serial, "shell", cmd], capture_output=True, text=True, encoding="utf-8",
                       errors="replace", timeout=60)
    return r.stdout


def seconds(hms):
    """'4:37:04' or '12:17' or '00:02' to seconds."""
    total = 0
    for part in hms.split(":"):
        total = total * 60 + int(part)
    return total


def number(pattern, text, default=None, kind=int):
    m = re.search(pattern, text)
    return kind(m.group(1)) if m else default


def packages(serial):
    """{package: (enabled, type)} from pm list."""
    def names(flag):
        return {line.split(":", 1)[1].strip() for line in shell(serial, f"pm list packages {flag}").splitlines()
                if line.startswith("package:")}
    every, disabled, system = names("-u"), names("-d"), names("-s")
    return {p: (p not in disabled, "system" if p in system else "user") for p in sorted(every)}


def usage(serial):
    """Today's per-app screen time and launches: the 'In-memory daily stats' block of dumpsys usagestats."""
    dump = shell(serial, "dumpsys usagestats")
    start = dump.find("In-memory daily stats")
    end = dump.find("In-memory weekly stats", start)
    rows = []
    for line in dump[start:end].splitlines():
        m = re.search(r'package=(\S+) .*totalTimeVisible="([\d:]+)" lastTimeVisible="([^"]+)".*appLaunchCount=(\d+)',
                      line)
        if m and (seconds(m.group(2)) or int(m.group(4))):
            last = m.group(3) if not m.group(3).startswith("1969") else ""
            rows.append((m.group(1), seconds(m.group(2)), last, int(m.group(4))))
    return rows


def system(serial, pkgs):
    mem = shell(serial, "cat /proc/meminfo")
    home = shell(serial, "cmd package resolve-activity --brief -c android.intent.category.HOME "
                         "-a android.intent.action.MAIN").strip().splitlines()[-1].split("/")[0]
    pss = number(r"TOTAL PSS:\s+(\d+)", shell(serial, f"dumpsys meminfo --package {home}"))
    gfx = shell(serial, f"dumpsys gfxinfo {home}")
    if "Total frames" not in gfx:   # the home screen may run in its own process (Free My TV: io.freemytv:home)
        for proc in shell(serial, "ps -A -o NAME").split():
            if proc.startswith(home + ":"):
                gfx = shell(serial, f"dumpsys gfxinfo {proc}")
                if "Total frames" in gfx:
                    break
    df = (shell(serial, "df -h /data 2>&1").strip().splitlines() or [""])[-1].split()
    return {
        "mem_total_kb": number(r"MemTotal:\s+(\d+)", mem),
        "mem_available_kb": number(r"MemAvailable:\s+(\d+)", mem),
        "home_app": home,
        "home_pss_kb": pss,
        "storage_used_pct": int(df[4].rstrip("%")) if len(df) > 4 and df[4].endswith("%") else None,
        "packages": len(pkgs),
        "packages_disabled": sum(1 for e, _ in pkgs.values() if not e),
        "packages_user": sum(1 for _, t in pkgs.values() if t == "user"),
        # Frame times of the home screen since it last started (or since the counters were reset).
        "frames": number(r"Total frames rendered: (\d+)", gfx),
        "janky_pct": number(r"Janky frames: \d+ \(([\d.]+)%\)", gfx, kind=float),
        "frame_p50_ms": number(r"50th percentile: (\d+)ms", gfx),
        "frame_p90_ms": number(r"90th percentile: (\d+)ms", gfx),
        "frame_p95_ms": number(r"95th percentile: (\d+)ms", gfx),
    }


def append(name, header, rows):
    path = OUT / name
    new = not path.exists()
    with open(path, "a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if new:
            w.writerow(header)
        w.writerows(rows)


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    serial = sys.argv[1] if ":" in sys.argv[1] else sys.argv[1] + ":5555"
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    if subprocess.run([ADB, "-s", serial, "get-state"], capture_output=True, text=True).stdout.strip() != "device":
        sys.exit(f"Can't reach the TV at {serial}. Is it on, with USB debugging allowed for this computer?")
    OUT.mkdir(parents=True, exist_ok=True)
    taken = datetime.now().isoformat(timespec="seconds")

    pkgs = packages(serial)
    info = system(serial, pkgs)
    append("system.csv", ["taken_at", *info], [[taken, *info.values()]])
    append("packages.csv", ["taken_at", "package", "enabled", "type"],
           [[taken, p, "yes" if e else "no", t] for p, (e, t) in pkgs.items()])
    use = usage(serial)
    append("usage.csv", ["taken_at", "package", "screen_seconds_today", "last_visible", "launches_today"],
           [[taken, *row] for row in use])
    print(f"{taken}: {len(pkgs)} packages ({info['packages_disabled']} disabled), {len(use)} apps used today, "
          f"{info['mem_available_kb'] // 1024} MB free, home {info['home_app']}")


if __name__ == "__main__":
    main()
