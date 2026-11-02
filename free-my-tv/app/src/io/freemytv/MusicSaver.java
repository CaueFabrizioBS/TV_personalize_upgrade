package io.freemytv;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.WindowManager;

/**
 * The screensaver while music plays on the home screen: the same picture as Screensaver (the sky, the date, the clock and
 * the weather, plus the song in the top right corner), opened by the home screen after 2 minutes without a key. It's an
 * activity, not the system's screensaver, because a screensaver can't keep the TV on: the system would put it to sleep
 * in the middle of the music. This one keeps the screen on while the music plays, and lets the TV's usual timers run
 * again once it stops. Any key goes back to the home screen; the media and volume keys go on to the music.
 */
public class MusicSaver extends Activity {
    private DreamView view;
    private NowPlaying.Music music;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);
        view = new DreamView(this);
        setContentView(view);
        getWindow().setBackgroundDrawable(null);
        music = new NowPlaying.Music(this, this::keepOn);
    }

    private void keepOn() {
        if (music.playing) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        StayAwake.want(this, music.playing);
    }

    @Override protected void onResume() {
        super.onResume();
        music.start();
        keepOn();
        view.start();
    }

    @Override protected void onPause() {
        view.stop();
        music.stop();
        StayAwake.want(this, false);   // the home screen says again if the music still plays
        super.onPause();
    }

    @Override protected void onStop() {
        super.onStop();
        finish();   // another app or the home screen took over: never left behind under them
    }

    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (KeyEvent.isMediaSessionKey(code) || code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN
                || code == KeyEvent.KEYCODE_VOLUME_MUTE) {
            return super.dispatchKeyEvent(event);
        }
        if (event.getAction() == KeyEvent.ACTION_UP) finish();   // on release, so the home screen doesn't get it
        return true;
    }
}
