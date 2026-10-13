package com.exor.cinestream;

import android.content.Context;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Keep Media3's Settings -> speed picker, without duplicating the app's audio/subtitle sheet. */
public final class PlayerSettingsRow extends LinearLayout {
    public PlayerSettingsRow(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        TextView title = findViewById(androidx.media3.ui.R.id.exo_main_text);
        // Compare Media3's localized resource, not an English string or a private adapter field.
        boolean audio = title != null && TextUtils.equals(title.getText(),
                getResources().getString(androidx.media3.ui.R.string.exo_track_selection_title_audio));
        setImportantForAccessibility(audio ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        setDescendantFocusability(audio ? FOCUS_BLOCK_DESCENDANTS : FOCUS_AFTER_DESCENDANTS);
        setFocusable(!audio);
        setClickable(!audio);
        if (audio) { setMeasuredDimension(0, 0); return; }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
