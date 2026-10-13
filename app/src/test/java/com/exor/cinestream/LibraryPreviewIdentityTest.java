package com.exor.cinestream;

import org.junit.Test;
import static org.junit.Assert.*;

public class LibraryPreviewIdentityTest {
    @Test public void unchangedSourceKeepsCache() {
        String key = LibraryPreviewCache.identity("content://media/video/7", 1234, 10);
        assertEquals(key, LibraryPreviewCache.identity("content://media/video/7", 1234, 10));
        assertTrue(key.matches("[a-f0-9]{64}"));
    }
    @Test public void replacementOrModificationInvalidatesCache() {
        String key = LibraryPreviewCache.identity("content://media/video/7", 1234, 10);
        assertNotEquals(key, LibraryPreviewCache.identity("content://media/video/7", 1235, 10));
        assertNotEquals(key, LibraryPreviewCache.identity("content://media/video/7", 1234, 11));
        assertNotEquals(key, LibraryPreviewCache.identity("content://media/video/8", 1234, 10));
    }
}
