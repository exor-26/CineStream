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
import android.provider.MediaStore;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
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
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.FilteringMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
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
    private PlayerView playerView;
    private ImageButton rotateButton;
    private ImageButton cropButton;
    private ImageButton audioTrackButton;

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
    private Uri compatibilityOriginalUri;
    private Uri compatibilityVideoUri;
    private String compatibilityPlaybackKey;

    private PlaybackEnginePolicy.DecoderMode decoderMode =
            PlaybackEnginePolicy.DecoderMode.HARDWARE_FIRST;
    private DeviceVideoCapabilities.Assessment currentVideoAssessment;
    private Format softwareRecoveryVideoFormat;

    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private Uri videoUri;
    private String playbackKey;
    private ArrayList<String> playlistTitles;

    private float maxVolume;
    private float currentVolume;
    private float currentBrightness;
    private float volumeBoostPercent = 100f;
    private LoudnessEnhancer loudnessEnhancer;
    private final Runnable hideBrightnessOverlayRunnable = () -> brightnessOverlay.setVisibility(View.GONE);
    private final Runnable hideVolumeOverlayRunnable = () -> volumeOverlay.setVisibility(View.GONE);

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
                compatibilityTranscodeAttempted = false;
                softwareVideoRecoveryAttempted = false;
                hardwareVideoFailureObserved = false;
                softwareRecoveryVideoFormat = null;
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
            if (transitionedUri != null
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
        }

        @Override
        public void onPlayerError(PlaybackException error) {
            handlePlaybackError(error);
        }
    };

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

        videoUri = resolveVideoUri();
        if (videoUri == null) {
            Log.e("VideoError", "Invalid video URI");
            GlassUi.showToast(this, "Invalid video source.");
            finish();
            return;
        }

        playbackKey = resolvePlaybackKey(videoUri);
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
        releasePlayerOnly();

        trackSelector = new DefaultTrackSelector(this);
        trackSelector.setParameters(
                trackSelector.buildUponParameters()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                        .build()
        );

        exoPlayer = new ExoPlayer.Builder(
                this,
                PlaybackEnginePolicy.createRenderersFactory(this, decoderMode)
        )
                .setTrackSelector(trackSelector)
                .build();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        exoPlayer.setAudioAttributes(audioAttributes, true);
        exoPlayer.addListener(playbackListener);
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

    private void handlePlaybackError(PlaybackException error) {
        Log.e("VideoPlayer", "Playback failed: " + error.getErrorCodeName(), error);

        Format failedVideo = PlaybackEnginePolicy.getFailedVideoFormat(error);
        if (failedVideo != null && PlaybackEnginePolicy.isVideoDecoderFailure(error)) {
            softwareRecoveryVideoFormat = failedVideo;
            if (!decoderMode.preferSoftwareVideo) {
                hardwareVideoFailureObserved = true;
            }
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

        if (PlaybackEnginePolicy.shouldRetryWithSoftwareVideo(decoderMode, error)) {
            softwareVideoRecoveryAttempted = true;
            decoderMode = decoderMode.withSoftwareVideo();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();

            Log.w("VideoPlayer",
                    "Retrying with bundled software video renderer for "
                            + (failedVideo != null ? failedVideo.sampleMimeType : "video")
                            + " at " + position + " ms");
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, playWhenReady);
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
                && PlaybackEnginePolicy.isVideoDecoderFailure(error)
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
        if (videoUri == null || playbackKey == null) {
            GlassUi.showToast(this, "Unable to resolve the original video for compatibility mode.");
            return;
        }

        ArrayList<MediaItem> items = snapshotMediaItems();
        int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
        long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
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

        releasePlayerOnly();
        compatibilityTranscodeSession = CompatibilityVideoTranscoder.start(
                this,
                sourceUri,
                failedVideo.width,
                failedVideo.height,
                failedVideo.frameRate,
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
                if (decoderMode.preferSoftwareVideo) {
                    softwareRecoveryVideoFormat = format;
                }
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
                && CineFfmpegLibrary.isDeclaredVideoMimeType(format.sampleMimeType)) {
            softwareVideoRecoveryAttempted = true;
            hardwareVideoFailureObserved = true;
            softwareRecoveryVideoFormat = format;
            decoderMode = decoderMode.withSoftwareVideo();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();
            Log.w("VideoCapability", "Trying direct bundled software video playback first");
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, playWhenReady);
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

        long durationMs = C.TIME_UNSET;
        if (exoPlayer != null) {
            Timeline timeline = exoPlayer.getCurrentTimeline();
            int index = positionInfo.mediaItemIndex;
            if (!timeline.isEmpty() && index >= 0 && index < timeline.getWindowCount()) {
                Timeline.Window window = new Timeline.Window();
                timeline.getWindow(index, window);
                durationMs = window.getDurationMs();
            }
        }
        if (durationMs <= 0 || durationMs == C.TIME_UNSET) {
            durationMs = PlaybackPrefs.getInstance(this).getDuration(key);
        }
        if (durationMs > 0 && positionInfo.positionMs >= 0) {
            PlaybackPrefs.getInstance(this).save(key, positionInfo.positionMs, durationMs);
        }
    }

    private void releasePlayerOnly() {
        releaseLoudnessEnhancer();
        if (exoPlayer != null) {
            exoPlayer.removeListener(playbackListener);
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
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            if (playbackKey != null) {
                PlaybackPrefs.getInstance(this).save(
                        playbackKey,
                        exoPlayer.getCurrentPosition(),
                        exoPlayer.getDuration()
                );
            }
            exoPlayer.setPlayWhenReady(false);
        }
    }

    @Override
    protected void onDestroy() {
        if (compatibilityTranscodeSession != null) {
            compatibilityTranscodeSession.cancel();
            compatibilityTranscodeSession = null;
        }
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
            if (playlistTitles != null && currentIndex >= 0 && currentIndex < playlistTitles.size()) {
                return stripExtension(playlistTitles.get(currentIndex));
            }
            MediaItem mediaItem = exoPlayer.getCurrentMediaItem();
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
            java.util.ArrayList<GlassUi.ActionItem> actions = new java.util.ArrayList<>();
            actions.add(new GlassUi.ActionItem(0, "Original", "Preserve the source framing"));
            actions.add(new GlassUi.ActionItem(1, "Fill", "Stretch to fill the player bounds"));
            actions.add(new GlassUi.ActionItem(2, "Fit", "Zoom to cover while keeping aspect"));

            GlassUi.showActionSheet(this, "Crop mode", actions, item -> {
                switch (item.id) {
                    case 0:
                        applyCropping(CropType.ORIGINAL);
                        GlassUi.showToast(this, "Crop mode: Original");
                        break;
                    case 1:
                        applyCropping(CropType.FILL);
                        GlassUi.showToast(this, "Crop mode: Fill");
                        break;
                    case 2:
                        applyCropping(CropType.FIT);
                        GlassUi.showToast(this, "Crop mode: Fit");
                        break;
                    default:
                        break;
                }
            });
        });
    }

    private enum CropType {
        ORIGINAL, FILL, FIT
    }

    @OptIn(markerClass = UnstableApi.class)
    private void applyCropping(CropType cropType) {
        switch (cropType) {
            case ORIGINAL:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FIT);
                cropButton.setImageResource(R.drawable.ic_crop);
                break;
            case FILL:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_FILL);
                cropButton.setImageResource(R.drawable.ic_crop_fill);
                break;
            case FIT:
                playerView.setResizeMode(AspectRatioFrameLayout.RESIZE_MODE_ZOOM);
                cropButton.setImageResource(R.drawable.ic_crop_fit);
                break;
        }
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
