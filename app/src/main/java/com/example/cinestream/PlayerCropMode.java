package com.example.cinestream;

enum PlayerCropMode {
    ORIGINAL,
    FILL,
    FIT;

    PlayerCropMode next() {
        switch (this) {
            case ORIGINAL:
                return FILL;
            case FILL:
                return FIT;
            case FIT:
            default:
                return ORIGINAL;
        }
    }
}
