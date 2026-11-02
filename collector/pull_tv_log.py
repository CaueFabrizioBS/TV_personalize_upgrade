"""Bring home the performance log the TV keeps by itself, and add its new lines to datasets/private/tv_perf.csv.

The home screen (Free My TV) writes one line every 15 minutes while the TV is on, even with the PC off:
Android/data/io.freemytv/files/perf.csv on the TV. Run this whenever the PC and the TV are both on.

    python collector/pull_tv_log.py 192.168.1.50
"""
import subprocess
import sys
import tempfile
from pathlib import Path

import pandas as pd

from collect import ADB, OUT

ON_TV = "/sdcard/Android/data/io.freemytv/files/"


def main():
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    serial = sys.argv[1] if ":" in sys.argv[1] else sys.argv[1] + ":5555"
    subprocess.run([ADB, "connect", serial], capture_output=True, timeout=30)
    frames = []
    with tempfile.TemporaryDirectory() as tmp:
        for name in ("perf.old.csv", "perf.csv"):   # the TV keeps the last 2 MB in two files
            local = Path(tmp) / name
            subprocess.run([ADB, "-s", serial, "pull", ON_TV + name, str(local)], capture_output=True, timeout=120)
            if local.exists():
                frames.append(pd.read_csv(local))
    if not frames:
        sys.exit("No log on the TV yet (the first line comes 15 minutes after the home screen starts).")
    OUT.mkdir(parents=True, exist_ok=True)
    path = OUT / "tv_perf.csv"
    if path.exists():
        frames.insert(0, pd.read_csv(path))
    log = pd.concat(frames).drop_duplicates("taken_at").sort_values("taken_at")
    log.to_csv(path, index=False)
    print(f"{len(log)} lines, from {log['taken_at'].min()} to {log['taken_at'].max()}")


if __name__ == "__main__":
    main()
