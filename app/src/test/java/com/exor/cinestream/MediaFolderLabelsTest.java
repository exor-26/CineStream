package com.exor.cinestream;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class MediaFolderLabelsTest {
    @Test
    public void rootRelativePathsUseInternalStorageLabel() {
        assertEquals(MediaFolderLabels.INTERNAL_STORAGE_KEY,
                MediaFolderLabels.keyFromRelativePath("/"));
        assertEquals(MediaFolderLabels.INTERNAL_STORAGE_KEY,
                MediaFolderLabels.keyFromRelativePath("  "));
        assertEquals(MediaFolderLabels.INTERNAL_STORAGE_NAME,
                MediaFolderLabels.displayNameFromKey(MediaFolderLabels.INTERNAL_STORAGE_KEY));
    }

    @Test
    public void namedRelativePathsKeepTheirLeafFolder() {
        String key = MediaFolderLabels.keyFromRelativePath("Movies/Downloads/");
        assertEquals("Movies/Downloads/", key);
        assertEquals("Downloads", MediaFolderLabels.displayNameFromKey(key));
    }
}
