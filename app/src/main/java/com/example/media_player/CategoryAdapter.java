package com.example.media_player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class CategoryAdapter extends RecyclerView.Adapter<CategoryAdapter.ViewHolder> {

    public static final int VIEW_TYPE_LIST = 0;
    public static final int VIEW_TYPE_GRID = 1;

    public interface OnCategoryClickListener {
        void onCategoryClick(CategoryItem item);
    }

    private final List<CategoryItem> items;
    private final OnCategoryClickListener listener;
    private final int viewType;
    private int artworkSizePx = -1;

    public CategoryAdapter(List<CategoryItem> items, OnCategoryClickListener listener, int viewType) {
        this.items = items;
        this.listener = listener;
        this.viewType = viewType;
    }

    @Override
    public int getItemViewType(int position) {
        return viewType;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId = viewType == VIEW_TYPE_GRID
                ? R.layout.item_category_grid
                : R.layout.item_category;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ViewHolder(view, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CategoryItem item = items.get(position);
        holder.tvTitle.setText(item.title);
        holder.tvSubtitle.setText(item.subtitle);
        if (holder.tvCount != null) {
            if (item.trackCount > 0) {
                holder.tvCount.setVisibility(View.VISIBLE);
                holder.tvCount.setText(String.valueOf(item.trackCount));
            } else {
                holder.tvCount.setVisibility(View.GONE);
            }
        }
        if (holder.ivArtwork != null && item.artworkKey != null) {
            holder.ivArtwork.setVisibility(View.VISIBLE);
            if (artworkSizePx < 0) {
                artworkSizePx = holder.ivArtwork.getContext().getResources().getDisplayMetrics().widthPixels / 3;
            }
            ArtworkCache.getInstance(holder.ivArtwork.getContext())
                    .loadArtwork(item.artworkKey, holder.ivArtwork, artworkSizePx);
        } else if (holder.ivArtwork != null) {
            holder.ivArtwork.setVisibility(View.GONE);
        }
        holder.itemView.setOnClickListener(v -> listener.onCategoryClick(item));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvTitle;
        final TextView tvSubtitle;
        final TextView tvCount;
        final ImageView ivArtwork;

        ViewHolder(View itemView, int viewType) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_category_title);
            tvSubtitle = itemView.findViewById(R.id.tv_category_subtitle);
            tvCount = itemView.findViewById(R.id.tv_category_count);
            ivArtwork = viewType == VIEW_TYPE_GRID
                    ? itemView.findViewById(R.id.iv_grid_artwork)
                    : null;
        }
    }
}
