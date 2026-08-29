package com.example.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TemporarySpeedGestureLogicTest {

    @Test
    public void onlyRightHalfStartsCandidate() {
        assertFalse(TemporarySpeedGestureLogic.startsOnRightHalf(499f, 1000f));
        assertTrue(TemporarySpeedGestureLogic.startsOnRightHalf(500f, 1000f));
        assertTrue(TemporarySpeedGestureLogic.startsOnRightHalf(1000f, 1000f));
    }

    @Test
    public void movementInsideSlopKeepsCandidateStationary() {
        assertFalse(TemporarySpeedGestureLogic.movedBeyondSlop(
                800f, 400f, 806f, 406f, 8));
    }

    @Test
    public void horizontalOrVerticalMovementPastSlopCancelsCandidate() {
        assertTrue(TemporarySpeedGestureLogic.movedBeyondSlop(
                800f, 400f, 809f, 400f, 8));
        assertTrue(TemporarySpeedGestureLogic.movedBeyondSlop(
                800f, 400f, 800f, 409f, 8));
    }
}
