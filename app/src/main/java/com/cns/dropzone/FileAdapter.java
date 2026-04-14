package com.cns.dropzone;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.cns.dropzone.model.FileItem;

import java.util.List;
import java.util.function.Consumer;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.FileViewHolder> {

    private final List<FileItem> files;
    private final Consumer<FileItem> onDownload;
    private final Consumer<FileItem> onOpen;

    public FileAdapter(List<FileItem> files, Consumer<FileItem> onDownload, Consumer<FileItem> onOpen) {
        this.files = files;
        this.onDownload = onDownload;
        this.onOpen = onOpen;
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
        holder.download.setVisibility(file.isDownloaded() ? View.GONE : View.VISIBLE);
        holder.open.setVisibility(file.isDownloaded() ? View.VISIBLE : View.GONE);
        holder.name.setText(file.getName());
        holder.meta.setText(file.getSize() + " • " + file.getLastModified());
        holder.download.setOnClickListener(v -> onDownload.accept(file));
        holder.open.setOnClickListener(v -> onOpen.accept(file));

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

    @Override
    public int getItemCount() { return files.size(); }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView icon;
        TextView name, meta;
        ImageButton download, open;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            icon = itemView.findViewById(R.id.iv_file_icon);
            name = itemView.findViewById(R.id.tv_file_name);
            meta = itemView.findViewById(R.id.tv_file_meta);
            open = itemView.findViewById(R.id.btn_open);
            download = itemView.findViewById(R.id.btn_download);
        }
    }
}