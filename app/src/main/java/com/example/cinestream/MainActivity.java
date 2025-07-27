package com.example.cinestream;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.card.MaterialCardView;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity {

    // ── View state ──────────────────────────────────────────────────
    private enum ViewState { ALL_VIDEOS, FOLDER_LIST, FOLDER_CONTENTS }
    private ViewState currentState = ViewState.ALL_VIDEOS;
    // ────────────────────────────────────────────────────────────────

    private RecyclerView      recyclerView;
    private MaterialCardView  titleCard;
    private MaterialCardView  toggleViewBtn;
    private ImageView         toggleIcon;

    private VideoAdapter      videoAdapter;
    private FolderAdapter     folderAdapter;

    private final List<VideoFile>  videoFiles    = new ArrayList<>();
    private final List<FolderItem> folderItems   = new ArrayList<>();

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted) checkManageAllFilesPermission();
                else Toast.makeText(this, "Permission denied.", Toast.LENGTH_SHORT).show();
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Base_Theme_CineStream);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                getWindow().setBackgroundBlurRadius(40);
                getWindow().addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND);
            } catch (Exception e) { e.printStackTrace(); }
        }

        titleCard     = findViewById(R.id.titleCard);
        recyclerView  = findViewById(R.id.recyclerView);
        toggleViewBtn = findViewById(R.id.toggleViewBtn);
        toggleIcon    = findViewById(R.id.toggleIcon);

        // ── Card color adapts to light/dark ──
        if (isLightMode()) {
            titleCard.setCardBackgroundColor(Color.argb(180, 255, 255, 255));
            toggleViewBtn.setCardBackgroundColor(Color.argb(200, 240, 240, 245));
        } else {
            titleCard.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
            toggleViewBtn.setCardBackgroundColor(Color.argb(140, 15, 15, 20));
        }

        // ── Insets: status bar + recyclerView paddingTop ──
        ViewCompat.setOnApplyWindowInsetsListener(titleCard, (v, insets) -> {
            int statusBarHeight = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top;
            int navBarHeight    = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;

            androidx.constraintlayout.widget.ConstraintLayout.LayoutParams lp =
                    (androidx.constraintlayout.widget.ConstraintLayout.LayoutParams) titleCard.getLayoutParams();
            lp.topMargin = statusBarHeight + 12;
            titleCard.setLayoutParams(lp);

            // Also push toggle button above nav bar
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
                                int gap = (int) (16 * getResources().getDisplayMetrics().density);
                                recyclerView.setPadding(0, cardBottom + gap, 0, navBarHeight);
                                recyclerView.scrollToPosition(0);
                                titleCard.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            }
                        }
                    });
            return insets;
        });

        // ── Toggle button click ──────────────────────────────────────
        toggleViewBtn.setOnClickListener(v -> {
            // Haptic feedback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            } else {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            }

            // Visual feedback — quick scale animation
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
        // ─────────────────────────────────────────────────────────────

        // ── Back press handling ──────────────────────────────────────
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
        // ─────────────────────────────────────────────────────────────

        // ── Scroll opacity ───────────────────────────────────────────
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                applyScrollOpacity(rv);
            }
        });
        // ─────────────────────────────────────────────────────────────

        customizeStatusBar();
        setupRecyclerView();
        checkPermissionsAndLoadFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkManageAllFilesPermission();
    }

    private void setupRecyclerView() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        videoAdapter  = new VideoAdapter(this, videoFiles);
        recyclerView.setAdapter(videoAdapter);
    }

    // ── State switches ───────────────────────────────────────────────

    @SuppressLint("NotifyDataSetChanged")
    private void showAllVideos() {
        currentState = ViewState.ALL_VIDEOS;
        toggleIcon.setImageResource(R.drawable.folder);  // show folder icon = switch to folders
        recyclerView.setAdapter(videoAdapter);
        videoAdapter.notifyDataSetChanged();
        recyclerView.scrollToPosition(0);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void showFolderList() {
        currentState = ViewState.FOLDER_LIST;
        toggleIcon.setImageResource(R.drawable.video);  // show video icon = switch back to all
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            buildFolderList();
        }
        folderAdapter = new FolderAdapter(this, folderItems, folder -> {
            // Haptic on folder tap
            recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            showFolderContents(folder);
        });        recyclerView.setAdapter(folderAdapter);
        recyclerView.scrollToPosition(0);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void showFolderContents(FolderItem folder) {
        currentState = ViewState.FOLDER_CONTENTS;

        // Filter videos whose direct parent matches this folder
        List<VideoFile> filtered = new ArrayList<>();
        for (VideoFile vf : videoFiles) {
            String parent = new File(vf.getPath()).getParent();
            if (folder.getPath().equals(parent)) {
                filtered.add(vf);
            }
        }

        VideoAdapter folderVideoAdapter = new VideoAdapter(this, filtered);
        recyclerView.setAdapter(folderVideoAdapter);
        recyclerView.scrollToPosition(0);
    }

    // ── Build folder list from already-loaded videoFiles ────────────
    @RequiresApi(api = Build.VERSION_CODES.N)
    private void buildFolderList() {
        folderItems.clear();
        // LinkedHashMap preserves insertion order
        Map<String, Integer> folderMap = new LinkedHashMap<>();
        for (VideoFile vf : videoFiles) {
            String parent = new File(vf.getPath()).getParent();
            if (parent != null) {
                folderMap.put(parent, folderMap.getOrDefault(parent, 0) + 1);
            }
        }
        for (Map.Entry<String, Integer> entry : folderMap.entrySet()) {
            String path  = entry.getKey();
            String name  = new File(path).getName();
            int    count = entry.getValue();
            folderItems.add(new FolderItem(name, path, count));
        }
    }
    // ────────────────────────────────────────────────────────────────

    private void applyScrollOpacity(RecyclerView rv) {
        if (titleCard == null) return;
        float fadeEnd  = titleCard.getBottom();
        float fadeZone = titleCard.getHeight();
        for (int i = 0; i < rv.getChildCount(); i++) {
            View child     = rv.getChildAt(i);
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
        Window window = getWindow();
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            View decor = window.getDecorView();
            int flags  = decor.getSystemUiVisibility();
            flags = isLightMode()
                    ? flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    : flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            decor.setSystemUiVisibility(flags);
        }
    }

    private boolean isLightMode() {
        int nightModeFlags = getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return nightModeFlags != android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    private void checkPermissionsAndLoadFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_VIDEO);
            } else {
                checkManageAllFilesPermission();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE);
            } else {
                checkManageAllFilesPermission();
            }
        }
    }

    private void checkManageAllFilesPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            } else {
                loadVideoFiles();
            }
        } else {
            loadVideoFiles();
        }
    }

    @SuppressLint("NotifyDataSetChanged")
    private void loadVideoFiles() {
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATA,
                MediaStore.Video.Media.DATE_MODIFIED
        };
        String sortOrder = MediaStore.Video.Media.DATE_MODIFIED + " DESC";

        try (Cursor cursor = getContentResolver().query(collection, projection, null, null, sortOrder)) {
            if (cursor != null && cursor.moveToFirst()) {
                videoFiles.clear();
                do {
                    String id          = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID));
                    String displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME));
                    String data        = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA));
                    long dateModified  = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED));
                    videoFiles.add(new VideoFile(id, displayName, data, dateModified));
                } while (cursor.moveToNext());

                // Pin last played to top
                String lastPlayed = PlaybackPrefs.getInstance(this).getLastPlayed();
                if (lastPlayed != null) {
                    for (int i = 0; i < videoFiles.size(); i++) {
                        if (videoFiles.get(i).getPath().equals(lastPlayed)) {
                            VideoFile last = videoFiles.remove(i);
                            videoFiles.add(0, last);
                            break;
                        }
                    }
                }

                videoAdapter.notifyDataSetChanged();
            } else {
                Toast.makeText(this, "No video files found.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Error loading videos: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
}