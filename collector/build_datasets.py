"""Build the public, anonymized datasets in datasets/ from the private ones in datasets/private/.

    python collector/build_datasets.py

What stays private: the TV's address and the exact dates and times. In the public files a date is "day N"
(day 1 = the day the cleanup started) and a time is just the hour.

Inputs (datasets/private/, never committed):
    benchmark*.csv   from benchmark.py, one row per run of the same test on each version
    tv_perf.csv      from pull_tv_log.py, the log the TV keeps by itself (one line every 15 minutes)
"""
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parent.parent
PRIVATE, OUT = ROOT / "datasets" / "private", ROOT / "datasets"
# Day 1 = the day the cleanup started: datasets/private/day_one.txt (YYYY-MM-DD).
DAY_ONE = pd.Timestamp((PRIVATE / "day_one.txt").read_text().strip()).normalize()


def day(ts):
    return (pd.Timestamp(ts).normalize() - DAY_ONE).days + 1


VERSIONS = {   # the benchmark's labels, and what each version is
    "V0": "Google TV, factory", "V1": "Google TV, cleaned up", "V2": "Free My TV, original",
    "V5": "Free My TV, this fork", "V6": "This fork, lighter sky",
}


def benchmark():
    """The same test on each version (collector/benchmark.py): runs of one version are numbered, times dropped."""
    for name in ("benchmark", "benchmark_frames", "benchmark_apps", "benchmark_app_frames"):
        if not (PRIVATE / f"{name}.csv").exists():
            continue
        b = pd.read_csv(PRIVATE / f"{name}.csv")
        b["version"] = b["version"].str.split().str[0]
        b["run"] = b.groupby("version")["taken_at"].rank(method="dense").astype(int)
        b.insert(1, "label", b["version"].map(VERSIONS))
        b.drop(columns=["taken_at", "home_app"], errors="ignore").to_csv(OUT / f"{name}.csv", index=False)


def tv_perf():
    """The TV's own 15-minute log (pull_tv_log.py): day N and the hour instead of the date."""
    if not (PRIVATE / "tv_perf.csv").exists():
        return
    t = pd.read_csv(PRIVATE / "tv_perf.csv")
    at = pd.to_datetime(t.pop("taken_at"))
    t.insert(0, "day", at.map(day))
    t.insert(1, "hour", at.dt.hour)
    t.to_csv(OUT / "tv_perf.csv", index=False)


if __name__ == "__main__":
    benchmark()
    tv_perf()
    for f in sorted(OUT.glob("*.csv")):
        print(f"{f.name}: {len(pd.read_csv(f))} rows")
