"""Sample the home screen's frame times since the last sample, and append them to datasets/private/frames.csv.

Read-only: it reads dumpsys gfxinfo (Android's own frame counters) and changes nothing on the TV. Run it every 15
minutes or so (Task Scheduler, cron): Android only counts a frame when the home screen draws one, so the frames of
each interval are the time the home screen was actually on screen.

    python collector/frames.py 192.168.1.50

Each row is one frame-time bucket of one interval: taken_at, period (the sky scene of that hour), frame_ms, frames.
"""
import csv
import json
import re
import subprocess
import sys
from datetime import datetime

from collect import ADB, OUT, shell

# The sky scenes by hour (the same split the home screen uses).
PERIODS = [(0, "deep night"), (3, "night"), (5, "sunrise"), (7, "morning"), (11, "midday"), (16.5, "sunset"),
           (19.5, "night")]


def period(t):
    h = t.hour + t.minute / 60
    return [name for start, name in PERIODS if h >= start][-1]


def counters(serial, home):
    """The home screen's cumulative frame histogram {ms: frames}, from its own process."""
    for proc in [home] + [p for p in shell(serial, "ps -A -o NAME").split() if p.startswith(home + ":")]:
        gfx = shell(serial, f"dumpsys gfxinfo {proc}")
        m = re.search(r"^HISTOGRAM: (.*)$", gfx, re.M)
        if m and "Total frames rendered: 0" not in gfx:
            since = re.search(r"Stats since: (\d+)ns", gfx).group(1)
            return since, {int(ms): int(n) for ms, n in re.findall(r"(\d+)ms=(\d+)", m.group(1))}
    return None, {}


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    serial = sys.argv[1] if ":" in sys.argv[1] else sys.argv[1] + ":5555"
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    if subprocess.run([ADB, "-s", serial, "get-state"], capture_output=True, text=True).stdout.strip() != "device":
        sys.exit(f"Can't reach the TV at {serial} (off or asleep).")
    home = shell(serial, "cmd package resolve-activity --brief -c android.intent.category.HOME "
                         "-a android.intent.action.MAIN").strip().splitlines()[-1].split("/")[0]
    since, now = counters(serial, home)
    OUT.mkdir(parents=True, exist_ok=True)
    state = OUT / "frames_last.json"
    last = json.loads(state.read_text()) if state.exists() else {}
    state.write_text(json.dumps({"home": home, "since": since, "hist": now}))
    if not last:
        print("First sample: counters saved, frames are counted from the next run.")
        return
    # Same process as last time: count only the new frames. Restarted (or another home app): all its frames are new.
    before = {int(k): v for k, v in last["hist"].items()} if last["since"] == since and last["home"] == home else {}
    delta = {ms: n - before.get(ms, 0) for ms, n in now.items() if n - before.get(ms, 0) > 0}
    t = datetime.now()
    path = OUT / "frames.csv"
    new = not path.exists()
    with open(path, "a", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        if new:
            w.writerow(["taken_at", "home_app", "period", "frame_ms", "frames"])
        w.writerows([t.isoformat(timespec="seconds"), home, period(t), ms, n] for ms, n in sorted(delta.items()))
    print(f"{t:%H:%M} {period(t)}: {sum(delta.values())} new frames")


if __name__ == "__main__":
    main()
