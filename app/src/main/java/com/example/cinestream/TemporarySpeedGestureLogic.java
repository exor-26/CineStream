package com.example.cinestream;

final class TemporarySpeedGestureLogic {
    private TemporarySpeedGestureLogic() {}

    static boolean startsOnRightHalf(float x, float width) {
        return width > 0f && x >= width / 2f && x <= width;
    }

    static boolean movedBeyondSlop(
            float downX,
            float downY,
            float currentX,
            float currentY,
            int touchSlop
    ) {
        float dx = currentX - downX;
        float dy = currentY - downY;
        return Math.max(Math.abs(dx), Math.abs(dy)) > Math.max(0, touchSlop);
    }
}
