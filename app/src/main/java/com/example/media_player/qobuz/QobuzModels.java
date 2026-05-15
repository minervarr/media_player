package com.example.media_player.qobuz;

public class QobuzModels {

    /** Quality format IDs matching Qobuz API. */
    public static final int QUALITY_MP3 = 5;
    public static final int QUALITY_LOSSLESS = 6;     // FLAC 16-bit/44.1kHz
    public static final int QUALITY_HI_RES = 7;       // FLAC 24-bit/≤96kHz
    public static final int QUALITY_ULTRA_HI_RES = 27; // FLAC 24-bit/>96kHz

    /** Descending quality order for fallback. */
    public static final int[] QUALITY_FALLBACK = {
            QUALITY_ULTRA_HI_RES, QUALITY_HI_RES, QUALITY_LOSSLESS, QUALITY_MP3
    };

    public static class ImageSet {
        public final String small;
        public final String thumbnail;
        public final String large;
        public final String extralarge;
        public final String mega;
        public final String back;

        public ImageSet(String small, String thumbnail, String large,
                        String extralarge, String mega, String back) {
            this.small = small;
            this.thumbnail = thumbnail;
            this.large = large;
            this.extralarge = extralarge;
            this.mega = mega;
            this.back = back;
        }

        /** Returns the highest-resolution image URL available. */
        public String best() {
            if (mega != null && !mega.isEmpty()) return mega;
            if (extralarge != null && !extralarge.isEmpty()) return extralarge;
            if (large != null && !large.isEmpty()) return large;
            if (thumbnail != null && !thumbnail.isEmpty()) return thumbnail;
            if (small != null && !small.isEmpty()) return small;
            return null;
        }
    }

    public static class QobuzAlbum {
        public final String id;
        public final String title;
        public final String artist;
        public final ImageSet image;
        public final int tracksCount;
        public final int duration;
        public final boolean hires;

        public QobuzAlbum(String id, String title, String artist, ImageSet image,
                          int tracksCount, int duration, boolean hires) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.image = image;
            this.tracksCount = tracksCount;
            this.duration = duration;
            this.hires = hires;
        }

        public String getArtworkUrl() {
            return image != null ? image.best() : null;
        }
    }

    public static class QobuzTrack {
        public final long id;
        public final String title;
        public final String artist;
        public final int duration;       // seconds
        public final int trackNumber;
        public final String albumTitle;
        public final String albumId;
        public final boolean streamable;
        public final boolean hiresStreamable;
        public final double maximumSamplingRate;
        public final int maximumBitDepth;
        public final String artworkUrl;

        public QobuzTrack(long id, String title, String artist, int duration,
                          int trackNumber, String albumTitle, String albumId,
                          boolean streamable, boolean hiresStreamable,
                          double maximumSamplingRate, int maximumBitDepth,
                          String artworkUrl) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
            this.trackNumber = trackNumber;
            this.albumTitle = albumTitle;
            this.albumId = albumId;
            this.streamable = streamable;
            this.hiresStreamable = hiresStreamable;
            this.maximumSamplingRate = maximumSamplingRate;
            this.maximumBitDepth = maximumBitDepth;
            this.artworkUrl = artworkUrl;
        }

        /** Duration in milliseconds (for Track conversion). */
        public long durationMs() {
            return duration * 1000L;
        }
    }

    public static class QobuzPlaylist {
        public final long id;
        public final String name;
        public final String description;
        public final String ownerName;
        public final int tracksCount;
        public final int duration;
        public final String artworkUrl;

        public QobuzPlaylist(long id, String name, String description,
                             String ownerName, int tracksCount, int duration,
                             String artworkUrl) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.ownerName = ownerName;
            this.tracksCount = tracksCount;
            this.duration = duration;
            this.artworkUrl = artworkUrl;
        }
    }

    public static class StreamUrl {
        public final String url;
        public final int formatId;
        public final String mimeType;
        public final double samplingRate;
        public final Integer bitDepth;
        public final long trackId;

        public StreamUrl(String url, int formatId, String mimeType,
                         double samplingRate, Integer bitDepth, long trackId) {
            this.url = url;
            this.formatId = formatId;
            this.mimeType = mimeType;
            this.samplingRate = samplingRate;
            this.bitDepth = bitDepth;
            this.trackId = trackId;
        }

        public String qualityLabel() {
            switch (formatId) {
                case QUALITY_MP3:           return "MP3 320kbps";
                case QUALITY_LOSSLESS:      return "FLAC 16/44.1";
                case QUALITY_HI_RES:        return "FLAC 24/≤96k";
                case QUALITY_ULTRA_HI_RES:  return "FLAC 24/>96k";
                default:                    return "Unknown";
            }
        }
    }
}
