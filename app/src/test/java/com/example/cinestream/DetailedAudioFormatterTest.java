package com.example.cinestream;

import androidx.media3.common.C;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class DetailedAudioFormatterTest {
    @Test
    public void multipleTracksKeepCodecAndTechnicalDataPaired() {
        List<DetailedAudioFormatter.Track> tracks = Arrays.asList(
                new DetailedAudioFormatter.Track(1, "English", "E-AC-3", 6, 48000,
                        C.SELECTION_FLAG_DEFAULT),
                new DetailedAudioFormatter.Track(2, "Hindi", "AAC", 2, 44100, 0));

        String codecs = DetailedAudioFormatter.multiLineCodecs(tracks);
        String details = DetailedAudioFormatter.multiLineDetails(tracks);

        assertTrue(codecs.contains("Track 1: English — E-AC-3"));
        assertTrue(codecs.contains("Track 2: Hindi — AAC"));
        assertTrue(details.contains("English — E-AC-3 • 5.1 (6 ch) • 48 kHz • Default"));
        assertTrue(details.contains("Hindi — AAC • Stereo (2 ch) • 44.1 kHz"));
    }

    @Test
    public void platformFallbackOnlyFillsMissingValues() {
        List<DetailedAudioFormatter.Track> tracks = Arrays.asList(
                new DetailedAudioFormatter.Track(1, "English", "E-AC-3", 6, 48000, 0),
                new DetailedAudioFormatter.Track(2, "Hindi", "Audio", 0, 0, 0));

        DetailedAudioFormatter.enrichMissing(
                tracks,
                Arrays.asList("AAC", "DTS-HD"),
                Arrays.asList(2, 8),
                Arrays.asList(44100, 96000));

        assertEquals("E-AC-3", tracks.get(0).codec);
        assertEquals(6, tracks.get(0).channelCount);
        assertEquals(48000, tracks.get(0).sampleRate);
        assertEquals("DTS-HD", tracks.get(1).codec);
        assertEquals(8, tracks.get(1).channelCount);
        assertEquals(96000, tracks.get(1).sampleRate);
    }
}
