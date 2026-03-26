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
    public final int discNumber;
    public final int year;
    public final String folderPath;
    public final String folderName;
    public final Source source;
    public final String tidalTrackId;
    public final String artworkUrl;

    // Extended metadata
    public final String albumArtist;
    public final String genre;
    public final String composer;
    public final int bitrate;
    public final int sampleRate;
    public final int bitDepth;
    public final int channels;
    public final String format;

    /** Original local-track constructor (unchanged signature). */
    public Track(long id, String title, String artist, long durationMs, Uri uri,
                 String album, long albumId, int trackNumber, int year,
                 String folderPath, String folderName) {
        this(id, title, artist, durationMs, uri, album, albumId, trackNumber, 1, year,
                folderPath, folderName, Source.LOCAL, null, null,
                null, null, null, 0, 0, 0, 0, null);
    }

    /** Local-track constructor with extended metadata. */
    public Track(long id, String title, String artist, long durationMs, Uri uri,
                 String album, long albumId, int trackNumber, int discNumber, int year,
                 String folderPath, String folderName,
                 String albumArtist, String genre, String composer,
                 int bitrate, int sampleRate, int bitDepth, int channels, String format) {
        this(id, title, artist, durationMs, uri, album, albumId, trackNumber, discNumber, year,
                folderPath, folderName, Source.LOCAL, null, null,
                albumArtist, genre, composer, bitrate, sampleRate, bitDepth, channels, format);
    }

    /** Full constructor for DB reconstitution. Package-private. */
    Track(long id, String title, String artist, long durationMs, Uri uri,
          String album, long albumId, int trackNumber, int discNumber, int year,
          String folderPath, String folderName, Source source,
          String tidalTrackId, String artworkUrl,
          String albumArtist, String genre, String composer,
          int bitrate, int sampleRate, int bitDepth, int channels, String format) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.durationMs = durationMs;
        this.uri = uri;
        this.album = album;
        this.albumId = albumId;
        this.trackNumber = trackNumber;
        this.discNumber = discNumber;
        this.year = year;
        this.folderPath = folderPath;
        this.folderName = folderName;
        this.source = source;
        this.tidalTrackId = tidalTrackId;
        this.artworkUrl = artworkUrl;
        this.albumArtist = albumArtist;
        this.genre = genre;
        this.composer = composer;
        this.bitrate = bitrate;
        this.sampleRate = sampleRate;
        this.bitDepth = bitDepth;
        this.channels = channels;
        this.format = format;
    }

    /** Tidal constructor (private). */
    private Track(long id, String title, String artist, long durationMs,
                  String album, long albumId, int trackNumber,
                  String tidalTrackId, String artworkUrl) {
        this(id, title, artist, durationMs, null, album, albumId, trackNumber, 1, 0,
                "", "", Source.TIDAL, tidalTrackId, artworkUrl,
                null, null, null, 0, 0, 0, 0, null);
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
