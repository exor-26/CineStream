package com.example.cinestream;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProgressiveCompatibilitySessionRegistryTest {
    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @After
    public void resetRegistry() {
        ProgressiveCompatibilitySessionRegistry.resetForTests();
    }

    @Test
    public void activityRecreationJoinsExistingGeneration() {
        ProgressiveCompatibilitySessionRegistry.Lease owner =
                ProgressiveCompatibilitySessionRegistry.acquireOrJoin("source-a");
        ProgressiveCompatibilitySessionRegistry.Lease recreatedActivity =
                ProgressiveCompatibilitySessionRegistry.acquireOrJoin("source-a");

        assertTrue(owner.isOwner());
        assertFalse(recreatedActivity.isOwner());
        assertEquals(owner.token(), recreatedActivity.token());
        assertTrue(owner.isCurrent());
        assertTrue(recreatedActivity.isCurrent());
    }

    @Test
    public void staleGenerationCannotCompleteReplacement() {
        ProgressiveCompatibilitySessionRegistry.Lease oldGeneration =
                ProgressiveCompatibilitySessionRegistry.acquireOrJoin("source-b");
        ProgressiveCompatibilitySessionRegistry.Lease replacement =
                ProgressiveCompatibilitySessionRegistry.replace("source-b");

        assertFalse(oldGeneration.isCurrent());
        assertTrue(replacement.isCurrent());
        oldGeneration.complete();
        assertTrue(replacement.isCurrent());
    }

    @Test
    public void cancellationDeletesOnlyRegisteredPartFile() throws Exception {
        File directory = temporaryFolder.newFolder("segments");
        File completed = new File(directory, "progressive_source_s000000.mp4");
        File incomplete = new File(directory, "progressive_source_s000001.mp4.part");
        assertTrue(completed.createNewFile());
        assertTrue(incomplete.createNewFile());

        ProgressiveCompatibilitySessionRegistry.Lease owner =
                ProgressiveCompatibilitySessionRegistry.acquireOrJoin("source-c");
        assertTrue(owner.registerIncomplete(incomplete));
        owner.cancel();

        assertFalse(incomplete.exists());
        assertTrue(completed.exists());
        assertFalse(owner.isCurrent());
    }

    @Test
    public void nonOwnerCannotRegisterOrCancelGeneratorOutput() throws Exception {
        File incomplete = temporaryFolder.newFile("segment.mp4.part");
        ProgressiveCompatibilitySessionRegistry.Lease owner =
                ProgressiveCompatibilitySessionRegistry.acquireOrJoin("source-d");
        ProgressiveCompatibilitySessionRegistry.Lease observer =
                ProgressiveCompatibilitySessionRegistry.acquireOrJoin("source-d");

        assertFalse(observer.registerIncomplete(incomplete));
        observer.cancel();
        assertTrue(owner.isCurrent());
        assertTrue(incomplete.exists());
    }
}
