package com.example.cinestream;

import android.content.Context;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.PlayerView;

@UnstableApi
public final class UnifiedPlayerView extends PlayerView {

    private static final long FEEDBACK_VISIBLE_MS = 650L;
    private static final long UNLOCK_HINT_VISIBLE_MS = 1_800L;

    private final PlayerGestureStateMachine gestureStateMachine =
            new PlayerGestureStateMachine();
    private final int touchSlop;

    private boolean nativeControllerOwnsGesture;
    private boolean delegatedGestureCancelled;

    private final Runnable hideFeedbackRunnable = this::animateFeedbackOut;
    private final Runnable hideUnlockHintRunnable = this::animateUnlockHintOut;

    public UnifiedPlayerView(Context context) {
        this(context, null);
    }

    public UnifiedPlayerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (gestureStateMachine.isLocked()) {
            return handleLockedTouch(event);
        }

        if (action == MotionEvent.ACTION_DOWN) {
            delegatedGestureCancelled = false;
            nativeControllerOwnsGesture = isControllerFullyVisible();
            gestureStateMachine.beginGesture(
                    event.getX(), event.getY(), getWidth(), getHeight());
            return super.dispatchTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            gestureStateMachine.onPointerCountChanged(event.getPointerCount());
            nativeControllerOwnsGesture = false;
            cancelDelegatedGesture(event);
            return true;
        }

        if (nativeControllerOwnsGesture) {
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                finishGesture();
            }
            return handled;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    gestureStateMachine.onPointerCountChanged(event.getPointerCount());
                    cancelDelegatedGesture(event);
                    return true;
                }

                PlayerGestureStateMachine.Owner owner =
                        gestureStateMachine.classifyUnlockedMove(
                                event.getX(), event.getY(), getWidth(), touchSlop);
                if (owner == PlayerGestureStateMachine.Owner.BRIGHTNESS
                        || owner == PlayerGestureStateMachine.Owner.VOLUME) {
                    return dispatchOwnedVerticalEvent(event, owner);
                }
                if (owner == PlayerGestureStateMachine.Owner.SEEK
                        || owner == PlayerGestureStateMachine.Owner.PINCH_ZOOM
                        || owner == PlayerGestureStateMachine.Owner.TEMP_SPEED) {
                    cancelDelegatedGesture(event);
                    return true;
                }
                return true;

            case MotionEvent.ACTION_UP:
                PlayerGestureStateMachine.Owner finalOwner = gestureStateMachine.getOwner();
                boolean handled = true;
                if (!delegatedGestureCancelled
                        && (finalOwner == PlayerGestureStateMachine.Owner.PENDING
                        || finalOwner == PlayerGestureStateMachine.Owner.BRIGHTNESS
                        || finalOwner == PlayerGestureStateMachine.Owner.VOLUME)) {
                    handled = super.dispatchTouchEvent(event);
                }
                finishGesture();
                return handled;

            case MotionEvent.ACTION_CANCEL:
                if (!delegatedGestureCancelled) {
                    super.dispatchTouchEvent(event);
                }
                finishGesture();
                return true;

            default:
                return true;
        }
    }

    private boolean dispatchOwnedVerticalEvent(
            MotionEvent source,
            PlayerGestureStateMachine.Owner owner
    ) {
        MotionEvent delegated = MotionEvent.obtain(source);
        float anchorX = gestureStateMachine.getDownX();
        if (owner == PlayerGestureStateMachine.Owner.BRIGHTNESS) {
            anchorX = Math.min(anchorX, Math.max(0f, getWidth() / 2f - 1f));
        } else if (owner == PlayerGestureStateMachine.Owner.VOLUME) {
            anchorX = Math.max(anchorX, Math.min(getWidth(), getWidth() / 2f + 1f));
        }
        // The legacy brightness/volume executor still checks the current X coordinate. Keep the
        // delegated event anchored to the classified side so crossing the screen midpoint cannot
        // change gesture ownership after touch slop has resolved the state.
        delegated.setLocation(anchorX, source.getY());
        boolean handled = super.dispatchTouchEvent(delegated);
        delegated.recycle();
        return handled;
    }

    public void lockPlayer(@Nullable View hapticSource) {
        if (gestureStateMachine.isLocked()) {
            return;
        }

        gestureStateMachine.setLocked(true);
        nativeControllerOwnsGesture = false;
        delegatedGestureCancelled = true;

        hideController();
        setUseController(false);
        setPlayerControlsEnabled(false);
        hideTransientAdjustmentOverlays();

        View feedbackSource = hapticSource != null ? hapticSource : this;
        feedbackSource.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        showFeedback(getResources().getString(R.string.screen_locked));
        showUnlockHint();
        announceForAccessibility(
                getResources().getString(R.string.screen_locked_accessibility));
    }

    private boolean handleLockedTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStateMachine.beginGesture(
                        event.getX(), event.getY(), getWidth(), getHeight());
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                gestureStateMachine.onPointerCountChanged(event.getPointerCount());
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    gestureStateMachine.onPointerCountChanged(event.getPointerCount());
                    return true;
                }
                if (gestureStateMachine.getOwner()
                        == PlayerGestureStateMachine.Owner.UNLOCK_SWIPE) {
                    gestureStateMachine.updateUnlock(
                            event.getX(),
                            event.getY(),
                            touchSlop,
                            minimumUnlockDistance(),
                            maximumUnlockVerticalDrift()
                    );
                }
                return true;

            case MotionEvent.ACTION_UP:
                boolean shouldUnlock = gestureStateMachine.finishUnlockGesture();
                if (shouldUnlock) {
                    unlockPlayer();
                } else {
                    gestureStateMachine.resetGesture();
                    showUnlockHint();
                }
                return true;

            case MotionEvent.ACTION_CANCEL:
                gestureStateMachine.resetGesture();
                return true;

            default:
                return true;
        }
    }

    private void unlockPlayer() {
        gestureStateMachine.setLocked(false);
        finishGesture();
        cancelUnlockHint();

        setUseController(true);
        setPlayerControlsEnabled(true);
        showController();

        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        showFeedback(getResources().getString(R.string.screen_unlocked));
        announceForAccessibility(getResources().getString(R.string.screen_unlocked));
    }

    private void cancelDelegatedGesture(MotionEvent source) {
        if (delegatedGestureCancelled) {
            return;
        }
        MotionEvent cancel = MotionEvent.obtain(source);
        cancel.setAction(MotionEvent.ACTION_CANCEL);
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        delegatedGestureCancelled = true;
    }

    private void finishGesture() {
        gestureStateMachine.resetGesture();
        nativeControllerOwnsGesture = false;
        delegatedGestureCancelled = false;
    }

    private float minimumUnlockDistance() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(
                touchSlop * 8f,
                Math.min(getWidth() * 0.36f, 240f * density)
        );
    }

    private float maximumUnlockVerticalDrift() {
        float density = getResources().getDisplayMetrics().density;
        return Math.max(
                touchSlop * 3f,
                Math.min(getHeight() * 0.10f, 64f * density)
        );
    }

    private void setPlayerControlsEnabled(boolean enabled) {
        View root = getRootView();
        View topBar = root.findViewById(R.id.top_bar);
        setEnabledRecursively(topBar, enabled);
        if (!enabled && topBar != null) {
            topBar.setVisibility(View.GONE);
        }

        View lockButton = root.findViewById(R.id.btn_screen_lock);
        if (lockButton != null) {
            lockButton.setEnabled(enabled);
            lockButton.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    private static void setEnabledRecursively(@Nullable View view, boolean enabled) {
        if (view == null) {
            return;
        }
        view.setEnabled(enabled);
        if (!(view instanceof ViewGroup)) {
            return;
        }
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) {
            setEnabledRecursively(group.getChildAt(i), enabled);
        }
    }

    private void hideTransientAdjustmentOverlays() {
        View root = getRootView();
        View brightness = root.findViewById(R.id.brightness_overlay_container);
        View volume = root.findViewById(R.id.overlay_container);
        if (brightness != null) {
            brightness.animate().cancel();
            brightness.setVisibility(View.GONE);
        }
        if (volume != null) {
            volume.animate().cancel();
            volume.setVisibility(View.GONE);
        }
    }

    private void showFeedback(String text) {
        TextView feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
        feedback.animate().cancel();
        feedback.setText(text);
        feedback.setVisibility(View.VISIBLE);
        feedback.setAlpha(0f);
        feedback.setScaleX(0.92f);
        feedback.setScaleY(0.92f);
        feedback.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(120L)
                .start();
        feedback.postDelayed(hideFeedbackRunnable, FEEDBACK_VISIBLE_MS);
    }

    private void animateFeedbackOut() {
        TextView feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.animate().cancel();
        feedback.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .setDuration(140L)
                .withEndAction(() -> feedback.setVisibility(View.GONE))
                .start();
    }

    private void showUnlockHint() {
        View hint = getRootView().findViewById(R.id.unlock_hint_container);
        if (hint == null) {
            return;
        }
        hint.removeCallbacks(hideUnlockHintRunnable);
        hint.animate().cancel();
        hint.setVisibility(View.VISIBLE);
        hint.setAlpha(0f);
        hint.setTranslationX(-12f * getResources().getDisplayMetrics().density);
        hint.animate()
                .alpha(1f)
                .translationX(0f)
                .setDuration(180L)
                .start();
        hint.postDelayed(hideUnlockHintRunnable, UNLOCK_HINT_VISIBLE_MS);
    }

    private void animateUnlockHintOut() {
        View hint = getRootView().findViewById(R.id.unlock_hint_container);
        if (hint == null) {
            return;
        }
        hint.animate().cancel();
        hint.animate()
                .alpha(0f)
                .translationX(18f * getResources().getDisplayMetrics().density)
                .setDuration(180L)
                .withEndAction(() -> hint.setVisibility(View.GONE))
                .start();
    }

    private void cancelUnlockHint() {
        View hint = getRootView().findViewById(R.id.unlock_hint_container);
        if (hint == null) {
            return;
        }
        hint.removeCallbacks(hideUnlockHintRunnable);
        hint.animate().cancel();
        hint.setAlpha(0f);
        hint.setTranslationX(0f);
        hint.setVisibility(View.GONE);
    }

    private void cancelGestureFeedback() {
        TextView feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
        feedback.animate().cancel();
        feedback.setAlpha(0f);
        feedback.setScaleX(1f);
        feedback.setScaleY(1f);
        feedback.setVisibility(View.GONE);
    }

    private void resetLockWithoutFeedback() {
        if (!gestureStateMachine.isLocked()) {
            cancelUnlockHint();
            cancelGestureFeedback();
            finishGesture();
            return;
        }
        gestureStateMachine.setLocked(false);
        finishGesture();
        cancelUnlockHint();
        cancelGestureFeedback();
        setUseController(true);
        setPlayerControlsEnabled(true);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            // Lock is intentionally session-scoped. Leaving/recreating the player must not return
            // to an interaction-trapped surface when the window becomes active again.
            resetLockWithoutFeedback();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        resetLockWithoutFeedback();
        super.onDetachedFromWindow();
    }
}
