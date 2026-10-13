package com.exor.cinestream;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerControlView;
import androidx.media3.ui.TimeBar;
import java.util.function.Consumer;

/** Single owner of controller opacity and timeout; Media3 retains every playback interaction. */
@UnstableApi
final class PlayerControlsCoordinator implements AutoCloseable {
    private final UnifiedPlayerView playerView;
    private final PlayerControlView controller;
    private final View panel;
    private final Consumer<Boolean> visibilityChanged;
    private final PlayerControlsVisibilityState state = new PlayerControlsVisibilityState();
    private final Runnable hide = this::hideAnimated;
    private final PlayerControlView.VisibilityListener nativeVisibility = this::onNativeVisibility;
    private final TimeBar timeBar;
    private final PlayerSettingsMenu settingsMenu;
    private final View.OnLayoutChangeListener layoutChanged = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l != or - ol || b - t != ob - ot) {
            dismissSettingsMenu();
            ViewCompat.requestApplyInsets(v);
            positionActiveFeedback();
            androidx.core.view.OneShotPreDrawListener.add(v, this::positionControls);
        }
    };
    private boolean changingNative;
    private boolean closed;
    private int generation;
    private final TimeBar.OnScrubListener scrubListener = new TimeBar.OnScrubListener() {
        @Override public void onScrubStart(TimeBar bar, long position) { state.scrubbing = true; rearm(); }
        @Override public void onScrubMove(TimeBar bar, long position) {}
        @Override public void onScrubStop(TimeBar bar, long position, boolean canceled) {
            state.scrubbing = false; rearm();
        }
    };

    PlayerControlsCoordinator(UnifiedPlayerView view, Consumer<Boolean> visibilityChanged) {
        playerView = view;
        this.visibilityChanged = visibilityChanged;
        controller = view.findViewById(androidx.media3.ui.R.id.exo_controller);
        panel = view.findViewById(R.id.glass_player_controls);
        timeBar = view.findViewById(androidx.media3.ui.R.id.exo_progress);
        controller.setAnimationEnabled(false);
        controller.setShowTimeoutMs(0);
        view.setControllerShowTimeoutMs(0);
        view.setControllerAutoShow(false);
        view.setControllerHideOnTouch(false);
        controller.addVisibilityListener(nativeVisibility);
        timeBar.addListener(scrubListener);
        settingsMenu = new PlayerSettingsMenu(view, this);
        View.OnLayoutChangeListener controlsChanged = (v, l, t, r, b, ol, ot, or, ob) -> positionControls();
        panel.findViewById(androidx.media3.ui.R.id.exo_center_controls).addOnLayoutChangeListener(controlsChanged);
        panel.findViewById(R.id.glass_player_dock).addOnLayoutChangeListener(controlsChanged);
        View progress = (View) timeBar;
        progress.addOnLayoutChangeListener((v, l, t, r, b, oldL, oldT, oldR, oldB) -> {
            // Media3's stock layout puts its timebar outside the dock and assigns a bottom margin.
            // Our native timebar is inside the dock: retain its touch/seek behavior, not that spacing.
            ViewGroup.MarginLayoutParams p = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (p.bottomMargin != 0) { p.bottomMargin = 0; v.setLayoutParams(p); }
        });
        ViewCompat.setOnApplyWindowInsetsListener(panel, (v, insets) -> {
            applyInsets(insets); return insets;
        });
        panel.addOnLayoutChangeListener(layoutChanged);
        for (int id : new int[]{R.id.gesture_feedback, R.id.brightness_overlay_container, R.id.overlay_container}) {
            View feedback = view.getRootView().findViewById(id);
            if (feedback != null) PlayerFeedbackPlacement.install(feedback);
        }
        ViewCompat.requestApplyInsets(panel);
    }

    private void applyInsets(WindowInsetsCompat insets) {
        Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());
        Insets visibleNavigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
        // Only visible side navigation is reserved across the controller, not a side notch.
        panel.setPadding(visibleNavigation.left, 0, visibleNavigation.right, 0);
        float density = panel.getResources().getDisplayMetrics().density;
        int widthDp = panel.getWidth() > 0 ? Math.round((panel.getWidth()
                - visibleNavigation.left - visibleNavigation.right) / density)
                : panel.getResources().getConfiguration().screenWidthDp;
        int heightDp = panel.getHeight() > 0 ? Math.round(panel.getHeight() / density)
                : panel.getResources().getConfiguration().screenHeightDp;
        int gapDp = PlayerControlsLayoutPolicy.edgeGap(widthDp, heightDp);
        int gap = Math.round(gapDp * density);
        View top = panel.findViewById(R.id.top_bar);
        // Do not reserve hidden status bars or shift the whole landscape UI away from a side notch.
        top.setPadding(gap, cutout.top + gap, gap, gap);
        View bottom = panel.findViewById(R.id.glass_player_dock);
        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) bottom.getLayoutParams();
        params.leftMargin = gap;
        params.rightMargin = gap;
        params.setMarginStart(params.leftMargin);
        params.setMarginEnd(params.rightMargin);
        params.bottomMargin = visibleNavigation.bottom + gap;
        params.width = Math.round(PlayerControlsLayoutPolicy.dockWidth(widthDp, gapDp) * density);
        params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
        bottom.setLayoutParams(params);
        View center = panel.findViewById(androidx.media3.ui.R.id.exo_center_controls);
        center.setTranslationX(0);
        center.setTranslationY(0);
        positionActiveFeedback();
        androidx.core.view.OneShotPreDrawListener.add(panel, this::positionControls);
    }

    private void positionControls() {
        if (closed || panel.getHeight() <= 0) return;
        View top = panel.findViewById(R.id.top_bar);
        View dock = panel.findViewById(R.id.glass_player_dock);
        View center = panel.findViewById(androidx.media3.ui.R.id.exo_center_controls);
        int gap = Math.round(8 * panel.getResources().getDisplayMetrics().density);
        int target = PlayerControlsLayoutPolicy.centerTop(panel.getHeight(), center.getHeight(),
                top.getBottom() - top.getPaddingBottom(), dock.getTop(), gap);
        center.setTranslationY(target - center.getTop());
    }

    void show() {
        if (closed) return;
        feedbackChanged();
        if (!state.canShow() || !playerView.getUseController()) {
            if (controller.isVisible()) hideNow();
            return;
        }
        generation++;
        panel.animate().cancel();
        boolean animate = !state.visible;
        state.visible = true;
        changingNative = true;
        try { playerView.showNativeControls(); } finally { changingNative = false; }
        visibilityChanged.accept(true);
        if (animate && GlassPlayerButton.motionEnabled()) {
            panel.setAlpha(0);
            panel.animate().alpha(1).setDuration(250).setInterpolator(new LinearInterpolator()).start();
        } else panel.setAlpha(1);
        rearm();
    }

    void hideAnimated() {
        if (closed) return;
        if (state.feedbackActive || state.suppressed) { hideNow(); return; }
        panel.removeCallbacks(hide);
        int token = ++generation;
        panel.animate().cancel();
        state.visible = false;
        visibilityChanged.accept(false);
        if (!GlassPlayerButton.motionEnabled() || !controller.isVisible()) { hideNow(); return; }
        panel.animate().alpha(0).setDuration(250).setInterpolator(new LinearInterpolator())
                .withEndAction(() -> { if (!closed && generation == token) hideNow(); }).start();
    }

    void hideNow() {
        generation++;
        panel.removeCallbacks(hide);
        panel.animate().cancel();
        panel.setAlpha(0);
        state.visible = false;
        dismissSettingsMenu();
        changingNative = true;
        try { playerView.hideNativeControls(); } finally { changingNative = false; }
        visibilityChanged.accept(false);
    }

    private void onNativeVisibility(int visibility) {
        if (closed || changingNative) return;
        if (visibility == View.VISIBLE) show();
        else { generation++; panel.animate().cancel(); panel.setAlpha(0);
            state.visible = false; panel.removeCallbacks(hide); visibilityChanged.accept(false); }
    }

    void interaction() { rearm(); }
    void menuOpen(boolean open) { state.menuOpen = open; rearm(); }
    void windowFocus(boolean focused) { state.focused = focused; rearm(); }
    void suppress(boolean suppressed) {
        state.suppressed = suppressed;
        if (suppressed) {
            state.pointerDown = false;
            state.scrubbing = false;
            hideNow();
        } else rearm();
    }
    void touch(int action) {
        if (action == MotionEvent.ACTION_DOWN) state.pointerDown = true;
        else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) state.pointerDown = false;
        rearm();
    }
    private void rearm() {
        panel.removeCallbacks(hide);
        if (!closed && state.canAutoHide()) panel.postDelayed(hide, 3000);
    }

    void feedbackChanged() {
        if (closed) return;
        View root = playerView.getRootView();
        boolean active = false;
        for (int id : new int[]{R.id.gesture_feedback, R.id.brightness_overlay_container, R.id.overlay_container}) {
            View feedback = root.findViewById(id);
            if (feedback != null && feedback.getVisibility() == View.VISIBLE) { active = true; break; }
        }
        state.feedbackActive = active;
        // Never mask individual groups: top, center, dock and dimming share one state.
        if (active && (state.visible || controller.isVisible())) hideNow();
        else rearm();
    }

    private void positionActiveFeedback() {
        if (closed) return;
        View root = playerView.getRootView();
        for (int id : new int[]{R.id.gesture_feedback, R.id.brightness_overlay_container, R.id.overlay_container}) {
            View feedback = root.findViewById(id);
            if (feedback != null && feedback.getVisibility() == View.VISIBLE) PlayerFeedbackPlacement.position(feedback);
        }
        feedbackChanged();
    }

    private void dismissSettingsMenu() {
        if (settingsMenu != null) settingsMenu.dismiss();
    }

    @Override public void close() {
        closed = true;
        generation++;
        panel.removeCallbacks(hide);
        panel.animate().cancel();
        settingsMenu.close();
        controller.removeVisibilityListener(nativeVisibility);
        timeBar.removeListener(scrubListener);
        panel.removeOnLayoutChangeListener(layoutChanged);
    }
}
