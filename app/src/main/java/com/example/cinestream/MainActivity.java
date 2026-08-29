package com.example.cinestream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.app.PendingIntent;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

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

import eightbitlab.com.blurview.BlurTarget;
import eightbitlab.com.blurview.BlurView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements VideoAdapter.Listener {

    private static final long SEARCH_DEBOUNCE_MS = 140L;

    private enum ViewState { ALL_VIDEOS, FOLDER_LIST, FOLDER_CONTENTS }
    private enum PendingMediaAction { NONE, DELETE, RENAME }
    private enum SortMode { NAME, DATE, DURATION }

    private ViewState currentState = ViewState.ALL_VIDEOS;

    private RecyclerView recyclerView;
    private BlurTarget mainBlurTarget;
    private BlurView titleCard;
    private BlurView aboutBtn;
    private ImageView aboutIcon;
    private BlurView toggleViewBtn;
    private ImageView toggleIcon;

    private BlurView searchCard;
    private EditText searchInput;
    private ImageButton searchClear;
    private ImageButton sortBtn;
    private BlurView sortPopup;
    private View sortDismissLayer;
    private TextView sortByName;
    private TextView sortByDate;
    private TextView sortByDuration;
    private SortMode sortMode = SortMode.DATE;
    private BlurView selectionActionBar;
    private ImageButton selectionRename;
    private ImageButton selectionShare;
    private ImageButton selectionDelete;
    private ImageButton selectionDetails;
    private VideoAdapter selectionAdapter;
    private final List<VideoFile> selectedVideos = new ArrayList<>();

    private final List<VideoFile> videoFiles = new ArrayList<>();
    private final List<VideoFile> filteredFiles = new ArrayList<>();
    private final List<FolderItem> folderItems = new ArrayList<>();

    private VideoAdapter videoAdapter;
    private VideoAdapter filteredAdapter;
    private FolderAdapter folderAdapter;
    private FolderItem currentFolder;
    private VideoListDiffer videoListDiffer;
    private VideoListDiffer filteredListDiffer;

    private final Handler mainHandler = AppExecutors.mainHandler();
    private Runnable pendingSearchRunnable;
    private int searchGeneration = 0;

    private int lastNavBarInset = 0;
    private boolean initialHeaderPaddingApplied = false;

    private boolean libraryLoaded = false;
    private boolean libraryLoadInProgress = false;
    private boolean libraryDirty = true;
    private boolean playbackUiDirty = false;
    private ContentObserver mediaObserver;

    private VideoFile pendingVideoActionFile;
    private final ArrayList<VideoFile> pendingDeleteFiles = new ArrayList<>();
    private final ArrayList<VideoFile> legacyDeleteQueue = new ArrayList<>();
    private int legacyDeletedCount;
    private boolean legacyDeleteBatchActive;
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
                boolean hasPendingTarget = pendingVideoActionFile != null
                        || !pendingDeleteFiles.isEmpty();
                if (result.getResultCode() != RESULT_OK || !hasPendingTarget) {
                    clearPendingMediaAction();
                    return;
                }

                if (pendingMediaAction == PendingMediaAction.DELETE) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        int deletedCount = pendingDeleteFiles.isEmpty()
                                ? 1 : pendingDeleteFiles.size();
                        reloadAfterMutation();
                        GlassUi.showToast(this, deletedCount == 1
                                ? "Video deleted."
                                : deletedCount + " videos deleted.");
                        clearPendingMediaAction();
                    } else if (legacyDeleteBatchActive) {
                        deleteApprovedLegacyItem();
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

        mainBlurTarget = findViewById(R.id.main_blur_target);
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
        sortBtn = findViewById(R.id.sortBtn);
        sortPopup = findViewById(R.id.sortPopup);
        sortDismissLayer = findViewById(R.id.sortDismissLayer);
        sortByName = findViewById(R.id.sortByName);
        sortByDate = findViewById(R.id.sortByDate);
        sortByDuration = findViewById(R.id.sortByDuration);
        selectionActionBar = findViewById(R.id.selectionActionBar);
        selectionRename = findViewById(R.id.selectionRename);
        selectionShare = findViewById(R.id.selectionShare);
        selectionDelete = findViewById(R.id.selectionDelete);
        selectionDetails = findViewById(R.id.selectionDetails);

        int statusBarEst = 0;
        int resId = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) statusBarEst = getResources().getDimensionPixelSize(resId);
        float dp = getResources().getDisplayMetrics().density;
        int estimatedPadding = statusBarEst + (int) ((12 + 59 + 8 + 48 + 8) * dp);
        recyclerView.setPadding(0, estimatedPadding, 0, 0);

        aboutIcon.setColorFilter(ContextCompat.getColor(this, R.color.glass_icon_tint));
        setupBrowsingGlass();

        setupRecyclerView();
        setupSearch();
        setupSelectionActions();
        setupSortMenu();
        setupInsets();

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
                if (sortPopup.getVisibility() == View.VISIBLE) {
                    hideSortPopup();
                } else if (selectionAdapter != null) {
                    exitSelectionMode();
                } else if (currentState == ViewState.FOLDER_CONTENTS) {
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
        checkPermissionsAndLoadFiles();
    }

    private void setupBrowsingGlass() {
        ActivityManager activityManager = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        boolean lowRamDevice = activityManager != null && activityManager.isLowRamDevice();
        float blurRadius = lowRamDevice ? 10f : 18f;

        setupGlassView(titleCard, blurRadius);
        setupGlassView(aboutBtn, blurRadius);
        setupGlassView(searchCard, blurRadius);
        setupGlassView(toggleViewBtn, blurRadius);
        setupGlassView(selectionActionBar, blurRadius);
        setupGlassView(sortPopup, blurRadius);
    }

    private void setupGlassView(BlurView blurView, float blurRadius) {
        blurView.setupWith(mainBlurTarget).setBlurRadius(blurRadius);
        blurView.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        blurView.setClipToOutline(true);
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setHasFixedSize(true);

        videoAdapter = new VideoAdapter(this, videoFiles, this);
        filteredAdapter = new VideoAdapter(this, filteredFiles, this);
        videoListDiffer = new VideoListDiffer(videoFiles, videoAdapter);
        filteredListDiffer = new VideoListDiffer(filteredFiles, filteredAdapter);
        recyclerView.setAdapter(videoAdapter);
    }

    private void setupSearch() {
        searchInput.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void afterTextChanged(android.text.Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handleSearchText(s);
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
    }

    private void setupSortMenu() {
        String savedMode = getSharedPreferences("library_ui", MODE_PRIVATE)
                .getString("video_sort_mode", SortMode.DATE.name());
        try {
            sortMode = SortMode.valueOf(savedMode);
        } catch (IllegalArgumentException ignored) {
            sortMode = SortMode.DATE;
        }

        sortBtn.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (sortPopup.getVisibility() == View.VISIBLE) {
                hideSortPopup();
            } else {
                showSortPopup();
            }
        });
        sortDismissLayer.setOnClickListener(v -> hideSortPopup());
        sortByName.setOnClickListener(v -> selectSortMode(SortMode.NAME));
        sortByDate.setOnClickListener(v -> selectSortMode(SortMode.DATE));
        sortByDuration.setOnClickListener(v -> selectSortMode(SortMode.DURATION));
        updateSortOptions();
    }

    private void showSortPopup() {
        updateSortOptions();
        sortDismissLayer.setVisibility(View.VISIBLE);
        sortPopup.setAlpha(0f);
        sortPopup.setScaleX(0.96f);
        sortPopup.setScaleY(0.96f);
        sortPopup.setPivotX(sortPopup.getWidth());
        sortPopup.setPivotY(0f);
        sortPopup.setVisibility(View.VISIBLE);
        sortPopup.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150L)
                .start();
    }

    private void hideSortPopup() {
        sortPopup.animate().cancel();
        sortPopup.setVisibility(View.GONE);
        sortDismissLayer.setVisibility(View.GONE);
    }

    private void updateSortOptions() {
        bindSortOption(sortByName, SortMode.NAME);
        bindSortOption(sortByDate, SortMode.DATE);
        bindSortOption(sortByDuration, SortMode.DURATION);
    }

    private void bindSortOption(TextView view, SortMode option) {
        boolean selected = sortMode == option;
        view.setActivated(selected);
        view.setTextColor(ContextCompat.getColor(
                this,
                selected ? R.color.glass_text_accent : R.color.glass_text_primary
        ));
        view.setCompoundDrawablePadding(selected
                ? (int) (8 * getResources().getDisplayMetrics().density) : 0);
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, selected ? R.drawable.ic_check : 0, 0
        );
        view.setCompoundDrawableTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.glass_text_accent)));
    }

    private void selectSortMode(SortMode selectedMode) {
        sortMode = selectedMode;
        getSharedPreferences("library_ui", MODE_PRIVATE)
                .edit()
                .putString("video_sort_mode", sortMode.name())
                .apply();
        hideSortPopup();
        if (selectionAdapter != null) {
            exitSelectionMode();
        }

        ArrayList<VideoFile> sorted = new ArrayList<>(videoFiles);
        sortVideos(sorted);
        videoListDiffer.submit(sorted, this::refreshVisibleVideosAfterSort);
    }

    private void refreshVisibleVideosAfterSort() {
        if (currentState == ViewState.FOLDER_CONTENTS && currentFolder != null) {
            showFolderContents(currentFolder);
        } else if (currentState == ViewState.ALL_VIDEOS) {
            if (currentSearchQuery().isEmpty()) {
                recyclerView.setAdapter(videoAdapter);
            } else {
                rerunCurrentSearch();
            }
        }
        recyclerView.scrollToPosition(0);
    }

    private void sortVideos(List<VideoFile> videos) {
        Comparator<VideoFile> nameComparator = (left, right) -> {
            String leftName = left.getName() == null ? "" : left.getName();
            String rightName = right.getName() == null ? "" : right.getName();
            int result = leftName.compareToIgnoreCase(rightName);
            return result != 0 ? result : Long.compare(right.getDateModified(), left.getDateModified());
        };

        if (sortMode == SortMode.NAME) {
            videos.sort(nameComparator);
        } else if (sortMode == SortMode.DURATION) {
            videos.sort((left, right) -> {
                int result = Long.compare(right.getDurationMs(), left.getDurationMs());
                return result != 0 ? result : nameComparator.compare(left, right);
            });
        } else {
            videos.sort((left, right) -> {
                int result = Long.compare(right.getDateModified(), left.getDateModified());
                return result != 0 ? result : nameComparator.compare(left, right);
            });
        }
    }

    private void setupSelectionActions() {
        selectionRename.setOnClickListener(v -> {
            if (selectedVideos.size() != 1 || selectionAdapter == null) {
                return;
            }
            VideoFile selected = selectedVideos.get(0);
            exitSelectionMode();
            onRenameVideo(selected);
        });

        selectionShare.setOnClickListener(v -> {
            if (selectedVideos.isEmpty() || selectionAdapter == null) {
                return;
            }
            VideoAdapter adapter = selectionAdapter;
            ArrayList<VideoFile> videos = new ArrayList<>(selectedVideos);
            exitSelectionMode();
            adapter.shareVideos(videos);
        });

        selectionDelete.setOnClickListener(v -> {
            if (!selectedVideos.isEmpty()) {
                onDeleteVideos(new ArrayList<>(selectedVideos));
            }
        });

        selectionDetails.setOnClickListener(v -> {
            if (selectedVideos.size() != 1 || selectionAdapter == null) {
                return;
            }
            VideoAdapter adapter = selectionAdapter;
            VideoFile selected = selectedVideos.get(0);
            exitSelectionMode();
            adapter.showDetails(selected);
        });
    }

    @Override
    public void onVideoSelectionChanged(
            VideoAdapter adapter,
            List<VideoFile> selection
    ) {
        if (selection != null && !selection.isEmpty() && selectionAdapter != adapter) {
            VideoAdapter previous = selectionAdapter;
            selectionAdapter = adapter;
            if (previous != null) {
                previous.clearSelection();
            }
        }

        if (selectionAdapter != null && selectionAdapter != adapter) {
            return;
        }

        selectedVideos.clear();
        if (selection != null) {
            selectedVideos.addAll(selection);
        }
        if (selectedVideos.isEmpty()) {
            selectionAdapter = null;
        }
        updateSelectionBar();
    }

    private void updateSelectionBar() {
        boolean active = !selectedVideos.isEmpty();
        boolean single = selectedVideos.size() == 1;
        setSelectionActionEnabled(selectionRename, single);
        setSelectionActionEnabled(selectionDetails, single);
        setSelectionActionEnabled(selectionShare, active);
        setSelectionActionEnabled(selectionDelete, active);

        selectionActionBar.animate().cancel();
        if (active) {
            toggleViewBtn.setVisibility(View.GONE);
            if (selectionActionBar.getVisibility() != View.VISIBLE) {
                selectionActionBar.setAlpha(0f);
                selectionActionBar.setTranslationY(
                        20f * getResources().getDisplayMetrics().density);
                selectionActionBar.setVisibility(View.VISIBLE);
            }
            selectionActionBar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180L)
                    .start();
        } else if (selectionActionBar.getVisibility() == View.VISIBLE) {
            selectionActionBar.animate()
                    .alpha(0f)
                    .translationY(16f * getResources().getDisplayMetrics().density)
                    .setDuration(140L)
                    .withEndAction(() -> {
                        if (selectedVideos.isEmpty()) {
                            selectionActionBar.setVisibility(View.GONE);
                            selectionActionBar.setTranslationY(0f);
                            toggleViewBtn.setVisibility(View.VISIBLE);
                        }
                    })
                    .start();
        } else {
            selectionActionBar.setVisibility(View.GONE);
            toggleViewBtn.setVisibility(View.VISIBLE);
        }
    }

    private void setSelectionActionEnabled(View action, boolean enabled) {
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : 0.34f);
    }

    private void exitSelectionMode() {
        VideoAdapter adapter = selectionAdapter;
        selectionAdapter = null;
        selectedVideos.clear();
        if (adapter != null) {
            adapter.clearSelection();
        }
        updateSelectionBar();
    }

    private void handleSearchText(CharSequence text) {
        if (selectionAdapter != null) {
            exitSelectionMode();
        }
        final int generation = ++searchGeneration;
        if (pendingSearchRunnable != null) {
            mainHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }

        String query = text == null ? "" : text.toString().trim().toLowerCase();
        if (query.isEmpty()) {
            searchClear.setVisibility(View.GONE);
            filteredListDiffer.submit(Collections.emptyList());
            if (currentState == ViewState.ALL_VIDEOS) {
                recyclerView.setAdapter(videoAdapter);
            }
            return;
        }

        searchClear.setVisibility(View.VISIBLE);
        if (currentState != ViewState.ALL_VIDEOS) {
            return;
        }

        pendingSearchRunnable = () -> executeSearch(query, generation);
        mainHandler.postDelayed(pendingSearchRunnable, SEARCH_DEBOUNCE_MS);
    }

    private void executeSearch(String query, int generation) {
        pendingSearchRunnable = null;
        List<VideoFile> sourceSnapshot = new ArrayList<>(videoFiles);

        AppExecutors.listWork().execute(() -> {
            List<VideoFile> matches = new ArrayList<>();
            for (VideoFile videoFile : sourceSnapshot) {
                String name = videoFile.getName();
                if (name != null && name.toLowerCase().contains(query)) {
                    matches.add(videoFile);
                }
            }

            mainHandler.post(() -> {
                if (generation != searchGeneration
                        || currentState != ViewState.ALL_VIDEOS
                        || !query.equals(currentSearchQuery())) {
                    return;
                }

                filteredListDiffer.submit(matches, () -> {
                    if (generation == searchGeneration
                            && currentState == ViewState.ALL_VIDEOS
                            && query.equals(currentSearchQuery())) {
                        recyclerView.setAdapter(filteredAdapter);
                    }
                });
            });
        });
    }

    private void rerunCurrentSearch() {
        String query = currentSearchQuery();
        if (query.isEmpty() || currentState != ViewState.ALL_VIDEOS) {
            return;
        }

        int generation = ++searchGeneration;
        if (pendingSearchRunnable != null) {
            mainHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        executeSearch(query, generation);
    }

    private String currentSearchQuery() {
        if (searchInput == null || searchInput.getText() == null) {
            return "";
        }
        return searchInput.getText().toString().trim().toLowerCase();
    }

    private void setupInsets() {
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

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams selectionLp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams)
                            selectionActionBar.getLayoutParams();
            selectionLp.bottomMargin = navBarHeight + 24;
            selectionActionBar.setLayoutParams(selectionLp);

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!hasMediaReadPermission()) {
            return;
        }

        if (!libraryLoaded) {
            registerMediaObserver();
            if (!libraryLoadInProgress) {
                libraryDirty = true;
                loadVideoFiles();
            }
            return;
        }

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
        searchGeneration++;
        if (pendingSearchRunnable != null) {
            mainHandler.removeCallbacks(pendingSearchRunnable);
            pendingSearchRunnable = null;
        }
        if (videoListDiffer != null) videoListDiffer.cancelPending();
        if (filteredListDiffer != null) filteredListDiffer.cancelPending();

        if (mediaObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(mediaObserver);
            } catch (Exception ignored) {
            }
            mediaObserver = null;
        }
        super.onDestroy();
    }

    private void refreshPlaybackPresentation() {
        List<VideoFile> reordered = new ArrayList<>(videoFiles);
        sortVideos(reordered);
        videoListDiffer.submit(reordered, () -> {
            refreshVideoAdapter(videoAdapter);
            refreshVideoAdapter(filteredAdapter);

            RecyclerView.Adapter<?> activeAdapter = recyclerView.getAdapter();
            if (activeAdapter instanceof VideoAdapter
                    && activeAdapter != videoAdapter
                    && activeAdapter != filteredAdapter) {
                refreshVideoAdapter((VideoAdapter) activeAdapter);
            }
        });
    }

    private void refreshVideoAdapter(VideoAdapter adapter) {
        if (adapter != null && adapter.getItemCount() > 0) {
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        }
    }

    private void showAllVideos() {
        if (selectionAdapter != null) {
            exitSelectionMode();
        }
        hideSortPopup();
        currentFolder = null;
        currentState = ViewState.ALL_VIDEOS;
        toggleIcon.setImageResource(R.drawable.folder);
        searchCard.setVisibility(View.VISIBLE);

        String query = currentSearchQuery();
        if (query.isEmpty()) {
            recyclerView.setAdapter(videoAdapter);
        } else {
            rerunCurrentSearch();
        }

        recyclerView.scrollToPosition(0);
        int gap = (int) (8 * getResources().getDisplayMetrics().density);
        recyclerView.setPadding(0, searchCard.getBottom() + gap,
                recyclerView.getPaddingRight(), recyclerView.getPaddingBottom());
    }

    private void showFolderList() {
        if (selectionAdapter != null) {
            exitSelectionMode();
        }
        hideSortPopup();
        currentFolder = null;
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
        if (selectionAdapter != null) {
            exitSelectionMode();
        }
        hideSortPopup();
        currentFolder = folder;
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

        mediaObserver = new ContentObserver(mainHandler) {
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

    @SuppressLint("Range")
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
                        long durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION));
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
                                durationMs,
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
            mainHandler.post(() -> {
                libraryLoadInProgress = false;

                if (isFinishing()
                        || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed())) {
                    return;
                }

                if (finalErrorMessage != null) {
                    libraryDirty = true;
                    recyclerView.setVisibility(View.VISIBLE);
                    GlassUi.showToast(this, "Error loading videos: " + finalErrorMessage);
                    return;
                }

                libraryLoaded = true;
                sortVideos(loadedFiles);
                videoListDiffer.submit(loadedFiles,
                        () -> onLibraryListCommitted(loadedFiles.isEmpty()));
            });
        });
    }

    private void onLibraryListCommitted(boolean empty) {
        recyclerView.setVisibility(View.VISIBLE);

        if (empty) {
            filteredListDiffer.submit(Collections.emptyList());
            if (currentState != ViewState.ALL_VIDEOS) {
                showAllVideos();
            } else {
                recyclerView.setAdapter(videoAdapter);
            }
            GlassUi.showToast(this, "No video files found.");
        } else if (currentState == ViewState.FOLDER_LIST
                || currentState == ViewState.FOLDER_CONTENTS) {
            showFolderList();
        } else if (!currentSearchQuery().isEmpty()) {
            rerunCurrentSearch();
        } else {
            recyclerView.setAdapter(videoAdapter);
            filteredListDiffer.submit(Collections.emptyList());
        }

        if (libraryDirty) {
            loadVideoFiles();
        }
    }

    private String[] buildProjection() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new String[]{
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DATE_MODIFIED,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.BUCKET_DISPLAY_NAME
            };
        }
        return new String[]{
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DURATION,
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

        String normalized = folderKey.endsWith("/")
                ? folderKey.substring(0, folderKey.length() - 1)
                : folderKey;
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

    private void pinLastPlayed(List<VideoFile> files) {
        String lastPlayedKey = PlaybackPrefs.getInstance(this).getLastPlayedKey();
        if (lastPlayedKey == null) {
            return;
        }

        for (int i = 0; i < files.size(); i++) {
            if (lastPlayedKey.equals(files.get(i).getPlaybackKey())) {
                VideoFile lastPlayed = files.remove(i);
                files.add(0, lastPlayed);
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
    public void onDeleteVideos(List<VideoFile> videos) {
        if (videos == null || videos.isEmpty()) {
            return;
        }
        ArrayList<VideoFile> requestedVideos = new ArrayList<>(videos);
        int count = requestedVideos.size();
        GlassUi.showConfirmDialog(
                this,
                count == 1 ? "Delete video" : "Delete " + count + " videos",
                count == 1
                        ? "This will remove the selected media item from device storage."
                        : "This will permanently remove all selected videos from device storage.",
                "Delete",
                () -> {
                    exitSelectionMode();
                    if (requestedVideos.size() == 1) {
                        performDelete(requestedVideos.get(0));
                    } else {
                        performDeleteBatch(requestedVideos);
                    }
                }
        );
    }

    private void performDeleteBatch(List<VideoFile> videos) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestScopedDeleteAccess(videos);
            return;
        }

        legacyDeleteQueue.clear();
        legacyDeleteQueue.addAll(videos);
        legacyDeletedCount = 0;
        legacyDeleteBatchActive = true;
        deleteNextLegacyItem();
    }

    private void requestScopedDeleteAccess(List<VideoFile> videos) {
        pendingDeleteFiles.clear();
        pendingDeleteFiles.addAll(videos);
        pendingVideoActionFile = null;
        pendingRenameName = null;
        pendingMediaAction = PendingMediaAction.DELETE;

        ArrayList<Uri> uris = new ArrayList<>();
        for (VideoFile video : videos) {
            uris.add(video.getContentUri());
        }
        try {
            PendingIntent pendingIntent =
                    MediaStore.createDeleteRequest(getContentResolver(), uris);
            mediaActionLauncher.launch(new IntentSenderRequest.Builder(
                    pendingIntent.getIntentSender()).build());
        } catch (Exception e) {
            clearPendingMediaAction();
            GlassUi.showToast(this, "Delete request failed: " + e.getMessage());
        }
    }

    private void deleteNextLegacyItem() {
        if (!legacyDeleteBatchActive) {
            return;
        }
        if (legacyDeleteQueue.isEmpty()) {
            int deletedCount = legacyDeletedCount;
            legacyDeleteBatchActive = false;
            if (deletedCount > 0) {
                reloadAfterMutation();
            }
            GlassUi.showToast(this, deletedCount + " videos deleted.");
            clearPendingMediaAction();
            return;
        }

        VideoFile video = legacyDeleteQueue.remove(0);
        try {
            if (getContentResolver().delete(video.getContentUri(), null, null) > 0) {
                legacyDeletedCount++;
            }
            mainHandler.post(this::deleteNextLegacyItem);
        } catch (SecurityException securityException) {
            requestScopedWriteAccess(
                    video,
                    PendingMediaAction.DELETE,
                    null,
                    securityException
            );
        } catch (Exception ignored) {
            mainHandler.post(this::deleteNextLegacyItem);
        }
    }

    private void deleteApprovedLegacyItem() {
        VideoFile approvedVideo = pendingVideoActionFile;
        pendingVideoActionFile = null;
        pendingMediaAction = PendingMediaAction.NONE;
        if (approvedVideo != null) {
            try {
                if (getContentResolver().delete(
                        approvedVideo.getContentUri(), null, null) > 0) {
                    legacyDeletedCount++;
                }
            } catch (Exception ignored) {
            }
        }
        mainHandler.post(this::deleteNextLegacyItem);
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
        pendingDeleteFiles.clear();
        legacyDeleteQueue.clear();
        legacyDeleteBatchActive = false;
        legacyDeletedCount = 0;
        pendingRenameName = null;
        pendingMediaAction = PendingMediaAction.NONE;
    }

    private void showAboutDialog() {
        GlassUi.showInfoDialog(this, "About CineStream", Collections.emptyList());
    }
}
