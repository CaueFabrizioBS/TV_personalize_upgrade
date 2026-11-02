# TV_personalize_upgrade

A new home screen for a TCL Google TV (Android 12, 2 GB of RAM), and a data analysis of whether it actually runs
better than the factory one.

The TV came with Google's home screen: ads, pre-installed extras and a launcher that took 738 ms to come back from the
remote's Home button. This project replaced it with a customized fork of the open-source
[Free My TV](https://github.com/simbasjungles/free-my-tv) launcher (an animated, illustrated sky with weather, a
lookout tower and music scenes) and measured every version along the way.

| Folder | What it is |
|---|---|
| [`free-my-tv/`](free-my-tv) | The launcher: the fork's Android source, the art tools and install guide. |
| [`tv-cleaner-analysis/`](tv-cleaner-analysis) | The analysis: a read-only ADB collector, a benchmark of every version, anonymized datasets, an EDA notebook and a Streamlit dashboard. |

## Highlights

* **Back home in about 100 ms** with Free My TV, against 140 to 150 ms with Google's home screen (738 ms through the
  Home button).
* **57 fps** with every scene on, up from 49 fps in the first version of the fork (Google's factory screen: 58).
* **Less memory than Google's:** about 145 MB against 170 to 200 MB.

The full method and charts are in the [analysis README](tv-cleaner-analysis/README.md).

## Credits

Free My TV is by its contributors under the MIT License; its full history is kept in this repository.
