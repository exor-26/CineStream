package com.example.cinestream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
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
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
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

    // The home screen only has three real modes. Keeping them explicit makes back-navigation
    // predictable and avoids brittle "which adapter is active?" checks.
    private enum ViewState { ALL_VIDEOS, FOLDER_LIST, FOLDER_CONTENTS }
    // Storage mutations can require a system approval round-trip, so we keep track of the
    // action that was in progress before Android temporarily leaves our app.
    private enum PendingMediaAction { NONE, DELETE, RENAME }

    private ViewState currentState = ViewState.ALL_VIDEOS;

    private RecyclerView recyclerView;
    private MaterialCardView titleCard;
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

    private VideoFile pendingVideoActionFile;
    private String pendingRenameName;
    private PendingMediaAction pendingMediaAction = PendingMediaAction.NONE;

    // Runtime media permission is the only permission we ask for now. Once granted, the app
    // can load the library directly from MediaStore without broad file-manager access.
    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) {
                    loadVideoFiles();
                } else {
                    Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show();
                }
            });

    // Rename/delete on Android 10+ may require a user-confirmation intent from MediaStore.
    // This launcher resumes the original operation after the system prompt returns.
    private final ActivityResultLauncher<IntentSenderRequest> mediaActionLauncher =
            registerForActivityResult(new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                if (result.getResultCode() != RESULT_OK || pendingVideoActionFile == null) {
                    clearPendingMediaAction();
                    return;
                }

                if (pendingMediaAction == PendingMediaAction.DELETE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        reloadAfterMutation();
                        Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show();
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

        // The blur is optional polish only. It is guarded because OEMs vary in how well they
        // support it, and we never want startup to fail over a visual effect.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getWindow().setBackgroundBlurRadius(40);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        titleCard = findViewById(R.id.titleCard);
        recyclerView = findViewById(R.id.recyclerView);
        toggleViewBtn = findViewById(R.id.toggleViewBtn);
        toggleIcon = findViewById(R.id.toggleIcon);

        searchCard = findViewById(R.id.searchCard);
        searchInput = findViewById(R.id.searchInput);
        searchClear = findViewById(R.id.searchClear);

        // The cards use slightly different alpha in light and dark mode so the frosted look
        // stays visible without feeling muddy or washed out.
        if (isLightMode()) {
            titleCard.setCardBackgroundColor(Color.argb(180, 255, 255, 255));
            toggleViewBtn.setCardBackgroundColor(Color.argb(200, 240, 240, 245));
            searchCard.setCardBackgroundColor(Color.argb(180, 220, 220, 225));
        } else {
            titleCard.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
            toggleViewBtn.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
            searchCard.setCardBackgroundColor(Color.argb(60, 255, 255, 255));
        }

        filteredAdapter = new VideoAdapter(this, filteredFiles, this);

        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // Search is intentionally limited to the "all videos" view. Folder mode should
                // stay focused on structure, while the top-level list handles broad discovery.
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
            // We explicitly close the keyboard so clearing search feels complete.
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
            }
        });

        // Insets are handled manually because the screen is designed edge-to-edge and the title,
        // search bar, and floating toggle button all need to dodge system bars cleanly.
        ViewCompat.setOnApplyWindowInsetsListener(titleCard, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) titleCard.getLayoutParams();
            lp.topMargin = statusBarHeight + 12;
            titleCard.setLayoutParams(lp);

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams tlp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) toggleViewBtn.getLayoutParams();
            tlp.bottomMargin = navBarHeight + 24;
            toggleViewBtn.setLayoutParams(tlp);

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

        toggleViewBtn.setOnClickListener(v -> {
            // The button is effectively a mode switch, so it gets both haptic and scale feedback
            // to make the transition feel deliberate.
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

        // Back behaves like navigation inside the app until we are back at the main all-videos
        // state, then we let the normal system back behavior finish the activity.
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
        // Reload on resume so rename/delete done through system approval flows are reflected
        // immediately when the activity comes back to the foreground.
        if (hasMediaReadPermission()) {
            loadVideoFiles();
        }
    }

    private void setupRecyclerView() {
        // A simple linear list keeps the media browser fast and familiar.
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        videoAdapter = new VideoAdapter(this, videoFiles, this);
        recyclerView.setAdapter(videoAdapter);
    }

    private void showAllVideos() {
        // Restore the primary browsing mode with search visible.
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
        // Folder mode hides search because the user is navigating structure instead of querying.
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
        // Folder contents are not separately queried from MediaStore; they are derived from the
        // already loaded library list for speed and to keep sorting consistent.
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
        // LinkedHashMap preserves insertion order so the folder list feels stable between
        // refreshes and mirrors the order in which videos were loaded.
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
        // Fade the rows under the title area so the glass header feels visually anchored instead
        // of floating awkwardly over fully opaque content.
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

    private void customizeStatusBar() {
        // We opt into edge-to-edge layout, then adjust icon appearance based on the current theme.
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
        // Permission flow is intentionally minimal: ask once, then go straight into the library.
        if (!hasMediaReadPermission()) {
            requestPermissionLauncher.launch(getReadPermission());
            return;
        }
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

    @SuppressLint({"NotifyDataSetChanged", "Range"})
    private void loadVideoFiles() {
        // MediaStore is now the single source of truth. We no longer depend on broad storage
        // permissions or raw file enumeration to discover local videos.
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = buildProjection();
        String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, sortOrder)) {
            if (cursor == null || !cursor.moveToFirst()) {
                videoFiles.clear();
                filteredFiles.clear();
                videoAdapter.notifyDataSetChanged();
                filteredAdapter.notifyDataSetChanged();
                if (currentState != ViewState.ALL_VIDEOS) {
                    showAllVideos();
                }
                Toast.makeText(this, "No video files found.", Toast.LENGTH_SHORT).show();
                return;
            }

            videoFiles.clear();
            do {
                // Store both display-friendly metadata and the content Uri needed for playback,
                // sharing, rename, and delete flows under scoped storage.
                long id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                long dateModified = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED));
                long sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
                Uri contentUri = ContentUris.withAppendedId(collection, id);

                String folderKey = resolveFolderKey(cursor);
                String folderName = resolveFolderName(cursor, folderKey);

                videoFiles.add(new VideoFile(
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

            pinLastPlayed();
            videoAdapter.notifyDataSetChanged();
            filteredAdapter.notifyDataSetChanged();

            if (currentState == ViewState.FOLDER_LIST || currentState == ViewState.FOLDER_CONTENTS) {
                showFolderList();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading videos: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private String[] buildProjection() {
        // Android 10+ gives us folder-friendly metadata directly; older devices still need the
        // legacy DATA column as a fallback for parent-folder derivation.
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
        // RELATIVE_PATH is the preferred folder identity because it survives scoped storage.
        // For pre-Android-10 devices we derive the parent path from the legacy DATA column.
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
        // BUCKET_DISPLAY_NAME is the clean user-facing folder label when MediaStore exposes it.
        // Otherwise we extract the final path segment ourselves as a fallback.
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
        // This is a tiny quality-of-life feature: bring the most recently watched item to the top
        // so resume is always one tap away.
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

    @Override
    public void onPlayVideo(VideoFile videoFile) {
        // Pass the Uri and stable playback key separately so the player can resume reliably even
        // when it is launched from inside the app instead of from an external intent.
        Intent intent = new Intent(this, VideoPlayerActivity.class);
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_URI, videoFile.getContentUri().toString());
        intent.putExtra(VideoPlayerActivity.EXTRA_PLAYBACK_KEY, videoFile.getPlaybackKey());
        intent.putExtra(VideoPlayerActivity.EXTRA_VIDEO_TITLE, videoFile.getName());
        startActivity(intent);
    }

    @Override
    public void onRenameVideo(VideoFile videoFile) {
        // We normalize the extension automatically so the user can rename the visible title
        // without accidentally stripping the file type.
        EditText input = new EditText(this);
        input.setText(videoFile.getName());

        new AlertDialog.Builder(this)
                .setTitle("Rename Video")
                .setView(input)
                .setPositiveButton("OK", (dialog, which) -> {
                    String requestedName = input.getText().toString().trim();
                    String normalizedName = normalizeDisplayName(videoFile.getName(), requestedName);
                    if (normalizedName == null) {
                        Toast.makeText(this, "Name cannot be empty", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    performRename(videoFile, normalizedName);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onDeleteVideo(VideoFile videoFile) {
        // Delete stays behind a confirmation because these are real device files, not app-local
        // temporary entries.
        new AlertDialog.Builder(this)
                .setTitle("Delete Video")
                .setMessage("Are you sure you want to delete this video?")
                .setPositiveButton("Yes", (dialog, which) -> performDelete(videoFile))
                .setNegativeButton("No", null)
                .show();
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
            // Direct delete works when the app already owns write access to the media item.
            // If not, Android will throw SecurityException and we fall back to a system prompt.
            int rows = getContentResolver().delete(videoFile.getContentUri(), null, null);
            if (rows > 0) {
                reloadAfterMutation();
                Toast.makeText(this, "Video deleted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Unable to delete video", Toast.LENGTH_SHORT).show();
            }
            clearPendingMediaAction();
        } catch (SecurityException securityException) {
            requestScopedWriteAccess(videoFile, PendingMediaAction.DELETE, null, securityException);
        } catch (Exception e) {
            clearPendingMediaAction();
            Toast.makeText(this, "Delete failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void performRename(VideoFile videoFile, String newName) {
        try {
            // Rename is just a MediaStore metadata update on modern Android. We do not move files
            // around manually anymore.
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, newName);

            int rows = getContentResolver().update(videoFile.getContentUri(), values, null, null);
            if (rows > 0) {
                reloadAfterMutation();
                Toast.makeText(this, "Video renamed", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
            }
            clearPendingMediaAction();
        } catch (SecurityException securityException) {
            requestScopedWriteAccess(videoFile, PendingMediaAction.RENAME, newName, securityException);
        } catch (Exception e) {
            clearPendingMediaAction();
            Toast.makeText(this, "Rename failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void requestScopedWriteAccess(
            VideoFile videoFile,
            PendingMediaAction action,
            String renameName,
            SecurityException securityException
    ) {
        // Cache the original request so we can continue after Android's confirmation dialog
        // returns control to the app.
        pendingVideoActionFile = videoFile;
        pendingRenameName = renameName;
        pendingMediaAction = action;

        try {
            IntentSender intentSender = null;
            // Android 11+ has explicit MediaStore request APIs. Android 10 exposes a
            // RecoverableSecurityException instead. We handle both paths.
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
                Toast.makeText(this, "This action is not supported on this device.", Toast.LENGTH_SHORT).show();
                return;
            }

            mediaActionLauncher.launch(new IntentSenderRequest.Builder(intentSender).build());
        } catch (Exception e) {
            clearPendingMediaAction();
            Toast.makeText(this, "Permission request failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void reloadAfterMutation() {
        // Re-query from MediaStore after any mutation so folder counts, ordering, and names stay
        // aligned with the actual system media database.
        loadVideoFiles();
        if (currentState == ViewState.FOLDER_LIST || currentState == ViewState.FOLDER_CONTENTS) {
            showFolderList();
        } else {
            showAllVideos();
        }
    }

    private void clearPendingMediaAction() {
        // Reset the temporary mutation state once the flow completes or is cancelled.
        pendingVideoActionFile = null;
        pendingRenameName = null;
        pendingMediaAction = PendingMediaAction.NONE;
    }
}
