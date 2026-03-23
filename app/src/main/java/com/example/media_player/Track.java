package com.example.media_player;

import android.net.Uri;

public class Track {

    public enum Source { LOCAL, TIDAL }

    public final long id;
    public final String title;
    public final String artist;
    public final long durationMs;
    public final Uri uri;
    public final String album;
    public final long albumId;
    public final int trackNumber;
    public final int year;
    public final String folderPath;
    public final String folderName;
    public final Source source;
    public final String tidalTrackId;
    public final String artworkUrl;

    public Track(long id, String title, String artist, long durationMs, Uri uri,
                 String album, long albumId, int trackNumber, int year,
                 String folderPath, String folderName) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.uri = uri;
        this.album = album;
        this.albumId = albumId;
        this.trackNumber = trackNumber;
        this.year = year;
        this.folderPath = folderPath;
        this.folderName = folderName;
        this.source = Source.LOCAL;
        this.tidalTrackId = null;
        this.artworkUrl = null;
    }

    private Track(long id, String title, String artist, long durationMs,
                  String album, long albumId, int trackNumber,
                  String tidalTrackId, String artworkUrl) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.uri = null;
        this.album = album;
        this.albumId = albumId;
        this.trackNumber = trackNumber;
        this.year = 0;
        this.folderPath = "";
        this.folderName = "";
        this.source = Source.TIDAL;
        this.tidalTrackId = tidalTrackId;
        this.artworkUrl = artworkUrl;
    }

    public static Track tidalTrack(long tidalId, String title, String artist, long durationMs,
                                   String album, long tidalAlbumId, int trackNumber,
                                   String artworkUrl) {
        long id = ("tidal:" + tidalId).hashCode();
        long albumId = ("tidal_album:" + tidalAlbumId).hashCode();
        return new Track(id, title, artist, durationMs, album, albumId, trackNumber,
                String.valueOf(tidalId), artworkUrl);
    }

    public String getFormattedDuration() {
        long totalSeconds = durationMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return minutes + ":" + String.format("%02d", seconds);
    }
}
