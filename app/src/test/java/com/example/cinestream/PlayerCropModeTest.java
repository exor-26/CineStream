package com.example.cinestream;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class PlayerCropModeTest {

    @Test
    public void cropSequenceCyclesInExistingOrder() {
        assertEquals(PlayerCropMode.FILL, PlayerCropMode.ORIGINAL.next());
        assertEquals(PlayerCropMode.FIT, PlayerCropMode.FILL.next());
        assertEquals(PlayerCropMode.ORIGINAL, PlayerCropMode.FIT.next());
    }
}
