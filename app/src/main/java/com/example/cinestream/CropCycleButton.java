package com.example.cinestream;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatImageButton;

public final class CropCycleButton extends AppCompatImageButton {

    private boolean ownsClickRouting;

    public CropCycleButton(Context context) {
        this(context, null);
    }

    public CropCycleButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        super.setOnClickListener(v -> {
            UnifiedPlayerView playerView = getRootView().findViewById(R.id.player_view);
            if (playerView != null) {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                playerView.cycleCropMode(this);
            }
        });
        ownsClickRouting = true;
    }

    @Override
    public void setOnClickListener(@Nullable View.OnClickListener listener) {
        // The activity still installs its legacy crop-sheet listener. This button intentionally
        // owns click routing so Phase 5 can cycle modes directly without a competing popup path.
        if (!ownsClickRouting) {
            super.setOnClickListener(listener);
        }
    }
}
