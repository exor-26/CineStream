package com.exor.cinestream;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatImageButton;

/** Media3 still sets the displayed state and owns clicks; this view only animates presentation. */
public class GlassPlayerButton extends AppCompatImageButton {
    private final PlayerControlButtonMotion buttonMotion = new PlayerControlButtonMotion(this);
    public GlassPlayerButton(Context context, AttributeSet attrs) { super(context, attrs); }

    static boolean motionEnabled() {
        return Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
    }

    @Override public void setImageDrawable(Drawable drawable) {
        Drawable previous = getDrawable();
        if (previous == drawable) { super.setImageDrawable(drawable); return; }
        if (previous instanceof Animatable) ((Animatable) previous).stop();
        super.setImageDrawable(drawable);
        if (drawable instanceof Animatable) {
            Animatable morph = (Animatable) drawable;
            morph.start();
            // Initial binding/hidden controls must show the final symbol, not a delayed state.
            if (previous == null || !isLaidOut() || !isShown() || !motionEnabled()) morph.stop();
        }
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        buttonMotion.touch(event.getActionMasked());
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        buttonMotion.activate();
        return super.performClick();
    }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        buttonMotion.transform(canvas);
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override protected void onDetachedFromWindow() {
        buttonMotion.reset();
        if (getDrawable() instanceof Animatable) ((Animatable) getDrawable()).stop();
        super.onDetachedFromWindow();
    }
}
