package com.exor.cinestream;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.LinearInterpolator;

/** Presentation only: leaves Media3 click wiring and the button's touch rectangle unchanged. */
final class PlayerControlButtonMotion {
    private final View view;
    private ValueAnimator activation;
    private float pulse;

    PlayerControlButtonMotion(View view) { this.view = view; }

    void touch(int action) {
        if (!view.isEnabled()) return;
        boolean down = action == MotionEvent.ACTION_DOWN;
        boolean end = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
        if (!down && !end) return;
        view.animate().cancel();
        if (GlassPlayerButton.motionEnabled()) {
            view.animate().scaleX(down ? .94f : 1).scaleY(down ? .94f : 1)
                    .setDuration(down ? 70 : 140).start();
        } else { view.setScaleX(1); view.setScaleY(1); }
    }

    void activate() {
        if (activation != null) activation.cancel();
        pulse = 0;
        if (!view.isEnabled() || !view.isShown() || !GlassPlayerButton.motionEnabled()) {
            view.invalidate(); return;
        }
        activation = ValueAnimator.ofFloat(0, 1);
        activation.setDuration(240);
        activation.setInterpolator(new LinearInterpolator());
        activation.addUpdateListener(animation -> {
            pulse = (float) Math.sin(Math.PI * (float) animation.getAnimatedValue());
            view.invalidate();
        });
        activation.start();
    }

    void transform(Canvas canvas) {
        float cx = view.getWidth() / 2f, cy = view.getHeight() / 2f;
        int id = view.getId();
        float direction = id == androidx.media3.ui.R.id.exo_prev
                || id == androidx.media3.ui.R.id.exo_rew_with_amount || id == R.id.btn_back ? -1
                : id == androidx.media3.ui.R.id.exo_next
                || id == androidx.media3.ui.R.id.exo_ffwd_with_amount ? 1 : 0;
        float turn = id == R.id.btn_rotate ? 20
                : id == R.id.btn_player_settings ? 12 : 0;
        canvas.translate(direction * pulse * 3 * view.getResources().getDisplayMetrics().density, 0);
        canvas.rotate(turn * pulse, cx, cy);
        canvas.scale(1 - .12f * pulse, 1 - .12f * pulse, cx, cy);
    }

    void reset() {
        if (activation != null) activation.cancel();
        activation = null;
        pulse = 0;
        view.animate().cancel();
        view.setScaleX(1); view.setScaleY(1);
        view.invalidate();
    }
}
