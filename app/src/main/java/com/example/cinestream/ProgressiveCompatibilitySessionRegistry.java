package com.example.cinestream;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/** Process-level ownership and generation-token guard for progressive compatibility work. */
final class ProgressiveCompatibilitySessionRegistry {
    private static final Map<String, State> ACTIVE = new HashMap<>();
    private static long nextToken = 1L;

    static final class Lease {
        private final String sourceKey;
        private final long token;
        private final boolean owner;

        private Lease(String sourceKey, long token, boolean owner) {
            this.sourceKey = sourceKey;
            this.token = token;
            this.owner = owner;
        }

        long token() {
            return token;
        }

        boolean isOwner() {
            return owner;
        }

        boolean isCurrent() {
            synchronized (ProgressiveCompatibilitySessionRegistry.class) {
                State state = ACTIVE.get(sourceKey);
                return state != null && state.token == token;
            }
        }

        boolean registerIncomplete(File file) {
            if (!owner || !ProgressiveCompatibilityCache.isIncompleteSegment(file)) {
                return false;
            }
            synchronized (ProgressiveCompatibilitySessionRegistry.class) {
                State state = ACTIVE.get(sourceKey);
                if (state == null || state.token != token) {
                    return false;
                }
                state.incompleteFile = file.getAbsoluteFile();
                return true;
            }
        }

        void complete() {
            synchronized (ProgressiveCompatibilitySessionRegistry.class) {
                State state = ACTIVE.get(sourceKey);
                if (owner && state != null && state.token == token) {
                    ACTIVE.remove(sourceKey);
                }
            }
        }

        void cancel() {
            File incomplete = null;
            synchronized (ProgressiveCompatibilitySessionRegistry.class) {
                State state = ACTIVE.get(sourceKey);
                if (owner && state != null && state.token == token) {
                    incomplete = state.incompleteFile;
                    ACTIVE.remove(sourceKey);
                }
            }
            if (ProgressiveCompatibilityCache.isIncompleteSegment(incomplete)) {
                // A lease can only remove the exact incomplete file it registered. Completed
                // segments and the existing full-file cache are never candidates here.
                //noinspection ResultOfMethodCallIgnored
                incomplete.delete();
            }
        }
    }

    private static final class State {
        final long token;
        File incompleteFile;

        State(long token) {
            this.token = token;
        }
    }

    private ProgressiveCompatibilitySessionRegistry() {
    }

    static synchronized Lease acquireOrJoin(String sourceKey) {
        State existing = ACTIVE.get(sourceKey);
        if (existing != null) {
            return new Lease(sourceKey, existing.token, false);
        }
        State created = new State(nextToken++);
        ACTIVE.put(sourceKey, created);
        return new Lease(sourceKey, created.token, true);
    }

    static synchronized Lease replace(String sourceKey) {
        State replacement = new State(nextToken++);
        ACTIVE.put(sourceKey, replacement);
        return new Lease(sourceKey, replacement.token, true);
    }

    static synchronized void resetForTests() {
        ACTIVE.clear();
        nextToken = 1L;
    }
}
