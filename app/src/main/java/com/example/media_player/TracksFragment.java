package com.example.media_player;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class TracksFragment extends Fragment implements PlaybackObserver, TrackAdapter.OnTrackClickListener {

    private TrackDataProvider dataProvider;
    private final List<Track> tracks = new ArrayList<>();
    private TrackAdapter adapter;
    private RecyclerView recyclerView;
    private TextView tvEmpty;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        dataProvider = (TrackDataProvider) context;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_tracks, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        recyclerView = view.findViewById(R.id.recycler_tracks);
        tvEmpty = view.findViewById(R.id.tv_empty);

        adapter = new TrackAdapter(tracks, this);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        loadData();
    }

    public void loadData() {
        if (dataProvider == null || adapter == null) return;
        tracks.clear();
        tracks.addAll(dataProvider.getAllTracks());
        adapter.notifyDataSetChanged();
        adapter.setPlayingTrackId(dataProvider.getPlayingTrackId());

        tvEmpty.setVisibility(tracks.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(tracks.isEmpty() ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onTrackClick(Track track) {
        dataProvider.playTrack(track, tracks);
    }

    @Override
    public void onPlayingTrackChanged(long trackId) {
        if (adapter != null) {
            adapter.setPlayingTrackId(trackId);
        }
    }
}
