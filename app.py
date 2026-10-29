"""TV Cleaner dashboard: how a Google TV was used, what was changed and what it did to performance."""
from pathlib import Path

import pandas as pd
import plotly.express as px
import streamlit as st

D = Path(__file__).parent / "datasets"
st.set_page_config(page_title="TV Cleaner", page_icon="📺", layout="wide")


@st.cache_data
def load(name, with_apps=True):
    df = pd.read_csv(D / name)
    return df.merge(pd.read_csv(D / "apps.csv"), on="package", how="left") if with_apps else df


before = load("usage_before.csv")
changes = load("changes.csv")
perf = load("performance.csv", with_apps=False)
system = load("snapshots_system.csv", with_apps=False)
pkgs = load("snapshots_packages.csv")

st.title("📺 TV Cleaner")
st.write("A TCL Google TV (Android 12, 2 GB of RAM), cleaned up and given an ad-free home screen over four days. "
         "Dates are *day N* (day 1 = the day it started) and screen time is a share of the total, not hours.")

latest = system.iloc[-1]
first = perf[perf["metric"] == "janky_pct"].iloc[0]["value"]
c1, c2, c3, c4 = st.columns(4)
c1.metric("Packages turned off", int(latest["packages_disabled"]), f"of {int(latest['packages'])}",
          delta_color="off")
c2.metric("Uninstalled from the system", 0, "every change undoable", delta_color="off")
c3.metric("Janky frames now", f"{latest['janky_pct']:.1f}%", f"{latest['janky_pct'] - first:+.0f} pts vs. rain v1",
          delta_color="inverse")
c4.metric("Typical frame", f"{int(latest['frame_p50_ms'])} ms", "60 fps needs ≤ 16.7 ms", delta_color="off")

tab1, tab2, tab3, tab4 = st.tabs(["Before", "Changes", "Performance", "Now"])

with tab1:
    st.subheader("Where the screen time went, before any change")
    metric = st.radio("Show", ["screen_share_pct", "launch_share_pct"], horizontal=True,
                      format_func={"screen_share_pct": "Screen time", "launch_share_pct": "Launches"}.get)
    top = before.nlargest(12, metric)
    st.plotly_chart(px.bar(top.sort_values(metric), x=metric, y="name", color="category", orientation="h",
                           labels={metric: "% of the total", "name": ""}), use_container_width=True)
    st.caption("Three streaming apps hold about 90% of the screen time, while the home screen gets a third of the "
               "launches: everyone passes through it, so it's the screen worth making fast and ad-free.")

with tab2:
    st.subheader("What was changed")
    actions = st.multiselect("Actions", sorted(changes["action"].unique()), default=sorted(changes["action"].unique()))
    shown = changes[changes["action"].isin(actions)]
    st.plotly_chart(px.histogram(shown, y="category", color="action", barmode="stack",
                                 labels={"category": "", "count": "packages"}), use_container_width=True)
    st.dataframe(shown[["day", "name", "category", "action", "round", "reason"]], hide_index=True,
                 use_container_width=True)

with tab3:
    st.subheader("Frame times while switching titles in the rain")
    fr = perf[perf["metric"].isin(["frame_ms", "janky_pct"])].copy()
    fr["metric"] = fr["metric"].map({"frame_ms": "Frame time (ms)", "janky_pct": "Janky frames (%)"})
    fig = px.bar(fr, x="stage", y="value", color="metric", barmode="group", labels={"stage": "", "value": ""})
    fig.add_hline(y=16.7, line_dash="dash", line_color="gray", annotation_text="60 fps")
    st.plotly_chart(fig, use_container_width=True)
    st.subheader("Free memory on day 1")
    mem = perf[perf["metric"] == "mem_available_mb"]
    st.plotly_chart(px.bar(mem, x="stage", y="value", labels={"stage": "", "value": "MB free for apps"}),
                    use_container_width=True)

with tab4:
    st.subheader(f"The TV on day {int(latest['day'])}")
    state = pkgs[pkgs["day"] == pkgs["day"].max()]
    st.plotly_chart(px.histogram(state, y="category", color="enabled", barmode="stack",
                                 labels={"category": "", "enabled": "Enabled"}), use_container_width=True)
    # The changes log against the TV's real state: anything that drifted.
    last = changes.sort_values("day").groupby("package").last()
    off = set(state.loc[state["enabled"] == "no", "package"])
    drift = sorted((set(last[last["action"] == "disabled"].index) - off)
                   | (set(last[last["action"] == "enabled"].index) & off))
    if drift:
        st.warning("Not as the log says: " + ", ".join(drift))
    else:
        st.success("The TV matches the changes log.")
    st.dataframe(system, hide_index=True, use_container_width=True)
