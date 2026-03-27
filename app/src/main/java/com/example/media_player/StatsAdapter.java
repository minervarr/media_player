package com.example.media_player;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class StatsAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_TRACK = 1;
    private static final int TYPE_ARTIST = 2;
    private static final int TYPE_ACTION = 3;

    private final List<Object> items;
    private final OnStatsInteractionListener listener;
    private long playingTrackId = -1;

    public interface OnStatsInteractionListener {
        void onTrackClick(Track track, List<Track> sectionTracks);
        void onActionClick(StatAction action);
    }

    public StatsAdapter(List<Object> items, OnStatsInteractionListener listener) {
        this.items = items;
        this.listener = listener;
    }

    public void setPlayingTrackId(long trackId) {
        long old = playingTrackId;
        playingTrackId = trackId;
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i) instanceof StatTrack) {
                Track t = ((StatTrack) items.get(i)).track;
                if (t.id == old || t.id == trackId) notifyItemChanged(i);
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        Object item = items.get(position);
        if (item instanceof StatHeader) return TYPE_HEADER;
        if (item instanceof StatTrack) return TYPE_TRACK;
        if (item instanceof StatsDao.ArtistPlayCount) return TYPE_ARTIST;
        if (item instanceof StatAction) return TYPE_ACTION;
        return TYPE_HEADER;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        switch (viewType) {
            case TYPE_TRACK:
                return new TrackVH(inflater.inflate(R.layout.item_track, parent, false));
            case TYPE_ARTIST:
                return new ArtistVH(inflater.inflate(R.layout.item_category, parent, false));
            case TYPE_ACTION:
                return new ActionVH(inflater.inflate(R.layout.item_stat_header, parent, false));
            default:
                return new HeaderVH(inflater.inflate(R.layout.item_stat_header, parent, false));
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof HeaderVH) {
            ((HeaderVH) holder).bind((StatHeader) item);
        } else if (holder instanceof TrackVH) {
            ((TrackVH) holder).bind((StatTrack) item, position);
        } else if (holder instanceof ArtistVH) {
            ((ArtistVH) holder).bind((StatsDao.ArtistPlayCount) item);
        } else if (holder instanceof ActionVH) {
            ((ActionVH) holder).bind((StatAction) item);
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    // -- View holders --

    class HeaderVH extends RecyclerView.ViewHolder {
        final TextView tvTitle, tvSummary;
        HeaderVH(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tv_header_title);
            tvSummary = view.findViewById(R.id.tv_header_summary);
        }
        void bind(StatHeader header) {
            tvTitle.setText(header.title);
            if (header.summary != null && !header.summary.isEmpty()) {
                tvSummary.setText(header.summary);
                tvSummary.setVisibility(View.VISIBLE);
            } else {
                tvSummary.setVisibility(View.GONE);
            }
        }
    }

    class TrackVH extends RecyclerView.ViewHolder {
        final ImageView ivArtwork;
        final TextView tvNumber, tvTitle, tvArtist, tvDuration;
        TrackVH(View view) {
            super(view);
            ivArtwork = view.findViewById(R.id.iv_track_artwork);
            tvNumber = view.findViewById(R.id.tv_track_number);
            tvTitle = view.findViewById(R.id.tv_title);
            tvArtist = view.findViewById(R.id.tv_artist);
            tvDuration = view.findViewById(R.id.tv_duration);
        }
        void bind(StatTrack st, int position) {
            Track track = st.track;
            boolean isPlaying = track.id == playingTrackId;
            itemView.setBackgroundColor(itemView.getContext().getColor(
                    isPlaying ? R.color.bg_item_playing : R.color.bg_item));
            int titleColor = itemView.getContext().getColor(
                    isPlaying ? R.color.text_playing : R.color.text_primary);

            tvNumber.setText("");
            tvTitle.setText(track.title);
            tvTitle.setTextColor(titleColor);
            String subtitle = track.artist;
            if (track.album != null && !track.album.isEmpty()) {
                subtitle = track.artist + " -- " + track.album;
            }
            tvArtist.setText(subtitle);
            tvDuration.setText(st.extra);

            String artworkKey;
            if (track.source == Track.Source.TIDAL && track.artworkUrl != null) {
                artworkKey = "tidal:" + track.artworkUrl;
            } else {
                artworkKey = "album:" + track.albumId;
            }
            ArtworkCache.getInstance(ivArtwork.getContext())
                    .loadArtwork(artworkKey, ivArtwork, 120);

            itemView.setOnClickListener(v -> {
                // Collect section tracks for queue
                java.util.List<Track> sectionTracks = new java.util.ArrayList<>();
                for (Object o : items) {
                    if (o instanceof StatTrack) sectionTracks.add(((StatTrack) o).track);
                }
                listener.onTrackClick(track, sectionTracks);
            });
        }
    }

    static class ArtistVH extends RecyclerView.ViewHolder {
        final TextView tvTitle, tvSubtitle, tvCount;
        ArtistVH(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tv_category_title);
            tvSubtitle = view.findViewById(R.id.tv_category_subtitle);
            tvCount = view.findViewById(R.id.tv_category_count);
        }
        void bind(StatsDao.ArtistPlayCount apc) {
            tvTitle.setText(apc.artist);
            tvSubtitle.setVisibility(View.GONE);
            tvCount.setText(String.valueOf(apc.totalPlayCount));
        }
    }

    class ActionVH extends RecyclerView.ViewHolder {
        final TextView tvTitle, tvSummary;
        ActionVH(View view) {
            super(view);
            tvTitle = view.findViewById(R.id.tv_header_title);
            tvSummary = view.findViewById(R.id.tv_header_summary);
        }
        void bind(StatAction action) {
            tvTitle.setText(action.label);
            tvTitle.setTextColor(itemView.getContext().getColor(R.color.green_primary));
            tvSummary.setVisibility(View.GONE);
            itemView.setOnClickListener(v -> listener.onActionClick(action));
        }
    }

    // -- Data classes --

    public static class StatHeader {
        public final String title;
        public final String summary;
        public StatHeader(String title, String summary) {
            this.title = title;
            this.summary = summary;
        }
    }

    public static class StatTrack {
        public final Track track;
        public final String extra;
        public StatTrack(Track track, String extra) {
            this.track = track;
            this.extra = extra;
        }
    }

    public static class StatAction {
        public final String label;
        public final String key;
        public StatAction(String label, String key) {
            this.label = label;
            this.key = key;
        }
    }
}
