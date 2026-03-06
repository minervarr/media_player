package com.example.media_player;

public interface PlaybackObserver {
    void onPlayingTrackChanged(long trackId);
}
