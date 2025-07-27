package com.example.cinestream;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class FolderAdapter extends RecyclerView.Adapter<FolderAdapter.FolderViewHolder> {

    public interface OnFolderClickListener {
        void onFolderClick(FolderItem folder);
    }

    private final Context context;
    private final List<FolderItem> folders;
    private final OnFolderClickListener listener;

    public FolderAdapter(Context context, List<FolderItem> folders, OnFolderClickListener listener) {
        this.context  = context;
        this.folders  = folders;
        this.listener = listener;
    }

    @NonNull
    @Override
    public FolderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_folder, parent, false);
        return new FolderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FolderViewHolder holder, int position) {
        FolderItem folder = folders.get(position);
        holder.folderName.setText(folder.getName());
        holder.folderCount.setText(String.valueOf(folder.getVideoCount()));
        holder.itemView.setOnClickListener(v -> listener.onFolderClick(folder));
    }

    @Override
    public int getItemCount() {
        return folders.size();
    }

    public static class FolderViewHolder extends RecyclerView.ViewHolder {
        TextView folderName, folderCount;

        public FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            folderName  = itemView.findViewById(R.id.folder_name);
            folderCount = itemView.findViewById(R.id.folder_count);
        }
    }
}