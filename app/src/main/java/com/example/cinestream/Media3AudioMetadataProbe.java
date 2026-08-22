package com.example.cinestream;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.inspector.MetadataRetriever;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Detailed-info probe backed by Media3's own extractor stack. */
@UnstableApi
final class Media3AudioMetadataProbe {

    static final class Result {
        final List<DetailedAudioFormatter.Track> tracks = new ArrayList<>();

        boolean hasAudioTracks() {
            return !tracks.isEmpty();
        }

        void enrichMissingFromPlatform(
                List<String> codecs,
                List<Integer> channels,
                List<Integer> sampleRates
        ) {
            DetailedAudioFormatter.enrichMissing(tracks, codecs, channels, sampleRates);
        }

        List<String> detailLines() {
            return DetailedAudioFormatter.detailLines(tracks);
        }

        String multiLineCodecs() {
            return DetailedAudioFormatter.multiLineCodecs(tracks);
        }

        String multiLineDetails() {
            return DetailedAudioFormatter.multiLineDetails(tracks);
        }

        DetailedAudioFormatter.Track primaryTrack() {
            DetailedAudioFormatter.Track first = null;
            for (DetailedAudioFormatter.Track track : tracks) {
                if (first == null) first = track;
                if ((track.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) return track;
            }
            return first;
        }
    }

    private Media3AudioMetadataProbe() {}

    static Result probe(Context context, Uri uri) {
        Result result = new Result();
        MediaItem item = MediaItem.fromUri(uri);

        try (MetadataRetriever retriever = new MetadataRetriever.Builder(context, item).build()) {
            TrackGroupArray groups = retriever.retrieveTrackGroups().get(8, TimeUnit.SECONDS);
            int ordinal = 1;
            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                if (group.type != C.TRACK_TYPE_AUDIO) continue;
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format format = group.getFormat(trackIndex);
                    result.tracks.add(new DetailedAudioFormatter.Track(
                            ordinal,
                            AudioTrackFormatter.buildTitle(ordinal, format),
                            AudioTrackFormatter.codecLabel(format),
                            Math.max(0, format.channelCount),
                            Math.max(0, format.sampleRate),
                            format.selectionFlags));
                    ordinal++;
                }
            }
        } catch (Exception e) {
            Log.w("Media3MetadataProbe", "Media3 audio metadata probe failed", e);
        }
        return result;
    }
}
