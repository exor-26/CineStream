package com.example.cinestream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.media3.common.util.UnstableApi;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private enum ViewState { ALL_VIDEOS, FOLDER_LIST, FOLDER_CONTENTS }
    private enum PendingMediaAction { NONE, DELETE, RENAME }

    private ViewState currentState = ViewState.ALL_VIDEOS;

    private RecyclerView recyclerView;
    private MaterialCardView titleCard;
    private MaterialCardView aboutBtn;
    private ImageView aboutIcon;
    private MaterialCardView toggleViewBtn;
    private ImageView toggleIcon;

    private MaterialCardView searchCard;
    private EditText searchInput;
    private ImageButton searchClear;
    private final List<VideoFile> filteredFiles = new ArrayList<>();
    private VideoAdapter filteredAdapter;

    private VideoAdapter videoAdapter;
    private FolderAdapter folderAdapter;

    private final List<VideoFile> videoFiles = new ArrayList<>();
    private final List<FolderItem> folderItems = new ArrayList<>();
    private int lastNavBarInset = 0;
    private boolean initialHeaderPaddingApplied = false;

    // Phase 1 runtime state. MediaStore is only queried when the library is actually dirty.
    private boolean libraryLoaded = false;
    private boolean libraryLoadInProgress = false;
    private boolean libraryDirty = true;
    private boolean playbackUiDirty = false;
    private ContentObserver mediaObserver;

    private VideoFile pendingVideoActionFile;
    private String pendingRenameName;
    private PendingMediaAction pendingMediaAction = PendingMediaAction.NONE;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    registerMediaObserver();
                    libraryDirty = true;
                    loadVideoFiles();
                } else {
                    GlassUi.showToast(this, "Permission denied.");
                }
            });

    private final ActivityResultLauncher<IntentSenderRequest> mediaActionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || pendingVideoActionFile == null) {
                    clearPendingMediaAction();
                    return;
                }

                if (pendingMediaAction == PendingMediaAction.DELETE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reloadAfterMutation();
                        GlassUi.showToast(this, "Video deleted.");
                        clearPendingMediaAction();
                    } else {
                        performDelete(pendingVideoActionFile);
                    }
                } else if (pendingMediaAction == PendingMediaAction.RENAME) {
                    performRename(pendingVideoActionFile, pendingRenameName);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Base_Theme_CineStream);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean lowRamDevice = activityManager != null && activityManager.isLowRamDevice();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !lowRamDevice) {
            try {
                getWindow().setBackgroundBlurRadius(40);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        titleCard = findViewById(R.id.titleCard);
        aboutBtn = findViewById(R.id.aboutBtn);
        aboutIcon = findViewById(R.id.aboutIcon);
        recyclerView = findViewById(R.id.recyclerView);
        toggleViewBtn = findViewById(R.id.toggleViewBtn);
        toggleIcon = findViewById(R.id.toggleIcon);
        recyclerView.setVisibility(View.INVISIBLE);

        searchCard = findViewById(R.id.searchCard);
        searchInput = findViewById(R.id.searchInput);
        searchClear = findViewById(R.id.searchClear);

        int statusBarEst = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) statusBarEst = getResources().getDimensionPixelSize(resId);
        float dp = getResources().getDisplayMetrics().density;
        int estimatedPadding = statusBarEst + (int)((12 + 59 + 8 + 48 + 8) * dp);
        recyclerView.setPadding(0, estimatedPadding, 0, 0);

        if (isLightMode()) {
            titleCard.setCardBackgroundColor(Color.argb(180, 255, 255, 255));
            aboutBtn.setCardBackgroundColor(Color.argb(200, 240, 240, 245));
            aboutIcon.setColorFilter(Color.argb(255, 34, 43, 58));
            toggleViewBtn.setCardBackgroundColor(Color.argb(200, 240, 240, 245));
            searchCard.setCardBackgroundColor(Color.argb(180, 220, 220, 225));
        } else {
            titleCard.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
            aboutBtn.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
            aboutIcon.setColorFilter(Color.argb(255, 245, 247, 255));
            toggleViewBtn.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
            searchCard.setCardBackgroundColor(Color.argb(60, 255, 255, 255));
        }

        filteredAdapter = new VideoAdapter(this, filteredFiles, this);

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                if (query.isEmpty()) {
                    searchClear.setVisibility(View.GONE);
                    if (currentState == ViewState.ALL_VIDEOS) {
                        recyclerView.setAdapter(videoAdapter);
                    }
                } else {
                    searchClear.setVisibility(View.VISIBLE);
                    if (currentState == ViewState.ALL_VIDEOS) {
                        filteredFiles.clear();
                        for (VideoFile videoFile : videoFiles) {
                            if (videoFile.getName().toLowerCase().contains(query)) {
                                filteredFiles.add(videoFile);
                            }
                        }
                        recyclerView.setAdapter(filteredAdapter);
                        filteredAdapter.notifyDataSetChanged();
                    }
                }
            }
        });

        searchClear.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.clearFocus();
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(titleCard, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            lastNavBarInset = navBarHeight;

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) titleCard.getLayoutParams();
            lp.topMargin = statusBarHeight + 12;
            titleCard.setLayoutParams(lp);

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams tlp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) toggleViewBtn.getLayoutParams();
            tlp.bottomMargin = navBarHeight + 24;
            toggleViewBtn.setLayoutParams(tlp);

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams alp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) aboutBtn.getLayoutParams();
            alp.topMargin = statusBarHeight + 12;
            aboutBtn.setLayoutParams(alp);
            titleCard.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            int cardBottom = titleCard.getBottom();
                            if (cardBottom > 0) {
                                int gap = (int) (8 * getResources().getDisplayMetrics().density);
                                androidx.constraintlayout.widget.ConstraintLayout.LayoutParams slp =
                                        (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) searchCard.getLayoutParams();
                                slp.topMargin = cardBottom + gap;
                                searchCard.setLayoutParams(slp);

                                searchCard.getViewTreeObserver().addOnGlobalLayoutListener(
                                        new ViewTreeObserver.OnGlobalLayoutListener() {
                                            @Override
                                            public void onGlobalLayout() {
                                                int searchBottom = searchCard.getBottom();
                                                if (searchBottom > 0) {
                                                    int gap2 = (int) (8 * getResources().getDisplayMetrics().density);
                                                    recyclerView.setPadding(0, searchBottom + gap2, 0, navBarHeight);
                                                    recyclerView.scrollToPosition(0);
                                                    searchCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                                }
                                            }
                                        });

                                titleCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        }
                    });
            return insets;
        });

        aboutBtn.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showAboutDialog();
        });

        toggleViewBtn.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            v.animate()
                    .scaleX(0.85f)
                    .scaleY(0.85f)
                    .setDuration(80)
                    .withEndAction(() ->
                            v.animate()
                                    .scaleX(1f)
                                    .scaleY(1f)
                                    .setDuration(80)
                                    .start()
                    ).start();

            if (currentState == ViewState.ALL_VIDEOS) {
                showFolderList();
            } else {
                showAllVideos();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (currentState == ViewState.FOLDER_CONTENTS) {
                    showFolderList();
                } else if (currentState == ViewState.FOLDER_LIST) {
                    showAllVideos();
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });

        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                applyScrollOpacity(rv);
            }
        });

        customizeStatusBar();
        setupRecyclerView();
        checkPermissionsAndLoadFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasMediaReadPermission() || !libraryLoaded) {
            return;
        }

        // Playback progress and last-played ordering live in SharedPreferences, so refreshing them
        // does not require a MediaStore scan. This preserves the old visible resume behavior while
        // avoiding disk/provider work every time the player closes.
        if (playbackUiDirty) {
            playbackUiDirty = false;
            refreshPlaybackPresentation();
        }

        if (libraryDirty && !libraryLoadInProgress) {
            loadVideoFiles();
        }
    }

    @Override
    protected void onDestroy() {
        if (mediaObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(mediaObserver);
            } catch (Exception ignored) {
            }
            mediaObserver = null;
        }
        super.onDestroy();
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);
        videoAdapter = new VideoAdapter(this, videoFiles, this);
        recyclerView.setAdapter(videoAdapter);
    }

    private void refreshPlaybackPresentation() {
        pinLastPlayed();
        videoAdapter.notifyDataSetChanged();
        filteredAdapter.notifyDataSetChanged();

        RecyclerView.Adapter<?> activeAdapter = recyclerView.getAdapter();
        if (activeAdapter instanceof VideoAdapter
                && activeAdapter != videoAdapter
                && activeAdapter != filteredAdapter) {
            activeAdapter.notifyDataSetChanged();
        }
    }

    private void showAllVideos() {
        currentState = ViewState.ALL_VIDEOS;
        toggleIcon.setImageResource(R.drawable.folder);
        recyclerView.setAdapter(videoAdapter);
        videoAdapter.notifyDataSetChanged();
        recyclerView.scrollToPosition(0);
        searchCard.setVisibility(View.VISIBLE);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(0, searchCard.getBottom() + gap,
                recyclerView.getPaddingRight(), recyclerView.getPaddingBottom());
    }

    private void showFolderList() {
        currentState = ViewState.FOLDER_LIST;
        toggleIcon.setImageResource(R.drawable.video);
        searchCard.setVisibility(View.GONE);
        searchInput.setText("");
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(0, titleCard.getBottom() + gap,
                recyclerView.getPaddingRight(), recyclerView.getPaddingBottom());
        buildFolderList();
        folderAdapter = new FolderAdapter(this, folderItems, folder -> {
            recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showFolderContents(folder);
        });
        recyclerView.setAdapter(folderAdapter);
        recyclerView.scrollToPosition(0);
    }

    private void showFolderContents(FolderItem folder) {
        currentState = ViewState.FOLDER_CONTENTS;
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(0, titleCard.getBottom() + gap,
                recyclerView.getPaddingRight(), recyclerView.getPaddingBottom());

        List<VideoFile> filtered = new ArrayList<>();
        for (VideoFile videoFile : videoFiles) {
            if (folder.getPath().equals(videoFile.getFolderKey())) {
                filtered.add(videoFile);
            }
        }

        VideoAdapter folderVideoAdapter = new VideoAdapter(this, filtered, this);
        recyclerView.setAdapter(folderVideoAdapter);
        recyclerView.scrollToPosition(0);
    }

    private void buildFolderList() {
        folderItems.clear();
        Map<String, FolderItem> folderMap = new LinkedHashMap<>();
        for (VideoFile videoFile : videoFiles) {
            String folderKey = videoFile.getFolderKey();
            if (folderKey == null || folderKey.isEmpty()) {
                continue;
            }

            FolderItem existing = folderMap.get(folderKey);
            if (existing == null) {
                folderMap.put(folderKey, new FolderItem(videoFile.getFolderName(), folderKey, 1));
            } else {
                folderMap.put(folderKey,
                        new FolderItem(existing.getName(), existing.getPath(), existing.getVideoCount() + 1));
            }
        }
        folderItems.addAll(folderMap.values());
    }

    private void applyScrollOpacity(RecyclerView rv) {
        if (titleCard == null) return;
        float fadeEnd = titleCard.getBottom();
        float fadeZone = titleCard.getHeight();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child = rv.getChildAt(i);
            float childTop = child.getY();
            if (childTop >= fadeEnd) {
                child.setAlpha(1f);
            } else if (childTop <= fadeEnd - fadeZone) {
                child.setAlpha(0.08f);
            } else {
                float fraction = (childTop - (fadeEnd - fadeZone)) / fadeZone;
                child.setAlpha(Math.max(0.08f, fraction));
            }
        }
    }

    private void prepareInitialHeaderPadding() {
        View root = findViewById(android.R.id.content);
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (initialHeaderPaddingApplied) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                    return true;
                }

                int anchorBottom = searchCard.getBottom();
                if (anchorBottom <= 0) {
                    return false;
                }

                int gap = (int) (8 * getResources().getDisplayMetrics().density);
                recyclerView.setPadding(0, anchorBottom + gap, 0, lastNavBarInset);
                initialHeaderPaddingApplied = true;
                root.getViewTreeObserver().removeOnPreDrawListener(this);
                return true;
            }
        });
    }

    private void customizeStatusBar() {
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        if (controller != null) {
            controller.setAppearanceLightStatusBars(isLightMode());
            controller.setAppearanceLightNavigationBars(isLightMode());
        }
    }

    private boolean isLightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void checkPermissionsAndLoadFiles() {
        if (!hasMediaReadPermission()) {
            requestPermissionLauncher.launch(getReadPermission());
            return;
        }
        registerMediaObserver();
        loadVideoFiles();
    }

    private boolean hasMediaReadPermission() {
        return ContextCompat.checkSelfPermission(this, getReadPermission())
                == PackageManager.PERMISSION_GRANTED;
    }

    private String getReadPermission() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
    }

    private void registerMediaObserver() {
        if (mediaObserver != null || !hasMediaReadPermission()) {
            return;
        }
        mediaObserver = new ContentObserver(AppExecutors.mainHandler()) {
            @Override
            public void onChange(boolean selfChange) {
                libraryDirty = true;
            }

            @Override
            public void onChange(boolean selfChange, Uri uri) {
                libraryDirty = true;
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    true,
                    mediaObserver
            );
        } catch (Exception e) {
            mediaObserver = null;
        }
    }

    @SuppressLint({"NotifyDataSetChanged", "Range"})
    private void loadVideoFiles() {
        if (libraryLoadInProgress) {
            return;
        }

        libraryLoadInProgress = true;
        libraryDirty = false;

        final Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        final String[] projection = buildProjection();
        final String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

        AppExecutors.mediaIo().execute(() -> {
            List<VideoFile> loadedFiles = new ArrayList<>();
            String errorMessage = null;

            try (Cursor cursor = getContentResolver().query(
                    collection,
                    projection,
                    null,
                    null,
                    sortOrder
            )) {
                if (cursor != null && cursor.moveToFirst()) {
                    do {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                        String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                        long dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED));
                        long sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                        Uri contentUri = ContentUris.withAppendedId(collection, id);

                        String folderKey = resolveFolderKey(cursor);
                        String folderName = resolveFolderName(cursor, folderKey);

                        loadedFiles.add(new VideoFile(
                                id,
                                displayName,
                                contentUri,
                                sizeBytes,
                                dateModified,
                                folderName,
                                folderKey,
                                "media:" + id
                        ));
                    } while (cursor.moveToNext());
                }
            } catch (Exception e) {
                e.printStackTrace();
                errorMessage = e.getMessage();
            }

            final String finalErrorMessage = errorMessage;
            AppExecutors.mainHandler().post(() -> {
                libraryLoadInProgress = false;

                if (isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                    return;
                }

                if (finalErrorMessage != null) {
                    libraryDirty = true;
                    recyclerView.setVisibility(View.VISIBLE);
                    GlassUi.showToast(this, "Error loading videos: " + finalErrorMessage);
                    return;
                }

                videoFiles.clear();
                videoFiles.addAll(loadedFiles);
                libraryLoaded = true;

                if (loadedFiles.isEmpty()) {
                    filteredFiles.clear();
                    videoAdapter.notifyDataSetChanged();
                    filteredAdapter.notifyDataSetChanged();
                    recyclerView.setVisibility(View.VISIBLE);
                    if (currentState != ViewState.ALL_VIDEOS) {
                        showAllVideos();
                    }
                    GlassUi.showToast(this, "No video files found.");
                } else {
                    pinLastPlayed();
                    videoAdapter.notifyDataSetChanged();
                    filteredAdapter.notifyDataSetChanged();
                    recyclerView.setVisibility(View.VISIBLE);

                    if (currentState == ViewState.FOLDER_LIST || currentState == ViewState.FOLDER_CONTENTS) {
                        showFolderList();
                    }
                }

                if (libraryDirty) {
                    loadVideoFiles();
                }
            });
        });
    }

    private String[] buildProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            };
        }
        return new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATA
        };
    }

    private String resolveFolderKey(Cursor cursor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int index = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
            if (index >= 0) {
                String relativePath = cursor.getString(index);
                if (relativePath != null && !relativePath.isEmpty()) {
                    return relativePath;
                }
            }
        }

        int dataIndex = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
        if (dataIndex >= 0) {
            String fullPath = cursor.getString(dataIndex);
            if (fullPath != null) {
                File parent = new File(fullPath).getParentFile();
                if (parent != null) {
                    return parent.getAbsolutePath();
                }
            }
        }
        return "Unknown";
    }

    private String resolveFolderName(Cursor cursor, String folderKey) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            int bucketIndex = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            if (bucketIndex >= 0) {
                String bucketName = cursor.getString(bucketIndex);
                if (bucketName != null && !bucketName.isEmpty()) {
                    return bucketName;
                }
            }
        }

        if (folderKey == null || folderKey.isEmpty() || "Unknown".equals(folderKey)) {
            return "Unknown";
        }

        String normalized = folderKey.endsWith("/") ? folderKey.substring(0, folderKey.length() - 1) : folderKey;
        int slash = normalized.lastIndexOf('/');
        if (slash >= 0 && slash < normalized.length() - 1) {
            return normalized.substring(slash + 1);
        }
        int backslash = normalized.lastIndexOf('\\');
        if (backslash >= 0 && backslash < normalized.length() - 1) {
            return normalized.substring(backslash + 1);
        }
        return normalized;
    }

    private void pinLastPlayed() {
        String lastPlayedKey = PlaybackPrefs.getInstance(this).getLastPlayedKey();
        if (lastPlayedKey == null) {
            return;
        }

        for (int i = 0; i < videoFiles.size(); i++) {
            if (lastPlayedKey.equals(videoFiles.get(i).getPlaybackKey())) {
                VideoFile lastPlayed = videoFiles.remove(i);
                videoFiles.add(0, lastPlayed);
                return;
            }
        }
    }

    @UnstableApi
    @Override
    public void onPlayVideo(VideoFile videoFile, List<VideoFile> playlist, int position) {
        playbackUiDirty = true;
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, videoFile.getContentUri().toString());
        intent.putExtra(VideoPlayerActivity.EXTRA_PLAYBACK_KEY, videoFile.getPlaybackKey());
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, videoFile.getName());
        intent.putStringArrayListExtra(
                VideoPlayerActivity.EXTRA_PLAYLIST_URIS,
                buildPlaylistValues(playlist, PlaylistField.URI)
        );
        intent.putStringArrayListExtra(
                VideoPlayerActivity.EXTRA_PLAYLIST_KEYS,
                buildPlaylistValues(playlist, PlaylistField.KEY)
        );
        intent.putStringArrayListExtra(
                VideoPlayerActivity.EXTRA_PLAYLIST_TITLES,
                buildPlaylistValues(playlist, PlaylistField.TITLE)
        );
        intent.putExtra(VideoPlayerActivity.EXTRA_PLAYLIST_INDEX, position);
        startActivity(intent);
    }

    private enum PlaylistField { URI, KEY, TITLE }

    private ArrayList<String> buildPlaylistValues(List<VideoFile> playlist, PlaylistField field) {
        ArrayList<String> values = new ArrayList<>();
        if (playlist == null) {
            return values;
        }

        for (VideoFile file : playlist) {
            switch (field) {
                case URI:
                    values.add(file.getContentUri().toString());
                    break;
                case KEY:
                    values.add(file.getPlaybackKey());
                    break;
                case TITLE:
                    values.add(file.getName());
                    break;
            }
        }
        return values;
    }

    @Override
    public void onRenameVideo(VideoFile videoFile) {
        GlassUi.showInputDialog(
                this,
                "Rename video",
                videoFile.getName(),
                "Enter a new name",
                "Save",
                value -> {
                    String requestedName = value.trim();
                    String normalizedName = normalizeDisplayName(videoFile.getName(), requestedName);
                    if (normalizedName == null) {
                        GlassUi.showToast(this, "Name cannot be empty.");
                        return;
                    }
                    performRename(videoFile, normalizedName);
                }
        );
    }

    @Override
    public void onDeleteVideo(VideoFile videoFile) {
        GlassUi.showConfirmDialog(
                this,
                "Delete video",
                "This will remove the selected media item from device storage.",
                "Delete",
                () -> performDelete(videoFile)
        );
    }

    private String normalizeDisplayName(String currentName, String requestedName) {
        if (requestedName == null || requestedName.trim().isEmpty()) {
            return null;
        }

        int dot = currentName.lastIndexOf('.');
        if (dot <= 0) {
            return requestedName;
        }

        String extension = currentName.substring(dot);
        return requestedName.endsWith(extension) ? requestedName : requestedName + extension;
    }

    private void performDelete(VideoFile videoFile) {
        try {
            int rows = getContentResolver().delete(videoFile.getContentUri(), null, null);
            if (rows > 0) {
                reloadAfterMutation();
                GlassUi.showToast(this, "Video deleted.");
            } else {
                GlassUi.showToast(this, "Unable to delete video.");
            }
            clearPendingMediaAction();
        } catch (SecurityException securityException) {
            requestScopedWriteAccess(videoFile, PendingMediaAction.DELETE, null, securityException);
        } catch (Exception e) {
            clearPendingMediaAction();
            GlassUi.showToast(this, "Delete failed: " + e.getMessage());
        }
    }

    private void performRename(VideoFile videoFile, String newName) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, newName);

            int rows = getContentResolver().update(videoFile.getContentUri(), values, null, null);
            if (rows > 0) {
                reloadAfterMutation();
                GlassUi.showToast(this, "Video renamed.");
            } else {
                GlassUi.showToast(this, "Rename failed.");
            }
            clearPendingMediaAction();
        } catch (SecurityException securityException) {
            requestScopedWriteAccess(videoFile, PendingMediaAction.RENAME, newName, securityException);
        } catch (Exception e) {
            clearPendingMediaAction();
            GlassUi.showToast(this, "Rename failed: " + e.getMessage());
        }
    }

    private void requestScopedWriteAccess(
            VideoFile videoFile,
            PendingMediaAction action,
            String renameName,
            SecurityException securityException
    ) {
        pendingVideoActionFile = videoFile;
        pendingRenameName = renameName;
        pendingMediaAction = action;

        try {
            IntentSender intentSender = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PendingIntent pendingIntent = action == PendingMediaAction.DELETE
                        ? MediaStore.createDeleteRequest(getContentResolver(),
                        Collections.singletonList(videoFile.getContentUri()))
                        : MediaStore.createWriteRequest(getContentResolver(),
                        Collections.singletonList(videoFile.getContentUri()));
                intentSender = pendingIntent.getIntentSender();
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q
                    && securityException instanceof android.app.RecoverableSecurityException) {
                intentSender = ((android.app.RecoverableSecurityException) securityException)
                        .getUserAction()
                        .getActionIntent()
                        .getIntentSender();
            }

            if (intentSender == null) {
                clearPendingMediaAction();
                GlassUi.showToast(this, "This action is not supported on this device.");
                return;
            }

            mediaActionLauncher.launch(new IntentSenderRequest.Builder(intentSender).build());
        } catch (Exception e) {
            clearPendingMediaAction();
            GlassUi.showToast(this, "Permission request failed: " + e.getMessage());
        }
    }

    private void reloadAfterMutation() {
        libraryDirty = true;
        loadVideoFiles();
    }

    private void clearPendingMediaAction() {
        pendingVideoActionFile = null;
        pendingRenameName = null;
        pendingMediaAction = PendingMediaAction.NONE;
    }

    private void showAboutDialog() {
        List<GlassUi.InfoItem> rows = new ArrayList<>();
        rows.add(new GlassUi.InfoItem("Project", "CineStream"));
        rows.add(new GlassUi.InfoItem("Version", "v9.2"));
        rows.add(new GlassUi.InfoItem("Developer", "Aditya"));
        rows.add(new GlassUi.InfoItem("About", "CineStream is a local-first Android video player focused on clean browsing, smooth playback, folder navigation, and a refined glass-inspired interface."));
        rows.add(new GlassUi.InfoItem("Highlights", "Folder and list browsing, playlist-aware next and previous playback, subtitle and audio track selection, gesture controls, resume progress, and detailed media inspection."));
        rows.add(new GlassUi.InfoItem("Design", "Built to keep the library simple on the home screen while giving the player a modern full-screen experience with lightweight overlays and direct controls."));
        rows.add(new GlassUi.InfoItem("License", "MIT License"));
        rows.add(new GlassUi.InfoItem("Open source note", "This project may be used, copied, modified, merged, published, distributed, sublicensed, and sold under the terms of the MIT License."));
        GlassUi.showInfoDialog(this, "About CineStream", rows);
    }
}
