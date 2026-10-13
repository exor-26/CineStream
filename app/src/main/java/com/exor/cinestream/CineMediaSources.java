package com.exor.cinestream;

import android.content.Context;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;

@UnstableApi
final class CineMediaSources {
    private CineMediaSources() {}
    static DefaultMediaSourceFactory factory(Context context) {
        return new DefaultMediaSourceFactory(context, new CineExtractorsFactory());
    }
}
