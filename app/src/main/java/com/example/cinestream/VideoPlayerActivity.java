package com.example.cinestream;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.GestureDetectorCompat;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.TrackGroupArray;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;

import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

@UnstableApi
public class VideoPlayerActivity extends AppCompatActivity {

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

    private boolean isLockedInPortrait = false;
    private boolean isLockedInLandscape = false;
    private boolean isControlsVisible = true;

    private GestureDetectorCompat gestureDetector;
    private AudioManager audioManager;

    private float maxVolume;
    private float currentVolume;
    private float currentBrightness;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_video_player);

        // Keep the screen on while the activity is running
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Initialize PlayerView and Buttons
        playerView = findViewById(R.id.player_view);
        rotateButton = findViewById(R.id.btn_rotate);
        cropButton = findViewById(R.id.btn_crop);
        audioTrackButton = findViewById(R.id.audio_track);

        brightnessOverlay = findViewById(R.id.brightness_overlay_container);
        brightnessProgressBar = findViewById(R.id.brightness_progress);
        brightnessIcon = findViewById(R.id.brightness_icon);
        volumeOverlay = findViewById(R.id.overlay_container);
        volumeProgressBar = findViewById(R.id.volume_progress);
        volumeIcon = findViewById(R.id.volume_icon);

        // Initialize video URI based on intent source
        Uri videoUri = getIntent().getData();
        if (videoUri == null) {
            // Fallback for internal app launches
            String videoPath = getIntent().getStringExtra("VIDEO_PATH");
            if (videoPath == null || videoPath.isEmpty()) {
                Log.e("VideoError", "Invalid video path");
                Toast.makeText(this, "Invalid video file path", Toast.LENGTH_SHORT).show();
                finish(); // Close activity if no valid path
                return;
            }
            videoUri = Uri.fromFile(new File(videoPath)); // Convert path to URI for internal use
        }

        // Initialize ExoPlayer with a custom RenderersFactory that enables extension renderers
        // 1) Keep the default selector (no special offload prefs needed)
        DefaultTrackSelector trackSelector = new DefaultTrackSelector(this);

        // 2) RenderersFactory: prefer the FFmpeg extension for any track it can handle
        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(this)
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true);

        exoPlayer = new ExoPlayer.Builder(this, renderersFactory)
                .setTrackSelector(trackSelector)
                .build();

        playerView.setPlayer(exoPlayer);



        // Prepare and play video using the URI
        MediaItem mediaItem = MediaItem.fromUri(videoUri);
        exoPlayer.setMediaItem(mediaItem);
        exoPlayer.prepare();
        exoPlayer.play();

        // Set audio attributes with Media3's AudioAttributes class
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build();
        exoPlayer.setAudioAttributes(audioAttributes, true);

        // Initialize AudioManager
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

        // Initialize current brightness
        currentBrightness = getWindow().getAttributes().screenBrightness;

        // Implement buttons
        setupRotationButton();
        setupAudioTrackButton();

        setupCropButton();

        // Handle gestures for volume and brightness
        setupGestureDetection();

        // Make the activity full screen and use the notch area if available
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Request the window to use the notch area
            getWindow().getAttributes().layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        setupInteractionListeners();
        // Enter full-screen modes
        hideSystemUI();
        resetHideControlsTimer(); // Start the timer for hiding controls
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Hide overlays at the start
        brightnessOverlay.setVisibility(View.GONE);
        volumeOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(true);  // Resume playback
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (exoPlayer != null) {
            exoPlayer.setPlayWhenReady(false);  // Pause playback when activity is paused
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
            hideSystemUI();  // Reapply full-screen mode when the window regains focus
        }
    }

    private void setupAudioTrackButton() {
        audioTrackButton.setOnClickListener(v -> {
            if (exoPlayer == null) return;

            // Reuse the same selector you set up above
            DefaultTrackSelector trackSelector = (DefaultTrackSelector) exoPlayer.getTrackSelector();
            MappingTrackSelector.MappedTrackInfo mappedTrackInfo = trackSelector.getCurrentMappedTrackInfo();
            if (mappedTrackInfo == null) {
                Toast.makeText(this, "No audio tracks available.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Find the audio renderer index
            int audioRendererIndex = IntStream.range(0, mappedTrackInfo.getRendererCount())
                    .filter(i -> mappedTrackInfo.getRendererType(i) == C.TRACK_TYPE_AUDIO)
                    .findFirst()
                    .orElse(-1);
            if (audioRendererIndex == -1) {
                Toast.makeText(this, "No audio track available.", Toast.LENGTH_SHORT).show();
                return;
            }

            TrackGroupArray trackGroups = mappedTrackInfo.getTrackGroups(audioRendererIndex);
            if (trackGroups.length == 0) {
                Toast.makeText(this, "No audio tracks found.", Toast.LENGTH_SHORT).show();
                return;
            }

            PopupMenu popupMenu = new PopupMenu(this, audioTrackButton);

            // Populate menu with ALL tracks (including E‑AC‑3)
            for (int i = 0; i < trackGroups.length; i++) {
                TrackGroup trackGroup = trackGroups.get(i);
                for (int j = 0; j < trackGroup.length; j++) {
                    Format format = trackGroup.getFormat(j);
                    String lang = format.language;
                    String name = (lang == null || lang.isEmpty())
                            ? "Track " + (j + 1)
                            : "Track " + (j + 1) + " – " + lang;
                    popupMenu.getMenu().add(Menu.NONE, i * 100 + j, j, name);
                }
            }

            // Handle selection override as before
            popupMenu.setOnMenuItemClickListener(item -> {
                int groupIndex = item.getItemId() / 100;
                int trackIndex = item.getItemId() % 100;
                Format fmt = trackGroups.get(groupIndex).getFormat(trackIndex);

                if (isAudioFormatSupported(fmt)) {
                    DefaultTrackSelector.Parameters params =
                            trackSelector.buildUponParameters()
                                    .setRendererDisabled(audioRendererIndex, false)
                                    .setSelectionOverride(
                                            audioRendererIndex,
                                            trackGroups,
                                            new DefaultTrackSelector.SelectionOverride(groupIndex, trackIndex)
                                    )
                                    .build();
                    trackSelector.setParameters(params);
                    Toast.makeText(this, "Selected: " + item.getTitle(), Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Unsupported audio format.", Toast.LENGTH_SHORT).show();
                }
                return true;
            });

            popupMenu.show();
        });
    }

    private boolean isAudioFormatSupported(Format format) {
        String mime = format.sampleMimeType;
        List<String> supported = Arrays.asList(
                "audio/mp4a-latm",  // AAC
                "audio/mpeg",       // MP3
                "audio/vorbis",     // Vorbis
                "audio/opus",       // Opus
                "audio/eac3",        // ← add E‑AC‑3 here
                "audio/ac3"
        );
        return supported.contains(mime);
    }

    @SuppressLint("SourceLockedOrientationActivity")
    private void setupRotationButton() {
        rotateButton.setOnClickListener(v -> {
            if (!isLockedInPortrait && getResources().getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
                // Lock to portrait if currently in landscape
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                isLockedInPortrait = true;
                isLockedInLandscape = false;
                rotateButton.setImageResource(R.drawable.ic_rotation_locked); // Set locked icon
            } else if (!isLockedInLandscape && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
                // Lock to sensor-based landscape mode to allow flipping
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                isLockedInLandscape = true;
                isLockedInPortrait = false;
                rotateButton.setImageResource(R.drawable.ic_rotation_locked); // Set locked icon
            } else {
                // Unlock to allow sensor-based rotation
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR);
                isLockedInPortrait = false;
                isLockedInLandscape = false;
                rotateButton.setImageResource(R.drawable.ic_rotate); // Set unlocked icon
            }

            hideSystemUI(); // Reapply full-screen mode after rotation
        });
    }

    private void setupCropButton() {
        cropButton.setOnClickListener(v -> {
            PopupMenu popupMenu = new PopupMenu(this, cropButton);
            popupMenu.getMenu().add(Menu.NONE, 0, 0, "Original");
            popupMenu.getMenu().add(Menu.NONE, 1, 1, "Fill");
            popupMenu.getMenu().add(Menu.NONE, 2, 2, "Fit");

            popupMenu.setOnMenuItemClickListener(item -> {
                switch (item.getItemId()) {
                    case 0:
                        applyCropping(CropType.ORIGINAL);
                        return true;
                    case 1:
                        applyCropping(CropType.FILL);
                        return true;
                    case 2:
                        applyCropping(CropType.FIT);
                        return true;
                    default:
                        return false;
                }
            });

            popupMenu.show();
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
        // Flag to know if user is dragging to adjust brightness/volume
        final boolean[] isAdjusting = {false};
        // Remember down time for distinguishing taps
        final long[] downTime = {0};

        gestureDetector = new GestureDetectorCompat(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                isAdjusting[0] = false;
                downTime[0] = System.currentTimeMillis();
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                isAdjusting[0] = true;
                // Delegate to your existing handlers:
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

            switch (event.getAction()) {
                case MotionEvent.ACTION_UP:
                    long upTime = System.currentTimeMillis();
                    // It's a tap if we never scrolled AND duration < tap timeout
                    if (!isAdjusting[0]
                            && upTime - downTime[0] < ViewConfiguration.getTapTimeout()) {
                        // Toggle your controls
                        if (isControlsVisible) {
                            hideControls();
                        } else {
                            showControls();
                        }
                    }
                    break;
                // We no longer handle ACTION_DOWN here
            }
            return true;
        });
    }

    private void handleBrightnessChange(float deltaY) {
        if (deltaY != 0) {
            // Calculate brightness change
            float change = deltaY / 1000; // Use deltaY for brightness change
            // Get current brightness
            WindowManager.LayoutParams layoutParams = getWindow().getAttributes();
            float brightness = layoutParams.screenBrightness + change;

            // Ensure brightness is within the 0.0 (completely dark) to 1.0 (fully bright) range
            brightness = Math.max(0.0f, Math.min(1.0f, brightness));
            layoutParams.screenBrightness = brightness;
            getWindow().setAttributes(layoutParams);

            // Update current brightness and overlay
            currentBrightness = brightness;
            updateBrightnessOverlay(brightness);  // Call to update overlay
        }
    }
    
    private void handleVolumeChange(float deltaY) {
        if (deltaY != 0) {
            // Invert deltaY for volume control
            float volume = currentVolume + (deltaY / 100); // Adjust volume based on deltaY
            volume = Math.max(0, Math.min(maxVolume, volume));
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (int) volume, 0);
            currentVolume = volume;

            // Calculate the correct percentage for the progress bar
            float volumePercentage = (currentVolume / maxVolume) * 100;
            updateVolumeOverlay(volumePercentage); // Pass the percentage to update
        }
    }

    private void updateBrightnessOverlay(float brightness) {
        // Hide volume overlay if it’s currently visible
        volumeOverlay.setVisibility(View.GONE);
        volumeOverlay.removeCallbacks(hideControlsRunnable);

        int brightnessPercentage = (int) (brightness * 100);
        brightnessProgressBar.setProgress(brightnessPercentage);
        brightnessOverlay.setVisibility(View.VISIBLE);

        // Hide after a delay
        brightnessOverlay.postDelayed(() -> brightnessOverlay.setVisibility(View.GONE), 1500);
    }

    private void updateVolumeOverlay(float volumePercentage) {
        // Hide brightness overlay if it’s currently visible
        brightnessOverlay.setVisibility(View.GONE);
        brightnessOverlay.removeCallbacks(hideControlsRunnable);

        volumeProgressBar.setProgress((int) volumePercentage);
        volumeOverlay.setVisibility(View.VISIBLE);

        // Hide after a delay
        volumeOverlay.postDelayed(() -> volumeOverlay.setVisibility(View.GONE), 1500);
    }

    @OptIn(markerClass = UnstableApi.class)
    private void showControls() {
        playerView.showController();  // Show Media3's built-in controls
        rotateButton.setVisibility(View.VISIBLE);
        cropButton.setVisibility(View.VISIBLE);
        audioTrackButton.setVisibility(View.VISIBLE);
        isControlsVisible = true;
        resetHideControlsTimer();  // Restart the hide timer each time controls are shown
    }

    @OptIn(markerClass = UnstableApi.class)
    private void hideControls() {
        playerView.hideController();  // Hide Media3's built-in controls
        rotateButton.setVisibility(View.GONE);
        audioTrackButton.setVisibility(View.GONE);
        cropButton.setVisibility(View.GONE);
        isControlsVisible = false;
        hideSystemUI();  // Keep UI in immersive mode when controls are hidden
    }

    @SuppressLint("ClickableViewAccessibility")
    private void setupInteractionListeners() {
        // Assume these views are defined
        View brightnessOverlay = findViewById(R.id.brightness_overlay_container);
        View volumeOverlay = findViewById(R.id.overlay_container); // This is the volume overlay

        brightnessOverlay.setOnTouchListener((v, event) -> {
            // Do something for brightness interaction
            resetHideControlsTimer(); // Reset timer when user interacts
            return true; // Consume touch event
        });

        volumeOverlay.setOnTouchListener((v, event) -> {
            // Do something for volume interaction
            resetHideControlsTimer(); // Reset timer when user interacts
            return true; // Consume touch event
        });
    }

    private final Handler uiHandler = new Handler();
    private final Runnable hideControlsRunnable = () -> hideControls(); // Hides controls after a delay

    private void resetHideControlsTimer() {
        uiHandler.removeCallbacks(hideControlsRunnable); // Clear any previous callbacks
        uiHandler.postDelayed(hideControlsRunnable, 3000); // Auto-hide controls after 3 seconds
    }

    private void hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}