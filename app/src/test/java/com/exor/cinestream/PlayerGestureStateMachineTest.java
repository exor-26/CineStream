package com.exor.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PlayerGestureStateMachineTest {
    @Test public void inactiveSeekCleanupDoesNotHidePinchPercentage() {
        assertFalse(PlayerGestureStateMachine.shouldHideSeekFeedback(true, false, false));
    }

    @Test public void cancellingAnActiveSeekHidesItsPreview() {
        assertTrue(PlayerGestureStateMachine.shouldHideSeekFeedback(true, true, false));
    }

    @Test public void seekCleanupPreservesActiveSpeedFeedback() {
        assertFalse(PlayerGestureStateMachine.shouldHideSeekFeedback(true, true, true));
    }

    @Test public void seekCleanupWithoutHideRequestPreservesFeedback() {
        assertFalse(PlayerGestureStateMachine.shouldHideSeekFeedback(false, true, false));
    }

    @Test public void centeredSliderHandleCanStartUnlockOutsideLegacyLeftZone() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        assertEquals(PlayerGestureStateMachine.Owner.UNLOCK_SWIPE,
                machine.beginGesture(550, 900, 1000, 1000, true));
        assertEquals(PlayerGestureStateMachine.UnlockProgress.COMPLETE,
                machine.updateUnlock(800, 900, 8, 200, 80));
        assertTrue(machine.finishUnlockGesture());
    }

    @Test public void centeredSliderStillRejectsVerticalSwipes() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(550, 900, 1000, 1000, true);
        assertEquals(PlayerGestureStateMachine.UnlockProgress.CANCELLED,
                machine.updateUnlock(570, 750, 8, 200, 80));
        assertFalse(machine.finishUnlockGesture());
    }

    @Test public void centeredSliderStillRejectsMultiplePointers() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(550, 900, 1000, 1000, true);
        machine.onPointerCountChanged(2);
        assertFalse(machine.finishUnlockGesture());
    }


    @Test
    public void movementInsideTouchSlopRemainsPending() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(false);
        machine.beginGesture(100f, 200f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.Owner.PENDING,
                machine.classifyUnlockedMove(104f, 204f, 1000f, 8));
    }

    @Test
    public void verticalGestureChoosesSideOnce() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.beginGesture(100f, 200f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.Owner.BRIGHTNESS,
                machine.classifyUnlockedMove(104f, 240f, 1000f, 8));
        assertEquals(100f, machine.getDownX(), 0f);
        assertEquals(200f, machine.getDownY(), 0f);
        assertEquals(
                PlayerGestureStateMachine.Owner.BRIGHTNESS,
                machine.classifyUnlockedMove(800f, 245f, 1000f, 8));

        machine.resetGesture();
        machine.beginGesture(800f, 200f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.Owner.VOLUME,
                machine.classifyUnlockedMove(804f, 240f, 1000f, 8));
    }

    @Test
    public void horizontalGestureReservesSeekOwnership() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.beginGesture(300f, 300f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.Owner.SEEK,
                machine.classifyUnlockedMove(380f, 304f, 1000f, 8));
        assertFalse(machine.claimTemporarySpeed());
    }

    @Test
    public void stationaryPendingGestureCanClaimTemporarySpeed() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.beginGesture(800f, 300f, 1000f, 1000f);
        assertTrue(machine.claimTemporarySpeed());
        assertEquals(PlayerGestureStateMachine.Owner.TEMP_SPEED, machine.getOwner());
        assertEquals(
                PlayerGestureStateMachine.Owner.TEMP_SPEED,
                machine.classifyUnlockedMove(900f, 300f, 1000f, 8));
    }

    @Test
    public void lockedGestureCannotClaimTemporarySpeed() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(100f, 850f, 1000f, 1000f);
        assertFalse(machine.claimTemporarySpeed());
    }

    @Test
    public void secondPointerOwnsGestureAsPinch() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.beginGesture(300f, 300f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.Owner.PINCH_ZOOM,
                machine.onPointerCountChanged(2));
    }

    @Test
    public void secondPointerOverridesTemporarySpeedOwnership() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.beginGesture(800f, 300f, 1000f, 1000f);
        assertTrue(machine.claimTemporarySpeed());
        assertEquals(
                PlayerGestureStateMachine.Owner.PINCH_ZOOM,
                machine.onPointerCountChanged(2));
    }

    @Test
    public void unlockMustStartInBottomLeftZone() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        assertEquals(
                PlayerGestureStateMachine.Owner.BLOCKED,
                machine.beginGesture(700f, 850f, 1000f, 1000f));
        assertFalse(machine.finishUnlockGesture());
    }

    @Test
    public void shortUnlockSwipeDoesNotComplete() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(100f, 850f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.UnlockProgress.IN_PROGRESS,
                machine.updateUnlock(250f, 860f, 8, 300f, 80f));
        assertFalse(machine.finishUnlockGesture());
    }

    @Test
    public void deliberateHorizontalUnlockCompletes() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(100f, 850f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.UnlockProgress.COMPLETE,
                machine.updateUnlock(430f, 870f, 8, 300f, 80f));
        assertTrue(machine.finishUnlockGesture());
    }

    @Test
    public void verticalDriftCancelsUnlock() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(100f, 850f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.UnlockProgress.CANCELLED,
                machine.updateUnlock(220f, 950f, 8, 300f, 60f));
        assertFalse(machine.finishUnlockGesture());
    }

    @Test
    public void secondPointerCancelsUnlockCandidate() {
        PlayerGestureStateMachine machine = new PlayerGestureStateMachine();
        machine.setLocked(true);
        machine.beginGesture(100f, 850f, 1000f, 1000f);
        assertEquals(
                PlayerGestureStateMachine.Owner.BLOCKED,
                machine.onPointerCountChanged(2));
        assertFalse(machine.finishUnlockGesture());
    }
}
