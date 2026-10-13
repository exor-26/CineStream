package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackAdjustmentStateTest {
    @Test public void inheritedBrightnessUsesCurrentSystemLevel() {
        assertEquals(128f / 255, PlaybackAdjustmentState.resolveBrightness(-1, 128), .001f);
    }
    @Test public void appBrightnessUsesItsLastLevel() {
        assertEquals(.7f, PlaybackAdjustmentState.resolveBrightness(.7f, 40), .001f);
    }
    @Test public void brightnessAndBoostStayBounded() {
        assertEquals(1f, PlaybackAdjustmentState.resolveBrightness(-1, 400), 0f);
        assertEquals(0f, PlaybackAdjustmentState.resolveBrightness(-1, -3), 0f);
        assertEquals(0f, PlaybackAdjustmentState.clampVolumePercent(-1), 0f);
        assertEquals(200f, PlaybackAdjustmentState.clampVolumePercent(250), 0f);
    }
    @Test public void repeatedAppChangesRestoreOriginalLevel() {
        PlaybackAdjustmentState state = new PlaybackAdjustmentState();
        state.recordVolumeChange(3, 4);
        state.recordVolumeChange(4, 7);
        assertEquals(3, state.volumeToRestore(7));
        assertEquals(-1, state.volumeToRestore(3));
    }
    @Test public void untouchedVolumeIsNotRestored() {
        assertEquals(-1, new PlaybackAdjustmentState().volumeToRestore(5));
    }
    @Test public void laterExternalChangeIsNotOverwritten() {
        PlaybackAdjustmentState state = new PlaybackAdjustmentState();
        state.recordVolumeChange(3, 4);
        assertEquals(-1, state.volumeToRestore(8));
    }
    @Test public void appAdjustmentAfterExternalChangeUsesNewBaseline() {
        PlaybackAdjustmentState state = new PlaybackAdjustmentState();
        state.recordVolumeChange(3, 4);
        state.recordVolumeChange(6, 7);
        assertEquals(6, state.volumeToRestore(7));
    }
    @Test public void rejectedOrFixedVolumeAdjustmentDoesNotClaimOwnership() {
        PlaybackAdjustmentState state = new PlaybackAdjustmentState();
        state.recordVolumeChange(4, 4);
        assertEquals(-1, state.volumeToRestore(4));
    }
}
