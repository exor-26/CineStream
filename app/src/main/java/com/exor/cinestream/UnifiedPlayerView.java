package com.exor.cinestream;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.os.Build;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.Objects;

@UnstableApi
public final class UnifiedPlayerView extends PlayerView {

    private static final long FEEDBACK_VISIBLE_MS = 900L;
    private static final long UNLOCK_HINT_VISIBLE_MS = 1_800L;
    private static final long SEEK_PREVIEW_GRANULARITY_MS = 250L;

    private final PlayerGestureStateMachine gestureStateMachine =
            new PlayerGestureStateMachine();
    private final int touchSlop;
    private final int longPressTimeoutMs;
    private final Rect hitRect = new Rect();
    private final ScaleGestureDetector scaleGestureDetector;

    private boolean nativeControllerOwnsGesture;
    private boolean delegatedGestureCancelled;
    private boolean pictureInPictureMode;
    @Nullable private PlayerControlsCoordinator controlsCoordinator;

    void setControlsCoordinator(@Nullable PlayerControlsCoordinator coordinator) {
        controlsCoordinator = coordinator;
    }

    void showNativeControls() { super.showController(); }
    void hideNativeControls() { super.hideController(); }

    void refreshFeedbackVisibility() {
        if (controlsCoordinator != null) controlsCoordinator.feedbackChanged();
    }

    @Override public void showController() {
        if (controlsCoordinator != null) controlsCoordinator.show();
        else super.showController();
    }

    @Override public void hideController() {
        if (controlsCoordinator == null) { super.hideController(); return; }
        if (pictureInPictureMode || isPlayerLocked()) controlsCoordinator.hideNow();
        else controlsCoordinator.hideAnimated();
    }

    private float currentZoom = 1f;
    private int lastZoomPercentage = 100;
    private PlayerCropMode cropMode = PlayerCropMode.ORIGINAL;
    private String currentLogicalMediaId;

    private boolean seekPreviewActive;
    private long seekStartPositionMs;
    private long seekDurationMs;
    private long seekTargetMs;
    private long lastPresentedSeekTargetMs = Long.MIN_VALUE;

    private boolean temporarySpeedCandidate;
    private boolean temporarySpeedActive;
    private Player temporarySpeedPlayer;
    private PlaybackParameters previousPlaybackParameters;
    @Nullable private Runnable playerUnlockedListener;

    private final Runnable hideFeedbackRunnable = this::animateFeedbackOut;
    private final Runnable hideUnlockHintRunnable = this::animateUnlockHintOut;
    private final Runnable temporarySpeedHoldRunnable = this::activateTemporarySpeed;

    private final Player.Listener internalPlayerListener = new Player.Listener() {
        @Override
        public void onMediaItemTransition(@Nullable MediaItem mediaItem, int reason) {
            cancelTemporarySpeed(true);
            handleLogicalMediaIdentity(mediaItem);
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                cancelTemporarySpeed(true);
                cancelSeekPreview(true);
            }
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (!playWhenReady) {
                cancelTemporarySpeed(true);
            }
        }
    };

    public UnifiedPlayerView(Context context) {
        this(context, null);
    }

    public UnifiedPlayerView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        longPressTimeoutMs = ViewConfiguration.getLongPressTimeout();
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
                            presentZoomPercentage();
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
        cancelTemporarySpeed(true);
        cancelSeekPreview(true);
        if (previous != null) {
            previous.removeListener(internalPlayerListener);
        }
        super.setPlayer(player);
        if (player != null) {
            player.addListener(internalPlayerListener);
            handleLogicalMediaIdentity(player.getCurrentMediaItem());
        }
    }

    public void setOnPlayerUnlockedListener(@Nullable Runnable listener) {
        playerUnlockedListener = listener;
    }

    public void setPictureInPictureMode(boolean enabled) {
        pictureInPictureMode = enabled;
        if (controlsCoordinator != null) controlsCoordinator.suppress(enabled);
        resetTransientPlayerUi();
        if (enabled) {
            hideController();
            setUseController(false);
            setPlayerControlsEnabled(false);
        } else if (!gestureStateMachine.isLocked()) {
            setUseController(true);
            setPlayerControlsEnabled(true);
        }
    }

    public boolean isPlayerLocked() {
        return gestureStateMachine.isLocked();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (pictureInPictureMode) {
            return super.dispatchTouchEvent(event);
        }
        int action = event.getActionMasked();

        if (gestureStateMachine.isLocked()) {
            return handleLockedTouch(event);
        }

        if (action == MotionEvent.ACTION_DOWN) {
            cancelTemporarySpeed(true);
            cancelGestureFeedback();
            delegatedGestureCancelled = false;
            nativeControllerOwnsGesture = isTouchOnInteractiveControllerChild(event);
            gestureStateMachine.beginGesture(
                    event.getX(), event.getY(), getWidth(), getHeight());
            scaleGestureDetector.onTouchEvent(event);
            if (!nativeControllerOwnsGesture
                    && TemporarySpeedGestureLogic.startsOnRightHalf(event.getX(), getWidth())) {
                startTemporarySpeedCandidate();
            }
            return super.dispatchTouchEvent(event);
        }

        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() >= 2) {
            cancelTemporarySpeed(true);
            gestureStateMachine.onPointerCountChanged(event.getPointerCount());
            nativeControllerOwnsGesture = false;
            cancelSeekPreview(true);
            cancelDelegatedGesture(event);
            hideController();
            scaleGestureDetector.onTouchEvent(event);
            lastZoomPercentage = -1;
            presentZoomPercentage();
            return true;
        }

        if (nativeControllerOwnsGesture) {
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                cancelTemporarySpeed(true);
                finishGesture();
            }
            return handled;
        }

        switch (action) {
            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    cancelTemporarySpeed(true);
                    gestureStateMachine.onPointerCountChanged(event.getPointerCount());
                    cancelSeekPreview(true);
                    cancelDelegatedGesture(event);
                    hideController();
                    scaleGestureDetector.onTouchEvent(event);
                    return true;
                }

                if (TemporarySpeedGestureLogic.movedBeyondSlop(
                        gestureStateMachine.getDownX(),
                        gestureStateMachine.getDownY(),
                        event.getX(),
                        event.getY(),
                        touchSlop)) {
                    if (temporarySpeedActive) {
                        cancelTemporarySpeed(true);
                        return true;
                    }
                    cancelTemporarySpeedCandidate();
                }

                PlayerGestureStateMachine.Owner owner =
                        gestureStateMachine.classifyUnlockedMove(
                                event.getX(), event.getY(), getWidth(), touchSlop);
                if (owner == PlayerGestureStateMachine.Owner.BRIGHTNESS
                        || owner == PlayerGestureStateMachine.Owner.VOLUME) {
                    cancelTemporarySpeed(true);
                    cancelGestureFeedback();
                    return dispatchOwnedVerticalEvent(event, owner);
                }
                if (owner == PlayerGestureStateMachine.Owner.SEEK) {
                    cancelTemporarySpeed(true);
                    cancelDelegatedGesture(event);
                    hideController();
                    updateSeekPreview(event.getX() - gestureStateMachine.getDownX());
                    return true;
                }
                if (owner == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    cancelTemporarySpeed(true);
                    cancelDelegatedGesture(event);
                    hideController();
                    scaleGestureDetector.onTouchEvent(event);
                    return true;
                }
                if (owner == PlayerGestureStateMachine.Owner.TEMP_SPEED) {
                    return true;
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                if (gestureStateMachine.getOwner()
                        == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    scaleGestureDetector.onTouchEvent(event);
                }
                return true;

            case MotionEvent.ACTION_UP:
                PlayerGestureStateMachine.Owner finalOwner = gestureStateMachine.getOwner();
                boolean handled = true;
                if (finalOwner == PlayerGestureStateMachine.Owner.TEMP_SPEED
                        || temporarySpeedActive) {
                    cancelTemporarySpeed(true);
                } else if (finalOwner == PlayerGestureStateMachine.Owner.SEEK) {
                    cancelTemporarySpeedCandidate();
                    commitSeekPreview();
                } else if (!delegatedGestureCancelled
                        && (finalOwner == PlayerGestureStateMachine.Owner.PENDING
                        || finalOwner == PlayerGestureStateMachine.Owner.BRIGHTNESS
                        || finalOwner == PlayerGestureStateMachine.Owner.VOLUME)) {
                    cancelTemporarySpeedCandidate();
                    handled = super.dispatchTouchEvent(event);
                } else {
                    cancelTemporarySpeedCandidate();
                }
                if (finalOwner == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    scaleGestureDetector.onTouchEvent(event);
                    scheduleFeedbackHide();
                }
                finishGesture();
                return handled;

            case MotionEvent.ACTION_CANCEL:
                if (gestureStateMachine.getOwner()
                        == PlayerGestureStateMachine.Owner.PINCH_ZOOM) {
                    scaleGestureDetector.onTouchEvent(event);
                }
                cancelTemporarySpeed(true);
                cancelSeekPreview(true);
                cancelGestureFeedback();
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

    private void startTemporarySpeedCandidate() {
        cancelTemporarySpeedCandidate();
        temporarySpeedCandidate = true;
        postDelayed(temporarySpeedHoldRunnable, longPressTimeoutMs);
    }

    private void cancelTemporarySpeedCandidate() {
        temporarySpeedCandidate = false;
        removeCallbacks(temporarySpeedHoldRunnable);
    }

    private void activateTemporarySpeed() {
        if (!temporarySpeedCandidate
                || gestureStateMachine.isLocked()
                || gestureStateMachine.getOwner() != PlayerGestureStateMachine.Owner.PENDING) {
            cancelTemporarySpeedCandidate();
            return;
        }
        Player player = getPlayer();
        if (player == null
                || !player.isPlaying()
                || !player.getAvailableCommands().contains(Player.COMMAND_SET_SPEED_AND_PITCH)
                || !gestureStateMachine.claimTemporarySpeed()) {
            cancelTemporarySpeedCandidate();
            return;
        }

        cancelTemporarySpeedCandidate();
        cancelSeekPreview(true);
        cancelDelegatedGestureWithoutSource();
        temporarySpeedPlayer = player;
        previousPlaybackParameters = player.getPlaybackParameters();
        player.setPlaybackSpeed(2f);
        temporarySpeedActive = true;
        hideController();
        showFeedback(
                0,
                getResources().getString(R.string.temporary_speed_feedback),
                null,
                false
        );
        announceForAccessibility(
                getResources().getString(R.string.temporary_speed_accessibility));
    }

    private void cancelTemporarySpeed(boolean hideFeedback) {
        boolean wasActive = temporarySpeedActive;
        cancelTemporarySpeedCandidate();
        if (temporarySpeedActive
                && temporarySpeedPlayer != null
                && previousPlaybackParameters != null
                && temporarySpeedPlayer.getAvailableCommands()
                .contains(Player.COMMAND_SET_SPEED_AND_PITCH)) {
            temporarySpeedPlayer.setPlaybackParameters(previousPlaybackParameters);
        }
        temporarySpeedActive = false;
        temporarySpeedPlayer = null;
        previousPlaybackParameters = null;
        if (hideFeedback && wasActive) {
            cancelGestureFeedback();
        }
    }

    private void cancelDelegatedGestureWithoutSource() {
        if (delegatedGestureCancelled) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        MotionEvent cancel = MotionEvent.obtain(
                now,
                now,
                MotionEvent.ACTION_CANCEL,
                gestureStateMachine.getDownX(),
                gestureStateMachine.getDownY(),
                0
        );
        super.dispatchTouchEvent(cancel);
        cancel.recycle();
        delegatedGestureCancelled = true;
    }

    public void cycleCropMode(@Nullable View hapticSource) {
        if (gestureStateMachine.isLocked()) {
            return;
        }
        cancelTemporarySpeed(true);
        cropMode = cropMode.next();
        currentZoom = 1f;
        lastZoomPercentage = 100;
        applyCropMode();
        applyVideoSurfaceScale();
        updateCropButtonPresentation(hapticSource);
        showFeedback(cropModeIcon(), cropModeLabel(), cropModeDetail(), true);
    }

    private void applyCropMode() {
        switch (cropMode) {
            case ORIGINAL:
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                break;
            case FILL:
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                break;
            case FIT:
            default:
                setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
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

    private int cropModeIcon() {
        switch (cropMode) {
            case FILL:
                return R.drawable.ic_crop_fill;
            case FIT:
                return R.drawable.ic_crop_fit;
            case ORIGINAL:
            default:
                return R.drawable.ic_crop;
        }
    }

    private String cropModeDetail() {
        switch (cropMode) {
            case FILL:
                return getResources().getString(R.string.crop_fill_detail);
            case FIT:
                return getResources().getString(R.string.crop_fit_detail);
            case ORIGINAL:
            default:
                return getResources().getString(R.string.crop_original_detail);
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
        cancelTemporarySpeed(true);
        cancelSeekPreview(true);
        finishGesture();
        post(this::applyVideoSurfaceScale);
    }

    private float clampZoomToSurface(float requestedZoom) {
        View scaleTarget = videoScaleTarget();
        int surfaceWidth = scaleTarget != null ? scaleTarget.getWidth() : 0;
        int surfaceHeight = scaleTarget != null ? scaleTarget.getHeight() : 0;
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
        View scaleTarget = videoScaleTarget();
        if (scaleTarget == null) {
            return;
        }
        // Scaling SurfaceView/TextureView directly leaves it clipped by Media3's aspect-ratio
        // frame. Transform the frame instead so zoom can expand in both axes up to the real
        // PlayerView boundary, while controller and subtitle layers remain unaffected.
        if (surface != null && surface != scaleTarget) {
            surface.setScaleX(1f);
            surface.setScaleY(1f);
        }
        currentZoom = clampZoomToSurface(currentZoom);
        scaleTarget.setScaleX(currentZoom);
        scaleTarget.setScaleY(currentZoom);
    }

    @Nullable
    private View videoScaleTarget() {
        View contentFrame = findViewById(androidx.media3.ui.R.id.exo_content_frame);
        return contentFrame != null ? contentFrame : getVideoSurfaceView();
    }

    private void presentZoomPercentage() {
        int percentage = PlayerGestureMath.zoomPercentage(currentZoom);
        if (percentage == lastZoomPercentage) {
            return;
        }
        lastZoomPercentage = percentage;
        showFeedback(
                R.drawable.ic_gesture_zoom,
                percentage + "%",
                getResources().getString(R.string.pinch_zoom),
                false
        );
    }

    private void updateSeekPreview(float horizontalDistancePx) {
        if (!seekPreviewActive) {
            LogicalTimelineSnapshot snapshot = captureLogicalTimeline();
            if (snapshot.durationMs <= 0L || snapshot.positionMs < 0L) {
                showFeedback(
                        R.drawable.ic_forward,
                        getResources().getString(R.string.seek_unavailable),
                        null,
                        false
                );
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
        String sign = deltaMs >= 0L ? "+" : "−";
        String delta = sign
                + PlayerGestureMath.formatTimestamp(Math.abs(deltaMs));
        showFeedback(
                deltaMs >= 0L ? R.drawable.ic_forward : R.drawable.ic_rewind,
                PlayerGestureMath.formatTimestamp(seekTargetMs),
                delta,
                false
        );
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
        boolean wasActive = seekPreviewActive;
        seekPreviewActive = false;
        seekStartPositionMs = 0L;
        seekDurationMs = 0L;
        seekTargetMs = 0L;
        lastPresentedSeekTargetMs = Long.MIN_VALUE;
        if (PlayerGestureStateMachine.shouldHideSeekFeedback(hideFeedback, wasActive, temporarySpeedActive)) {
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

        cancelTemporarySpeed(true);
        cancelSeekPreview(true);
        gestureStateMachine.setLocked(true);
        if (controlsCoordinator != null) controlsCoordinator.suppress(true);
        nativeControllerOwnsGesture = false;
        delegatedGestureCancelled = true;

        hideController();
        setUseController(false);
        setPlayerControlsEnabled(false);
        hideTransientAdjustmentOverlays();

        View feedbackSource = hapticSource != null ? hapticSource : this;
        feedbackSource.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        showFeedback(
                R.drawable.ic_screen_lock,
                getResources().getString(R.string.screen_locked),
                null,
                true
        );
        showUnlockHint();
        announceForAccessibility(
                getResources().getString(R.string.screen_locked_accessibility));
    }

    private boolean handleLockedTouch(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelTemporarySpeed(true);
                View unlockHandle = getRootView().findViewById(R.id.unlock_swipe_thumb);
                boolean handleTouched = unlockHandle != null && unlockHandle.isShown()
                        && unlockHandle.getGlobalVisibleRect(hitRect)
                        && hitRect.contains(Math.round(event.getRawX()), Math.round(event.getRawY()));
                gestureStateMachine.beginGesture(
                        event.getX(), event.getY(), getWidth(), getHeight(), handleTouched);
                showUnlockHint();
                View activeHint = getRootView().findViewById(R.id.unlock_hint_container);
                if (activeHint != null) activeHint.removeCallbacks(hideUnlockHintRunnable);
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
                    PlayerGestureStateMachine.UnlockProgress progress = gestureStateMachine.updateUnlock(
                            event.getX(),
                            event.getY(),
                            touchSlop,
                            minimumUnlockDistance(),
                            maximumUnlockVerticalDrift()
                    );
                    updateUnlockHintProgress(progress == PlayerGestureStateMachine.UnlockProgress.CANCELLED
                            ? 0f : Math.max(0f, Math.min(1f,
                            (event.getX() - gestureStateMachine.getDownX()) / minimumUnlockDistance())));
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
                showUnlockHint();
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
        if (controlsCoordinator != null) controlsCoordinator.suppress(false);
        if (playerUnlockedListener != null) {
            playerUnlockedListener.run();
        } else {
            showController();
        }
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        showFeedback(
                R.drawable.ic_screen_lock,
                getResources().getString(R.string.screen_unlocked),
                null,
                true
        );
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
        if (topBar != null) {
            topBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
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
        refreshFeedbackVisibility();
    }

    private boolean animationsEnabled() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || ValueAnimator.areAnimatorsEnabled();
    }

    private void showFeedback(
            int iconResource,
            String title,
            @Nullable String detail,
            boolean autoHide
    ) {
        hideTransientAdjustmentOverlays();
        cancelUnlockHint();
        View feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        ImageView icon = feedback.findViewById(R.id.gesture_feedback_icon);
        TextView titleView = feedback.findViewById(R.id.gesture_feedback_title);
        TextView detailView = feedback.findViewById(R.id.gesture_feedback_detail);
        boolean cropPreview = iconResource == R.drawable.ic_crop
                || iconResource == R.drawable.ic_crop_fill || iconResource == R.drawable.ic_crop_fit;
        styleFeedback(feedback, titleView, detailView, cropPreview);
        View speedArrows = feedback.findViewById(R.id.gesture_feedback_speed);
        if (speedArrows != null) {
            speedArrows.setVisibility(temporarySpeedActive && iconResource == 0 ? View.VISIBLE : View.GONE);
        }
        if (icon != null) {
            icon.setVisibility(iconResource == 0 ? View.GONE : View.VISIBLE);
            if (iconResource != 0) icon.setImageResource(iconResource);
        }
        if (titleView != null) {
            titleView.setText(title);
        }
        if (detailView != null) {
            boolean hasDetail = detail != null && !detail.isEmpty();
            detailView.setText(hasDetail ? detail : "");
            detailView.setVisibility(hasDetail ? View.VISIBLE : View.GONE);
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
        feedback.animate().cancel();
        PlayerFeedbackPlacement.position(feedback);
        if (!animationsEnabled()) {
            feedback.setAlpha(1f);
            feedback.setScaleX(1f);
            feedback.setScaleY(1f);
            feedback.setTranslationY(0f);
            feedback.setVisibility(View.VISIBLE);
        } else if (feedback.getVisibility() != View.VISIBLE) {
            feedback.setVisibility(View.VISIBLE);
            feedback.setAlpha(0f);
            feedback.setScaleX(0.90f);
            feedback.setScaleY(0.90f);
            feedback.setTranslationY(8f * getResources().getDisplayMetrics().density);
            feedback.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .translationY(0f)
                    .setDuration(150L)
                    .start();
        } else {
            feedback.setAlpha(1f);
            feedback.setScaleX(1f);
            feedback.setScaleY(1f);
            feedback.setTranslationY(0f);
        }
        if (autoHide) {
            feedback.postDelayed(hideFeedbackRunnable, FEEDBACK_VISIBLE_MS);
        }
        // Hide every controller group together, before the feedback's first frame.
        refreshFeedbackVisibility();
    }

    private void styleFeedback(View feedback, TextView title, TextView detail, boolean crop) {
        LinearLayout text = feedback.findViewById(R.id.gesture_feedback_text);
        if (text == null || title == null || detail == null) return;
        float density = getResources().getDisplayMetrics().density;
        LinearLayout container = (LinearLayout) feedback;
        container.setOrientation(crop ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        container.setGravity(crop ? android.view.Gravity.CENTER : android.view.Gravity.CENTER_VERTICAL);
        container.setMinimumWidth(crop ? Math.round(164 * density) : 0);
        container.setMinimumHeight(crop ? 0 : Math.round(40 * density));
        text.setOrientation(crop ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        text.setGravity(crop ? android.view.Gravity.CENTER
                : android.view.Gravity.CENTER_VERTICAL);
        feedback.setPadding(Math.round((crop ? 20 : 12) * density), Math.round((crop ? 16 : 8) * density),
                Math.round((crop ? 20 : 12) * density), Math.round((crop ? 16 : 8) * density));
        View icon = feedback.findViewById(R.id.gesture_feedback_icon);
        if (icon != null) {
            LinearLayout.LayoutParams iconParams = (LinearLayout.LayoutParams) icon.getLayoutParams();
            iconParams.width = iconParams.height = Math.round((crop ? 28 : 20) * density);
            iconParams.setMarginEnd(crop ? 0 : Math.round(8 * density));
            icon.setLayoutParams(iconParams);
        }
        LinearLayout.LayoutParams textParams = (LinearLayout.LayoutParams) text.getLayoutParams();
        textParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        textParams.weight = 0;
        text.setLayoutParams(textParams);
        title.setMaxWidth(crop ? Integer.MAX_VALUE : Math.round(100 * density));
        title.setGravity(android.view.Gravity.CENTER);
        title.setTextSize(crop ? 18 : 14);
        LinearLayout.LayoutParams titleParams = (LinearLayout.LayoutParams) title.getLayoutParams();
        titleParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        titleParams.topMargin = crop ? Math.round(8 * density) : 0;
        title.setLayoutParams(titleParams);
        title.setEllipsize(crop ? null : android.text.TextUtils.TruncateAt.END);
        detail.setMaxWidth(Math.round((crop ? 220 : 140) * density));
        detail.setMaxLines(crop ? Integer.MAX_VALUE : 1);
        detail.setMinLines(1);
        detail.setGravity(android.view.Gravity.CENTER);
        detail.setEllipsize(crop ? null : android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams detailParams = (LinearLayout.LayoutParams) detail.getLayoutParams();
        detailParams.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        detailParams.setMarginStart(crop ? 0 : Math.round(8 * density));
        detailParams.topMargin = crop ? Math.round(3 * density) : 0;
        detail.setLayoutParams(detailParams);
        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams params =
                (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) feedback.getLayoutParams();
        params.width = params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        params.setMarginStart(Math.round(16 * density));
        params.setMarginEnd(Math.round(16 * density));
        feedback.setLayoutParams(params);
    }

    private void scheduleFeedbackHide() {
        View feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
        feedback.postDelayed(hideFeedbackRunnable, FEEDBACK_VISIBLE_MS);
    }

    private void animateFeedbackOut() {
        View feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.animate().cancel();
        if (!animationsEnabled()) {
            feedback.setVisibility(View.GONE);
            feedback.setAlpha(0f);
            lastZoomPercentage = -1;
            refreshFeedbackVisibility();
            return;
        }
        feedback.animate()
                .alpha(0f)
                .scaleX(0.96f)
                .scaleY(0.96f)
                .translationY(-4f * getResources().getDisplayMetrics().density)
                .setDuration(140L)
                .withEndAction(() -> {
                    feedback.setVisibility(View.GONE);
                    lastZoomPercentage = -1;
                    refreshFeedbackVisibility();
                })
                .start();
    }

    private void showUnlockHint() {
        View hint = getRootView().findViewById(R.id.unlock_hint_container);
        if (hint == null) {
            return;
        }
        hint.removeCallbacks(hideUnlockHintRunnable);
        hint.animate().cancel();
        View thumb = hint.findViewById(R.id.unlock_swipe_thumb);
        if (thumb != null) {
            thumb.animate().cancel();
            if (animationsEnabled()) thumb.animate().translationX(0f).setDuration(160L).start();
            else thumb.setTranslationX(0f);
        }
        View label = hint.findViewById(R.id.unlock_swipe_label);
        if (label != null) {
            label.animate().cancel();
            if (animationsEnabled()) label.animate().alpha(1f).setDuration(160L).start();
            else label.setAlpha(1f);
        }
        androidx.core.view.WindowInsetsCompat insets = androidx.core.view.ViewCompat.getRootWindowInsets(hint);
        if (insets != null) {
            androidx.core.graphics.Insets safe = insets.getInsets(
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout()
                            | androidx.core.view.WindowInsetsCompat.Type.systemBars());
            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams params =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams) hint.getLayoutParams();
            float density = getResources().getDisplayMetrics().density;
            int sideMargin = Math.max(safe.left, safe.right) + Math.round(16 * density);
            params.gravity = android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL;
            params.leftMargin = params.rightMargin = sideMargin;
            params.width = Math.min(Math.round(260 * density), Math.max(1, getWidth() - 2 * sideMargin));
            params.bottomMargin = safe.bottom + Math.round(32 * density);
            hint.setLayoutParams(params);
        }
        hint.setVisibility(View.VISIBLE);
        if (!animationsEnabled()) {
            hint.setAlpha(1f);
            hint.setTranslationX(0f);
        } else {
            hint.setAlpha(0f);
            hint.setTranslationX(-12f * getResources().getDisplayMetrics().density);
            hint.animate()
                    .alpha(1f)
                    .translationX(0f)
                    .setDuration(180L)
                    .start();
        }
        hint.postDelayed(hideUnlockHintRunnable, UNLOCK_HINT_VISIBLE_MS);
    }

    private void updateUnlockHintProgress(float progress) {
        View hint = getRootView().findViewById(R.id.unlock_hint_container);
        if (hint == null) return;
        hint.removeCallbacks(hideUnlockHintRunnable);
        hint.animate().cancel();
        hint.setAlpha(1f);
        hint.setTranslationX(0f);
        View thumb = hint.findViewById(R.id.unlock_swipe_thumb);
        if (thumb != null) {
            thumb.animate().cancel();
            float gap = 8 * getResources().getDisplayMetrics().density;
            thumb.setTranslationX(progress * Math.max(0, hint.getWidth() - thumb.getWidth() - gap));
        }
        View label = hint.findViewById(R.id.unlock_swipe_label);
        if (label != null) {
            label.animate().cancel();
            label.setAlpha(Math.max(0f, 1 - progress * 2));
        }
    }

    private void animateUnlockHintOut() {
        View hint = getRootView().findViewById(R.id.unlock_hint_container);
        if (hint == null) {
            return;
        }
        hint.animate().cancel();
        if (!animationsEnabled()) {
            hint.setVisibility(View.GONE);
            hint.setAlpha(0f);
            return;
        }
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
        lastZoomPercentage = -1;
        View feedback = getRootView().findViewById(R.id.gesture_feedback);
        if (feedback == null) {
            return;
        }
        feedback.removeCallbacks(hideFeedbackRunnable);
        feedback.animate().cancel();
        feedback.setAlpha(0f);
        feedback.setScaleX(1f);
        feedback.setScaleY(1f);
        feedback.setTranslationY(0f);
        feedback.setVisibility(View.GONE);
        refreshFeedbackVisibility();
    }

    /** Clear presentation only; gesture ownership, zoom, speed and playback are unchanged. */
    public void dismissTransientFeedback() {
        cancelGestureFeedback();
        if (!isPlayerLocked()) cancelUnlockHint();
        hideTransientAdjustmentOverlays();
    }

    private void resetTransientPlayerUi() {
        cancelTemporarySpeed(true);
        cancelSeekPreview(true);
        cancelUnlockHint();
        cancelGestureFeedback();
        removeCallbacks(temporarySpeedHoldRunnable);
        hideTransientAdjustmentOverlays();
        finishGesture();
    }

    private void resetLockWithoutFeedback() {
        resetTransientPlayerUi();
        if (!gestureStateMachine.isLocked()) {
            return;
        }
        gestureStateMachine.setLocked(false);
        setUseController(true);
        setPlayerControlsEnabled(true);
        if (controlsCoordinator != null) controlsCoordinator.suppress(pictureInPictureMode);
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
        resetLockWithoutFeedback();
        super.onDetachedFromWindow();
    }

    private static final class LogicalTimelineSnapshot {
        static final LogicalTimelineSnapshot EMPTY = new LogicalTimelineSnapshot(
                0,
                new long[0],
                -1L,
                0L
        );

        final int firstWindowIndex;
        final long[] windowDurationsMs;
        final long positionMs;
        final long durationMs;

        LogicalTimelineSnapshot(
                int firstWindowIndex,
                long[] windowDurationsMs,
                long positionMs,
                long durationMs
        ) {
            this.firstWindowIndex = firstWindowIndex;
            this.windowDurationsMs = windowDurationsMs;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
        }
    }
}
