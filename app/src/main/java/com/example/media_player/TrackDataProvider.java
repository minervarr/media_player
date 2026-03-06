package com.example.media_player;

import java.util.List;

public interface TrackDataProvider {
    List<Track> getAllTracks();
    void playTrack(Track track, List<Track> queue);
    long getPlayingTrackId();
}
