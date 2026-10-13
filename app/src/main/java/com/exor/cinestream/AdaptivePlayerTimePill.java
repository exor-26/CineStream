package com.exor.cinestream;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;

/** Keep complete, font-scaled timestamps away from Settings; stack only when needed. */
public final class AdaptivePlayerTimePill extends LinearLayout {
    public AdaptivePlayerTimePill(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        float density = getResources().getDisplayMetrics().density;
        int available = Math.max(1, MeasureSpec.getSize(widthSpec) - Math.round(56 * density));
        View separator = findViewById(R.id.player_time_separator);
        int naturalWidth = getPaddingLeft() + getPaddingRight();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) child.getLayoutParams();
            naturalWidth += child.getMeasuredWidth() + params.getMarginStart() + params.getMarginEnd();
        }
        boolean stacked = naturalWidth > available;
        int orientation = stacked ? VERTICAL : HORIZONTAL;
        if (getOrientation() != orientation) setOrientation(orientation);
        int separatorVisibility = stacked ? GONE : VISIBLE;
        if (separator.getVisibility() != separatorVisibility) separator.setVisibility(separatorVisibility);
        super.onMeasure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), heightSpec);
    }
}
