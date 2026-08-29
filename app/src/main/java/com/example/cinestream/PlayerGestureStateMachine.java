package com.example.cinestream;

final class PlayerGestureStateMachine {

    enum Owner {
        NONE,
        PENDING,
        PINCH_ZOOM,
        TEMP_SPEED,
        SEEK,
        BRIGHTNESS,
        VOLUME,
        UNLOCK_SWIPE,
        BLOCKED
    }

    enum UnlockProgress {
        IN_PROGRESS,
        COMPLETE,
        CANCELLED
    }

    private static final float UNLOCK_START_LEFT_FRACTION = 0.40f;
    private static final float UNLOCK_START_BOTTOM_FRACTION = 0.26f;
    private static final float UNLOCK_HORIZONTAL_DOMINANCE = 1.35f;

    private Owner owner = Owner.NONE;
    private boolean locked;
    private float downX;
    private float downY;
    private boolean unlockThresholdReached;

    void setLocked(boolean locked) {
        this.locked = locked;
        resetGesture();
    }

    boolean isLocked() {
        return locked;
    }

    Owner getOwner() {
        return owner;
    }

    float getDownX() {
        return downX;
    }

    float getDownY() {
        return downY;
    }

    Owner beginGesture(float x, float y, float width, float height) {
        downX = x;
        downY = y;
        unlockThresholdReached = false;
        if (locked) {
            owner = isUnlockStart(x, y, width, height)
                    ? Owner.UNLOCK_SWIPE
                    : Owner.BLOCKED;
        } else {
            owner = Owner.PENDING;
        }
        return owner;
    }

    boolean claimTemporarySpeed() {
        if (locked || owner != Owner.PENDING) {
            return false;
        }
        owner = Owner.TEMP_SPEED;
        return true;
    }

    Owner onPointerCountChanged(int pointerCount) {
        if (pointerCount < 2) {
            return owner;
        }
        unlockThresholdReached = false;
        owner = locked ? Owner.BLOCKED : Owner.PINCH_ZOOM;
        return owner;
    }

    Owner classifyUnlockedMove(float currentX, float currentY, float width, int touchSlop) {
        if (locked || owner != Owner.PENDING) {
            return owner;
        }
        float dx = currentX - downX;
        float dy = currentY - downY;
        if (Math.max(Math.abs(dx), Math.abs(dy)) < touchSlop) {
            return owner;
        }
        if (Math.abs(dx) > Math.abs(dy)) {
            owner = Owner.SEEK;
        } else {
            owner = downX < width / 2f ? Owner.BRIGHTNESS : Owner.VOLUME;
        }
        return owner;
    }

    UnlockProgress updateUnlock(
            float currentX,
            float currentY,
            int touchSlop,
            float minHorizontalDistance,
            float maxVerticalDrift
    ) {
        if (!locked || owner != Owner.UNLOCK_SWIPE) {
            return UnlockProgress.CANCELLED;
        }

        float dx = currentX - downX;
        float dy = currentY - downY;
        float absDy = Math.abs(dy);

        if (dx < -touchSlop
                || absDy > maxVerticalDrift
                || (absDy > touchSlop * 2f && absDy > Math.abs(dx))) {
            owner = Owner.BLOCKED;
            unlockThresholdReached = false;
            return UnlockProgress.CANCELLED;
        }

        if (dx >= minHorizontalDistance
                && dx >= absDy * UNLOCK_HORIZONTAL_DOMINANCE) {
            unlockThresholdReached = true;
            return UnlockProgress.COMPLETE;
        }
        return UnlockProgress.IN_PROGRESS;
    }

    boolean finishUnlockGesture() {
        return locked && owner == Owner.UNLOCK_SWIPE && unlockThresholdReached;
    }

    void resetGesture() {
        owner = Owner.NONE;
        downX = 0f;
        downY = 0f;
        unlockThresholdReached = false;
    }

    static boolean isUnlockStart(float x, float y, float width, float height) {
        return width > 0f
                && height > 0f
                && x >= 0f
                && y >= 0f
                && x <= width * UNLOCK_START_LEFT_FRACTION
                && y >= height * (1f - UNLOCK_START_BOTTOM_FRACTION);
    }
}
