package com.example.cinestream;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.Objects;

@UnstableApi
public final class UnifiedPlayerView extends PlayerView {

    private static final long FEEDBACK_VISIBLE_MS = 650L;
    private static final long UNLOCK_HINT_VISIBLE_MS = 1_800L;
    private static final long SEEK_PREVIEW_GRANULARITY_MS = 250L;

    private final PlayerGestureStateMachine gestureStateMachine =
            new PlayerGestureStateMachine();
    private final int touchSlop;
    private final Rect hitRect = new Rect();
    private final ScaleGestureDetector scaleGestureDetector;

    private boolean nativeControllerOwnsGesture;
    private boolean delegatedGestureCancelled;
    private float currentZoom = 1f;
    private int lastZoomPercentage = 100;
    private PlayerCropMode cropMode = PlayerCropMode.ORIGINAL;
    private String currentLogicalMediaId;

    private boolean seekPreviewActive;
    private long seekStartPositionMs;
    private long seekDurationMs;
    private long seekTargetMs;
    private long lastPresentedSeekTargetMs = Long.MIN_VALUE;

    private final Runnable hideFeedbackRunnable = this::animateFeedbackOut;
    private final Runnable hideUnlockHintRunnable = this::animateUnlockHintOut;

    private final Player.Listener internalPlayerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            handleLogicalMediaIdentity(mediaItem);
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED) {
                cancelSeekPreview(true);
            }
        }
    };

    public UnifiedPlayerView(Context context) {
        this(context, null);
    }

    public UnifiedPlayerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scaleGestureDetector = new ScaleGestureDetector(
                context,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(ScaleGestureDetector detector) {
                        boolean accepted = !gestureStateMachine.isLocked()
                                && gestureStateMachine.getOwner()
                                == PlayerGestureStateMachine.Owner.PINCH_ZOOM;
                        if (accepted) {
                            lastZoomPercentage = -1;
                            presentZoomPercentage();
                        }
                        return accepted;
                    }

                    @Override
                    public boolean onScale(ScaleGestureDetector detector) {
                        if (gestureStateMachine.isLocked()
                                || gestureStateMachine.getOwner()
                                != PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                            return false;
                        }
                        float requested = currentZoom * detector.getScaleFactor();
                        float bounded = clampZoomToSurface(requested);
                        if (Math.abs(bounded - currentZoom) < 0.0005f) {
                            return true;
                        }
                        currentZoom = bounded;
                        applyVideoSurfaceScale();
                        presentZoomPercentage();
                        return true;
                    }

                    @Override
                    public void onScaleEnd(ScaleGestureDetector detector) {
                        if (gestureStateMachine.getOwner()
                                == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                            scheduleFeedbackHide();
                        }
                    }
                }
        );
        scaleGestureDetector.setQuickScaleEnabled(false);
        scaleGestureDetector.setStylusScaleEnabled(false);
    }

    @Override
    public void setPlayer(@Nullable Player player) {
        Player previous = getPlayer();
        if (previous != null && internalPlayerListener != null) {
            previous.removeListener(internalPlayerListener);
        }
        super.setPlayer(player);
        if (player != null && internalPlayerListener != null) {
            player.addListener(internalPlayerListener);
            handleLogicalMediaIdentity(player.getCurrentMediaItem());
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();

        if (gestureStateMachine.isLocked()) {
            return handleLockedTouch(event);
        }

        if (action == MotionEvent.ACTION_DOWN) {
            delegatedGestureCancelled = false;
            nativeControllerOwnsGesture = isTouchOnInteractiveControllerChild(event);
            gestureStateMachine.beginGesture(
                    event.getX(), event.getY(), getWidth(), getHeight());
            scaleGestureDetector.onTouchEvent(event);
            return super.dispatchTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            gestureStateMachine.onPointerCountChanged(event.getPointerCount());
            nativeControllerOwnsGesture = false;
            cancelSeekPreview(true);
            cancelDelegatedGesture(event);
            hideController();
            scaleGestureDetector.onTouchEvent(event);
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
                    cancelSeekPreview(true);
                    cancelDelegatedGesture(event);
                    hideController();
                    scaleGestureDetector.onTouchEvent(event);
                    return true;
                }

                PlayerGestureStateMachine.Owner owner =
                        gestureStateMachine.classifyUnlockedMove(
                                event.getX(), event.getY(), getWidth(), touchSlop);
                if (owner == PlayerGestureStateMachine.Owner.BRIGHTNESS
                        || owner == PlayerGestureStateMachine.Owner.VOLUME) {
                    return dispatchOwnedVerticalEvent(event, owner);
                }
                if (owner == PlayerGestureStateMachine.Owner.SEEK) {
                    cancelDelegatedGesture(event);
                    hideController();
                    updateSeekPreview(event.getX() - gestureStateMachine.getDownX());
                    return true;
                }
                if (owner == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    cancelDelegatedGesture(event);
                    hideController();
                    scaleGestureDetector.onTouchEvent(event);
                    return true;
                }
                if (owner == PlayerGestureStateMachine.Owner.TEMP_SPEED) {
                    cancelDelegatedGesture(event);
                    return true;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (gestureStateMachine.getOwner()
                        == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    scaleGestureDetector.onTouchEvent(event);
                    return true;
                }
                return true;

            case MotionEvent.ACTION_UP:
                PlayerGestureStateMachine.Owner finalOwner = gestureStateMachine.getOwner();
                boolean handled = true;
                if (finalOwner == PlayerGestureStateMachine.Owner.SEEK) {
                    commitSeekPreview();
                } else if (!delegatedGestureCancelled
                        && (finalOwner == PlayerGestureStateMachine.Owner.PENDING
                        || finalOwner == PlayerGestureStateMachine.Owner.BRIGHTNESS
                        || finalOwner == PlayerGestureStateMachine.Owner.VOLUME)) {
                    handled = super.dispatchTouchEvent(event);
                }
                if (finalOwner == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    scaleGestureDetector.onTouchEvent(event);
                }
                finishGesture();
                return handled;

            case MotionEvent.ACTION_CANCEL:
                if (gestureStateMachine.getOwner()
                        == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    scaleGestureDetector.onTouchEvent(event);
                }
                cancelSeekPreview(true);
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
        delegated.setLocation(anchorX, source.getY());
        boolean handled = super.dispatchTouchEvent(delegated);
        delegated.recycle();
        return handled;
    }

    public void cycleCropMode(@Nullable View hapticSource) {
        if (gestureStateMachine.isLocked()) {
            return;
        }
        cropMode = cropMode.next();
        currentZoom = 1f;
        lastZoomPercentage = 100;
        applyCropMode();
        applyVideoSurfaceScale();
        updateCropButtonPresentation(hapticSource);
        hideTransientAdjustmentOverlays();
        showFeedback(cropModeLabel(), true);
    }

    private void applyCropMode() {
        switch (cropMode) {
            case ORIGINAL:
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case FILL:
                // Cover the player while preserving the source aspect ratio. The previous FILL
                // mode stretched pixels, which conflicts with continuous aspect-safe zoom.
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                break;
            case FIT:
            default:
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH);
                break;
        }
    }

    private void updateCropButtonPresentation(@Nullable View source) {
        View candidate = source != null ? source : getRootView().findViewById(R.id.btn_crop);
        if (!(candidate instanceof ImageButton)) {
            return;
        }
        ImageButton button = (ImageButton) candidate;
        switch (cropMode) {
            case ORIGINAL:
                button.setImageResource(R.drawable.ic_crop);
                break;
            case FILL:
                button.setImageResource(R.drawable.ic_crop_fill);
                break;
            case FIT:
            default:
                button.setImageResource(R.drawable.ic_crop_fit);
                break;
        }
        String description = getResources().getString(
                R.string.crop_mode_description,
                cropModeLabel()
        );
        button.setContentDescription(description);
        button.announceForAccessibility(description);
    }

    private String cropModeLabel() {
        switch (cropMode) {
            case FILL:
                return getResources().getString(R.string.fill);
            case FIT:
                return getResources().getString(R.string.fit);
            case ORIGINAL:
            default:
                return getResources().getString(R.string.original);
        }
    }

    private void handleLogicalMediaIdentity(@Nullable MediaItem mediaItem) {
        if (mediaItem == null) {
            return;
        }
        String nextId = mediaItem.mediaId;
        if (Objects.equals(currentLogicalMediaId, nextId)) {
            return;
        }
        currentLogicalMediaId = nextId;
        currentZoom = 1f;
        lastZoomPercentage = 100;
        cancelSeekPreview(true);
        finishGesture();
        post(this::applyVideoSurfaceScale);
    }

    private float clampZoomToSurface(float requestedZoom) {
        View surface = getVideoSurfaceView();
        int surfaceWidth = surface != null ? surface.getWidth() : 0;
        int surfaceHeight = surface != null ? surface.getHeight() : 0;
        return PlayerGestureMath.clampZoom(
                requestedZoom,
                surfaceWidth,
                surfaceHeight,
                getWidth(),
                getHeight()
        );
    }

    private void applyVideoSurfaceScale() {
        View surface = getVideoSurfaceView();
        if (surface == null) {
            return;
        }
        currentZoom = clampZoomToSurface(currentZoom);
        surface.setScaleX(currentZoom);
        surface.setScaleY(currentZoom);
    }

    private void presentZoomPercentage() {
        int percentage = PlayerGestureMath.zoomPercentage(currentZoom);
        if (percentage == lastZoomPercentage) {
            return;
        }
        lastZoomPercentage = percentage;
        hideTransientAdjustmentOverlays();
        showFeedback(percentage + "%", false);
    }

    private void updateSeekPreview(float horizontalDistancePx) {
        if (!seekPreviewActive) {
            LogicalTimelineSnapshot snapshot = captureLogicalTimeline();
            if (snapshot.durationMs <= 0L || snapshot.positionMs < 0L) {
                showFeedback(getResources().getString(R.string.seek_unavailable), false);
                return;
            }
            seekPreviewActive = true;
            seekStartPositionMs = snapshot.positionMs;
            seekDurationMs = snapshot.durationMs;
            seekTargetMs = seekStartPositionMs;
            lastPresentedSeekTargetMs = Long.MIN_VALUE;
        }

        seekTargetMs = PlayerGestureMath.mapSeekTarget(
                seekStartPositionMs,
                seekDurationMs,
                horizontalDistancePx,
                Math.max(1f, getWidth())
        );
        if (lastPresentedSeekTargetMs != Long.MIN_VALUE
                && Math.abs(seekTargetMs - lastPresentedSeekTargetMs)
                < SEEK_PREVIEW_GRANULARITY_MS) {
            return;
        }
        lastPresentedSeekTargetMs = seekTargetMs;
        long deltaMs = seekTargetMs - seekStartPositionMs;
        String arrow = deltaMs >= 0L ? "→" : "←";
        String sign = deltaMs >= 0L ? "+" : "−";
        String preview = arrow
                + " " + sign + PlayerGestureMath.formatTimestamp(Math.abs(deltaMs))
                + " • " + PlayerGestureMath.formatTimestamp(seekTargetMs);
        hideTransientAdjustmentOverlays();
        showFeedback(preview, false);
    }

    private void commitSeekPreview() {
        if (!seekPreviewActive) {
            scheduleFeedbackHide();
            return;
        }
        Player player = getPlayer();
        LogicalTimelineSnapshot snapshot = captureLogicalTimeline();
        if (player != null && snapshot.durationMs > 0L && snapshot.windowDurationsMs.length > 0) {
            long safeTarget = PlayerGestureMath.clampPosition(
                    seekTargetMs,
                    snapshot.durationMs
            );
            PlayerGestureMath.SeekWindow target = PlayerGestureMath.resolveSeekWindow(
                    snapshot.windowDurationsMs,
                    safeTarget
            );
            player.seekTo(
                    snapshot.firstWindowIndex + target.windowOffset,
                    target.positionInWindowMs
            );
        }
        seekPreviewActive = false;
        lastPresentedSeekTargetMs = Long.MIN_VALUE;
        scheduleFeedbackHide();
    }

    private void cancelSeekPreview(boolean hideFeedback) {
        seekPreviewActive = false;
        seekStartPositionMs = 0L;
        seekDurationMs = 0L;
        seekTargetMs = 0L;
        lastPresentedSeekTargetMs = Long.MIN_VALUE;
        if (hideFeedback) {
            cancelGestureFeedback();
        }
    }

    private LogicalTimelineSnapshot captureLogicalTimeline() {
        Player player = getPlayer();
        if (player == null) {
            return LogicalTimelineSnapshot.EMPTY;
        }
        Timeline timeline = player.getCurrentTimeline();
        int currentIndex = player.getCurrentMediaItemIndex();
        MediaItem currentItem = player.getCurrentMediaItem();
        if (timeline.isEmpty()
                || currentIndex < 0
                || currentIndex >= timeline.getWindowCount()
                || currentItem == null) {
            long duration = player.getDuration();
            if (duration == C.TIME_UNSET || duration <= 0L) {
                return LogicalTimelineSnapshot.EMPTY;
            }
            return new LogicalTimelineSnapshot(
                    currentIndex < 0 ? 0 : currentIndex,
                    new long[]{duration},
                    0,
                    Math.max(0L, player.getCurrentPosition()),
                    duration
            );
        }

        String mediaId = currentItem.mediaId;
        Timeline.Window window = new Timeline.Window();
        int first = currentIndex;
        while (first > 0 && mediaIdMatches(timeline, first - 1, mediaId, window)) {
            first--;
        }
        int last = currentIndex;
        while (last + 1 < timeline.getWindowCount()
                && mediaIdMatches(timeline, last + 1, mediaId, window)) {
            last++;
        }

        long[] durations = new long[last - first + 1];
        for (int i = first; i <= last; i++) {
            timeline.getWindow(i, window);
            long duration = window.getDurationMs();
            if (duration == C.TIME_UNSET || duration <= 0L) {
                return LogicalTimelineSnapshot.EMPTY;
            }
            durations[i - first] = duration;
        }
        int currentOffset = currentIndex - first;
        long logicalPosition = PlayerGestureMath.logicalPosition(
                durations,
                currentOffset,
                player.getCurrentPosition()
        );
        long logicalDuration = PlayerGestureMath.logicalDuration(durations);
        return new LogicalTimelineSnapshot(
                first,
                durations,
                currentOffset,
                logicalPosition,
                logicalDuration
        );
    }

    private boolean mediaIdMatches(
            Timeline timeline,
            int windowIndex,
            String mediaId,
            Timeline.Window reusableWindow
    ) {
        timeline.getWindow(windowIndex, reusableWindow);
        return reusableWindow.mediaItem != null
                && Objects.equals(mediaId, reusableWindow.mediaItem.mediaId);
    }

    private boolean isTouchOnInteractiveControllerChild(MotionEvent event) {
        if (!isControllerFullyVisible()) {
            return false;
        }
        return containsInteractiveLeafAt(this, event.getRawX(), event.getRawY());
    }

    private boolean containsInteractiveLeafAt(ViewGroup group, float rawX, float rawY) {
        for (int i = group.getChildCount() - 1; i >= 0; i--) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE || !child.isEnabled()) {
                continue;
            }
            if (!child.getGlobalVisibleRect(hitRect)
                    || !hitRect.contains(Math.round(rawX), Math.round(rawY))) {
                continue;
            }
            if (child instanceof ViewGroup) {
                if (containsInteractiveLeafAt((ViewGroup) child, rawX, rawY)) {
                    return true;
                }
                continue;
            }
            if (child.isClickable() || child.isLongClickable()) {
                return true;
            }
        }
        return false;
    }

    public void lockPlayer(@Nullable View hapticSource) {
        if (gestureStateMachine.isLocked()) {
            return;
        }

        gestureStateMachine.setLocked(true);
        nativeControllerOwnsGesture = false;
        delegatedGestureCancelled = true;
        cancelSeekPreview(true);

        hideController();
        setUseController(false);
        setPlayerControlsEnabled(false);
        hideTransientAdjustmentOverlays();

        View feedbackSource = hapticSource != null ? hapticSource : this;
        feedbackSource.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        showFeedback(getResources().getString(R.string.screen_locked), true);
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
        showFeedback(getResources().getString(R.string.screen_unlocked), true);
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

    private void showFeedback(String text, boolean autoHide) {
        TextView feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
        feedback.animate().cancel();
        feedback.setText(text);
        if (feedback.getVisibility() != View.VISIBLE) {
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
        } else {
            feedback.setAlpha(1f);
            feedback.setScaleX(1f);
            feedback.setScaleY(1f);
        }
        if (autoHide) {
            feedback.postDelayed(hideFeedbackRunnable, FEEDBACK_VISIBLE_MS);
        }
    }

    private void scheduleFeedbackHide() {
        TextView feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
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
        cancelSeekPreview(true);
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
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        post(this::applyVideoSurfaceScale);
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility != View.VISIBLE) {
            resetLockWithoutFeedback();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        Player player = getPlayer();
        if (player != null) {
            player.removeListener(internalPlayerListener);
        }
        resetLockWithoutFeedback();
        super.onDetachedFromWindow();
    }

    private static final class LogicalTimelineSnapshot {
        static final LogicalTimelineSnapshot EMPTY = new LogicalTimelineSnapshot(
                0,
                new long[0],
                0,
                -1L,
                0L
        );

        final int firstWindowIndex;
        final long[] windowDurationsMs;
        final int currentWindowOffset;
        final long positionMs;
        final long durationMs;

        LogicalTimelineSnapshot(
                int firstWindowIndex,
                long[] windowDurationsMs,
                int currentWindowOffset,
                long positionMs,
                long durationMs
        ) {
            this.firstWindowIndex = firstWindowIndex;
            this.windowDurationsMs = windowDurationsMs;
            this.currentWindowOffset = currentWindowOffset;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }
}
