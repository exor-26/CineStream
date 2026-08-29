package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
import android.media.audiofx.LoudnessEnhancer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.Timeline;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.FilteringMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;

import com.example.cinestream.ffmpeg.CineFfmpegLibrary;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

@UnstableApi
public class VideoPlayerActivity extends AppCompatActivity {

    public static final String EXTRA_VIDEO_URI = "VIDEO_URI";
    public static final String EXTRA_PLAYBACK_KEY = "PLAYBACK_KEY";
    public static final String EXTRA_VIDEO_TITLE = "VIDEO_TITLE";
    public static final String EXTRA_PLAYLIST_URIS = "PLAYLIST_URIS";
    public static final String EXTRA_PLAYLIST_KEYS = "PLAYLIST_KEYS";
    public static final String EXTRA_PLAYLIST_TITLES = "PLAYLIST_TITLES";
    public static final String EXTRA_PLAYLIST_INDEX = "PLAYLIST_INDEX";
    private static final int ACTION_SUBTITLE_OFF = -1;

    private ExoPlayer exoPlayer;
    private DefaultTrackSelector trackSelector;
    private UnifiedPlayerView playerView;
    private ImageButton rotateButton;
    private ImageButton cropButton;
    private ImageButton audioTrackButton;
    private ImageButton screenLockButton;

    private LinearLayout brightnessOverlay;
    private ProgressBar brightnessProgressBar;
    private ImageView brightnessIcon;
    private LinearLayout volumeOverlay;
    private ProgressBar volumeProgressBar;
    private ProgressBar volumeBoostProgressBar;
    private ImageView volumeIcon;
    private TextView tvVideoName;
    private ImageButton btnBack;

    private boolean isLockedInPortrait = false;
    private boolean isLockedInLandscape = false;
    private boolean isControlsVisible = true;
    private boolean compatibilityTranscodeAttempted = false;
    private boolean capabilityWarningLogged = false;
    private boolean softwareVideoRecoveryAttempted = false;
    private boolean hardwareVideoFailureObserved = false;
    private CompatibilityVideoTranscoder.Session compatibilityTranscodeSession;
    private ProgressiveCompatibilityManager.Handle progressiveCompatibilityHandle;
    private ConcatenatingMediaSource progressiveMediaSequence;
    private final ArrayList<ProgressiveCompatibilityManager.Segment> progressiveSegments =
            new ArrayList<>();
    private ArrayList<MediaItem> progressiveOriginalItems;
    private int progressiveOriginalIndex;
    private int progressiveInsertIndex;
    private boolean progressiveTrailingItemsAdded;
    private long progressiveOriginalDurationMs;
    private Uri progressiveOriginalUri;
    private String progressivePlaybackKey;
    private boolean progressivePlaybackActive;
    private boolean progressiveFallbackStarted;
    private boolean compatibilityCacheLookupPending;
    private Uri compatibilityOriginalUri;
    private Uri compatibilityVideoUri;
    private String compatibilityPlaybackKey;

    private PlaybackEnginePolicy.DecoderMode decoderMode =
            PlaybackEnginePolicy.DecoderMode.HARDWARE_FIRST;
    private DeviceVideoCapabilities.Assessment currentVideoAssessment;
    private Format softwareRecoveryVideoFormat;
    private RuntimeVideoResourceMonitor videoResourceMonitor;
    private CompatibilityVideoPolicy.Target compatibilityCeiling;
    private long softwareObservationStartMs;
    private long softwareObservationStartPositionMs;
    private int softwareRenderedFrames;
    private int softwareDroppedFrames;
    private boolean softwareStartupObserved;
    private boolean governorHandoffStarted;
    private boolean governedSoftwareVideoActive;
    private boolean startSoftwarePlaybackAfterFirstFrame;
    private boolean softwarePlaybackStartScheduled;
    private long firstFrameWatchStartMs;
    private long firstFrameWatchStartPositionMs;
    private boolean firstVideoFrameRendered;
    private String firstFrameWatchPlaybackKey;

    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private Uri videoUri;
    private String playbackKey;
    private ArrayList<String> playlistTitles;
    private int selectedAudioActionId = Integer.MIN_VALUE;
    private int selectedSubtitleActionId = ACTION_SUBTITLE_OFF;
    private boolean restoringTrackSelection;

    private float maxVolume;
    private float currentVolume;
    private float currentBrightness;
    private float volumeBoostPercent = 100f;
    private LoudnessEnhancer loudnessEnhancer;
    private final Runnable hideBrightnessOverlayRunnable = () -> brightnessOverlay.setVisibility(View.GONE);
    private final Runnable hideVolumeOverlayRunnable = () -> volumeOverlay.setVisibility(View.GONE);

    private final AnalyticsListener videoPerformanceListener = new AnalyticsListener() {
        @Override
        public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
            if (isObservingDirectSoftwareVideo()) {
                softwareDroppedFrames += Math.max(0, droppedFrames);
            }
        }

        @Override
        public void onVideoFrameProcessingOffset(
                EventTime eventTime,
                long totalProcessingOffsetUs,
                int frameCount
        ) {
            if (isObservingDirectSoftwareVideo()) {
                softwareRenderedFrames += Math.max(0, frameCount);
                if (frameCount > 0) {
                    handleFirstVideoFrameRendered();
                }
            }
        }
    };

    private final Runnable softwareGovernorRunnable = this::evaluateDirectSoftwarePlayback;
    private final Runnable firstFrameWatchdogRunnable = this::evaluateFirstVideoFrame;

    private final Player.Listener playbackListener = new Player.Listener() {
        @Override
        public void onAudioSessionIdChanged(int audioSessionId) {
            attachLoudnessEnhancer(audioSessionId);
            applyVolumeBoost();
        }

        @Override
        public void onMediaItemTransition(MediaItem mediaItem, int reason) {
            if (mediaItem == null) {
                return;
            }
            String previousPlaybackKey = playbackKey;
            playbackKey = mediaItem.mediaId;
            Uri transitionedUri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri : null;
            boolean sameLogicalItem = previousPlaybackKey != null
                    && previousPlaybackKey.equals(playbackKey);
            if (!sameLogicalItem) {
                resetFirstFrameWatchdog();
                compatibilityTranscodeAttempted = false;
                softwareVideoRecoveryAttempted = false;
                hardwareVideoFailureObserved = false;
                softwareRecoveryVideoFormat = null;
                compatibilityCeiling = null;
                governorHandoffStarted = false;
                governedSoftwareVideoActive = false;
                startSoftwarePlaybackAfterFirstFrame = false;
                softwarePlaybackStartScheduled = false;
                selectedAudioActionId = Integer.MIN_VALUE;
                selectedSubtitleActionId = ACTION_SUBTITLE_OFF;
                if (progressivePlaybackActive
                        && progressivePlaybackKey != null
                        && !progressivePlaybackKey.equals(playbackKey)) {
                    clearProgressivePlaybackState();
                }
                if (decoderMode.preferSoftwareVideo) {
                    decoderMode = decoderMode.withoutSoftwareVideo();
                    String targetPlaybackKey = playbackKey;
                    uiHandler.post(() -> {
                        if (exoPlayer == null
                                || targetPlaybackKey == null
                                || !targetPlaybackKey.equals(playbackKey)) {
                            return;
                        }
                        ArrayList<MediaItem> items = snapshotMediaItems();
                        int itemIndex = exoPlayer.getCurrentMediaItemIndex();
                        long position = Math.max(0L, exoPlayer.getCurrentPosition());
                        boolean playWhenReady = exoPlayer.getPlayWhenReady();
                        Log.i("VideoCompatibility",
                                "Resetting to hardware-first video for new media item");
                        rebuildPlayerPreservingCompatibility(
                                items, itemIndex, position, playWhenReady);
                    });
                }
            }
            if (progressivePlaybackActive
                    && progressivePlaybackKey != null
                    && progressivePlaybackKey.equals(playbackKey)
                    && progressiveOriginalUri != null) {
                videoUri = progressiveOriginalUri;
            } else if (transitionedUri != null
                    && compatibilityPlaybackKey != null
                    && compatibilityPlaybackKey.equals(playbackKey)
                    && compatibilityVideoUri != null
                    && compatibilityVideoUri.equals(transitionedUri)
                    && compatibilityOriginalUri != null) {
                videoUri = compatibilityOriginalUri;
            } else if (transitionedUri != null) {
                videoUri = transitionedUri;
            }
            currentVideoAssessment = null;
            capabilityWarningLogged = false;
            tvVideoName.setText(resolveCurrentTitle());
        }

        @Override
        public void onPositionDiscontinuity(
                Player.PositionInfo oldPosition,
                Player.PositionInfo newPosition,
                int reason
        ) {
            savePositionInfo(oldPosition);
        }

        @Override
        public void onTracksChanged(Tracks tracks) {
            assessSelectedVideoTrack(tracks);
            restoreSelectedTracks(tracks);
        }

        @Override
        public void onPlaybackStateChanged(int playbackState) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING) {
                startFirstFrameWatchdogIfNeeded();
            } else {
                stopFirstFrameWatchdogTimer();
            }
        }

        @Override
        public void onPlayWhenReadyChanged(boolean playWhenReady, int reason) {
            if (playWhenReady) {
                startFirstFrameWatchdogIfNeeded();
            } else {
                stopFirstFrameWatchdogTimer();
            }
        }

        @Override
        public void onRenderedFirstFrame() {
            handleFirstVideoFrameRendered();
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            handlePlaybackError(error);
        }
    };

    private void handleFirstVideoFrameRendered() {
        boolean firstFrame = !firstVideoFrameRendered;
        firstVideoFrameRendered = true;
        stopFirstFrameWatchdogTimer();
        if (firstFrame && isObservingDirectSoftwareVideo() && !softwareStartupObserved) {
            // Decoder creation and seeking to the preceding keyframe are startup costs, not
            // sustained playback speed. Measure realtime progress from the first real frame.
            resetSoftwareObservationWindow(
                    SystemClock.elapsedRealtime(),
                    exoPlayer != null ? exoPlayer.getCurrentPosition() : 0L
            );
        }
        if (!startSoftwarePlaybackAfterFirstFrame
                || softwarePlaybackStartScheduled
                || exoPlayer == null) {
            return;
        }
        softwarePlaybackStartScheduled = true;
        ExoPlayer preparedPlayer = exoPlayer;
        // While paused the bounded decoder pool fills behind the first rendered frame. This short,
        // source-independent preroll prevents the audio clock from making startup video frames
        // late without changing Media3's ongoing A/V-sync or frame-drop policy.
        uiHandler.postDelayed(() -> {
            softwarePlaybackStartScheduled = false;
            if (exoPlayer == preparedPlayer && startSoftwarePlaybackAfterFirstFrame) {
                startSoftwarePlaybackAfterFirstFrame = false;
                resetSoftwareObservationWindow(
                        SystemClock.elapsedRealtime(),
                        preparedPlayer.getCurrentPosition()
                );
                preparedPlayer.setPlayWhenReady(true);
            }
        }, 250L);
    }

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        rotateButton = findViewById(R.id.btn_rotate);
        cropButton = findViewById(R.id.btn_crop);
        audioTrackButton = findViewById(R.id.audio_track);
        screenLockButton = findViewById(R.id.btn_screen_lock);

        brightnessOverlay = findViewById(R.id.brightness_overlay_container);
        brightnessProgressBar = findViewById(R.id.brightness_progress);
        brightnessIcon = findViewById(R.id.brightness_icon);
        volumeOverlay = findViewById(R.id.overlay_container);

        getWindow().getDecorView().post(() -> {
            int screenHeight = getWindow().getDecorView().getHeight();
            int targetMargin = screenHeight / 4;

            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams volLp =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                            volumeOverlay.getLayoutParams();
            volLp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            volLp.topMargin = targetMargin;
            volumeOverlay.setLayoutParams(volLp);

            androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams briLp =
                    (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                            brightnessOverlay.getLayoutParams();
            briLp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
            briLp.topMargin = targetMargin;
            brightnessOverlay.setLayoutParams(briLp);
        });

        volumeProgressBar = findViewById(R.id.volume_progress);
        volumeBoostProgressBar = findViewById(R.id.volume_boost_progress);
        volumeIcon = findViewById(R.id.volume_icon);
        tvVideoName = findViewById(R.id.tv_video_name);
        btnBack = findViewById(R.id.btn_back);

        btnBack.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            finish();
        });

        final View topBar = findViewById(R.id.top_bar);
        ViewCompat.setOnApplyWindowInsetsListener(topBar, (v, insets) -> {
            int sidePad = (int) (8 * getResources().getDisplayMetrics().density);
            int sidePadLandscape = (int) (24 * getResources().getDisplayMetrics().density);

            boolean isLandscape = getResources().getConfiguration().orientation
                    == android.content.res.Configuration.ORIENTATION_LANDSCAPE;

            if (isLandscape) {
                int topPad = (int) (12 * getResources().getDisplayMetrics().density);
                v.setPadding(sidePadLandscape, topPad, sidePadLandscape, 0);
                return insets;
            }

            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            if (statusBarHeight > 0) {
                topBar.setTag(statusBarHeight);
                v.setPadding(sidePad, statusBarHeight, sidePad, 0);
            } else {
                Object saved = topBar.getTag();
                if (saved instanceof Integer) {
                    v.setPadding(sidePad, (Integer) saved, sidePad, 0);
                } else {
                    int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
                    int h = id > 0 ? getResources().getDimensionPixelSize(id) : 0;
                    v.setPadding(sidePad, h, sidePad, 0);
                }
            }
            return insets;
        });
        topBar.requestApplyInsets();
        installScreenLockSafeInsets();

        videoUri = resolveVideoUri();
        if (videoUri == null) {
            Log.e("VideoError", "Invalid video URI");
            GlassUi.showToast(this, "Invalid video source.");
            finish();
            return;
        }

        playbackKey = resolvePlaybackKey(videoUri);
        videoResourceMonitor = new RuntimeVideoResourceMonitor(this);
        playlistTitles = getIntent().getStringArrayListExtra(EXTRA_PLAYLIST_TITLES);
        tvVideoName.setText(resolveDisplayTitle(videoUri));

        playerView.setControllerVisibilityListener(
                (PlayerView.ControllerVisibilityListener) visibility -> {
                    if (visibility == View.VISIBLE) {
                        syncCustomControls(true);
                    } else {
                        syncCustomControls(false);
                    }
                }
        );
        playerView.setOnPlayerUnlockedListener(this::showControls);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audioManager != null
                ? audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) : 1f;
        currentVolume = audioManager != null
                ? audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) : 0f;

        ArrayList<MediaItem> playlistItems = buildPlaylistItems();
        int startIndex = resolveStartIndex(playlistItems);
        long savedPosition = PlaybackPrefs.getInstance(this).getPosition(playbackKey);
        createPlayer(playlistItems, startIndex, savedPosition, true);

        currentBrightness = getWindow().getAttributes().screenBrightness;

        setupRotationButton();
        setupAudioTrackButton();
        setupCropButton();
        setupScreenLockButton();
        setupGestureDetection();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setupInteractionListeners();
        hideSystemUI();
        resetHideControlsTimer();
    }

    private void initializePlayerShell() {
        resetFirstFrameWatchdog();
        releasePlayerOnly();

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
        );

        exoPlayer = new ExoPlayer.Builder(
                this,
                PlaybackEnginePolicy.createRenderersFactory(
                        this,
                        decoderMode,
                        governedSoftwareVideoActive
                )
        )
                .setTrackSelector(trackSelector)
                .build();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        exoPlayer.setAudioAttributes(audioAttributes, true);
        exoPlayer.addListener(playbackListener);
        exoPlayer.addAnalyticsListener(videoPerformanceListener);
        playerView.setPlayer(exoPlayer);
    }

    private void createPlayer(
            List<MediaItem> mediaItems,
            int startIndex,
            long startPositionMs,
            boolean playWhenReady
    ) {
        initializePlayerShell();

        if (mediaItems == null || mediaItems.isEmpty()) {
            MediaItem mediaItem = new MediaItem.Builder()
                    .setUri(videoUri)
                    .setMediaId(playbackKey)
                    .build();
            exoPlayer.setMediaItem(mediaItem);
            if (startPositionMs > 0) {
                exoPlayer.seekTo(startPositionMs);
            }
        } else {
            int safeIndex = Math.max(0, Math.min(startIndex, mediaItems.size() - 1));
            exoPlayer.setMediaItems(mediaItems, safeIndex, C.TIME_UNSET);
            if (startPositionMs > 0) {
                exoPlayer.seekTo(safeIndex, startPositionMs);
            }
        }

        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(playWhenReady);
        applyVolumeBoost();
        startSoftwareObservationIfNeeded();
    }

    private void createPlayerWithCompatibilityVideo(
            List<MediaItem> mediaItems,
            int startIndex,
            long startPositionMs,
            boolean playWhenReady,
            Uri originalUri,
            Uri compatibleVideoUri,
            String logicalPlaybackKey
    ) {
        if (mediaItems == null || mediaItems.isEmpty()) {
            createPlayer(mediaItems, startIndex, startPositionMs, playWhenReady);
            return;
        }

        compatibilityOriginalUri = originalUri;
        compatibilityVideoUri = compatibleVideoUri;
        compatibilityPlaybackKey = logicalPlaybackKey;
        videoUri = originalUri;
        playbackKey = logicalPlaybackKey;

        initializePlayerShell();
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(this);
        ArrayList<MediaSource> sources = new ArrayList<>();
        int safeIndex = Math.max(0, Math.min(startIndex, mediaItems.size() - 1));
        for (int i = 0; i < mediaItems.size(); i++) {
            MediaItem item = mediaItems.get(i);
            if (i == safeIndex) {
                sources.add(buildCompatibilityMediaSource(
                        sourceFactory,
                        item,
                        compatibleVideoUri
                ));
            } else {
                sources.add(sourceFactory.createMediaSource(item));
            }
        }

        exoPlayer.setMediaSources(
                sources,
                safeIndex,
                startPositionMs > 0 ? startPositionMs : C.TIME_UNSET
        );
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(playWhenReady);
        applyVolumeBoost();
        stopSoftwareObservation();
    }

    private MediaSource buildCompatibilityMediaSource(
            DefaultMediaSourceFactory sourceFactory,
            MediaItem originalItem,
            Uri compatibleVideoUri
    ) {
        MediaItem compatibleVideoItem = new MediaItem.Builder()
                .setUri(compatibleVideoUri)
                .setMediaId(originalItem.mediaId)
                .build();
        MediaSource compatibleVideoSource = new FilteringMediaSource(
                sourceFactory.createMediaSource(compatibleVideoItem),
                C.TRACK_TYPE_VIDEO
        );

        Set<Integer> originalTrackTypes = new HashSet<>();
        originalTrackTypes.add(C.TRACK_TYPE_AUDIO);
        originalTrackTypes.add(C.TRACK_TYPE_TEXT);
        originalTrackTypes.add(C.TRACK_TYPE_METADATA);
        MediaSource originalNonVideoSource = new FilteringMediaSource(
                sourceFactory.createMediaSource(originalItem),
                originalTrackTypes
        );

        return new MergingMediaSource(
                true,
                true,
                compatibleVideoSource,
                originalNonVideoSource
        );
    }

    private void rebuildPlayerPreservingCompatibility(
            List<MediaItem> items,
            int itemIndex,
            long position,
            boolean playWhenReady
    ) {
        if (compatibilityVideoUri != null
                && compatibilityOriginalUri != null
                && compatibilityPlaybackKey != null
                && compatibilityPlaybackKey.equals(playbackKey)) {
            createPlayerWithCompatibilityVideo(
                    items,
                    itemIndex,
                    position,
                    playWhenReady,
                    compatibilityOriginalUri,
                    compatibilityVideoUri,
                    compatibilityPlaybackKey
            );
        } else {
            createPlayer(items, itemIndex, position, playWhenReady);
        }
    }

    private boolean isObservingDirectSoftwareVideo() {
        return exoPlayer != null
                && decoderMode.preferSoftwareVideo
                && !governorHandoffStarted
                && (compatibilityPlaybackKey == null
                || !compatibilityPlaybackKey.equals(playbackKey));
    }

    private void startSoftwareObservationIfNeeded() {
        stopSoftwareObservation();
        if (!isObservingDirectSoftwareVideo()) {
            return;
        }
        softwareObservationStartMs = SystemClock.elapsedRealtime();
        softwareObservationStartPositionMs = Math.max(0L, exoPlayer.getCurrentPosition());
        uiHandler.postDelayed(softwareGovernorRunnable, 1_000L);
    }

    private void stopSoftwareObservation() {
        uiHandler.removeCallbacks(softwareGovernorRunnable);
        softwareObservationStartMs = 0L;
        softwareObservationStartPositionMs = 0L;
        softwareRenderedFrames = 0;
        softwareDroppedFrames = 0;
        softwareStartupObserved = false;
    }

    private boolean isWatchingHardwareVideo() {
        return exoPlayer != null
                && softwareRecoveryVideoFormat != null
                && !decoderMode.preferSoftwareVideo
                && !progressivePlaybackActive
                && (compatibilityPlaybackKey == null
                || !compatibilityPlaybackKey.equals(playbackKey));
    }

    private void startFirstFrameWatchdogIfNeeded() {
        if (!isWatchingHardwareVideo()
                || firstVideoFrameRendered
                || !exoPlayer.getPlayWhenReady()) {
            return;
        }
        String currentKey = playbackKey;
        if (firstFrameWatchStartMs == 0L
                || currentKey == null
                || !currentKey.equals(firstFrameWatchPlaybackKey)) {
            firstFrameWatchStartMs = SystemClock.elapsedRealtime();
            firstFrameWatchStartPositionMs = Math.max(0L, exoPlayer.getCurrentPosition());
            firstFrameWatchPlaybackKey = currentKey;
        }
        uiHandler.removeCallbacks(firstFrameWatchdogRunnable);
        uiHandler.postDelayed(firstFrameWatchdogRunnable, 1_000L);
    }

    private void stopFirstFrameWatchdogTimer() {
        uiHandler.removeCallbacks(firstFrameWatchdogRunnable);
        firstFrameWatchStartMs = 0L;
        firstFrameWatchStartPositionMs = 0L;
        firstFrameWatchPlaybackKey = null;
    }

    private void resetFirstFrameWatchdog() {
        stopFirstFrameWatchdogTimer();
        firstVideoFrameRendered = false;
    }

    private void evaluateFirstVideoFrame() {
        if (!isWatchingHardwareVideo() || firstVideoFrameRendered) {
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        long elapsedMs = Math.max(0L, nowMs - firstFrameWatchStartMs);
        long mediaProgressMs = Math.max(
                0L,
                exoPlayer.getCurrentPosition() - firstFrameWatchStartPositionMs
        );
        boolean suppressed = exoPlayer.getPlaybackSuppressionReason()
                != Player.PLAYBACK_SUPPRESSION_REASON_NONE;
        if (suppressed || !exoPlayer.getPlayWhenReady()) {
            firstFrameWatchStartMs = nowMs;
            firstFrameWatchStartPositionMs = Math.max(0L, exoPlayer.getCurrentPosition());
            uiHandler.postDelayed(firstFrameWatchdogRunnable, 1_000L);
            return;
        }
        if (!PlaybackEnginePolicy.shouldRecoverFromMissingFirstFrame(
                true,
                false,
                exoPlayer.getPlayWhenReady(),
                suppressed,
                exoPlayer.getPlaybackState(),
                elapsedMs,
                mediaProgressMs
        )) {
            uiHandler.postDelayed(firstFrameWatchdogRunnable, 1_000L);
            return;
        }

        Format failedVideo = softwareRecoveryVideoFormat;
        hardwareVideoFailureObserved = true;
        stopFirstFrameWatchdogTimer();
        Log.w("VideoCapability",
                "Selected platform decoder produced no first video frame; "
                        + "using runtime recovery for " + failedVideo);

        if (!softwareVideoRecoveryAttempted
                && !CineFfmpegLibrary.isDolbyVisionFormat(
                failedVideo.sampleMimeType,
                failedVideo.codecs
        )
                && PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder(
                failedVideo.sampleMimeType
        )) {
            softwareVideoRecoveryAttempted = true;
            governedSoftwareVideoActive =
                    PlaybackEnginePolicy.shouldUseGovernedFastVideoDecode(failedVideo);
            decoderMode = decoderMode.withSoftwareVideo();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer.getCurrentMediaItemIndex();
            long position = Math.max(0L, exoPlayer.getCurrentPosition());
            boolean playWhenReady = exoPlayer.getPlayWhenReady();
            Log.w("VideoCapability", "Retrying silent platform failure with CineFFmpeg");
            softwarePlaybackStartScheduled = false;
            startSoftwarePlaybackAfterFirstFrame = playWhenReady;
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, false);
            return;
        }

        if (!compatibilityTranscodeAttempted
                && CineFfmpegLibrary.supportsTransformerMimeType(
                failedVideo.sampleMimeType
        )) {
            compatibilityTranscodeAttempted = true;
            startCompatibilityRecovery(failedVideo);
        }
    }

    private void resetSoftwareObservationWindow(long nowMs, long mediaPositionMs) {
        softwareObservationStartMs = nowMs;
        softwareObservationStartPositionMs = Math.max(0L, mediaPositionMs);
        softwareRenderedFrames = 0;
        softwareDroppedFrames = 0;
    }

    private void evaluateDirectSoftwarePlayback() {
        if (!isObservingDirectSoftwareVideo() || videoResourceMonitor == null) {
            return;
        }
        if (!exoPlayer.getPlayWhenReady()) {
            resetSoftwareObservationWindow(
                    SystemClock.elapsedRealtime(),
                    exoPlayer.getCurrentPosition()
            );
            uiHandler.postDelayed(softwareGovernorRunnable, 1_000L);
            return;
        }

        Format format = softwareRecoveryVideoFormat;
        if (format == null) {
            uiHandler.postDelayed(softwareGovernorRunnable, 1_000L);
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        long elapsedMs = Math.max(0L, nowMs - softwareObservationStartMs);
        long requiredWindowMs = softwareStartupObserved
                ? VideoResourceGovernor.SOFTWARE_RECHECK_MS
                : VideoResourceGovernor.SOFTWARE_STARTUP_GRACE_MS;
        if (elapsedMs < requiredWindowMs) {
            uiHandler.postDelayed(
                    softwareGovernorRunnable,
                    Math.min(1_000L, requiredWindowMs - elapsedMs)
            );
            return;
        }

        long mediaProgressMs = Math.max(
                0L,
                exoPlayer.getCurrentPosition() - softwareObservationStartPositionMs
        );
        VideoResourceGovernor.Snapshot snapshot = videoResourceMonitor.capture(
                format,
                currentVideoAssessment,
                hardwareVideoFailureObserved
        );
        VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                snapshot,
                new VideoResourceGovernor.Observation(
                        elapsedMs,
                        mediaProgressMs,
                        softwareRenderedFrames,
                        softwareDroppedFrames,
                        softwareStartupObserved,
                        firstVideoFrameRendered
                )
        );
        compatibilityCeiling = decision.compatibilityCeiling(
                format.width,
                format.height,
                format.frameRate
        );
        Log.i("VideoResourceGovernor",
                "ceiling=" + decision.ceiling + " " + decision.reason);

        if (!decision.directSoftwareSustainable
                && !compatibilityTranscodeAttempted
                && CineFfmpegLibrary.supportsTransformerMimeType(format.sampleMimeType)) {
            governorHandoffStarted = true;
            compatibilityTranscodeAttempted = true;
            hardwareVideoFailureObserved = true;
            Log.w("VideoResourceGovernor",
                    "Direct software playback handed off to compatibility",
                    new VideoResourceGovernor.HandoffException(decision.reason));
            startCompatibilityRecovery(format);
            return;
        }

        softwareStartupObserved = true;
        resetSoftwareObservationWindow(nowMs, exoPlayer.getCurrentPosition());
        uiHandler.postDelayed(
                softwareGovernorRunnable,
                VideoResourceGovernor.SOFTWARE_RECHECK_MS
        );
    }

    private void handlePlaybackError(PlaybackException error) {
        Log.e("VideoPlayer", "Playback failed: " + error.getErrorCodeName(), error);

        Format failedVideo = PlaybackEnginePolicy.getFailedVideoFormat(error);
        boolean platformVideoRuntimeFailure = PlaybackEnginePolicy.isPlatformVideoRuntimeFailure(
                error,
                softwareRecoveryVideoFormat
        );
        if (failedVideo == null && platformVideoRuntimeFailure) {
            failedVideo = softwareRecoveryVideoFormat;
        }
        boolean recoverableVideoFailure = PlaybackEnginePolicy.isVideoDecoderFailure(error)
                || platformVideoRuntimeFailure;
        if (failedVideo != null && recoverableVideoFailure) {
            softwareRecoveryVideoFormat = failedVideo;
            if (!decoderMode.preferSoftwareVideo) {
                hardwareVideoFailureObserved = true;
            }
        }

        if (PlaybackEnginePolicy.isGovernorHandoff(error)
                && !compatibilityTranscodeAttempted
                && softwareRecoveryVideoFormat != null) {
            compatibilityTranscodeAttempted = true;
            governorHandoffStarted = true;
            startCompatibilityRecovery(softwareRecoveryVideoFormat);
            return;
        }

        if (PlaybackEnginePolicy.shouldRetryWithSoftwareAudio(decoderMode, error)) {
            decoderMode = decoderMode.withSoftwareAudio();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();

            Log.w("VideoPlayer", "Retrying with FFmpeg audio renderer at " + position + " ms");
            GlassUi.showToast(this, "Switching to compatibility audio decoder…");
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, playWhenReady);
            return;
        }

        boolean runtimeSoftwareVideoRetry = platformVideoRuntimeFailure
                && failedVideo != null
                && !decoderMode.preferSoftwareVideo
                && !CineFfmpegLibrary.isDolbyVisionFormat(
                failedVideo.sampleMimeType,
                failedVideo.codecs
        )
                && PlaybackEnginePolicy.hasBundledSoftwareVideoDecoder(
                failedVideo.sampleMimeType
        );
        if (PlaybackEnginePolicy.shouldRetryWithSoftwareVideo(decoderMode, error)
                || runtimeSoftwareVideoRetry) {
            softwareVideoRecoveryAttempted = true;
            governedSoftwareVideoActive =
                    PlaybackEnginePolicy.shouldUseGovernedFastVideoDecode(failedVideo);
            decoderMode = decoderMode.withSoftwareVideo();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();

            Log.w("VideoPlayer",
                    "Retrying with bundled software video renderer for "
                            + (failedVideo != null ? failedVideo.sampleMimeType : "video")
                            + " at " + position + " ms");
            softwarePlaybackStartScheduled = false;
            startSoftwarePlaybackAfterFirstFrame = playWhenReady;
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, false);
            return;
        }

        DeviceVideoCapabilities.Assessment assessment = currentVideoAssessment;
        if (failedVideo != null
                && (assessment == null
                || assessment.support == DeviceVideoCapabilities.Support.UNKNOWN)) {
            assessment = DeviceVideoCapabilities.assess(failedVideo);
            currentVideoAssessment = assessment;
        }

        boolean canCreateCompatibilityVideo = PlaybackEnginePolicy.shouldAllowCompatibilityRecovery(
                hardwareVideoFailureObserved,
                assessment != null ? assessment.support : DeviceVideoCapabilities.Support.UNKNOWN,
                CineFfmpegLibrary.supportsTransformerMimeType(
                        failedVideo != null ? failedVideo.sampleMimeType : null
                )
        );

        if (!compatibilityTranscodeAttempted
                && recoverableVideoFailure
                && failedVideo != null
                && failedVideo.width > 0
                && failedVideo.height > 0
                && canCreateCompatibilityVideo) {
            compatibilityTranscodeAttempted = true;
            startCompatibilityRecovery(failedVideo);
            return;
        }

        if (assessment != null
                && assessment.support == DeviceVideoCapabilities.Support.NO_PLATFORM_DECODER) {
            GlassUi.showToast(this,
                    "No compatible video decoder is available on this device.");
            return;
        }
        if (assessment != null
                && assessment.support
                == DeviceVideoCapabilities.Support.EXCEEDS_REPORTED_CAPABILITY) {
            GlassUi.showToast(this,
                    "This device could not create or play a compatible version of this video.");
            return;
        }

        GlassUi.showToast(this, "Playback failed: " + error.getErrorCodeName());
    }

    private ArrayList<MediaItem> snapshotMediaItems() {
        if (progressivePlaybackActive && progressiveOriginalItems != null) {
            return new ArrayList<>(progressiveOriginalItems);
        }
        ArrayList<MediaItem> items = new ArrayList<>();
        if (exoPlayer == null) {
            return items;
        }
        for (int i = 0; i < exoPlayer.getMediaItemCount(); i++) {
            MediaItem item = exoPlayer.getMediaItemAt(i);
            if (compatibilityPlaybackKey != null
                    && compatibilityPlaybackKey.equals(item.mediaId)
                    && item.localConfiguration != null
                    && compatibilityVideoUri != null
                    && compatibilityVideoUri.equals(item.localConfiguration.uri)
                    && compatibilityOriginalUri != null) {
                item = new MediaItem.Builder()
                        .setUri(compatibilityOriginalUri)
                        .setMediaId(item.mediaId)
                        .build();
            }
            items.add(item);
        }
        return items;
    }

    private void startCompatibilityRecovery(Format failedVideo) {
        if (videoUri == null
                || playbackKey == null
                || progressiveFallbackStarted
                || compatibilityCacheLookupPending) {
            return;
        }
        if (progressiveCompatibilityHandle != null) {
            return;
        }

        if (compatibilityCeiling == null && videoResourceMonitor != null) {
            VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                    videoResourceMonitor.capture(
                            failedVideo,
                            currentVideoAssessment,
                            hardwareVideoFailureObserved
                    ),
                    null
            );
            compatibilityCeiling = decision.compatibilityCeiling(
                    failedVideo.width,
                    failedVideo.height,
                    failedVideo.frameRate
            );
        }

        final Uri sourceUri = videoUri;
        final String sourceKey = playbackKey;
        final boolean forceSourceFfmpegDecoder = hardwareVideoFailureObserved
                || CineFfmpegLibrary.isDolbyVisionFormat(
                failedVideo.sampleMimeType,
                failedVideo.codecs
        );
        final ArrayList<MediaItem> recoveryItems = snapshotMediaItems();
        final int recoveryIndex = progressivePlaybackActive
                ? progressiveOriginalIndex
                : (exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0);
        final long recoveryPosition = currentLogicalPlaybackPosition();
        final boolean recoveryPlayWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();
        if (recoveryItems.isEmpty()) {
            recoveryItems.add(new MediaItem.Builder()
                    .setUri(sourceUri)
                    .setMediaId(sourceKey)
                    .build());
        }

        compatibilityCacheLookupPending = true;
        AppExecutors.playbackRecovery().execute(() -> {
            File cachedVideo = CompatibilityVideoTranscoder.findCachedVideoForPlayback(
                    VideoPlayerActivity.this,
                    sourceUri,
                    forceSourceFfmpegDecoder
            );
            uiHandler.post(() -> {
                compatibilityCacheLookupPending = false;
                if (isFinishing()
                        || isDestroyed()
                        || playbackKey == null
                        || !sourceKey.equals(playbackKey)
                        || videoUri == null
                        || !sourceUri.equals(videoUri)) {
                    return;
                }
                if (cachedVideo != null) {
                    Log.i("VideoCompatibility", "Starting completed compatibility cache");
                    startCachedCompatibilityPlayback(
                            cachedVideo,
                            recoveryItems,
                            recoveryIndex,
                            recoveryPosition,
                            recoveryPlayWhenReady,
                            sourceUri,
                            sourceKey
                    );
                    return;
                }
                // Partial segment sources currently expose only their generated prefix as the
                // item duration and may enable merged children at different positions. Keep the
                // original coherent timeline and use the proven completed-file fallback until
                // progressive video is represented as one stable Media3 period.
                startFullFileCompatibilityRecovery(failedVideo);
            });
        });
    }

    private void startCachedCompatibilityPlayback(
            File cachedVideo,
            ArrayList<MediaItem> recoveryItems,
            int recoveryIndex,
            long recoveryPosition,
            boolean recoveryPlayWhenReady,
            Uri sourceUri,
            String sourceKey
    ) {
        decoderMode = decoderMode.withoutSoftwareVideo();
        governedSoftwareVideoActive = false;
        stopSoftwareObservation();
        clearProgressivePlaybackState();
        releasePlayerOnly();
        Uri compatibleUri = Uri.fromFile(cachedVideo);
        compatibilityOriginalUri = sourceUri;
        compatibilityVideoUri = compatibleUri;
        compatibilityPlaybackKey = sourceKey;
        videoUri = sourceUri;
        playbackKey = sourceKey;
        createPlayerWithCompatibilityVideo(
                recoveryItems,
                recoveryIndex,
                recoveryPosition,
                recoveryPlayWhenReady,
                sourceUri,
                compatibleUri,
                sourceKey
        );
    }

    private void startProgressiveCompatibilityGeneration(
            Format failedVideo,
            ArrayList<MediaItem> recoveryItems,
            int recoveryIndex,
            long recoveryPosition,
            boolean recoveryPlayWhenReady,
            Uri sourceUri,
            String sourceKey,
            boolean forceSourceFfmpegDecoder
    ) {
        decoderMode = decoderMode.withoutSoftwareVideo();
        governedSoftwareVideoActive = false;
        stopSoftwareObservation();
        progressiveFallbackStarted = false;
        progressiveCompatibilityHandle = ProgressiveCompatibilityManager.start(
                this,
                sourceUri,
                failedVideo.width,
                failedVideo.height,
                failedVideo.frameRate,
                failedVideo.sampleMimeType,
                failedVideo.codecs,
                compatibilityCeiling,
                forceSourceFfmpegDecoder,
                new ProgressiveCompatibilityManager.Listener() {
                    @Override
                    public void onInitialSegments(
                            List<ProgressiveCompatibilityManager.Segment> segments,
                            boolean entirelyFromCache
                    ) {
                        if (isFinishing() || isDestroyed() || progressiveFallbackStarted) {
                            return;
                        }
                        Log.i("ProgressiveCompatibility",
                                "Starting from " + segments.size() + " validated segments"
                                        + (entirelyFromCache ? " in cache" : ""));
                        createPlayerWithProgressiveVideo(
                                recoveryItems,
                                recoveryIndex,
                                recoveryPosition,
                                recoveryPlayWhenReady,
                                sourceUri,
                                sourceKey,
                                failedVideo,
                                segments
                        );
                    }

                    @Override
                    public void onSegmentReady(ProgressiveCompatibilityManager.Segment segment) {
                        if (!isFinishing() && !isDestroyed() && !progressiveFallbackStarted) {
                            appendProgressiveSegment(segment, sourceUri, sourceKey);
                        }
                    }

                    @Override
                    public void onCompleted(
                            List<ProgressiveCompatibilityManager.Segment> segments
                    ) {
                        finishProgressiveSequence();
                        if (progressiveCompatibilityHandle != null) {
                            progressiveCompatibilityHandle.detach();
                            progressiveCompatibilityHandle = null;
                        }
                        Log.i("ProgressiveCompatibility",
                                "Validated progressive cache is complete: " + segments.size());
                    }

                    @Override
                    public void onFailed(String message, Throwable error) {
                        if (progressiveFallbackStarted || isFinishing() || isDestroyed()) {
                            return;
                        }
                        progressiveFallbackStarted = true;
                        if (error != null) {
                            Log.w("ProgressiveCompatibility", message, error);
                        } else {
                            Log.w("ProgressiveCompatibility", message);
                        }
                        if (progressiveCompatibilityHandle != null) {
                            progressiveCompatibilityHandle.detach();
                            progressiveCompatibilityHandle = null;
                        }
                        startFullFileCompatibilityRecovery(failedVideo);
                    }
                }
        );
    }

    private void startFullFileCompatibilityRecovery(Format failedVideo) {
        if (videoUri == null || playbackKey == null) {
            GlassUi.showToast(this, "Unable to resolve the original video for compatibility mode.");
            return;
        }

        ArrayList<MediaItem> items = snapshotMediaItems();
        int itemIndex = progressivePlaybackActive
                ? progressiveOriginalIndex
                : (exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0);
        long position = currentLogicalPlaybackPosition();
        boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();
        Uri sourceUri = videoUri;
        String sourceKey = playbackKey;

        if (items.isEmpty()) {
            items.add(new MediaItem.Builder()
                    .setUri(sourceUri)
                    .setMediaId(sourceKey)
                    .build());
            itemIndex = 0;
        }

        final ArrayList<MediaItem> recoveryItems = items;
        final int recoveryIndex = itemIndex;
        final long recoveryPosition = position;
        final boolean recoveryPlayWhenReady = playWhenReady;

        if (compatibilityCeiling == null && videoResourceMonitor != null) {
            VideoResourceGovernor.Decision decision = VideoResourceGovernor.evaluate(
                    videoResourceMonitor.capture(
                            failedVideo,
                            currentVideoAssessment,
                            hardwareVideoFailureObserved
                    ),
                    null
            );
            compatibilityCeiling = decision.compatibilityCeiling(
                    failedVideo.width,
                    failedVideo.height,
                    failedVideo.frameRate
            );
        }

        decoderMode = decoderMode.withoutSoftwareVideo();
        governedSoftwareVideoActive = false;
        stopSoftwareObservation();
        clearProgressivePlaybackState();
        // A device that cannot sustain the source must not run the same expensive decoder for
        // playback and export concurrently. Stop releases that decoder while the SurfaceView
        // retains its last submitted buffer; the ready callback replaces the player atomically.
        if (exoPlayer != null) {
            exoPlayer.stop();
        }
        compatibilityTranscodeSession = CompatibilityVideoTranscoder.start(
                this,
                sourceUri,
                failedVideo.width,
                failedVideo.height,
                failedVideo.frameRate,
                failedVideo.sampleMimeType,
                failedVideo.codecs,
                compatibilityCeiling,
                hardwareVideoFailureObserved,
                new CompatibilityVideoTranscoder.Callback() {
                    @Override
                    public void onReady(
                            File file,
                            CompatibilityVideoPolicy.Target target,
                            boolean fromCache
                    ) {
                        compatibilityTranscodeSession = null;
                        if (isFinishing() || isDestroyed()) {
                            return;
                        }
                        Uri compatibleUri = Uri.fromFile(file);
                        compatibilityOriginalUri = sourceUri;
                        compatibilityVideoUri = compatibleUri;
                        compatibilityPlaybackKey = sourceKey;
                        videoUri = sourceUri;
                        playbackKey = sourceKey;
                        Log.i("VideoCompatibility",
                                (fromCache ? "Using cached " : "Created ")
                                        + "H.264 compatibility video " + target);
                        releasePlayerOnly();
                        createPlayerWithCompatibilityVideo(
                                recoveryItems,
                                recoveryIndex,
                                recoveryPosition,
                                recoveryPlayWhenReady,
                                sourceUri,
                                compatibleUri,
                                sourceKey
                        );
                    }

                    @Override
                    public void onError(String message, Throwable error) {
                        compatibilityTranscodeSession = null;
                        if (error != null) {
                            Log.e("VideoCompatibility", message, error);
                        } else {
                            Log.w("VideoCompatibility", message);
                        }
                        if (recoveryPlayWhenReady) {
                            decoderMode = decoderMode.withSoftwareVideo();
                            governedSoftwareVideoActive =
                                    PlaybackEnginePolicy.shouldUseGovernedFastVideoDecode(
                                            failedVideo
                                    );
                            rebuildPlayerPreservingCompatibility(
                                    recoveryItems,
                                    recoveryIndex,
                                    recoveryPosition,
                                    true
                            );
                        }
                        if (!isFinishing() && !isDestroyed()) {
                            GlassUi.showToast(
                                    VideoPlayerActivity.this,
                                    "Unable to create a compatible video on this device."
                            );
                        }
                    }
                }
        );
    }

    private void createPlayerWithProgressiveVideo(
            List<MediaItem> mediaItems,
            int originalIndex,
            long originalPositionMs,
            boolean playWhenReady,
            Uri originalUri,
            String logicalPlaybackKey,
            Format failedVideo,
            List<ProgressiveCompatibilityManager.Segment> initialSegments
    ) {
        if (mediaItems == null || mediaItems.isEmpty() || initialSegments.isEmpty()) {
            startFullFileCompatibilityRecovery(failedVideo);
            return;
        }

        int safeOriginalIndex = Math.max(0, Math.min(originalIndex, mediaItems.size() - 1));
        releasePlayerOnly();
        progressiveOriginalItems = new ArrayList<>(mediaItems);
        progressiveOriginalIndex = safeOriginalIndex;
        progressiveOriginalUri = originalUri;
        progressivePlaybackKey = logicalPlaybackKey;
        progressiveOriginalDurationMs = CompatibilityVideoTranscoder.readDurationMs(
                this,
                originalUri
        );
        progressiveSegments.clear();
        progressiveSegments.addAll(initialSegments);
        progressiveTrailingItemsAdded = false;
        progressivePlaybackActive = true;
        compatibilityOriginalUri = originalUri;
        compatibilityVideoUri = null;
        compatibilityPlaybackKey = logicalPlaybackKey;
        videoUri = originalUri;
        playbackKey = logicalPlaybackKey;

        initializePlayerShell();
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(this);
        ConcatenatingMediaSource sequence = new ConcatenatingMediaSource();
        for (int i = 0; i < safeOriginalIndex; i++) {
            sequence.addMediaSource(sourceFactory.createMediaSource(mediaItems.get(i)));
        }
        MediaItem originalItem = mediaItems.get(safeOriginalIndex);
        for (ProgressiveCompatibilityManager.Segment segment : initialSegments) {
            sequence.addMediaSource(buildProgressiveSegmentMediaSource(
                    sourceFactory,
                    originalItem,
                    originalUri,
                    logicalPlaybackKey,
                    segment
            ));
        }
        progressiveInsertIndex = safeOriginalIndex + initialSegments.size();
        progressiveMediaSequence = sequence;
        playerView.setShowMultiWindowTimeBar(mediaItems.size() == 1);

        int segmentIndex = findProgressiveSegmentForPosition(originalPositionMs);
        ProgressiveCompatibilityManager.Segment startSegment =
                progressiveSegments.get(segmentIndex);
        long positionInSegmentMs = Math.max(
                0L,
                Math.min(
                        originalPositionMs - startSegment.window.startMs,
                        Math.max(0L, startSegment.window.durationMs() - 1L)
                )
        );
        exoPlayer.setMediaSource(sequence);
        exoPlayer.seekTo(safeOriginalIndex + segmentIndex, positionInSegmentMs);
        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(playWhenReady);
        applyVolumeBoost();
        stopSoftwareObservation();
    }

    private MediaSource buildProgressiveSegmentMediaSource(
            DefaultMediaSourceFactory sourceFactory,
            MediaItem originalItem,
            Uri originalUri,
            String logicalPlaybackKey,
            ProgressiveCompatibilityManager.Segment segment
    ) {
        MediaItem videoItem = new MediaItem.Builder()
                .setUri(Uri.fromFile(segment.file))
                .setMediaId(logicalPlaybackKey)
                .build();
        MediaSource videoSource = new FilteringMediaSource(
                sourceFactory.createMediaSource(videoItem),
                C.TRACK_TYPE_VIDEO
        );

        MediaItem originalClip = originalItem.buildUpon()
                .setUri(originalUri)
                .setMediaId(logicalPlaybackKey)
                .setClippingConfiguration(
                        new MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(segment.window.startMs)
                                .setEndPositionMs(segment.window.endMs)
                                .build()
                )
                .build();
        Set<Integer> originalTrackTypes = new HashSet<>();
        originalTrackTypes.add(C.TRACK_TYPE_AUDIO);
        originalTrackTypes.add(C.TRACK_TYPE_TEXT);
        originalTrackTypes.add(C.TRACK_TYPE_METADATA);
        MediaSource originalNonVideoSource = new FilteringMediaSource(
                sourceFactory.createMediaSource(originalClip),
                originalTrackTypes
        );

        return new MergingMediaSource(
                true,
                true,
                videoSource,
                originalNonVideoSource
        );
    }

    private void appendProgressiveSegment(
            ProgressiveCompatibilityManager.Segment segment,
            Uri originalUri,
            String logicalPlaybackKey
    ) {
        if (!progressivePlaybackActive
                || progressiveMediaSequence == null
                || progressiveOriginalItems == null
                || progressivePlaybackKey == null
                || !progressivePlaybackKey.equals(logicalPlaybackKey)) {
            return;
        }
        if (!progressiveSegments.isEmpty()) {
            ProgressiveCompatibilityManager.Segment previous =
                    progressiveSegments.get(progressiveSegments.size() - 1);
            if (segment.window.index <= previous.window.index) {
                return;
            }
        }
        MediaItem originalItem = progressiveOriginalItems.get(progressiveOriginalIndex);
        MediaSource mediaSource = buildProgressiveSegmentMediaSource(
                new DefaultMediaSourceFactory(this),
                originalItem,
                originalUri,
                logicalPlaybackKey,
                segment
        );
        boolean resumeFromExhaustedBuffer = exoPlayer != null
                && exoPlayer.getPlaybackState() == Player.STATE_ENDED;
        int insertedIndex = progressiveInsertIndex;
        progressiveSegments.add(segment);
        progressiveMediaSequence.addMediaSource(progressiveInsertIndex, mediaSource);
        progressiveInsertIndex++;
        if (resumeFromExhaustedBuffer && exoPlayer != null) {
            exoPlayer.seekTo(insertedIndex, 0L);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
        }
    }

    private void finishProgressiveSequence() {
        if (!progressivePlaybackActive
                || progressiveTrailingItemsAdded
                || progressiveMediaSequence == null
                || progressiveOriginalItems == null) {
            return;
        }
        DefaultMediaSourceFactory sourceFactory = new DefaultMediaSourceFactory(this);
        boolean hasTrailingItems = progressiveOriginalIndex + 1 < progressiveOriginalItems.size();
        boolean resumeFromCompletedVideo = hasTrailingItems
                && exoPlayer != null
                && exoPlayer.getPlaybackState() == Player.STATE_ENDED;
        int firstTrailingIndex = progressiveInsertIndex;
        for (int i = progressiveOriginalIndex + 1; i < progressiveOriginalItems.size(); i++) {
            progressiveMediaSequence.addMediaSource(
                    sourceFactory.createMediaSource(progressiveOriginalItems.get(i))
            );
        }
        progressiveTrailingItemsAdded = true;
        if (resumeFromCompletedVideo && exoPlayer != null) {
            exoPlayer.seekTo(firstTrailingIndex, C.TIME_UNSET);
            exoPlayer.prepare();
            exoPlayer.setPlayWhenReady(true);
        }
    }

    private int findProgressiveSegmentForPosition(long positionMs) {
        long safePositionMs = Math.max(0L, positionMs);
        for (int i = 0; i < progressiveSegments.size(); i++) {
            ProgressiveCompatibilityManager.Segment segment = progressiveSegments.get(i);
            if (safePositionMs < segment.window.endMs) {
                return i;
            }
        }
        return Math.max(0, progressiveSegments.size() - 1);
    }

    private long currentLogicalPlaybackPosition() {
        if (exoPlayer == null) {
            return 0L;
        }
        if (!progressivePlaybackActive) {
            return Math.max(0L, exoPlayer.getCurrentPosition());
        }
        int segmentIndex = exoPlayer.getCurrentMediaItemIndex() - progressiveOriginalIndex;
        if (segmentIndex >= 0 && segmentIndex < progressiveSegments.size()) {
            return progressiveSegments.get(segmentIndex).window.startMs
                    + Math.max(0L, exoPlayer.getCurrentPosition());
        }
        return Math.max(0L, exoPlayer.getCurrentPosition());
    }

    private long currentLogicalDuration() {
        if (progressivePlaybackActive && progressiveOriginalDurationMs > 0L) {
            return progressiveOriginalDurationMs;
        }
        return exoPlayer != null ? exoPlayer.getDuration() : C.TIME_UNSET;
    }

    private void clearProgressivePlaybackState() {
        if (progressiveCompatibilityHandle != null) {
            progressiveCompatibilityHandle.detach();
            progressiveCompatibilityHandle = null;
        }
        progressiveMediaSequence = null;
        progressiveSegments.clear();
        progressiveOriginalItems = null;
        progressiveOriginalIndex = 0;
        progressiveInsertIndex = 0;
        progressiveTrailingItemsAdded = false;
        progressiveOriginalDurationMs = 0L;
        progressiveOriginalUri = null;
        progressivePlaybackKey = null;
        progressivePlaybackActive = false;
        progressiveFallbackStarted = false;
        if (playerView != null) {
            playerView.setShowMultiWindowTimeBar(false);
        }
    }

    private void assessSelectedVideoTrack(Tracks tracks) {
        if (tracks == null) {
            return;
        }
        Format recoverableUnselectedVideo = null;
        int recoverableTrackSupport = C.FORMAT_UNSUPPORTED_TYPE;
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() != C.TRACK_TYPE_VIDEO) {
                continue;
            }
            TrackGroup mediaTrackGroup = group.getMediaTrackGroup();
            for (int i = 0; i < group.length; i++) {
                if (!group.isTrackSelected(i)) {
                    Format format = mediaTrackGroup.getFormat(i);
                    if (recoverableUnselectedVideo == null
                            && !group.isTrackSupported(i, true)
                            && CineFfmpegLibrary.supportsTransformerMimeType(
                            format.sampleMimeType)) {
                        recoverableUnselectedVideo = format;
                        recoverableTrackSupport = group.getTrackSupport(i);
                    }
                    continue;
                }
                Format format = mediaTrackGroup.getFormat(i);
                // Keep the selected format for both explicit decoder errors and silent platform
                // failures. Some MediaCodec implementations accept a track but never emit a
                // frame, so no exception exists from which to recover the format later.
                softwareRecoveryVideoFormat = format;
                currentVideoAssessment = DeviceVideoCapabilities.assess(format);
                Log.i("VideoCapability",
                        "mime=" + format.sampleMimeType
                                + " size=" + format.width + "x" + format.height
                                + " fps=" + format.frameRate
                                + " support=" + currentVideoAssessment.support
                                + " decoder=" + currentVideoAssessment.decoderName
                                + " hardware=" + currentVideoAssessment.hardwareAccelerated);

                if (!capabilityWarningLogged
                        && currentVideoAssessment.support
                        == DeviceVideoCapabilities.Support.EXCEEDS_REPORTED_CAPABILITY) {
                    capabilityWarningLogged = true;
                    Log.w("VideoCapability",
                            "Selected video exceeds the device-reported decode envelope; "
                                    + "playback is still attempted because OEM tables can under-report.");
                }
                startFirstFrameWatchdogIfNeeded();
                return;
            }
        }

        if (recoverableUnselectedVideo != null) {
            startCompatibilityForUnselectedVideo(
                    recoverableUnselectedVideo,
                    recoverableTrackSupport
            );
        }
    }

    private void startCompatibilityForUnselectedVideo(Format format, int trackSupport) {
        currentVideoAssessment = DeviceVideoCapabilities.assess(format);
        Log.w("VideoCapability",
                "No video track was selected; routing through compatibility conversion."
                        + " mime=" + format.sampleMimeType
                        + " codecs=" + format.codecs
                        + " size=" + format.width + "x" + format.height
                        + " fps=" + format.frameRate
                        + " trackSupport=" + trackSupport
                        + " platformSupport=" + currentVideoAssessment.support);

        if (compatibilityTranscodeAttempted
                || format.width <= 0
                || format.height <= 0) {
            return;
        }

        if (!decoderMode.preferSoftwareVideo
                && !softwareVideoRecoveryAttempted
                && !CineFfmpegLibrary.isDolbyVisionFormat(
                format.sampleMimeType,
                format.codecs
        )
                && CineFfmpegLibrary.isDeclaredVideoMimeType(format.sampleMimeType)) {
            softwareVideoRecoveryAttempted = true;
            hardwareVideoFailureObserved = true;
            softwareRecoveryVideoFormat = format;
            governedSoftwareVideoActive =
                    PlaybackEnginePolicy.shouldUseGovernedFastVideoDecode(format);
            decoderMode = decoderMode.withSoftwareVideo();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();
            Log.w("VideoCapability", "Trying direct bundled software video playback first");
            softwarePlaybackStartScheduled = false;
            startSoftwarePlaybackAfterFirstFrame = playWhenReady;
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, false);
            return;
        }

        compatibilityTranscodeAttempted = true;
        String recoveryPlaybackKey = playbackKey;
        uiHandler.post(() -> {
            if (isFinishing()
                    || isDestroyed()
                    || exoPlayer == null
                    || compatibilityTranscodeSession != null
                    || recoveryPlaybackKey == null
                    || !recoveryPlaybackKey.equals(playbackKey)) {
                return;
            }
            startCompatibilityRecovery(format);
        });
    }

    private void savePositionInfo(Player.PositionInfo positionInfo) {
        if (positionInfo == null || positionInfo.mediaItem == null) {
            return;
        }
        String key = positionInfo.mediaItem.mediaId;
        if (key == null || key.isEmpty()) {
            return;
        }

        long savedPositionMs = positionInfo.positionMs;
        long durationMs = C.TIME_UNSET;
        if (progressivePlaybackActive
                && progressivePlaybackKey != null
                && progressivePlaybackKey.equals(key)) {
            int segmentIndex = positionInfo.mediaItemIndex - progressiveOriginalIndex;
            if (segmentIndex >= 0 && segmentIndex < progressiveSegments.size()) {
                savedPositionMs = progressiveSegments.get(segmentIndex).window.startMs
                        + Math.max(0L, positionInfo.positionMs);
                durationMs = progressiveOriginalDurationMs;
            }
        }
        if (exoPlayer != null) {
            Timeline timeline = exoPlayer.getCurrentTimeline();
            int index = positionInfo.mediaItemIndex;
            if ((durationMs <= 0L || durationMs == C.TIME_UNSET)
                    && !timeline.isEmpty()
                    && index >= 0
                    && index < timeline.getWindowCount()) {
                Timeline.Window window = new Timeline.Window();
                timeline.getWindow(index, window);
                durationMs = window.getDurationMs();
            }
        }
        if (durationMs <= 0 || durationMs == C.TIME_UNSET) {
            durationMs = PlaybackPrefs.getInstance(this).getDuration(key);
        }
        if (durationMs > 0 && savedPositionMs >= 0) {
            PlaybackPrefs.getInstance(this).save(key, savedPositionMs, durationMs);
        }
    }

    private void releasePlayerOnly() {
        stopSoftwareObservation();
        resetFirstFrameWatchdog();
        releaseLoudnessEnhancer();
        if (exoPlayer != null) {
            exoPlayer.removeListener(playbackListener);
            exoPlayer.removeAnalyticsListener(videoPerformanceListener);
            playerView.setPlayer(null);
            exoPlayer.release();
            exoPlayer = null;
        }
        trackSelector = null;
    }

    @Override
    protected void onStart() {
        super.onStart();
        brightnessOverlay.removeCallbacks(hideBrightnessOverlayRunnable);
        volumeOverlay.removeCallbacks(hideVolumeOverlayRunnable);
        brightnessOverlay.setVisibility(View.GONE);
        volumeOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(true);
            startSoftwareObservationIfNeeded();
            startFirstFrameWatchdogIfNeeded();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            if (playbackKey != null) {
                PlaybackPrefs.getInstance(this).save(
                        playbackKey,
                        currentLogicalPlaybackPosition(),
                        currentLogicalDuration()
                );
            }
            exoPlayer.setPlayWhenReady(false);
            stopFirstFrameWatchdogTimer();
        }
    }

    @Override
    protected void onDestroy() {
        if (compatibilityTranscodeSession != null) {
            compatibilityTranscodeSession.cancel();
            compatibilityTranscodeSession = null;
        }
        clearProgressivePlaybackState();
        releasePlayerOnly();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUI();
        }
    }

    private Uri resolveVideoUri() {
        String internalUri = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        if (internalUri != null && !internalUri.isEmpty()) {
            return Uri.parse(internalUri);
        }
        return getIntent().getData();
    }

    private String resolvePlaybackKey(Uri uri) {
        String explicitKey = getIntent().getStringExtra(EXTRA_PLAYBACK_KEY);
        if (explicitKey != null && !explicitKey.isEmpty()) {
            return explicitKey;
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            Long id = tryResolveMediaStoreId(uri);
            if (id != null) {
                return "media:" + id;
            }
        }
        return "uri:" + uri.toString();
    }

    private String resolveDisplayTitle(Uri uri) {
        String explicitTitle = getIntent().getStringExtra(EXTRA_VIDEO_TITLE);
        if (explicitTitle != null && !explicitTitle.isEmpty()) {
            return stripExtension(explicitTitle);
        }

        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = getContentResolver().query(
                    uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                    null,
                    null,
                    null
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String displayName = cursor.getString(index);
                        if (displayName != null && !displayName.isEmpty()) {
                            return stripExtension(displayName);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e("VideoPlayer", "Unable to resolve display name", e);
            }
        }

        String lastSegment = uri.getLastPathSegment();
        return stripExtension(lastSegment != null ? lastSegment : "Video");
    }

    private ArrayList<MediaItem> buildPlaylistItems() {
        ArrayList<String> playlistUris = getIntent().getStringArrayListExtra(EXTRA_PLAYLIST_URIS);
        ArrayList<String> playlistKeys = getIntent().getStringArrayListExtra(EXTRA_PLAYLIST_KEYS);
        ArrayList<MediaItem> items = new ArrayList<>();

        if (playlistUris == null || playlistKeys == null || playlistUris.size() != playlistKeys.size()) {
            return items;
        }

        for (int i = 0; i < playlistUris.size(); i++) {
            items.add(new MediaItem.Builder()
                    .setUri(Uri.parse(playlistUris.get(i)))
                    .setMediaId(playlistKeys.get(i))
                    .build());
        }
        return items;
    }

    private int resolveStartIndex(List<MediaItem> playlistItems) {
        int requestedIndex = getIntent().getIntExtra(EXTRA_PLAYLIST_INDEX, 0);
        if (playlistItems.isEmpty()) {
            return 0;
        }
        return Math.max(0, Math.min(requestedIndex, playlistItems.size() - 1));
    }

    private String resolveCurrentTitle() {
        if (exoPlayer != null) {
            int currentIndex = exoPlayer.getCurrentMediaItemIndex();
            MediaItem currentItem = exoPlayer.getCurrentMediaItem();
            if (progressivePlaybackActive
                    && currentItem != null
                    && progressivePlaybackKey != null
                    && progressivePlaybackKey.equals(currentItem.mediaId)) {
                if (playlistTitles != null
                        && progressiveOriginalIndex >= 0
                        && progressiveOriginalIndex < playlistTitles.size()) {
                    return stripExtension(playlistTitles.get(progressiveOriginalIndex));
                }
                return resolveDisplayTitle(progressiveOriginalUri);
            }
            if (playlistTitles != null && currentIndex >= 0 && currentIndex < playlistTitles.size()) {
                return stripExtension(playlistTitles.get(currentIndex));
            }
            MediaItem mediaItem = currentItem;
            if (mediaItem != null && mediaItem.localConfiguration != null) {
                if (compatibilityPlaybackKey != null
                        && compatibilityPlaybackKey.equals(mediaItem.mediaId)
                        && compatibilityOriginalUri != null) {
                    return resolveDisplayTitle(compatibilityOriginalUri);
                }
                return resolveDisplayTitle(mediaItem.localConfiguration.uri);
            }
        }
        return resolveDisplayTitle(videoUri);
    }

    private Long tryResolveMediaStoreId(Uri uri) {
        try {
            return android.content.ContentUris.parseId(uri);
        } catch (Exception ignored) {
            return null;
        }
    }

    private String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void setupAudioTrackButton() {
        audioTrackButton.setOnClickListener(v -> {
            if (exoPlayer == null) return;

            DefaultTrackSelector currentTrackSelector = trackSelector;
            if (currentTrackSelector == null) return;
            List<Tracks.Group> audioGroups = new java.util.ArrayList<>();
            List<Tracks.Group> subtitleGroups = new java.util.ArrayList<>();
            for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
                if (group.getType() == C.TRACK_TYPE_AUDIO && group.length > 0) {
                    audioGroups.add(group);
                } else if (group.getType() == C.TRACK_TYPE_TEXT && group.length > 0) {
                    subtitleGroups.add(group);
                }
            }
            if (audioGroups.isEmpty() && subtitleGroups.isEmpty()) {
                GlassUi.showToast(this, "No audio or caption tracks found.");
                return;
            }

            ArrayList<GlassUi.ActionItem> subtitleActions = buildSubtitleActions(subtitleGroups);
            ArrayList<GlassUi.ActionItem> audioActions = buildAudioActions(audioGroups);

            GlassUi.showDualActionSheet(
                    this,
                    "Tracks",
                    "Captions",
                    subtitleActions,
                    item -> applySubtitleSelection(currentTrackSelector, subtitleGroups, item),
                    "Audio",
                    audioActions,
                    item -> applyAudioSelection(currentTrackSelector, audioGroups, item)
            );
        });
    }

    private ArrayList<GlassUi.ActionItem> buildAudioActions(List<Tracks.Group> audioGroups) {
        ArrayList<GlassUi.ActionItem> actions = new ArrayList<>();
        for (int i = 0; i < audioGroups.size(); i++) {
            TrackGroup trackGroup = audioGroups.get(i).getMediaTrackGroup();
            for (int j = 0; j < trackGroup.length; j++) {
                Format format = trackGroup.getFormat(j);
                int ordinal = actions.size() + 1;
                String title = AudioTrackFormatter.buildTitle(ordinal, format);
                String subtitle = AudioTrackFormatter.buildTechnicalDetails(
                        format, audioGroups.get(i).isTrackSupported(j));
                if (audioGroups.get(i).isTrackSelected(j)) {
                    subtitle = "Selected" + (subtitle.isEmpty() ? "" : " • " + subtitle);
                }
                actions.add(new GlassUi.ActionItem(i * 100 + j, title, subtitle));
            }
        }
        if (actions.isEmpty()) {
            actions.add(new GlassUi.ActionItem(Integer.MIN_VALUE, "No audio tracks", "Nothing available for this media"));
        }
        return actions;
    }

    private ArrayList<GlassUi.ActionItem> buildSubtitleActions(List<Tracks.Group> subtitleGroups) {
        ArrayList<GlassUi.ActionItem> actions = new ArrayList<>();
        boolean subtitleEnabled = isTextTrackEnabled();
        actions.add(new GlassUi.ActionItem(
                ACTION_SUBTITLE_OFF,
                "Off",
                subtitleEnabled ? "Disable captions" : "Currently off"
        ));

        int ordinal = 1;
        for (int i = 0; i < subtitleGroups.size(); i++) {
            TrackGroup trackGroup = subtitleGroups.get(i).getMediaTrackGroup();
            for (int j = 0; j < trackGroup.length; j++) {
                Format format = trackGroup.getFormat(j);
                String title = SubtitleTrackFormatter.buildTitle(ordinal++, format);
                String subtitle = SubtitleTrackFormatter.buildTechnicalDetails(
                        format, subtitleGroups.get(i).isTrackSupported(j));
                if (subtitleGroups.get(i).isTrackSelected(j) && subtitleEnabled) {
                    subtitle = "Selected" + (subtitle.isEmpty() ? "" : " • " + subtitle);
                }
                actions.add(new GlassUi.ActionItem(i * 100 + j, title, subtitle));
            }
        }
        return actions;
    }

    private void applyAudioSelection(DefaultTrackSelector trackSelector, List<Tracks.Group> audioGroups, GlassUi.ActionItem item) {
        if (item.id == Integer.MIN_VALUE) {
            return;
        }
        int groupIndex = item.id / 100;
        int trackIndex = item.id % 100;
        Tracks.Group selectedGroup = audioGroups.get(groupIndex);
        TrackGroup trackGroup = selectedGroup.getMediaTrackGroup();
        Format fmt = trackGroup.getFormat(trackIndex);

        if (selectedGroup.isTrackSupported(trackIndex) || isAudioFormatSupported(fmt)) {
            selectedAudioActionId = item.id;
            TrackSelectionOverride override = new TrackSelectionOverride(trackGroup, trackIndex);
            DefaultTrackSelector.Parameters params =
                    trackSelector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                            .addOverride(override)
                            .build();
            trackSelector.setParameters(params);
            GlassUi.showToast(this, "Selected audio: " + item.title);
        } else {
            GlassUi.showToast(this, "Unsupported audio format.");
        }
    }

    private void applySubtitleSelection(DefaultTrackSelector trackSelector, List<Tracks.Group> subtitleGroups, GlassUi.ActionItem item) {
        if (item.id == ACTION_SUBTITLE_OFF) {
            selectedSubtitleActionId = ACTION_SUBTITLE_OFF;
            DefaultTrackSelector.Parameters params =
                    trackSelector.buildUponParameters()
                            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build();
            trackSelector.setParameters(params);
            GlassUi.showToast(this, "Captions off");
            return;
        }

        int groupIndex = item.id / 100;
        int trackIndex = item.id % 100;
        Tracks.Group selectedGroup = subtitleGroups.get(groupIndex);
        if (!selectedGroup.isTrackSupported(trackIndex)) {
            GlassUi.showToast(this, "Unsupported caption track.");
            return;
        }

        TrackGroup trackGroup = selectedGroup.getMediaTrackGroup();
        selectedSubtitleActionId = item.id;
        TrackSelectionOverride override = new TrackSelectionOverride(trackGroup, trackIndex);
        DefaultTrackSelector.Parameters params =
                trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(override)
                        .build();
        trackSelector.setParameters(params);
        GlassUi.showToast(this, "Selected captions: " + item.title);
    }

    private void restoreSelectedTracks(Tracks tracks) {
        if (restoringTrackSelection || trackSelector == null || tracks == null) {
            return;
        }
        List<Tracks.Group> audioGroups = new ArrayList<>();
        List<Tracks.Group> subtitleGroups = new ArrayList<>();
        for (Tracks.Group group : tracks.getGroups()) {
            if (group.getType() == C.TRACK_TYPE_AUDIO && group.length > 0) {
                audioGroups.add(group);
            } else if (group.getType() == C.TRACK_TYPE_TEXT && group.length > 0) {
                subtitleGroups.add(group);
            }
        }

        DefaultTrackSelector.Parameters.Builder builder = trackSelector.buildUponParameters();
        boolean changed = false;
        if (selectedAudioActionId != Integer.MIN_VALUE) {
            int groupIndex = selectedAudioActionId / 100;
            int trackIndex = selectedAudioActionId % 100;
            if (groupIndex >= 0
                    && groupIndex < audioGroups.size()
                    && trackIndex >= 0
                    && trackIndex < audioGroups.get(groupIndex).length
                    && !audioGroups.get(groupIndex).isTrackSelected(trackIndex)) {
                TrackGroup trackGroup = audioGroups.get(groupIndex).getMediaTrackGroup();
                builder.setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                        .addOverride(new TrackSelectionOverride(trackGroup, trackIndex));
                changed = true;
            }
        }

        if (selectedSubtitleActionId == ACTION_SUBTITLE_OFF) {
            if (isTextTrackEnabled()) {
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true);
                changed = true;
            }
        } else {
            int groupIndex = selectedSubtitleActionId / 100;
            int trackIndex = selectedSubtitleActionId % 100;
            if (groupIndex >= 0
                    && groupIndex < subtitleGroups.size()
                    && trackIndex >= 0
                    && trackIndex < subtitleGroups.get(groupIndex).length
                    && subtitleGroups.get(groupIndex).isTrackSupported(trackIndex)
                    && !subtitleGroups.get(groupIndex).isTrackSelected(trackIndex)) {
                TrackGroup trackGroup = subtitleGroups.get(groupIndex).getMediaTrackGroup();
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                        .addOverride(new TrackSelectionOverride(trackGroup, trackIndex));
                changed = true;
            }
        }

        if (changed) {
            restoringTrackSelection = true;
            try {
                trackSelector.setParameters(builder.build());
            } finally {
                restoringTrackSelection = false;
            }
        }
    }

    private boolean isTextTrackEnabled() {
        if (exoPlayer == null) {
            return false;
        }
        for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
            if (group.getType() != C.TRACK_TYPE_TEXT) {
                continue;
            }
            for (int i = 0; i < group.length; i++) {
                if (group.isTrackSelected(i)) {
                    return true;
                }
            }
        }
        return false;
    }

    private String buildTrackTitle(String fallbackPrefix, int trackIndex, String language, String label) {
        if (label != null && !label.trim().isEmpty()) {
            return label.trim();
        }
        if (language != null && !language.trim().isEmpty()) {
            return String.format(Locale.US, "%s %d - %s", fallbackPrefix, trackIndex + 1, language);
        }
        return String.format(Locale.US, "%s %d", fallbackPrefix, trackIndex + 1);
    }

    private boolean isAudioFormatSupported(Format format) {
        String mime = format.sampleMimeType;
        List<String> supported = Arrays.asList(
                "audio/mp4a-latm",
                "audio/mpeg",
                "audio/vorbis",
                "audio/opus",
                "audio/eac3",
                "audio/eac3-joc",
                "audio/ac3",
                "audio/flac",
                "audio/ac4",
                "audio/vnd.dts",
                "audio/vnd.dts.hd",
                "audio/true-hd"
        );
        return supported.contains(mime);
    }

    private String buildAudioTrackSubtitle(Format format, boolean isSupported) {
        String codec = format.sampleMimeType != null
                ? format.sampleMimeType.replace("audio/", "").toUpperCase(Locale.US)
                : "Audio track";
        String channels = format.channelCount > 0 ? " • " + format.channelCount + " ch" : "";
        String sampleRate = format.sampleRate > 0 ? " • " + format.sampleRate + " Hz" : "";
        return isSupported ? codec + channels + sampleRate : codec + channels + sampleRate + " • limited";
    }

    private String buildSubtitleTrackSubtitle(Format format, boolean isSupported) {
        String language = format.language != null && !format.language.isEmpty()
                ? format.language
                : "Unknown language";
        String mime = format.sampleMimeType != null
                ? format.sampleMimeType.replace("application/", "").replace("text/", "").toUpperCase(Locale.US)
                : "Caption";
        return isSupported ? language + " • " + mime : language + " • " + mime + " • limited";
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void setupRotationButton() {
        rotateButton.setOnClickListener(v -> {
            if (!isLockedInPortrait && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                isLockedInPortrait = true;
                isLockedInLandscape = false;
                rotateButton.setImageResource(R.drawable.ic_rotation_locked);
            } else if (!isLockedInLandscape && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                isLockedInLandscape = true;
                isLockedInPortrait = false;
                rotateButton.setImageResource(R.drawable.ic_rotation_locked);
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                isLockedInPortrait = false;
                isLockedInLandscape = false;
                rotateButton.setImageResource(R.drawable.ic_rotate);
            }

            hideSystemUI();
        });
    }

    private void setupCropButton() {
        cropButton.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            playerView.cycleCropMode(v);
        });
    }

    private void setupScreenLockButton() {
        screenLockButton.setOnClickListener(v -> playerView.lockPlayer(v));
    }

    private void installScreenLockSafeInsets() {
        final int baseMargin = Math.round(8f * getResources().getDisplayMetrics().density);
        ViewCompat.setOnApplyWindowInsetsListener(screenLockButton, (view, insets) -> {
            androidx.core.graphics.Insets safeInsets = insets.getInsets(
                    WindowInsetsCompat.Type.displayCutout()
                            | WindowInsetsCompat.Type.systemBars()
            );
            boolean rtl = view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            int safeStart = rtl ? safeInsets.right : safeInsets.left;
            ViewGroup.LayoutParams rawParams = view.getLayoutParams();
            if (rawParams instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) rawParams;
                int requiredMargin = baseMargin + safeStart;
                if (params.getMarginStart() != requiredMargin) {
                    params.setMarginStart(requiredMargin);
                    view.setLayoutParams(params);
                }
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(screenLockButton);
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupGestureDetection() {
        final boolean[] isAdjusting = {false};
        final long[] downTime = {0};

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                isAdjusting[0] = false;
                downTime[0] = System.currentTimeMillis();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                isAdjusting[0] = true;
                float screenWidth = getWindow().getDecorView().getWidth();
                if (Math.abs(dy) > Math.abs(dx)) {
                    if (e2.getX() < screenWidth / 2) {
                        handleBrightnessChange(dy);
                    } else {
                        handleVolumeChange(dy);
                    }
                }
                return true;
            }
        });

        playerView.setOnTouchListener((v, event) -> {
            if (playerView.isControllerFullyVisible()) {
                return false;
            }
            gestureDetector.onTouchEvent(event);

            if (event.getAction() == MotionEvent.ACTION_UP) {
                long upTime = System.currentTimeMillis();
                if (!isAdjusting[0] && upTime - downTime[0] < ViewConfiguration.getTapTimeout()) {
                    if (isControlsVisible) {
                        hideControls();
                    } else {
                        showControls();
                    }
                }
            }
            return true;
        });
    }

    private void handleBrightnessChange(float deltaY) {
        if (deltaY != 0) {
            float change = deltaY / 1000;
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            float brightness = layoutParams.screenBrightness + change;

            brightness = Math.max(0.0f, Math.min(1.0f, brightness));
            layoutParams.screenBrightness = brightness;
            getWindow().setAttributes(layoutParams);

            currentBrightness = brightness;
            updateBrightnessOverlay(brightness);
        }
    }

    private void handleVolumeChange(float deltaY) {
        if (deltaY != 0 && audioManager != null) {
            float deltaPercent = deltaY / Math.max(1f, maxVolume);
            float targetPercent = Math.max(0f, Math.min(200f, getCombinedVolumePercent() + deltaPercent));

            float basePercent = Math.min(targetPercent, 100f);
            currentVolume = (basePercent / 100f) * maxVolume;
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(currentVolume), 0);

            volumeBoostPercent = targetPercent > 100f ? targetPercent : 100f;
            applyVolumeBoost();
            updateVolumeOverlay(targetPercent);
        }
    }

    private void updateBrightnessOverlay(float brightness) {
        brightnessOverlay.removeCallbacks(hideBrightnessOverlayRunnable);
        volumeOverlay.removeCallbacks(hideVolumeOverlayRunnable);
        volumeOverlay.setVisibility(View.GONE);
        volumeOverlay.removeCallbacks(hideControlsRunnable);

        positionOverlay(brightnessOverlay);

        int brightnessPercentage = (int) (brightness * 100);
        brightnessProgressBar.setProgress(brightnessPercentage);
        brightnessOverlay.setVisibility(View.VISIBLE);
        brightnessOverlay.postDelayed(hideBrightnessOverlayRunnable, 1200);
    }

    private void updateVolumeOverlay(float volumePercentage) {
        volumeOverlay.removeCallbacks(hideVolumeOverlayRunnable);
        brightnessOverlay.removeCallbacks(hideBrightnessOverlayRunnable);
        brightnessOverlay.setVisibility(View.GONE);
        brightnessOverlay.removeCallbacks(hideControlsRunnable);

        positionOverlay(volumeOverlay);

        int baseProgress = (int) Math.max(0f, Math.min(100f, volumePercentage));
        int boostProgress = volumePercentage > 100f
                ? (int) Math.max(0f, Math.min(100f, volumePercentage - 100f))
                : 0;
        volumeProgressBar.setProgress(baseProgress);
        volumeBoostProgressBar.setProgress(boostProgress);
        volumeOverlay.setVisibility(View.VISIBLE);
        volumeOverlay.postDelayed(hideVolumeOverlayRunnable, 1200);
    }

    private float getCombinedVolumePercent() {
        float systemPercent = maxVolume <= 0f ? 0f : (currentVolume / maxVolume) * 100f;
        return volumeBoostPercent > 100f ? Math.max(systemPercent, volumeBoostPercent) : systemPercent;
    }

    private void applyVolumeBoost() {
        if (exoPlayer == null) {
            return;
        }
        float playerVolume = volumeBoostPercent <= 100f
                ? 1f
                : Math.min(2f, volumeBoostPercent / 100f);
        exoPlayer.setVolume(playerVolume);
        if (loudnessEnhancer == null) {
            return;
        }

        int gainMb = volumeBoostPercent <= 100f
                ? 0
                : Math.round((volumeBoostPercent - 100f) * 15f);
        try {
            loudnessEnhancer.setTargetGain(gainMb);
            loudnessEnhancer.setEnabled(gainMb > 0);
        } catch (Exception e) {
            Log.w("VideoPlayer", "Unable to apply volume boost", e);
        }
    }

    private void attachLoudnessEnhancer(int audioSessionId) {
        releaseLoudnessEnhancer();
        if (audioSessionId == C.AUDIO_SESSION_ID_UNSET || audioSessionId == 0) {
            return;
        }
        try {
            loudnessEnhancer = new LoudnessEnhancer(audioSessionId);
        } catch (Exception e) {
            Log.w("VideoPlayer", "Unable to attach loudness enhancer", e);
            loudnessEnhancer = null;
        }
    }

    private void releaseLoudnessEnhancer() {
        if (loudnessEnhancer == null) {
            return;
        }
        try {
            loudnessEnhancer.release();
        } catch (Exception ignored) {
        }
        loudnessEnhancer = null;
    }

    private void positionOverlay(View overlay) {
        int screenHeight = getWindow().getDecorView().getHeight();
        int topMargin = screenHeight / 8;

        androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams lp =
                (androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)
                        overlay.getLayoutParams();
        lp.gravity = android.view.Gravity.TOP | android.view.Gravity.CENTER_HORIZONTAL;
        lp.topMargin = topMargin;
        overlay.setLayoutParams(lp);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showControls() {
        playerView.showController();
        syncCustomControls(true);
        resetHideControlsTimer();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void hideControls() {
        playerView.hideController();
        syncCustomControls(false);
        hideSystemUI();
    }

    private void syncCustomControls(boolean visible) {
        int state = visible ? View.VISIBLE : View.GONE;
        rotateButton.setVisibility(state);
        audioTrackButton.setVisibility(state);
        cropButton.setVisibility(state);
        screenLockButton.setVisibility(state);
        if (btnBack != null) btnBack.setVisibility(state);
        if (tvVideoName != null) tvVideoName.setVisibility(state);
        View topBar = findViewById(R.id.top_bar);
        if (topBar != null) topBar.setVisibility(state);
        isControlsVisible = visible;
        if (!visible) {
            hideSystemUI();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInteractionListeners() {
        View brightnessOverlayView = findViewById(R.id.brightness_overlay_container);
        View volumeOverlayView = findViewById(R.id.overlay_container);

        brightnessOverlayView.setOnTouchListener((v, event) -> {
            resetHideControlsTimer();
            return true;
        });

        volumeOverlayView.setOnTouchListener((v, event) -> {
            resetHideControlsTimer();
            return true;
        });
    }

    private final Handler uiHandler = new Handler(android.os.Looper.getMainLooper());
    private final Runnable hideControlsRunnable = this::hideControls;

    private void resetHideControlsTimer() {
        uiHandler.removeCallbacks(hideControlsRunnable);
        uiHandler.postDelayed(hideControlsRunnable, 3000);
    }

    private void hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (controller != null) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        }
    }
}
