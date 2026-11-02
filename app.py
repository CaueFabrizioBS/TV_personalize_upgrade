"""TV dashboard: what a custom home screen changed on a Google TV, keeping only the differences bigger than the noise."""
from pathlib import Path

import numpy as np
import pandas as pd
import plotly.express as px
import streamlit as st

D = Path(__file__).parent / "datasets"
ORDER = ["V0", "V1", "V2", "V5", "V6"]
COLOR = {"V0": "#9AA0A6", "V1": "#5F6368", "V2": "#72B7B2", "V5": "#F58518", "V6": "#B279A2"}
NOW = "V6"      # the version on the TV
FPS_LIMIT = 2   # an fps gap counts above 2 fps (the same version changed 0.6 fps between two runs)
st.set_page_config(page_title="TV Personalize Upgrade", page_icon="📺", layout="wide")


@st.cache_data
def load(name):
    path = D / name
    return pd.read_csv(path) if path.exists() else pd.DataFrame()


def fps(ms):
    return 1000 / np.maximum(ms, 1000 / 60)


def avg_fps(df):
    return np.average(fps(df["frame_ms"]), weights=df["frames"])


bench, frames, perf, log = load("benchmark.csv"), load("benchmark_frames.csv"), load("performance.csv"), load("tv_perf.csv")
names = bench.drop_duplicates("version").set_index("version")["label"]
label = {v: f"{v} {names[v]}" for v in ORDER}
mean = bench.groupby("version").mean(numeric_only=True).reindex(ORDER)
avg = frames.groupby("version").apply(avg_fps, include_groups=False).reindex(ORDER)


def gap(col):
    return (mean.loc[NOW, col] - mean.loc["V0", col]) / mean.loc["V0", col]


st.title("📺 TV Personalize Upgrade")
st.write("A TCL Google TV (Android 12, 2 GB of RAM), from the factory home screen to a custom one with an animated "
         "sky. Each version went through the same test on the TV. Only the differences bigger than the measurement "
         "noise are shown here; the full method is in the notebook.")

c1, c2, c3 = st.columns(3)
c1.metric("Back to the home screen", f"{mean.loc[NOW, 'open_ms']:.0f} ms", f"{100 * gap('open_ms'):+.0f}% vs. factory",
          delta_color="inverse")
c2.metric("Home screen's own RAM", f"{mean.loc[NOW, 'home_mb']:.0f} MB", f"{100 * gap('home_mb'):+.0f}% vs. factory",
          delta_color="inverse")
diff = avg[NOW] - avg["V0"]
c3.metric("Smoothness on the home screen", f"{avg[NOW]:.0f} fps",
          "same as factory" if abs(diff) <= FPS_LIMIT else f"{diff:+.0f} fps vs. factory",
          delta_color="off" if abs(diff) <= FPS_LIMIT else "normal")

tab1, tab2, tab3, tab4 = st.tabs(["Speed and memory", "Smoothness", "The rain", "Through the day"])

with tab1:
    metric = st.radio("Show", ["open_ms", "home_mb"], horizontal=True, format_func={
        "open_ms": "Back to the home screen (ms)", "home_mb": "Home screen's own RAM (MB)"}.get)
    m = mean.reset_index().assign(name=lambda d: d["version"].map(label))
    st.plotly_chart(px.bar(m, x="name", y=metric, color="version", color_discrete_map=COLOR, text_auto=".0f",
                           labels={"name": "", metric: ""}), width="stretch")
    if metric == "open_ms":
        st.markdown("**About 25% faster** with Free My TV (100 to 114 ms against 138 to 148 ms with Google's): "
                    "the clearest gain of the project. The cleanup alone (V1) didn't change it.")
    else:
        st.markdown("The fork's home screen uses **28% less RAM than Google's**, even with the animated sky. The "
                    "original Free My TV (V2) is lighter still: the sky costs about 55 MB.")

with tab2:
    f = frames.assign(band=pd.cut(fps(frames["frame_ms"]), [0, 30, 45, 59, 61],
                                  labels=["under 30", "30 to 45", "45 to 59", "60 (smooth)"]))
    share = f.groupby(["version", "band"], observed=False)["frames"].sum().reset_index()
    share["pct"] = 100 * share["frames"] / share.groupby("version")["frames"].transform("sum")
    share["name"] = share["version"].map(label)
    st.plotly_chart(px.bar(share, y="name", x="pct", color="band", orientation="h",
                           category_orders={"name": [label[v] for v in ORDER]},
                           color_discrete_sequence=["#E45756", "#F2B447", "#B5D99C", "#54A24B"],
                           labels={"pct": "% of the frames, 2 minutes moving with the arrows", "name": "",
                                   "band": "fps"}), width="stretch")
    st.markdown(f"The first version of the fork (V5) stuttered: **{avg['V5']:.0f} fps**, because the TV's graphics "
                f"chip repainted the whole sky on every frame. Giving the sky its own layer (V6) brought it to "
                f"**{avg['V6']:.0f} fps**, the same as Google's factory home screen ({avg['V0']:.0f} fps, a gap smaller "
                "than the noise).")

with tab3:
    st.markdown("Switching titles in the rain, the heaviest moment, at each step of the development:")
    fr = perf[perf["metric"].isin(["frame_ms", "janky_pct"])].copy()
    fr["metric"] = fr["metric"].map({"frame_ms": "Frame time (ms)", "janky_pct": "Late frames (%)"})
    fig = px.bar(fr, x="stage", y="value", color="metric", barmode="group", labels={"stage": "", "value": ""},
                 color_discrete_sequence=["#72B7B2", "#E45756"])
    fig.add_hline(y=16.7, line_dash="dash", line_color="gray", annotation_text="60 fps")
    st.plotly_chart(fig, width="stretch")
    st.markdown("Drawing the drops one by one left **39% of the frames late**; a ready-made texture that slides, "
                "with the sky still while titles move, brought it to **none**.")

with tab4:
    use = log[log["frames"] > 0] if not log.empty else log
    if use.empty:
        st.info("No data yet: the TV keeps its own log, one line every 15 minutes while it's on.")
    else:
        rows = [(r.day, r.period, int(ms), int(n)) for r in use.itertuples()
                for ms, n in (p.split(":") for p in str(r.histogram).split())]
        h = pd.DataFrame(rows, columns=["day", "period", "frame_ms", "frames"])
        by = h.groupby("period").apply(lambda g: pd.Series({"fps": avg_fps(g), "days": g["day"].nunique()}),
                                       include_groups=False)
        by = by.reindex([p for p in ["sunrise", "morning", "midday", "sunset", "night", "deep night"]
                         if p in by.index]).reset_index()
        st.warning(f"In progress: {log['day'].nunique()} day(s) of the TV's own log so far. It needs 2 to 4 weeks of "
                   "normal use before the periods can be compared, so these numbers are not a result yet.")
        st.plotly_chart(px.bar(by, x="period", y="fps", range_y=[0, 60], hover_data=["days"],
                               labels={"period": "", "fps": "average fps"}, color_discrete_sequence=["#72B7B2"]),
                        width="stretch")

with st.expander("What didn't change (left out above)"):
    st.markdown("* **Opening Netflix, Nuvio and Spotify** takes the same time in every version.\n"
                "* **RAM free for apps** stays within 7% in every version.\n"
                "* **Restart time** is 34 to 37 s; one run of V2 took 49 s with no explanation, so it needs a "
                "repeat.\n"
                "* **The cleanup alone (V1)** changed nothing measurable that we can trust: its gain is less "
                "clutter, not speed.")
