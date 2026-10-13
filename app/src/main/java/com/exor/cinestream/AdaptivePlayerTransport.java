package com.exor.cinestream;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

/** Five native controls retain 48dp touch targets, even in a narrow resized window. */
public final class AdaptivePlayerTransport extends HorizontalScrollView {
    public AdaptivePlayerTransport(Context context, AttributeSet attrs) {
        super(context, attrs);
        setHorizontalScrollBarEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
    }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        int windowWidth = MeasureSpec.getSize(widthSpec);
        int parentHeight = MeasureSpec.getSize(heightSpec);
        if (parentHeight <= 0) parentHeight = Math.round(getResources().getConfiguration().screenHeightDp * density);
        int viewport = Math.round(PlayerControlsLayoutPolicy.transportViewport(Math.round(windowWidth / density)) * density);
        int playSize = PlayerControlsLayoutPolicy.playSize(Math.round(viewport / density), Math.round(parentHeight / density));
        int gap = Math.round(PlayerControlsLayoutPolicy.transportGap(Math.round(viewport / density), playSize) * density);
        ViewGroup row = (ViewGroup) getChildAt(0);
        for (int i = 0; i < row.getChildCount(); i++) {
            View button = row.getChildAt(i);
            boolean play = button.getId() == androidx.media3.ui.R.id.exo_play_pause;
            int size = Math.round((play ? playSize : 48) * density);
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) button.getLayoutParams();
            int end = i < row.getChildCount() - 1 ? gap : 0;
            if (params.width != size || params.height != size || params.getMarginStart() != 0 || params.getMarginEnd() != end) {
                params.width = params.height = size;
                params.setMarginStart(0); params.setMarginEnd(end);
                button.setLayoutParams(params);
            }
            if (play) {
                int padding = Math.round((playSize == 64 ? 18 : 12) * density);
                if (button.getPaddingLeft() != padding) button.setPadding(padding, padding, padding, padding);
            }
        }
        ViewGroup.LayoutParams rowParams = row.getLayoutParams();
        int rowHeight = Math.round(playSize * density);
        if (rowParams.height != rowHeight) { rowParams.height = rowHeight; row.setLayoutParams(rowParams); }
        super.onMeasure(MeasureSpec.makeMeasureSpec(Math.max(1, viewport), MeasureSpec.AT_MOST), heightSpec);
    }

    private boolean needsScroll() { return getChildCount() > 0 && getChildAt(0).getWidth() > getWidth(); }

    @Override public boolean onInterceptTouchEvent(MotionEvent event) {
        return needsScroll() && super.onInterceptTouchEvent(event);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        // Ordinary decorative gaps must not steal existing video gestures.
        return needsScroll() && super.onTouchEvent(event);
    }
}
