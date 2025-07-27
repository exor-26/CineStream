package com.example.cinestream;

import android.content.Context;
import android.content.SharedPreferences;

public class PlaybackPrefs {

    private static final String PREFS_NAME    = "playback_prefs";
    private static final String KEY_LAST      = "last_played";
    private static final String PREFIX_POS    = "pos_";
    private static final String PREFIX_DUR    = "dur_";

    private static PlaybackPrefs instance;
    private final SharedPreferences prefs;

    private PlaybackPrefs(Context ctx) {
        prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static PlaybackPrefs getInstance(Context ctx) {
        if (instance == null) instance = new PlaybackPrefs(ctx);
        return instance;
    }

    public void save(String path, long positionMs, long durationMs) {
        prefs.edit()
                .putLong(PREFIX_POS + path, positionMs)
                .putLong(PREFIX_DUR + path, durationMs)
                .putString(KEY_LAST, path)
                .apply();
    }

    public long getPosition(String path) {
        return prefs.getLong(PREFIX_POS + path, 0);
    }

    public long getDuration(String path) {
        return prefs.getLong(PREFIX_DUR + path, 0);
    }

    public String getLastPlayed() {
        return prefs.getString(KEY_LAST, null);
    }

    // Fraction 0.0–1.0, returns 0 if no progress saved
    public float getProgressFraction(String path) {
        long pos = getPosition(path);
        long dur = getDuration(path);
        if (dur <= 0 || pos <= 0) return 0f;
        return Math.min(1f, (float) pos / (float) dur);
    }
}