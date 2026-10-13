package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.*;

public class PlayerControlsVisibilityStateTest {
    private PlayerControlsVisibilityState visible() {
        PlayerControlsVisibilityState state = new PlayerControlsVisibilityState();
        state.visible = true;
        return state;
    }
    @Test public void idleVisibleControlsCanHide() { assertTrue(visible().canAutoHide()); }
    @Test public void hiddenControlsCannotScheduleAnotherHide() {
        assertFalse(new PlayerControlsVisibilityState().canAutoHide());
    }
    @Test public void scrubHoldsControlsUntilRelease() {
        PlayerControlsVisibilityState state = visible();
        state.scrubbing = true; assertFalse(state.canAutoHide());
        state.scrubbing = false; assertTrue(state.canAutoHide());
    }
    @Test public void heldTouchPreventsHiding() {
        PlayerControlsVisibilityState state = visible();
        state.pointerDown = true; assertFalse(state.canAutoHide());
        state.pointerDown = false; assertTrue(state.canAutoHide());
    }
    @Test public void PopupFocusSuspendsTimer() {
        PlayerControlsVisibilityState state = visible();
        state.focused = false; assertFalse(state.canAutoHide());
        state.focused = true; assertTrue(state.canAutoHide());
    }
    @Test public void lockAndPipSuppressionCannotAutoHideOrRearm() {
        PlayerControlsVisibilityState state = visible();
        state.suppressed = true; assertFalse(state.canAutoHide());
    }
    @Test public void closingMenuDuringScrubDoesNotReleaseScrubHold() {
        PlayerControlsVisibilityState state = visible();
        state.scrubbing = true; state.focused = false;
        state.focused = true; assertFalse(state.canAutoHide());
    }
    @Test public void settingsMenuSuspendsTimeoutEvenBeforeWindowFocusChanges() {
        PlayerControlsVisibilityState state = visible();
        state.menuOpen = true; assertFalse(state.canAutoHide());
        state.menuOpen = false; assertTrue(state.canAutoHide());
    }
    @Test public void feedbackBlocksShowingEveryControllerGroup() {
        PlayerControlsVisibilityState state = visible();
        state.feedbackActive = true;
        assertFalse(state.canShow());
        assertFalse(state.canAutoHide());
        state.visible = false;
        assertFalse(state.canShow());
    }
    @Test public void feedbackEndingAllowsNextTapButDoesNotAutomaticallyShowControls() {
        PlayerControlsVisibilityState state = visible();
        state.feedbackActive = true; state.visible = false;
        state.feedbackActive = false;
        assertTrue(state.canShow()); assertFalse(state.visible); assertFalse(state.canAutoHide());
    }
    @Test public void feedbackEndingDoesNotReleaseLockOrPipSuppression() {
        PlayerControlsVisibilityState state = visible();
        state.feedbackActive = true; state.suppressed = true;
        state.feedbackActive = false;
        assertFalse(state.canShow()); assertFalse(state.canAutoHide());
    }
}
