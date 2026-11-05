# TV Personalize Upgrade

**[Download the launcher (APK) and the install guide](https://github.com/CaueFabrizioBS/TV_personalize_upgrade/releases/latest)**

A new, ad-free home screen for a TCL Google TV (Android 12, 2 GB of RAM), and a data analysis of whether it actually
runs better than the factory one.

The TV came with Google's home screen: ads, pre-installed extras and a launcher that's slow to come back to. This
project replaced it with a customized fork of the open-source [Free My TV](https://github.com/simbasjungles/free-my-tv)
launcher, with an animated, illustrated sky that follows the clock and the real weather, a lookout tower and music
scenes, and measured every version along the way, from data collected on the TV itself.

<p align="center"><img src="images/music-day.webp" width="420" alt="The new home screen's lookout tower, with its resident nodding to the music"></p>

## The project, step by step

| Version | Step | What changed on the TV |
|---|---|---|
| **V0** | Starting point | Google's factory home screen, every pre-installed extra on |
| **V1** | Cleanup | 20 unused extras turned off (never uninstalled), animations at 0.5x |
| **V2** | New home screen | Free My TV as published |
| **V5** | The fork | animated sky, weather, lookout tower and music scenes |
| **V6** | Optimization | the same scenes, with the sky in its own layer at half resolution |

Every version went through **the same test** (`collector/benchmark.py`): restart the TV, rest 3 minutes on the home
screen, then measure memory, how fast the home screen comes back from another app, 2 minutes moving around it with the
arrows, and opening Netflix, Nuvio and Spotify.

## Key findings

Only the differences bigger than the measurement noise count (the same version changed up to 11% between two runs,
so a gap must be above 15%, or above 2 fps for smoothness). The notebook shows how this was decided.

* **Back to the home screen about 25% faster:** 100 to 114 ms with Free My TV, against 138 to 148 ms with Google's.
* **28% less memory than Google's home screen:** 145 MB against 200 MB, even with the animated sky.
* **As smooth as the factory home screen:** the first version of the fork stuttered (49 fps) because the TV's graphics
  chip repainted the whole sky on every frame; giving the sky its own layer brought it to **57 fps**, against 58 for
  Google's, a gap smaller than the noise.
* **The rain went from 39% of the frames late to none** once the drops became a ready-made texture that slides.

<p align="center"><img src="images/fps-by-version.png" width="760" alt="Share of frames by fps band, per version"></p>

**No clear difference:** opening apps, RAM free for apps and restart time. The cleanup alone (V1) changed nothing
measurable we can trust: its gain is less clutter, not speed.

**What's given up:** with Free My TV, Google's home screen is off, and it's what answers the voice assistant's film and
series requests ("show me comedies"). Opening apps, music, the weather and questions still work.

**In progress:** the home screen keeps its own log on the TV (one line every 15 minutes), to compare the smoothness of
each time of day. It needs 2 to 4 weeks of normal use before it's a result.

## Repository structure

```
TV_personalize_upgrade/
├── .streamlit/
│   └── config.toml
├── collector/
│   ├── benchmark.py        the same test on any version of the home screen (restarts the TV)
│   ├── collect.py          adb helpers and a read-only snapshot of the TV
│   ├── pull_tv_log.py      brings home the log the TV keeps by itself
│   ├── build_datasets.py   anonymizes the raw data into datasets/
│   └── update.bat          pull + build, for Windows' Task Scheduler
├── datasets/               the anonymized data used by the notebook and the app
├── free-my-tv/             the launcher: the fork's Android source, art tools and install guide
├── images/
├── notebooks/
│   └── EDA.ipynb           the full analysis, step by step
├── release/
│   └── NOTAS-DA-VERSAO.md  the release notes (the APK itself is in GitHub Releases)
├── .gitignore
├── MANUAL-DE-INSTALACAO.txt  step-by-step install guide, in Portuguese (also in the release)
├── app.py                  the Streamlit dashboard
├── README.md
└── requirements.txt
```

* `datasets/`: no IP address and no dates (a date is *day N*). The raw files stay in `datasets/private/`, never
  committed.
* `notebooks/EDA.ipynb`: loads the data, checks its quality (there's nothing to clean, since we collected it, and the
  notebook shows why), defines what counts as a real difference, then goes through each result.
* `app.py`: the dashboard, with only the relevant differences.

## Run the dashboard

```bash
pip install -r requirements.txt
streamlit run app.py
```

## Install the home screen on your TV

A step-by-step guide in Portuguese, for people who aren't technical, is in [MANUAL-DE-INSTALACAO.txt](MANUAL-DE-INSTALACAO.txt). On Google TV and Android TV it needs only the remote and the free Downloader app. Below is the way with a computer.

The APK runs only on Google TV and Android TV. **Samsung (Tizen) and LG (webOS) can't install it:** they can run
upstream's web version, as an app, without the sky, the tower or the performance log (see the manual; not tested here).

You need a Google TV or Android TV (Android 8 or newer), a computer on the same Wi-Fi and
[adb](https://developer.android.com/tools/releases/platform-tools).

1. **On the TV**, turn on developer mode: Settings → System → About → press *Android TV OS build* 7 times. Then
   Settings → System → Developer options → turn on **USB debugging**. Note the TV's IP address (Settings → Network).
2. **On the computer**, download `FreeMyTV-sky.apk` from the [latest release](https://github.com/CaueFabrizioBS/TV_personalize_upgrade/releases/latest) and install it:
   ```bash
   adb connect 192.168.1.50:5555
   adb install -r FreeMyTV-sky.apk
   ```
3. **On the TV**, open *Free My TV* → *Home Screen* → *Free My TV*, then accept *Allow USB debugging?* with
   *Always allow*. One button in the same place brings back the factory home screen; nothing is uninstalled.

The ready APK has the sky, the weather, the tower and the music scenes, and takes your city from the TV's time zone.
It has no movie picks, since those need a personal [TMDB](https://www.themoviedb.org/settings/api) key: to get them,
build it yourself with your key ([free-my-tv/README.md](free-my-tv/README.md#the-manual-way)).

## Test your own TV

```bash
python collector/benchmark.py 192.168.1.50 "V0 my factory home screen"
python collector/build_datasets.py
```

## Performance from other TVs

Optional and off by default, with no sign-up: *Free My TV → Home Settings → Share performance*. Once a day, the TV sends
one anonymous line to a Google Form: a random id, TV model, Android version, total RAM, app version, frames, share of
late frames, frame times, free memory, the home screen's memory and CPU, and the chip's thermal state. Never which apps
are open, what's watched, an account or a city. Each line sent is also kept on the TV, in `perf_shared.csv`, so anyone
can check it. The code is in `PerfShare.java`.

## Tested on

| Device | How | Result |
| --- | --- | --- |
| TCL Google TV (Android 12) | Real TV, daily use | Everything works |
| Google TV (Android 14) | Official emulator | Install, activation, sky, tour, restart and going back to *Original* all work |
| Port 5555 closed, as on Chromecast with Google TV (Android 14) | Same emulator, port moved | The home screen keeps running; switching modes stops with a message pointing to the manual |
| Chromecast with Google TV, Google TV Streamer | Not tested on the real devices | Expected to work; may need a computer once |

The emulator shows that the software runs, not how fast a real device is.

Samsung and LG TVs can't install the launcher from their browser, so [`docs/teste/`](docs/teste/index.html) measures them
without installing anything: the TV's browser draws one fixed sky for a minute, and the person chooses whether to send the
result. Its lines start with format `2` (the app's start with `1`) and shouldn't be compared with the app's: they measure
the browser, not the launcher.

## Tools

Python, pandas, NumPy, matplotlib, Plotly, Streamlit, Jupyter, adb (Android Debug Bridge), and Java for the launcher.

## Credits

Free My TV is by its contributors, under the MIT License; its full history is kept in this repository.
