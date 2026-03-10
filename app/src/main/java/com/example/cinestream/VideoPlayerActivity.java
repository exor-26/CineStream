package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.database.Cursor;
import android.media.AudioManager;
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
import androidx.media3.common.TrackGroup;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.Tracks;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

@UnstableApi
public class VideoPlayerActivity extends AppCompatActivity {

    // The player can be opened from inside the app or through an external ACTION_VIEW intent.
    // These extras cover the richer in-app path while still allowing external fallback.
    public static final String EXTRA_VIDEO_URI = "VIDEO_URI";
    public static final String EXTRA_PLAYBACK_KEY = "PLAYBACK_KEY";
    public static final String EXTRA_VIDEO_TITLE = "VIDEO_TITLE";

    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private ImageButton rotateButton;
    private ImageButton cropButton;
    private ImageButton audioTrackButton;

    private LinearLayout brightnessOverlay;
    private ProgressBar brightnessProgressBar;
    private ImageView brightnessIcon;
    private LinearLayout volumeOverlay;
    private ProgressBar volumeProgressBar;
    private ImageView volumeIcon;
    private TextView tvVideoName;
    private ImageButton btnBack;

    private boolean isLockedInPortrait = false;
    private boolean isLockedInLandscape = false;
    private boolean isControlsVisible = true;

    private GestureDetector gestureDetector;
    private AudioManager audioManager;
    private Uri videoUri;
    private String playbackKey;

    private float maxVolume;
    private float currentVolume;
    private float currentBrightness;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        // Prevent the screen from dimming mid-playback.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        playerView = findViewById(R.id.player_view);
        rotateButton = findViewById(R.id.btn_rotate);
        cropButton = findViewById(R.id.btn_crop);
        audioTrackButton = findViewById(R.id.audio_track);

        brightnessOverlay = findViewById(R.id.brightness_overlay_container);
        brightnessProgressBar = findViewById(R.id.brightness_progress);
        brightnessIcon = findViewById(R.id.brightness_icon);
        volumeOverlay = findViewById(R.id.overlay_container);

        // Overlays are positioned after layout because they depend on the actual screen height,
        // not just the XML measurements.
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
        volumeIcon = findViewById(R.id.volume_icon);
        tvVideoName = findViewById(R.id.tv_video_name);
        btnBack = findViewById(R.id.btn_back);

        btnBack.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            finish();
        });

        final View topBar = findViewById(R.id.top_bar);

        // The top bar needs different padding rules in portrait and landscape because immersive
        // video playback should still look correct around cutouts and status bars.
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

        // Resolve the media source first. If this fails, there is nothing useful the player can do.
        videoUri = resolveVideoUri();
        if (videoUri == null) {
            Log.e("VideoError", "Invalid video URI");
            GlassUi.showToast(this, "Invalid video source.");
            finish();
            return;
        }

        playbackKey = resolvePlaybackKey(videoUri);
        tvVideoName.setText(resolveDisplayTitle(videoUri));

        // Prefer extension renderers so the FFmpeg decoder can handle formats that the device
        // codec stack might reject, especially less common audio tracks.
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);

        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(this)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true);

        exoPlayer = new ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector)
                .build();

        playerView.setPlayer(exoPlayer);

        // Restore the last playback position before starting playback so resume feels immediate.
        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        long savedPosition = PlaybackPrefs.getInstance(this).getPosition(playbackKey);
        if (savedPosition > 0) exoPlayer.seekTo(savedPosition);
        exoPlayer.play();

        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        exoPlayer.setAudioAttributes(audioAttributes, true);

        // Volume changes are routed through AudioManager because they affect the actual media stream.
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        // Use the current window brightness as the baseline for swipe gestures.
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

    @Override
    protected void onStart() {
        super.onStart();
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
            // Save progress before leaving so both in-app playback and external intent playback
            // can resume from the same stable key.
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
        super.onDestroy();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Some system UI states are reset when the window focus changes, especially around
            // permission prompts or multi-window transitions.
            hideSystemUI();
        }
    }

    private Uri resolveVideoUri() {
        // Internal launches pass the Uri as a string extra. External launches rely on the intent data.
        String internalUri = getIntent().getStringExtra(EXTRA_VIDEO_URI);
        if (internalUri != null && !internalUri.isEmpty()) {
            return Uri.parse(internalUri);
        }
        return getIntent().getData();
    }

    private String resolvePlaybackKey(Uri uri) {
        // If the app already knows the playback key, use it. Otherwise derive a best-effort key
        // from the MediaStore id or fall back to the Uri text for external sources.
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
        // Prefer a title explicitly passed by the library screen, then fall back to querying the
        // provider, and finally use the last Uri segment if nothing else is available.
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

            DefaultTrackSelector trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
            List<Tracks.Group> audioGroups = new java.util.ArrayList<>();
            for (Tracks.Group group : exoPlayer.getCurrentTracks().getGroups()) {
                if (group.getType() == C.TRACK_TYPE_AUDIO && group.length > 0) {
                    audioGroups.add(group);
                }
            }
            if (audioGroups.isEmpty()) {
                GlassUi.showToast(this, "No audio tracks found.");
                return;
            }

            java.util.ArrayList<GlassUi.ActionItem> actions = new java.util.ArrayList<>();

            // Show all exposed audio tracks and label them by language when possible.
            for (int i = 0; i < audioGroups.size(); i++) {
                TrackGroup trackGroup = audioGroups.get(i).getMediaTrackGroup();
                for (int j = 0; j < trackGroup.length; j++) {
                    Format format = trackGroup.getFormat(j);
                    String lang = format.language;
                    String name = (lang == null || lang.isEmpty())
                            ? String.format(Locale.US, "Track %d", j + 1)
                            : String.format(Locale.US, "Track %d - %s", j + 1, lang);
                    String subtitle = buildAudioTrackSubtitle(format, audioGroups.get(i).isTrackSupported(j));
                    actions.add(new GlassUi.ActionItem(i * 100 + j, name, subtitle));
                }
            }

            GlassUi.showActionSheet(this, "Audio tracks", actions, item -> {
                int groupIndex = item.id / 100;
                int trackIndex = item.id % 100;
                Tracks.Group selectedGroup = audioGroups.get(groupIndex);
                TrackGroup trackGroup = selectedGroup.getMediaTrackGroup();
                Format fmt = trackGroup.getFormat(trackIndex);

                if (selectedGroup.isTrackSupported(trackIndex) || isAudioFormatSupported(fmt)) {
                    // Clear any previous audio override so only the selected track remains active.
                    TrackSelectionOverride override =
                            new TrackSelectionOverride(trackGroup, trackIndex);
                    DefaultTrackSelector.Parameters params =
                            trackSelector.buildUponParameters()
                                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                                    .clearOverridesOfType(C.TRACK_TYPE_AUDIO)
                                    .addOverride(override)
                                    .build();
                    trackSelector.setParameters(params);
                    GlassUi.showToast(this, "Selected: " + item.title);
                } else {
                    GlassUi.showToast(this, "Unsupported audio format.");
                }
            });
        });
    }

    private boolean isAudioFormatSupported(Format format) {
        // The popup may expose more tracks than we explicitly want to promise. This filter keeps
        // the user away from formats we do not expect this build to handle reliably.
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

    @SuppressLint("SourceLockedOrientationActivity")
    private void setupRotationButton() {
        rotateButton.setOnClickListener(v -> {
            // The rotation button acts as a lock/unlock toggle based on the current orientation.
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
            // Resize modes are exposed as user-friendly terms rather than raw ExoPlayer constants.
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
        // Update both the underlying resize mode and the icon so the current display choice is visible.
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
        // Gesture handling splits the screen in half: left controls brightness, right controls volume.
        // Tap toggles the controls; vertical drag adjusts the matching setting.
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
            // Window-level brightness is enough here because the gesture is intended as a temporary
            // playback adjustment, not a permanent system setting.
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
        if (deltaY != 0) {
            // Volume changes are clamped against the real stream limits reported by AudioManager.
            float volume = currentVolume + (deltaY / 100);
            volume = Math.max(0, Math.min(maxVolume, volume));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) volume, 0);
            currentVolume = volume;

            float volumePercentage = (currentVolume / maxVolume) * 100;
            updateVolumeOverlay(volumePercentage);
        }
    }

    private void updateBrightnessOverlay(float brightness) {
        // Only one overlay should be visible at a time, otherwise the feedback gets cluttered.
        volumeOverlay.setVisibility(View.GONE);
        volumeOverlay.removeCallbacks(hideControlsRunnable);

        positionOverlay(brightnessOverlay);

        int brightnessPercentage = (int) (brightness * 100);
        brightnessProgressBar.setProgress(brightnessPercentage);
        brightnessOverlay.setVisibility(View.VISIBLE);
        brightnessOverlay.postDelayed(() -> brightnessOverlay.setVisibility(View.GONE), 1500);
    }

    private void updateVolumeOverlay(float volumePercentage) {
        brightnessOverlay.setVisibility(View.GONE);
        brightnessOverlay.removeCallbacks(hideControlsRunnable);

        positionOverlay(volumeOverlay);

        volumeProgressBar.setProgress((int) volumePercentage);
        volumeOverlay.setVisibility(View.VISIBLE);
        volumeOverlay.postDelayed(() -> volumeOverlay.setVisibility(View.GONE), 1500);
    }

    private void positionOverlay(View overlay) {
        // Recompute position every time so overlays stay centered even after rotation changes.
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
        // We manage the extra chrome ourselves because the screen has custom top controls in
        // addition to ExoPlayer's standard controller.
        playerView.showController();
        rotateButton.setVisibility(View.VISIBLE);
        cropButton.setVisibility(View.VISIBLE);
        audioTrackButton.setVisibility(View.VISIBLE);
        if (btnBack != null) btnBack.setVisibility(View.VISIBLE);
        if (tvVideoName != null) tvVideoName.setVisibility(View.VISIBLE);
        View topBar = findViewById(R.id.top_bar);
        if (topBar != null) topBar.setVisibility(View.VISIBLE);
        isControlsVisible = true;
        resetHideControlsTimer();
    }

    @OptIn(markerClass = UnstableApi.class)
    private void hideControls() {
        // Hiding both our own views and ExoPlayer's controls creates the clean full-screen state.
        playerView.hideController();
        rotateButton.setVisibility(View.GONE);
        audioTrackButton.setVisibility(View.GONE);
        cropButton.setVisibility(View.GONE);
        if (btnBack != null) btnBack.setVisibility(View.GONE);
        if (tvVideoName != null) tvVideoName.setVisibility(View.GONE);
        View topBar = findViewById(R.id.top_bar);
        if (topBar != null) topBar.setVisibility(View.GONE);
        isControlsVisible = false;
        hideSystemUI();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInteractionListeners() {
        // Touching the overlays should keep the controls alive long enough for the user to read them.
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
        // Any user interaction postpones the auto-hide countdown.
        uiHandler.removeCallbacks(hideControlsRunnable);
        uiHandler.postDelayed(hideControlsRunnable, 3000);
    }

    private void hideSystemUI() {
        // Modern insets APIs are used here so immersive mode behaves consistently across Android versions.
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
