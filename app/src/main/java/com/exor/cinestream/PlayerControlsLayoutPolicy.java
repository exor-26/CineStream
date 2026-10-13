package com.exor.cinestream;

/** Geometry only: no device names or playback behavior. */
final class PlayerControlsLayoutPolicy {
    private PlayerControlsLayoutPolicy() {}

    static int edgeGap(int width, int height) {
        if (width < 320 || height < 300) return 8;
        return width >= 600 && height >= 480 ? 24 : 16;
    }

    static int dockWidth(int windowWidth, int edgeGap) {
        return Math.max(1, Math.min(960, windowWidth - 2 * edgeGap));
    }

    static int transportViewport(int windowWidth) {
        return Math.max(1, windowWidth - 2 * (windowWidth >= 320 ? 16 : 8));
    }

    static int playSize(int viewportWidth, int windowHeight) {
        return viewportWidth >= 256 && windowHeight >= 280 ? 64 : 48;
    }

    static int transportGap(int viewportWidth, int playSize) {
        return Math.max(0, Math.min(8, (viewportWidth - 192 - playSize) / 4));
    }

    static int centerTop(int windowHeight, int centerHeight, int toolbarBottom, int dockTop, int gap) {
        int preferred = (windowHeight - centerHeight) / 2;
        int minimum = toolbarBottom + gap;
        int maximum = dockTop - centerHeight - gap;
        return maximum >= minimum ? Math.max(minimum, Math.min(preferred, maximum))
                : Math.max(0, maximum);
    }

    static int dialogWidth(int windowWidth) { return Math.max(1, Math.min(640, Math.round(windowWidth * .92f))); }

    static int lockOffsetY(int width, int height, int centerLeft, int centerRight,
            int lockLeft, int lockRight, int centerHeight, int lockHeight, int gap) {
        boolean collision = lockRight + gap > centerLeft && lockLeft - gap < centerRight;
        if (width >= height || !collision) return 0;
        // On narrow portrait screens, place the lock above the row, never over Previous/Next.
        return -((centerHeight + lockHeight) / 2 + gap);
    }
}
