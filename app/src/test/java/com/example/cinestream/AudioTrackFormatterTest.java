package com.example.cinestream;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class AudioTrackFormatterTest {

    @Test
    public void languageBeatsPromotionalMuxerLabel() {
        assertEquals(
                "English",
                AudioTrackFormatter.buildTitle(
                        1, "eng", "www.example-movies.com", /* roleFlags= */ 0)
        );
        assertEquals(
                "E-AC-3 • 5.1 (6 ch) • 48 kHz • Default",
                AudioTrackFormatter.buildTechnicalDetails(
                        "audio/eac3",
                        null,
                        6,
                        48000,
                        C.SELECTION_FLAG_DEFAULT,
                        true
                )
        );
    }

    @Test
    public void originalRoleFromTrackLabelIsKeptWithoutUsingSiteName() {
        assertEquals(
                "Hindi • Original",
                AudioTrackFormatter.buildTitle(
                        1, "hin", "Original Hindi", /* roleFlags= */ 0)
        );
    }

    @Test
    public void dubRoleIsExplicit() {
        assertEquals(
                "Hindi • Dub",
                AudioTrackFormatter.buildTitle(
                        2, "hin", null, C.ROLE_FLAG_DUB)
        );
        assertEquals(
                "E-AC-3 JOC (Atmos) • 7.1 (8 ch) • 48 kHz",
                AudioTrackFormatter.buildTechnicalDetails(
                        "audio/eac3-joc",
                        null,
                        8,
                        48000,
                        0,
                        true
                )
        );
    }

    @Test
    public void unknownLanguageDoesNotExposeWebsiteLabel() {
        String title = AudioTrackFormatter.buildTitle(
                3, null, "https://bad.example.com", /* roleFlags= */ 0);
        assertEquals("Audio 3", title);
        assertFalse(title.contains("example"));
    }

    @Test
    public void normalHumanLabelRemainsWhenLanguageIsMissing() {
        assertEquals(
                "Director Commentary",
                AudioTrackFormatter.buildTitle(
                        2, null, "Director Commentary", /* roleFlags= */ 0)
        );
    }
}
