"""TV Cleaner dashboard: a Google TV's performance, version by version, and through the day."""
from pathlib import Path

import numpy as np
import pandas as pd
import plotly.express as px
import streamlit as st

D = Path(__file__).parent / "datasets"
ORDER = ["V0", "V1", "V2", "V5"]
COLOR = {"V0": "#9AA0A6", "V1": "#5F6368", "V2": "#72B7B2", "V5": "#F58518"}
st.set_page_config(page_title="TV Cleaner", page_icon="📺", layout="wide")


@st.cache_data
def load(name):
    path = D / name
    return pd.read_csv(path) if path.exists() else pd.DataFrame()


def fps(ms):
    return 1000 / np.maximum(ms, 1000 / 60)


bench, frames, apps = load("benchmark.csv"), load("benchmark_frames.csv"), load("benchmark_apps.csv")
perf, log = load("performance.csv"), load("tv_perf.csv")
names = bench.drop_duplicates("version").set_index("version")["label"]
label = {v: f"{v} {names[v]}" for v in ORDER}
mean = bench.groupby("version").mean(numeric_only=True).reindex(ORDER)
avg_fps = (frames.assign(fps=fps(frames["frame_ms"]))
           .groupby("version").apply(lambda g: np.average(g["fps"], weights=g["frames"]), include_groups=False))

st.title("📺 TV Cleaner")
st.write("A TCL Google TV (Android 12, 2 GB of RAM), from the factory home screen to a custom one with an animated "
         "sky. Each version was rebuilt on the TV and put through the same test: restart, rest 3 minutes, then "
         "memory, the Home button, 2 minutes moving with the arrows, and opening Netflix, Nuvio and Spotify.")

c1, c2, c3, c4 = st.columns(4)
c1.metric("Back to the home screen", f"{mean.loc['V5', 'open_ms']:.0f} ms",
          f"{mean.loc['V5', 'open_ms'] - mean.loc['V0', 'open_ms']:+.0f} ms vs. factory", delta_color="inverse")
c2.metric("Home screen's own RAM", f"{mean.loc['V5', 'home_mb']:.0f} MB",
          f"{mean.loc['V5', 'home_mb'] - mean.loc['V0', 'home_mb']:+.0f} MB vs. factory", delta_color="inverse")
c3.metric("Average FPS on the home screen", f"{avg_fps['V5']:.0f}",
          f"{avg_fps['V5'] - avg_fps['V0']:+.0f} vs. factory")
c4.metric("Restart to home screen", f"{mean.loc['V5', 'boot_s']:.0f} s",
          f"{mean.loc['V5', 'boot_s'] - mean.loc['V0', 'boot_s']:+.0f} s vs. factory", delta_color="inverse")

tab1, tab2, tab3, tab4, tab5 = st.tabs(["Versions", "Smoothness", "Apps", "The rain", "Through the day"])

with tab1:
    st.subheader("Resting on the home screen, 3 minutes after a restart")
    metric = st.radio("Show", ["mem_available_mb", "home_mb", "open_ms", "boot_s"], horizontal=True, format_func={
        "mem_available_mb": "RAM free for apps (MB)", "home_mb": "Home screen's own RAM (MB)",
        "open_ms": "Back to the home screen (ms)", "boot_s": "Restart to home screen (s)"}.get)
    m = mean.reset_index().assign(name=lambda d: d["version"].map(label))
    st.plotly_chart(px.bar(m, x="name", y=metric, color="version", color_discrete_map=COLOR, text_auto=".0f",
                           labels={"name": "", metric: ""}), use_container_width=True)
    st.caption("V0 factory, V1 extras off and faster animations, V2 the original Free My TV, V5 this fork. "
               "Single runs differ by about 5 to 10%: smaller gaps are noise.")

with tab2:
    st.subheader("How smooth each frame was, 2 minutes moving with the arrows")
    f = frames.assign(band=pd.cut(fps(frames["frame_ms"]), [0, 30, 45, 59, 61],
                                  labels=["under 30", "30 to 45", "45 to 59", "60 (smooth)"]))
    share = f.groupby(["version", "band"], observed=False)["frames"].sum().reset_index()
    share["pct"] = 100 * share["frames"] / share.groupby("version")["frames"].transform("sum")
    share["name"] = share["version"].map(label)
    st.plotly_chart(px.bar(share, y="name", x="pct", color="band", orientation="h",
                           category_orders={"name": [label[v] for v in ORDER]},
                           color_discrete_sequence=["#E45756", "#F2B447", "#B5D99C", "#54A24B"],
                           labels={"pct": "% of the frames", "name": "", "band": "FPS"}), use_container_width=True)
    st.caption(f"Average FPS: " + ", ".join(f"{v} {avg_fps[v]:.0f}" for v in ORDER) + ". The fork's sky moves all "
               "the time, so the TV draws every frame: the price of the animation, and the next thing to improve.")

with tab3:
    st.subheader("Opening Netflix, Nuvio and Spotify")
    col = st.radio("Show", ["launch_ms", "mem_available_mb"], horizontal=True, format_func={
        "launch_ms": "Opening from scratch (ms)", "mem_available_mb": "RAM free with the app open (MB)"}.get)
    a = apps.groupby(["app", "version"]).mean(numeric_only=True).reset_index()
    a["name"] = a["version"].map(label)
    st.plotly_chart(px.bar(a, x="app", y=col, color="name", barmode="group",
                           color_discrete_sequence=[COLOR[v] for v in ORDER],
                           category_orders={"name": [label[v] for v in ORDER]}, labels={"app": "", col: "", "name": ""}),
                    use_container_width=True)

with tab4:
    st.subheader("How the animated sky got light: switching titles in the rain")
    st.markdown("1. **First rain:** three layers of drops drawn one by one, every frame: 39% of the frames late.\n"
                "2. **Rain as a texture:** a ready-made picture that slides, the sky still while titles move: 1%.\n"
                "3. **Final version:** the same with the tower and music scenes: none late, about 10 ms a frame.")
    fr = perf[perf["metric"].isin(["frame_ms", "janky_pct"])].copy()
    fr["metric"] = fr["metric"].map({"frame_ms": "Frame time (ms)", "janky_pct": "Late frames (%)"})
    fig = px.bar(fr, x="stage", y="value", color="metric", barmode="group", labels={"stage": "", "value": ""})
    fig.add_hline(y=16.7, line_dash="dash", line_color="gray", annotation_text="60 fps")
    st.plotly_chart(fig, use_container_width=True)

with tab5:
    st.subheader("FPS through the day, 10:00 to midnight")
    if log.empty:
        st.info("No data yet: the TV keeps its own log, one line every 15 minutes while it's on.")
    else:
        rows = [(r.period, int(ms), int(n)) for r in log[(log["hour"] >= 10) & (log["frames"] > 0)].itertuples()
                for ms, n in (p.split(":") for p in str(r.histogram).split())]
        h = pd.DataFrame(rows, columns=["period", "frame_ms", "frames"]).assign(fps=lambda d: fps(d["frame_ms"]))
        left, right = st.columns(2)
        left.plotly_chart(px.histogram(h, x="fps", y="frames", nbins=25, labels={"fps": "FPS"},
                                       color_discrete_sequence=["#F58518"]), use_container_width=True)
        by = h.groupby("period").apply(lambda g: np.average(g["fps"], weights=g["frames"]), include_groups=False)
        by = by.reindex([p for p in ["morning", "midday", "sunset", "night"] if p in by.index]).reset_index(name="fps")
        right.plotly_chart(px.bar(by, x="period", y="fps", range_y=[0, 60], labels={"period": "", "fps": "average FPS"},
                                  color_discrete_sequence=["#72B7B2"]), use_container_width=True)
        st.caption(f"{len(log)} lines over {log['day'].nunique()} day(s). It needs 2 to 4 weeks to mean something.")
