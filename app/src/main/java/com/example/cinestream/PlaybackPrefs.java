package com.example.cinestream;

import android.content.Context;
import android.content.SharedPreferences;

public class PlaybackPrefs {

    // Keep playback state in one small preference file so resume data survives app restarts
    // without needing a database for a few simple key/value pairs.
    private static final String PREFS_NAME    = "playback_prefs";
    // Store the last opened media key separately so the library can pin that item to the top.
    private static final String KEY_LAST      = "last_played_key";
    // Prefixes let us store many media entries in the same SharedPreferences file.
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

    // Save both playback position and total duration together so the library can show
    // a progress bar even before the user opens the player again.
    public void save(String mediaKey, long positionMs, long durationMs) {
        prefs.edit()
                .putLong(PREFIX_POS + mediaKey, positionMs)
                .putLong(PREFIX_DUR + mediaKey, durationMs)
                .putString(KEY_LAST, mediaKey)
                .apply();
    }

    public long getPosition(String mediaKey) {
        return prefs.getLong(PREFIX_POS + mediaKey, 0);
    }

    public long getDuration(String mediaKey) {
        return prefs.getLong(PREFIX_DUR + mediaKey, 0);
    }

    public String getLastPlayedKey() {
        return prefs.getString(KEY_LAST, null);
    }

    // Convert raw playback numbers into a UI-ready fraction for the list progress indicator.
    public float getProgressFraction(String mediaKey) {
        long pos = getPosition(mediaKey);
        long dur = getDuration(mediaKey);
        if (dur <= 0 || pos <= 0) return 0f;
        return Math.min(1f, (float) pos / (float) dur);
    }
}
