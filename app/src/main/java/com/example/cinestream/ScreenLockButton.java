package com.example.cinestream;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

public final class ScreenLockButton extends AppCompatImageButton {

    public ScreenLockButton(Context context) {
        this(context, null);
    }

    public ScreenLockButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        UnifiedPlayerView playerView = getRootView().findViewById(R.id.player_view);
        if (playerView != null) {
            playerView.lockPlayer(this);
            return true;
        }
        return handled;
    }
}
