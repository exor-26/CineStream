package com.example.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GlassUiGestureArbitrationTest {

    @Test
    public void upwardGestureAlwaysBelongsToList() {
        assertTrue(GlassUi.shouldActionListOwnVerticalGesture(-24f, 8, false));
    }

    @Test
    public void downwardGestureBelongsToScrollableList() {
        assertTrue(GlassUi.shouldActionListOwnVerticalGesture(24f, 8, true));
    }

    @Test
    public void downwardGestureAtTopMayDismissSheet() {
        assertFalse(GlassUi.shouldActionListOwnVerticalGesture(24f, 8, false));
    }

    @Test
    public void movementInsideTouchSlopStaysWithList() {
        assertTrue(GlassUi.shouldActionListOwnVerticalGesture(4f, 8, false));
    }
}
