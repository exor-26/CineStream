package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.*;

public class MatroskaHeaderReferenceTest {
    @Test public void indexedTracksNearEndKeepOriginalAbsolutePosition() {
        assertEquals(1233330320L, MatroskaHeaderReference.resolve(52, 0x4983205cL, 1233351952L));
    }
    @Test public void ordinaryFrontHeaderIsValid() {
        assertEquals(141L, MatroskaHeaderReference.resolve(52, 89, 1233351952L));
    }
    @Test public void unknownInputLengthStillAllowsIndexedHeaders() {
        assertEquals(1233330320L, MatroskaHeaderReference.resolve(52, 0x4983205cL, -1));
    }
    @Test public void rejectNegativeOffsetsAndMissingSegment() {
        assertEquals(-1L, MatroskaHeaderReference.resolve(52, -1, 1000));
        assertEquals(-1L, MatroskaHeaderReference.resolve(-1, 52, 1000));
    }
    @Test public void rejectPastEndAndExactEof() {
        assertEquals(-1L, MatroskaHeaderReference.resolve(52, 948, 1000));
        assertEquals(-1L, MatroskaHeaderReference.resolve(52, 949, 1000));
    }
    @Test public void rejectLongOverflow() {
        assertEquals(-1L, MatroskaHeaderReference.resolve(52, Long.MAX_VALUE, -1));
    }
    @Test public void metadataBudgetDoesNotAcceptUnknownOrHugeSizes() {
        assertFalse(MatroskaHeaderReference.boundedSize(-1));
        assertFalse(MatroskaHeaderReference.boundedSize(Long.MAX_VALUE));
        assertTrue(MatroskaHeaderReference.boundedSize(21632));
        assertTrue(MatroskaHeaderReference.boundedSize(MatroskaHeaderReference.MAX_HEADER_BYTES));
        assertFalse(MatroskaHeaderReference.boundedSize(MatroskaHeaderReference.MAX_HEADER_BYTES + 1));
    }
}
