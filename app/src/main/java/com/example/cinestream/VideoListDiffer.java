package com.example.cinestream;

import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Background DiffUtil coordinator for mutable lists already owned by VideoAdapter.
 *
 * The generation guard makes rapid submissions safe: only the newest calculated diff is allowed
 * to mutate the adapter's backing list. This is especially important for debounced search.
 */
public final class VideoListDiffer {

    private final List<VideoFile> target;
    private final RecyclerView.Adapter<?> adapter;
    private final AtomicInteger generation = new AtomicInteger();

    public VideoListDiffer(List<VideoFile> target, RecyclerView.Adapter<?> adapter) {
        this.target = target;
        this.adapter = adapter;
    }

    public void submit(List<VideoFile> next) {
        submit(next, null);
    }

    public void submit(List<VideoFile> next, Runnable commitCallback) {
        final int requestGeneration = generation.incrementAndGet();
        final List<VideoFile> oldSnapshot = new ArrayList<>(target);
        final List<VideoFile> newSnapshot = new ArrayList<>(next);

        AppExecutors.listWork().execute(() -> {
            DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
                @Override
                public int getOldListSize() {
                    return oldSnapshot.size();
                }

                @Override
                public int getNewListSize() {
                    return newSnapshot.size();
                }

                @Override
                public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                    return oldSnapshot.get(oldItemPosition).getId()
                            == newSnapshot.get(newItemPosition).getId();
                }

                @Override
                public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                    VideoFile oldItem = oldSnapshot.get(oldItemPosition);
                    VideoFile newItem = newSnapshot.get(newItemPosition);
                    return oldItem.getSizeBytes() == newItem.getSizeBytes()
                            && oldItem.getDateModified() == newItem.getDateModified()
                            && oldItem.getDurationMs() == newItem.getDurationMs()
                            && Objects.equals(oldItem.getName(), newItem.getName())
                            && Objects.equals(oldItem.getContentUri(), newItem.getContentUri())
                            && Objects.equals(oldItem.getFolderName(), newItem.getFolderName())
                            && Objects.equals(oldItem.getFolderKey(), newItem.getFolderKey())
                            && Objects.equals(oldItem.getPlaybackKey(), newItem.getPlaybackKey());
                }
            }, true);

            AppExecutors.mainHandler().post(() -> {
                if (requestGeneration != generation.get()) {
                    return;
                }
                target.clear();
                target.addAll(newSnapshot);
                result.dispatchUpdatesTo(adapter);
                if (commitCallback != null) {
                    commitCallback.run();
                }
            });
        });
    }

    public void cancelPending() {
        generation.incrementAndGet();
    }
}
