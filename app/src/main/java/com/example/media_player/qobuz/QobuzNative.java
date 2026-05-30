package com.example.media_player.qobuz;

import android.content.Context;

public final class QobuzNative {

    static {
        System.loadLibrary("qobuz_jni");
    }

    private QobuzNative() {}

    /** Must be called once at app startup (from Application.onCreate or similar). */
    public static void initAndroid(Context context) {
        nativeInitAndroid(context.getApplicationContext());
    }

    /**
     * Returns a streaming URL for the given track and format.
     * JSON: {"url":"...","format_id":6,"sampling_rate":44100.0,"bit_depth":16,"mime_type":"..."}
     * or "error: ..." on failure.
     */
    public static String getStreamUrl(
            String appId, String appSecret, String userId, String authToken,
            String trackId, String formatId) {
        return nativeGetStreamUrl(appId, appSecret, userId, authToken, trackId, formatId);
    }

    /**
     * Returns favorite albums as a JSON array.
     * Each: {"id","title","artist","tracksCount","artworkUrl"}
     */
    public static String getFavoriteAlbums(
            String appId, String appSecret, String userId, String authToken) {
        return nativeGetFavoriteAlbums(appId, appSecret, userId, authToken);
    }

    /**
     * Returns favorite tracks as a JSON array.
     * Each: {"id","title","artist","duration","albumTitle","albumId","trackNumber","artworkUrl","streamable"}
     */
    public static String getFavoriteTracks(
            String appId, String appSecret, String userId, String authToken) {
        return nativeGetFavoriteTracks(appId, appSecret, userId, authToken);
    }

    /**
     * Returns user playlists as a JSON array.
     * Each: {"id","name","ownerName","tracksCount","artworkUrl"}
     */
    public static String getUserPlaylists(
            String appId, String appSecret, String userId, String authToken) {
        return nativeGetUserPlaylists(appId, appSecret, userId, authToken);
    }

    /**
     * Returns tracks in an album as a JSON array (same shape as getFavoriteTracks items).
     */
    public static String getAlbumTracks(
            String appId, String appSecret, String userId, String authToken, String albumId) {
        return nativeGetAlbumTracks(appId, appSecret, userId, authToken, albumId);
    }

    /**
     * Returns tracks in a playlist as a JSON array (same shape as getFavoriteTracks items).
     */
    public static String getPlaylistTracks(
            String appId, String appSecret, String userId, String authToken, String playlistId) {
        return nativeGetPlaylistTracks(appId, appSecret, userId, authToken, playlistId);
    }

    /**
     * Searches albums and returns a JSON array.
     * Each: {"id","title","artist","tracksCount","artworkUrl"}
     */
    public static String searchAlbums(
            String appId, String appSecret, String userId, String authToken, String query) {
        return nativeSearchAlbums(appId, appSecret, userId, authToken, query);
    }

    // ── Native declarations ──────────────────────────────────────────────────

    private static native void   nativeInitAndroid(Context context);
    private static native String nativeGetStreamUrl(String appId, String appSecret, String userId, String authToken, String trackId, String formatId);
    private static native String nativeGetFavoriteAlbums(String appId, String appSecret, String userId, String authToken);
    private static native String nativeGetFavoriteTracks(String appId, String appSecret, String userId, String authToken);
    private static native String nativeGetUserPlaylists(String appId, String appSecret, String userId, String authToken);
    private static native String nativeGetAlbumTracks(String appId, String appSecret, String userId, String authToken, String albumId);
    private static native String nativeGetPlaylistTracks(String appId, String appSecret, String userId, String authToken, String playlistId);
    private static native String nativeSearchAlbums(String appId, String appSecret, String userId, String authToken, String query);
}
