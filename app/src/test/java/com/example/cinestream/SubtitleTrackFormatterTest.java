package com.example.cinestream;

import androidx.media3.common.C;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class SubtitleTrackFormatterTest {
    @Test
    public void languageBeatsAdvertisingMuxerLabel() {
        String title = SubtitleTrackFormatter.buildTitle(
                1, "en", "www.randomsite.com", 0, 0);
        assertEquals("English", title);
        assertFalse(title.toLowerCase().contains("randomsite"));
    }

    @Test
    public void advertisingLabelWithoutLanguageFallsBackToOrdinal() {
        assertEquals("Caption 2", SubtitleTrackFormatter.buildTitle(
                2, null, "Join our Telegram t.me/example", 0, 0));
    }

    @Test
    public void forcedAndSdhRolesComeFromMetadata() {
        assertEquals("English • Forced", SubtitleTrackFormatter.buildTitle(
                1, "en", "garbage", 0, C.SELECTION_FLAG_FORCED));
        assertEquals("Hindi • SDH", SubtitleTrackFormatter.buildTitle(
                2, "hi", null,
                C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND | C.ROLE_FLAG_TRANSCRIBES_DIALOG, 0));
    }

    @Test
    public void subtitleTypeAndFlagsAreReadable() {
        assertEquals("SRT • Default", SubtitleTrackFormatter.buildTechnicalDetails(
                "application/x-subrip", C.SELECTION_FLAG_DEFAULT, true));
        assertEquals("ASS/SSA • Forced • Limited", SubtitleTrackFormatter.buildTechnicalDetails(
                "text/x-ssa", C.SELECTION_FLAG_FORCED, false));
    }
}
