package com.example.media_player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.ViewHolder> {

    public interface OnTrackClickListener {
        void onTrackClick(Track track);
    }

    private final List<Track> tracks;
    private final OnTrackClickListener listener;
    private long playingTrackId = -1;

    public TrackAdapter(List<Track> tracks, OnTrackClickListener listener) {
        this.tracks = tracks;
        this.listener = listener;
    }

    public void setPlayingTrackId(long trackId) {
        long oldId = playingTrackId;
        playingTrackId = trackId;
        for (int i = 0; i < tracks.size(); i++) {
            long id = tracks.get(i).id;
            if (id == oldId || id == trackId) {
                notifyItemChanged(i);
            }
        }
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_track, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Track track = tracks.get(position);
        boolean isPlaying = track.id == playingTrackId;

        holder.itemView.setBackgroundColor(holder.itemView.getContext().getColor(
                isPlaying ? R.color.bg_item_playing : R.color.bg_item));

        int titleColor = holder.itemView.getContext().getColor(
                isPlaying ? R.color.text_playing : R.color.text_primary);

        holder.tvTrackNumber.setText(String.valueOf(position + 1));
        holder.tvTrackNumber.setTextColor(holder.itemView.getContext().getColor(
                isPlaying ? R.color.text_playing : R.color.text_secondary));

        holder.tvTitle.setText(track.title);
        holder.tvTitle.setTextColor(titleColor);

        holder.tvArtist.setText(track.artist);

        holder.tvDuration.setText(track.getFormattedDuration());

        ArtworkCache.getInstance(holder.ivArtwork.getContext())
                .loadArtwork("album:" + track.albumId, holder.ivArtwork, 120);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && pos < tracks.size()) {
                listener.onTrackClick(tracks.get(pos));
            }
        });
    }

    @Override
    public int getItemCount() {
        return tracks.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivArtwork;
        final TextView tvTrackNumber;
        final TextView tvTitle;
        final TextView tvArtist;
        final TextView tvDuration;

        ViewHolder(View itemView) {
            super(itemView);
            ivArtwork = itemView.findViewById(R.id.iv_track_artwork);
            tvTrackNumber = itemView.findViewById(R.id.tv_track_number);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            tvDuration = itemView.findViewById(R.id.tv_duration);
        }
    }
}
