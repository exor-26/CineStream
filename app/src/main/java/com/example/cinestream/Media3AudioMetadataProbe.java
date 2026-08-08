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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Detailed-info probe backed by Media3's own extractor stack. */
@UnstableApi
final class Media3AudioMetadataProbe {

    static final class Result {
        String primaryCodecLabel;
        int primaryChannelCount;
        int primarySampleRate;
        final List<String> trackDetails = new ArrayList<>();

        boolean hasAudioTracks() {
            return !trackDetails.isEmpty();
        }

        String multiLineDetails() {
            if (trackDetails.isEmpty()) {
                return "Unknown";
            }
            if (trackDetails.size() == 1) {
                return trackDetails.get(0);
            }
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < trackDetails.size(); i++) {
                builder.append("Track ").append(i + 1).append(": ")
                        .append(trackDetails.get(i));
                if (i < trackDetails.size() - 1) {
                    builder.append('\n');
                }
            }
            return builder.toString();
        }
    }

    private Media3AudioMetadataProbe() {
    }

    static Result probe(Context context, Uri uri) {
        Result result = new Result();
        MediaItem item = MediaItem.fromUri(uri);

        try (MetadataRetriever retriever = new MetadataRetriever.Builder(context, item).build()) {
            TrackGroupArray groups = retriever.retrieveTrackGroups().get(8, TimeUnit.SECONDS);
            Set<String> uniqueDetails = new LinkedHashSet<>();
            Format firstAudio = null;
            Format preferredAudio = null;
            int ordinal = 1;

            for (int groupIndex = 0; groupIndex < groups.length; groupIndex++) {
                TrackGroup group = groups.get(groupIndex);
                if (group.type != C.TRACK_TYPE_AUDIO) {
                    continue;
                }
                for (int trackIndex = 0; trackIndex < group.length; trackIndex++) {
                    Format format = group.getFormat(trackIndex);
                    if (firstAudio == null) {
                        firstAudio = format;
                    }
                    if (preferredAudio == null
                            && (format.selectionFlags & C.SELECTION_FLAG_DEFAULT) != 0) {
                        preferredAudio = format;
                    }
                    uniqueDetails.add(AudioTrackFormatter.buildInfoLine(ordinal++, format));
                }
            }

            result.trackDetails.addAll(uniqueDetails);
            Format primary = preferredAudio != null ? preferredAudio : firstAudio;
            if (primary != null) {
                result.primaryCodecLabel = AudioTrackFormatter.codecLabel(primary);
                result.primaryChannelCount = Math.max(0, primary.channelCount);
                result.primarySampleRate = Math.max(0, primary.sampleRate);
            }
        } catch (Exception e) {
            Log.w("Media3MetadataProbe", "Media3 audio metadata probe failed", e);
        }
        return result;
    }
}
