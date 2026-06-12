package com.example.media_player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.google.android.material.imageview.ShapeableImageView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.ViewHolder> {

    public interface OnTrackClickListener {
        void onTrackClick(Track track);
    }

    private final List<Track> tracks;
    private final OnTrackClickListener listener;
    private boolean showQualityBorder;
    private long playingTrackId = -1;

    public TrackAdapter(List<Track> tracks, OnTrackClickListener listener) {
        this(tracks, listener, true);
    }

    public TrackAdapter(List<Track> tracks, OnTrackClickListener listener, boolean showQualityBorder) {
        this.tracks = tracks;
        this.listener = listener;
        this.showQualityBorder = showQualityBorder;
    }

    public void setShowQualityBorder(boolean showQualityBorder) {
        if (this.showQualityBorder != showQualityBorder) {
            this.showQualityBorder = showQualityBorder;
            notifyDataSetChanged();
        }
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

        // Playing indicator accent bar
        if (holder.playingIndicator != null) {
            holder.playingIndicator.setVisibility(isPlaying ? View.VISIBLE : View.GONE);
            // Adjust artwork start margin when indicator is hidden
            ViewGroup.MarginLayoutParams artworkParams =
                    (ViewGroup.MarginLayoutParams) holder.ivArtwork.getLayoutParams();
            if (!isPlaying) {
                artworkParams.setMarginStart(0);
            }
        }

        int titleColor = holder.itemView.getContext().getColor(
                isPlaying ? R.color.text_playing : R.color.text_primary);

        holder.tvTrackNumber.setText(String.valueOf(position + 1));
        holder.tvTrackNumber.setTextColor(holder.itemView.getContext().getColor(
                isPlaying ? R.color.text_playing : R.color.text_secondary));

        holder.tvTitle.setText(track.title);
        holder.tvTitle.setTextColor(titleColor);

        holder.tvArtist.setText(track.artist);

        holder.tvDuration.setText(track.getFormattedDuration());

        String artworkKey;
        if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
            artworkKey = "tidal:" + track.artworkUrl;
        } else if (track.source == Track.Source.QOBUZ && track.artworkUrl != null) {
            artworkKey = track.artworkUrl;
        } else {
            artworkKey = "album:" + track.albumId;
        }
        ArtworkCache.getInstance(holder.ivArtwork.getContext())
                .loadArtwork(artworkKey, holder.ivArtwork, 120);

        int strokeColor = android.graphics.Color.TRANSPARENT;
        if (showQualityBorder) {
            if (track.format != null && (track.format.equalsIgnoreCase("DSF") || track.format.equalsIgnoreCase("DFF") || track.format.toUpperCase().startsWith("DS"))) {
                strokeColor = android.graphics.Color.WHITE;
            } else if (track.sampleRate >= 352800) {
                strokeColor = android.graphics.Color.parseColor("#FFA500");
            } else if (track.sampleRate >= 64000) {
                strokeColor = android.graphics.Color.parseColor("#00FFFF");
            } else if (track.sampleRate >= 44100) {
                strokeColor = android.graphics.Color.YELLOW;
            }
        }

        float density = holder.itemView.getContext().getResources().getDisplayMetrics().density;
        holder.ivArtwork.setStrokeColor(android.content.res.ColorStateList.valueOf(strokeColor));
        holder.ivArtwork.setStrokeWidth(strokeColor == android.graphics.Color.TRANSPARENT ? 0 : 2.0f * density);

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
        final View playingIndicator;
        final ShapeableImageView ivArtwork;
        final TextView tvTrackNumber;
        final TextView tvTitle;
        final TextView tvArtist;
        final TextView tvDuration;

        ViewHolder(View itemView) {
            super(itemView);
            playingIndicator = itemView.findViewById(R.id.playing_indicator);
            ivArtwork = itemView.findViewById(R.id.iv_track_artwork);
            tvTrackNumber = itemView.findViewById(R.id.tv_track_number);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvArtist = itemView.findViewById(R.id.tv_artist);
            tvDuration = itemView.findViewById(R.id.tv_duration);
        }
    }
}
