package com.example.cinestream;

import androidx.media3.common.C;
import androidx.media3.common.Format;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AudioTrackFormatterTest {

    @Test
    public void languageBeatsPromotionalMuxerLabel() {
        Format format = new Format.Builder()
                .setLanguage("eng")
                .setLabel("www.example-movies.com")
                .setSampleMimeType("audio/eac3")
                .setChannelCount(6)
                .setSampleRate(48000)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                .build();

        assertEquals("English", AudioTrackFormatter.buildTitle(1, format));
        assertEquals(
                "E-AC-3 • 5.1 (6 ch) • 48 kHz • Default",
                AudioTrackFormatter.buildTechnicalDetails(format, true)
        );
    }

    @Test
    public void originalRoleFromTrackLabelIsKeptWithoutUsingSiteName() {
        Format format = new Format.Builder()
                .setLanguage("hin")
                .setLabel("Original Hindi")
                .setSampleMimeType("audio/ac3")
                .setChannelCount(2)
                .setSampleRate(48000)
                .build();

        assertEquals("Hindi • Original", AudioTrackFormatter.buildTitle(1, format));
    }

    @Test
    public void dubRoleIsExplicit() {
        Format format = new Format.Builder()
                .setLanguage("hin")
                .setRoleFlags(C.ROLE_FLAG_DUB)
                .setSampleMimeType("audio/eac3-joc")
                .setChannelCount(8)
                .setSampleRate(48000)
                .build();

        assertEquals("Hindi • Dub", AudioTrackFormatter.buildTitle(2, format));
        assertEquals(
                "E-AC-3 JOC (Atmos) • 7.1 (8 ch) • 48 kHz",
                AudioTrackFormatter.buildTechnicalDetails(format, true)
        );
    }

    @Test
    public void unknownLanguageDoesNotExposeWebsiteLabel() {
        Format format = new Format.Builder()
                .setLabel("https://bad.example.com")
                .setSampleMimeType("audio/aac")
                .build();

        String title = AudioTrackFormatter.buildTitle(3, format);
        assertEquals("Audio 3", title);
        assertFalse(title.contains("example"));
    }
}
