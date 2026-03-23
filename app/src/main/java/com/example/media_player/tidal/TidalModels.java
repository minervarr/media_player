package com.example.media_player.tidal;

public class TidalModels {

    public static class TidalAlbum {
        public final long id;
        public final String title;
        public final String artist;
        public final String artworkId;
        public final int numberOfTracks;
        public final int duration;
        public final String quality;

        public TidalAlbum(long id, String title, String artist, String artworkId,
                          int numberOfTracks, int duration, String quality) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.artworkId = artworkId;
            this.numberOfTracks = numberOfTracks;
            this.duration = duration;
            this.quality = quality;
        }

        public String getArtworkUrl(int width, int height) {
            if (artworkId == null || artworkId.isEmpty()) return null;
            String path = artworkId.replace('-', '/');
            return "https://resources.tidal.com/images/" + path + "/" + width + "x" + height + ".jpg";
        }
    }

    public static class TidalTrack {
        public final long id;
        public final String title;
        public final String artist;
        public final long durationMs;
        public final int trackNumber;
        public final String albumTitle;
        public final long albumId;
        public final String artworkId;
        public final String quality;

        public TidalTrack(long id, String title, String artist, long durationMs,
                          int trackNumber, String albumTitle, long albumId,
                          String artworkId, String quality) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.trackNumber = trackNumber;
            this.albumTitle = albumTitle;
            this.albumId = albumId;
            this.artworkId = artworkId;
            this.quality = quality;
        }

        public String getArtworkUrl(int width, int height) {
            if (artworkId == null || artworkId.isEmpty()) return null;
            String path = artworkId.replace('-', '/');
            return "https://resources.tidal.com/images/" + path + "/" + width + "x" + height + ".jpg";
        }
    }

    public static class TidalPlaylist {
        public final String uuid;
        public final String title;
        public final int numberOfTracks;
        public final String artworkId;
        public final String creator;
        public final long durationMs;

        public TidalPlaylist(String uuid, String title, int numberOfTracks,
                             String artworkId, String creator, long durationMs) {
            this.uuid = uuid;
            this.title = title;
            this.numberOfTracks = numberOfTracks;
            this.artworkId = artworkId;
            this.creator = creator;
            this.durationMs = durationMs;
        }

        public String getArtworkUrl(int width, int height) {
            if (artworkId == null || artworkId.isEmpty()) return null;
            String path = artworkId.replace('-', '/');
            return "https://resources.tidal.com/images/" + path + "/" + width + "x" + height + ".jpg";
        }
    }

    public static class StreamInfo {
        public final String url;
        public final String codec;
        public final int bitDepth;
        public final int sampleRate;
        public final long fileSize;
        public final String quality;
        public final String requestedQuality;
        public final boolean wasDowngraded;
        public final String[] dashSegmentUrls; // non-null for DASH streams
        public final long estimatedDashSize;   // bandwidth-based estimate for DASH streams

        public StreamInfo(String url, String codec, int bitDepth, int sampleRate,
                          long fileSize, String quality, String requestedQuality) {
            this(url, codec, bitDepth, sampleRate, fileSize, quality, requestedQuality, null, 0);
        }

        public StreamInfo(String url, String codec, int bitDepth, int sampleRate,
                          long fileSize, String quality, String requestedQuality,
                          String[] dashSegmentUrls, long estimatedDashSize) {
            this.url = url;
            this.codec = codec;
            this.bitDepth = bitDepth;
            this.sampleRate = sampleRate;
            this.fileSize = fileSize;
            this.quality = quality;
            this.requestedQuality = requestedQuality;
            this.wasDowngraded = !quality.equals(requestedQuality);
            this.dashSegmentUrls = dashSegmentUrls;
            this.estimatedDashSize = estimatedDashSize;
        }

        public boolean isDash() {
            return dashSegmentUrls != null && dashSegmentUrls.length > 0;
        }
    }

    public static class TidalLyrics {
        public final long trackId;
        public final String lyrics;
        public final String subtitles;

        public TidalLyrics(long trackId, String lyrics, String subtitles) {
            this.trackId = trackId;
            this.lyrics = lyrics;
            this.subtitles = subtitles;
        }
    }

    public static class DeviceAuth {
        public final String deviceCode;
        public final String userCode;
        public final String verificationUri;
        public final int expiresIn;
        public final int interval;

        public DeviceAuth(String deviceCode, String userCode, String verificationUri,
                          int expiresIn, int interval) {
            this.deviceCode = deviceCode;
            this.userCode = userCode;
            this.verificationUri = verificationUri;
            this.expiresIn = expiresIn;
            this.interval = interval;
        }
    }

    public static class TokenResponse {
        public final String accessToken;
        public final String refreshToken;
        public final long expiresIn;
        public final long userId;

        public TokenResponse(String accessToken, String refreshToken,
                             long expiresIn, long userId) {
            this.accessToken = accessToken;
            this.refreshToken = refreshToken;
            this.expiresIn = expiresIn;
            this.userId = userId;
        }
    }
}
