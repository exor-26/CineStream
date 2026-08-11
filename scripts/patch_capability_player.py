from pathlib import Path

PATH = Path("app/src/main/java/com/example/cinestream/VideoPlayerActivity.java")
text = PATH.read_text()


def replace_once(old: str, new: str) -> None:
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"Expected exactly one match, found {count}: {old[:100]!r}")
    text = text.replace(old, new, 1)


replace_once(
'''import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
''',
'''import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.FilteringMediaSource;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
'''
)

replace_once(
'''import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
''',
'''import java.io.File;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
'''
)

replace_once(
'''    private boolean isControlsVisible = true;
    private boolean decoderRecoveryAttempted = false;
    private boolean capabilityWarningLogged = false;
''',
'''    private boolean isControlsVisible = true;
    private boolean compatibilityTranscodeAttempted = false;
    private boolean capabilityWarningLogged = false;
    private CompatibilityVideoTranscoder.Session compatibilityTranscodeSession;
    private Uri compatibilityOriginalUri;
    private Uri compatibilityVideoUri;
    private String compatibilityPlaybackKey;
'''
)

replace_once(
'''            playbackKey = mediaItem.mediaId;
            videoUri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri : videoUri;
            currentVideoAssessment = null;
            capabilityWarningLogged = false;
            tvVideoName.setText(resolveCurrentTitle());
''',
'''            String previousPlaybackKey = playbackKey;
            playbackKey = mediaItem.mediaId;
            Uri transitionedUri = mediaItem.localConfiguration != null
                    ? mediaItem.localConfiguration.uri : null;
            boolean sameLogicalItem = previousPlaybackKey != null
                    && previousPlaybackKey.equals(playbackKey);
            if (!sameLogicalItem) {
                compatibilityTranscodeAttempted = false;
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
'''
)

old_create_prefix = '''    private void createPlayer(
            List<MediaItem> mediaItems,
            int startIndex,
            long startPositionMs,
            boolean playWhenReady
    ) {
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

'''
new_create_prefix = '''    private void initializePlayerShell() {
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

'''
replace_once(old_create_prefix, new_create_prefix)

replace_once(
'''        exoPlayer.prepare();
        exoPlayer.setPlayWhenReady(playWhenReady);
        applyVolumeBoost();
    }

    private void handlePlaybackError(PlaybackException error) {
''',
'''        exoPlayer.prepare();
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
'''
)

old_error = '''    private void handlePlaybackError(PlaybackException error) {
        Log.e("VideoPlayer", "Playback failed: " + error.getErrorCodeName(), error);

        if (!decoderRecoveryAttempted
                && PlaybackEnginePolicy.shouldRetryWithSoftwareAudio(decoderMode, error)) {
            decoderRecoveryAttempted = true;
            decoderMode = PlaybackEnginePolicy.DecoderMode.SOFTWARE_AUDIO_FIRST;

            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();

            Log.w("VideoPlayer", "Retrying with FFmpeg audio renderer at " + position + " ms");
            GlassUi.showToast(this, "Switching to compatibility audio decoder…");
            createPlayer(items, itemIndex, position, playWhenReady);
            return;
        }

        if (currentVideoAssessment != null
                && (currentVideoAssessment.support
                == DeviceVideoCapabilities.Support.NO_PLATFORM_DECODER
                || currentVideoAssessment.support
                == DeviceVideoCapabilities.Support.EXCEEDS_REPORTED_CAPABILITY)) {
            GlassUi.showToast(this,
                    "This device cannot reliably hardware-decode this video format/resolution.");
            return;
        }

        GlassUi.showToast(this, "Playback failed: " + error.getErrorCodeName());
    }
'''
new_error = '''    private void handlePlaybackError(PlaybackException error) {
        Log.e("VideoPlayer", "Playback failed: " + error.getErrorCodeName(), error);

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
            decoderMode = decoderMode.withSoftwareVideo();
            ArrayList<MediaItem> items = snapshotMediaItems();
            int itemIndex = exoPlayer != null ? exoPlayer.getCurrentMediaItemIndex() : 0;
            long position = exoPlayer != null ? Math.max(0L, exoPlayer.getCurrentPosition()) : 0L;
            boolean playWhenReady = exoPlayer == null || exoPlayer.getPlayWhenReady();

            Format failedVideo = PlaybackEnginePolicy.getFailedVideoFormat(error);
            Log.w("VideoPlayer",
                    "Retrying with bundled software video renderer for "
                            + (failedVideo != null ? failedVideo.sampleMimeType : "video")
                            + " at " + position + " ms");
            GlassUi.showToast(this, "Switching to compatibility video decoder…");
            rebuildPlayerPreservingCompatibility(items, itemIndex, position, playWhenReady);
            return;
        }

        Format failedVideo = PlaybackEnginePolicy.getFailedVideoFormat(error);
        DeviceVideoCapabilities.Assessment assessment = currentVideoAssessment;
        if (failedVideo != null
                && (assessment == null
                || assessment.support == DeviceVideoCapabilities.Support.UNKNOWN)) {
            assessment = DeviceVideoCapabilities.assess(failedVideo);
            currentVideoAssessment = assessment;
        }

        if (!compatibilityTranscodeAttempted
                && PlaybackEnginePolicy.isVideoDecoderFailure(error)
                && failedVideo != null
                && failedVideo.width > 0
                && failedVideo.height > 0
                && assessment != null
                && assessment.support
                == DeviceVideoCapabilities.Support.EXCEEDS_REPORTED_CAPABILITY) {
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
'''
replace_once(old_error, new_error)

old_snapshot = '''    private ArrayList<MediaItem> snapshotMediaItems() {
        ArrayList<MediaItem> items = new ArrayList<>();
        if (exoPlayer == null) {
            return items;
        }
        for (int i = 0; i < exoPlayer.getMediaItemCount(); i++) {
            items.add(exoPlayer.getMediaItemAt(i));
        }
        return items;
    }
'''
new_snapshot = '''    private ArrayList<MediaItem> snapshotMediaItems() {
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
        GlassUi.showToast(this, "Preparing a compatible video for this device…");
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
                        GlassUi.showToast(
                                VideoPlayerActivity.this,
                                "Playing compatible " + target.width + "×" + target.height
                                        + (target.frameRate > 0f
                                        ? " • " + Math.round(target.frameRate) + " fps"
                                        : "")
                        );
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
'''
replace_once(old_snapshot, new_snapshot)

replace_once(
'''    @Override
    protected void onDestroy() {
        releasePlayerOnly();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
''',
'''    @Override
    protected void onDestroy() {
        if (compatibilityTranscodeSession != null) {
            compatibilityTranscodeSession.cancel();
            compatibilityTranscodeSession = null;
        }
        releasePlayerOnly();
        uiHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
'''
)

replace_once(
'''            MediaItem mediaItem = exoPlayer.getCurrentMediaItem();
            if (mediaItem != null && mediaItem.localConfiguration != null) {
                return resolveDisplayTitle(mediaItem.localConfiguration.uri);
            }
''',
'''            MediaItem mediaItem = exoPlayer.getCurrentMediaItem();
            if (mediaItem != null && mediaItem.localConfiguration != null) {
                if (compatibilityPlaybackKey != null
                        && compatibilityPlaybackKey.equals(mediaItem.mediaId)
                        && compatibilityOriginalUri != null) {
                    return resolveDisplayTitle(compatibilityOriginalUri);
                }
                return resolveDisplayTitle(mediaItem.localConfiguration.uri);
            }
'''
)

PATH.write_text(text)
