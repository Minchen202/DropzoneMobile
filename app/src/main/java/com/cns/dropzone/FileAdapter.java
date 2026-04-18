package com.cns.dropzone;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.constraintlayout.motion.widget.MotionLayout;
import androidx.recyclerview.widget.RecyclerView;

import com.cns.dropzone.model.FileItem;

import java.util.List;
import java.util.function.Consumer;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private final List<FileItem> files;
    private final Consumer<FileItem> onDownload;
    private final Consumer<FileItem> onOpen;
    private RecyclerView recyclerView;
    private int openedPosition = -1;

    public FileAdapter(List<FileItem> files, Consumer<FileItem> onDownload, Consumer<FileItem> onOpen) {
        this.files = files;
        this.onDownload = onDownload;
        this.onOpen = onOpen;
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        this.recyclerView = recyclerView;
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        FileItem file = files.get(position);
        
        // Reset motion layout state for recycled views
        if (openedPosition == position) {
            holder.motionLayout.setProgress(1f);
        } else {
            holder.motionLayout.setProgress(0f);
        }

        holder.name.setText(file.getName());
        holder.meta.setText(file.getSize() + " • " + file.getLastModified());

        holder.download.setOnClickListener(v -> onDownload.accept(file));
        holder.bookmark.setOnClickListener(v -> {
            // Handle bookmark click
        });

        holder.motionLayout.setTransitionListener(new MotionLayout.TransitionListener() {
            @Override
            public void onTransitionStarted(MotionLayout motionLayout, int startId, int endId) {
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;

                if (endId == R.id.end) {
                    int oldPos = openedPosition;
                    openedPosition = position;
                    if (oldPos != -1 && oldPos != position) {
                        closePosition(oldPos);
                    }
                }
            }

            @Override public void onTransitionChange(MotionLayout motionLayout, int startId, int endId, float progress) {}
            
            @Override 
            public void onTransitionCompleted(MotionLayout motionLayout, int currentId) {
                int position = holder.getBindingAdapterPosition();
                if (position == RecyclerView.NO_POSITION) return;

                if (currentId == R.id.start && openedPosition == position) {
                    openedPosition = -1;
                } else if (currentId == R.id.end) {
                    openedPosition = position;
                }
            }
            
            @Override public void onTransitionTrigger(MotionLayout motionLayout, int triggerId, boolean positive, float progress) {}
        });

        // Icon by extension
        String name = file.getName().toLowerCase();
        if (name.endsWith(".jpg") || name.endsWith(".png")) {
            holder.icon.setImageResource(R.drawable.ic_image);
        } else if (name.endsWith(".pdf")) {
            holder.icon.setImageResource(R.drawable.ic_pdf);
        } else if (name.endsWith(".mp4") || name.endsWith(".mov")) {
            holder.icon.setImageResource(R.drawable.ic_video);
        } else if (name.endsWith(".mp3")) {
            holder.icon.setImageResource(R.drawable.ic_audio);
        } else if (name.endsWith(".txt")) {
            holder.icon.setImageResource(R.drawable.ic_text);
        } else {
            holder.icon.setImageResource(R.drawable.ic_file);
        }
    }

    private void closePosition(int pos) {
        if (pos == -1) return;
        RecyclerView.ViewHolder vh = recyclerView.findViewHolderForAdapterPosition(pos);
        if (vh instanceof FileViewHolder) {
            ((FileViewHolder) vh).motionLayout.transitionToStart();
        } else {
            notifyItemChanged(pos);
        }
    }

    @Override
    public int getItemCount() { return files.size(); }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        MotionLayout motionLayout;
        View layoutContent;
        ImageView icon;
        TextView name, meta;
        ImageButton download, bookmark;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            motionLayout = (MotionLayout) itemView;
            layoutContent = itemView.findViewById(R.id.layout_content);
            icon = itemView.findViewById(R.id.iv_file_icon);
            name = itemView.findViewById(R.id.tv_file_name);
            meta = itemView.findViewById(R.id.tv_file_meta);
            download = itemView.findViewById(R.id.btn_download);
            bookmark = itemView.findViewById(R.id.btn_bookmark);
        }
    }
}