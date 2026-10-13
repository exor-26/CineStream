package com.exor.cinestream;

import android.view.Gravity;
import android.view.View;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** One safe, top-center feedback slot for every player gesture. */
final class PlayerFeedbackPlacement {
    private PlayerFeedbackPlacement() {}

    static void position(View overlay) {
        View container = (View) overlay.getParent();
        if (container == null || container.getHeight() <= 0) return;
        int gap = Math.round(8 * overlay.getResources().getDisplayMetrics().density);
        int safeTop = 0;
        WindowInsetsCompat windowInsets = ViewCompat.getRootWindowInsets(container);
        if (windowInsets != null) {
            // Immersive bar visibility changes when controls hide; that must not shift the HUD.
            Insets safe = windowInsets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout()
                    | WindowInsetsCompat.Type.statusBars());
            safeTop = safe.top;
        }
        CoordinatorLayout.LayoutParams params = (CoordinatorLayout.LayoutParams) overlay.getLayoutParams();
        int width = Math.max(1, container.getWidth() - params.getMarginStart() - params.getMarginEnd());
        overlay.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        // Toolbar/controller visibility must never move gesture feedback.
        int top = PlayerFeedbackLayoutPolicy.top(container.getHeight(), overlay.getMeasuredHeight(),
                safeTop, gap);
        if (params.gravity != (Gravity.TOP | Gravity.CENTER_HORIZONTAL) || params.topMargin != top) {
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            params.topMargin = top;
            overlay.setLayoutParams(params);
        }
    }

    static void install(View overlay) {
        overlay.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
            if (view.getVisibility() == View.VISIBLE && (r != or || b != ob || l != ol || t != ot)) {
                position(view);
                View player = view.getRootView().findViewById(R.id.player_view);
                if (player instanceof UnifiedPlayerView) ((UnifiedPlayerView) player).refreshFeedbackVisibility();
            }
        });
    }
}
