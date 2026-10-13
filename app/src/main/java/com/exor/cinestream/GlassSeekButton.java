package com.exor.cinestream;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import androidx.appcompat.widget.AppCompatTextView;

/** A themed circular seek symbol around Media3's own seek-increment label. */
public final class GlassSeekButton extends AppCompatTextView {
    private final PlayerControlButtonMotion buttonMotion = new PlayerControlButtonMotion(this);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    public GlassSeekButton(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onDraw(Canvas canvas) {
        int save = canvas.save();
        buttonMotion.transform(canvas);
        float density = getResources().getDisplayMetrics().density;
        float radius = 15 * density;
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        ring.setColor(getCurrentTextColor());
        ring.setStrokeWidth(1.6f * density);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeCap(Paint.Cap.ROUND);
        arc.set(cx - radius, cy - radius, cx + radius, cy + radius);
        boolean forward = getId() == androidx.media3.ui.R.id.exo_ffwd_with_amount;
        canvas.save();
        if (forward) canvas.scale(-1, 1, cx, cy);
        canvas.drawArc(arc, -65, 295, false, ring);
        float x = cx + radius * .42f, y = cy - radius * .91f;
        canvas.drawLine(x, y, x + 5 * density, y - density, ring);
        canvas.drawLine(x, y, x + density, y + 5 * density, ring);
        canvas.restore();
        super.onDraw(canvas);
        canvas.restoreToCount(save);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        buttonMotion.touch(event.getActionMasked());
        return super.onTouchEvent(event);
    }

    @Override public boolean performClick() {
        buttonMotion.activate();
        return super.performClick();
    }

    @Override protected void onDetachedFromWindow() {
        buttonMotion.reset();
        super.onDetachedFromWindow();
    }
}
