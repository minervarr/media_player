package com.example.media_player.qobuz;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST client for the Qobuz API.
 * All methods are synchronous -- call from background threads.
 */
public class QobuzApi {

    private static final String TAG = "QobuzApi";
    private static final String API_BASE = "https://www.qobuz.com/api.json/0.2";

    private final QobuzAuth auth;

    public QobuzApi(QobuzAuth auth) {
        this.auth = auth;
    }

    // ---- HTTP helpers ----

    private Map<String, String> apiHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-App-Id", auth.getAppId());
        String token = auth.getUserAuthToken();
        if (token != null) {
            headers.put("X-User-Auth-Token", token);
        }
        return headers;
    }

    private String apiGet(String url) throws Exception {
        return QobuzAuth.httpGet(url, apiHeaders());
    }

    // ---- Favorites (signed requests) ----

    public List<QobuzModels.QobuzAlbum> getFavoriteAlbums() throws Exception {
        return fetchFavoriteAlbums(0, 500);
    }

    private List<QobuzModels.QobuzAlbum> fetchFavoriteAlbums(int offset, int limit) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = auth.signFavorites(timestamp);

        String url = API_BASE + "/favorite/getUserFavorites"
                + "?type=albums"
                + "&limit=" + limit
                + "&offset=" + offset
                + "&request_ts=" + timestamp
                + "&request_sig=" + signature;

        String response = apiGet(url);
        JSONObject json = new JSONObject(response);
        JSONObject albumsObj = json.optJSONObject("albums");
        if (albumsObj == null) return new ArrayList<>();

        JSONArray items = albumsObj.optJSONArray("items");
        if (items == null) return new ArrayList<>();

        List<QobuzModels.QobuzAlbum> albums = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            albums.add(parseAlbum(items.getJSONObject(i)));
        }
        return albums;
    }

    public List<QobuzModels.QobuzTrack> getFavoriteTracks() throws Exception {
        List<QobuzModels.QobuzTrack> all = new ArrayList<>();
        int offset = 0;
        int limit = 200;
        while (true) {
            long timestamp = System.currentTimeMillis() / 1000;
            String signature = auth.signFavorites(timestamp);

            String url = API_BASE + "/favorite/getUserFavorites"
                    + "?type=tracks"
                    + "&limit=" + limit
                    + "&offset=" + offset
                    + "&request_ts=" + timestamp
                    + "&request_sig=" + signature;

            String response = apiGet(url);
            JSONObject json = new JSONObject(response);
            JSONObject tracksObj = json.optJSONObject("tracks");
            if (tracksObj == null) break;

            JSONArray items = tracksObj.optJSONArray("items");
            if (items == null || items.length() == 0) break;

            for (int i = 0; i < items.length(); i++) {
                all.add(parseTrack(items.getJSONObject(i)));
            }

            int total = tracksObj.optInt("total", 0);
            offset += items.length();
            if (offset >= total) break;
        }
        return all;
    }

    // ---- Playlists ----

    public List<QobuzModels.QobuzPlaylist> getUserPlaylists() throws Exception {
        String url = API_BASE + "/playlist/getUserPlaylists?limit=500&offset=0";
        String response = apiGet(url);
        JSONObject json = new JSONObject(response);
        JSONObject playlistsObj = json.optJSONObject("playlists");
        if (playlistsObj == null) return new ArrayList<>();

        JSONArray items = playlistsObj.optJSONArray("items");
        if (items == null) return new ArrayList<>();

        List<QobuzModels.QobuzPlaylist> playlists = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            playlists.add(parsePlaylist(items.getJSONObject(i)));
        }
        return playlists;
    }

    // ---- Album / Playlist tracks ----

    public List<QobuzModels.QobuzTrack> getAlbumTracks(String albumId) throws Exception {
        String url = API_BASE + "/album/get?album_id="
                + URLEncoder.encode(albumId, "UTF-8");
        String response = apiGet(url);
        JSONObject json = new JSONObject(response);

        // Album-level image for tracks
        String albumTitle = json.optString("title", "");
        String albumArtUrl = extractImageUrl(json.optJSONObject("image"));
        String artistName = "";
        JSONObject artist = json.optJSONObject("artist");
        if (artist != null) artistName = artist.optString("name", "");

        JSONObject tracksObj = json.optJSONObject("tracks");
        if (tracksObj == null) return new ArrayList<>();

        JSONArray items = tracksObj.optJSONArray("items");
        if (items == null) return new ArrayList<>();

        List<QobuzModels.QobuzTrack> tracks = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            tracks.add(parseTrackFromAlbum(item, albumId, albumTitle, albumArtUrl, artistName));
        }
        return tracks;
    }

    public List<QobuzModels.QobuzTrack> getPlaylistTracks(long playlistId) throws Exception {
        List<QobuzModels.QobuzTrack> all = new ArrayList<>();
        int offset = 0;
        int limit = 200;
        while (true) {
            String url = API_BASE + "/playlist/get?playlist_id=" + playlistId
                    + "&extra=tracks"
                    + "&limit=" + limit
                    + "&offset=" + offset;
            String response = apiGet(url);
            JSONObject json = new JSONObject(response);
            JSONObject tracksObj = json.optJSONObject("tracks");
            if (tracksObj == null) break;

            JSONArray items = tracksObj.optJSONArray("items");
            if (items == null || items.length() == 0) break;

            for (int i = 0; i < items.length(); i++) {
                all.add(parseTrack(items.getJSONObject(i)));
            }

            int total = tracksObj.optInt("total", 0);
            offset += items.length();
            if (offset >= total) break;
        }
        return all;
    }

    // ---- Search ----

    public List<QobuzModels.QobuzAlbum> searchAlbums(String query, int limit) throws Exception {
        String url = API_BASE + "/album/search?query="
                + URLEncoder.encode(query, "UTF-8")
                + "&limit=" + limit;
        String response = apiGet(url);
        JSONObject json = new JSONObject(response);
        JSONObject albumsObj = json.optJSONObject("albums");
        if (albumsObj == null) return new ArrayList<>();

        JSONArray items = albumsObj.optJSONArray("items");
        if (items == null) return new ArrayList<>();

        List<QobuzModels.QobuzAlbum> albums = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            albums.add(parseAlbum(items.getJSONObject(i)));
        }
        return albums;
    }

    // ---- Streaming ----

    public QobuzModels.StreamUrl getStreamUrl(long trackId, int formatId) throws Exception {
        long timestamp = System.currentTimeMillis() / 1000;
        String signature = auth.signFileUrl(trackId, formatId, timestamp);

        String url = API_BASE + "/track/getFileUrl"
                + "?track_id=" + trackId
                + "&format_id=" + formatId
                + "&intent=stream"
                + "&request_ts=" + timestamp
                + "&request_sig=" + signature;

        String response = apiGet(url);
        JSONObject json = new JSONObject(response);

        String streamUrl = json.optString("url", "");
        if (streamUrl.isEmpty()) {
            throw new IOException("No stream URL returned for track " + trackId);
        }

        return new QobuzModels.StreamUrl(
                streamUrl,
                json.optInt("format_id", formatId),
                json.optString("mime_type", ""),
                json.optDouble("sampling_rate", 0),
                json.has("bit_depth") ? json.optInt("bit_depth") : null,
                trackId
        );
    }

    /**
     * Try to get stream URL at preferred quality, falling back through lower qualities.
     */
    public QobuzModels.StreamUrl getStreamUrlWithFallback(long trackId, int preferredFormat)
            throws Exception {
        int[] fallback = QobuzModels.QUALITY_FALLBACK;
        int startIdx = 0;
        for (int i = 0; i < fallback.length; i++) {
            if (fallback[i] == preferredFormat) {
                startIdx = i;
                break;
            }
        }

        Exception lastError = null;
        for (int i = startIdx; i < fallback.length; i++) {
            try {
                QobuzModels.StreamUrl result = getStreamUrl(trackId, fallback[i]);
                if (result.url != null && !result.url.isEmpty()) {
                    if (fallback[i] != preferredFormat) {
                        Log.d(TAG, "Quality fallback: requested format " + preferredFormat
                                + " -> got " + fallback[i]);
                    }
                    return result;
                }
            } catch (QobuzAuth.HttpException e) {
                if (e.code == 400) {
                    // Invalid secret — don't retry
                    throw e;
                }
                lastError = e;
                Log.d(TAG, "Format " + fallback[i] + " failed: " + e.getMessage());
            } catch (Exception e) {
                lastError = e;
                Log.d(TAG, "Format " + fallback[i] + " failed: " + e.getMessage());
            }
        }

        throw lastError != null ? lastError
                : new IOException("No quality available for track " + trackId);
    }

    // ---- JSON parsing ----

    private QobuzModels.QobuzAlbum parseAlbum(JSONObject json) throws Exception {
        String id = json.optString("id", "");
        String title = json.optString("title", "");
        JSONObject artist = json.optJSONObject("artist");
        String artistName = artist != null ? artist.optString("name", "") : "";
        QobuzModels.ImageSet image = parseImageSet(json.optJSONObject("image"));
        int tracksCount = json.optInt("tracks_count", 0);
        int duration = json.optInt("duration", 0);
        boolean hires = json.optBoolean("hires_streamable", false);
        return new QobuzModels.QobuzAlbum(id, title, artistName, image, tracksCount, duration, hires);
    }

    private QobuzModels.QobuzTrack parseTrack(JSONObject json) throws Exception {
        long id = json.optLong("id", 0);
        String title = json.optString("title", "");
        int duration = json.optInt("duration", 0);
        int trackNumber = json.optInt("track_number", 0);
        boolean streamable = json.optBoolean("streamable", false);
        boolean hiresStreamable = json.optBoolean("hires_streamable", false);
        double maxSr = json.optDouble("maximum_sampling_rate", 0);
        int maxBd = json.optInt("maximum_bit_depth", 0);

        // Artist
        String artistName = "";
        JSONObject performer = json.optJSONObject("performer");
        if (performer != null) {
            artistName = performer.optString("name", "");
        }
        // Some responses use "artist" instead of "performer"
        if (artistName.isEmpty()) {
            JSONObject artist = json.optJSONObject("artist");
            if (artist != null) {
                artistName = artist.optString("name", "");
            }
        }

        // Album
        String albumTitle = "";
        String albumId = "";
        String artworkUrl = null;
        JSONObject album = json.optJSONObject("album");
        if (album != null) {
            albumTitle = album.optString("title", "");
            albumId = album.optString("id", "");
            artworkUrl = extractImageUrl(album.optJSONObject("image"));
        }

        return new QobuzModels.QobuzTrack(id, title, artistName, duration, trackNumber,
                albumTitle, albumId, streamable, hiresStreamable, maxSr, maxBd, artworkUrl);
    }

    /** Parse track from /album/get response where album info is at the parent level. */
    private QobuzModels.QobuzTrack parseTrackFromAlbum(JSONObject json,
            String albumId, String albumTitle, String albumArtUrl, String albumArtist)
            throws Exception {
        long id = json.optLong("id", 0);
        String title = json.optString("title", "");
        int duration = json.optInt("duration", 0);
        int trackNumber = json.optInt("track_number", 0);
        boolean streamable = json.optBoolean("streamable", false);
        boolean hiresStreamable = json.optBoolean("hires_streamable", false);
        double maxSr = json.optDouble("maximum_sampling_rate", 0);
        int maxBd = json.optInt("maximum_bit_depth", 0);

        String artistName = "";
        JSONObject performer = json.optJSONObject("performer");
        if (performer != null) {
            artistName = performer.optString("name", "");
        }
        if (artistName.isEmpty()) {
            artistName = albumArtist;
        }

        return new QobuzModels.QobuzTrack(id, title, artistName, duration, trackNumber,
                albumTitle, albumId, streamable, hiresStreamable, maxSr, maxBd, albumArtUrl);
    }

    private QobuzModels.QobuzPlaylist parsePlaylist(JSONObject json) throws Exception {
        long id = json.optLong("id", 0);
        String name = json.optString("name", "");
        String description = json.optString("description", "");
        int tracksCount = json.optInt("tracks_count", 0);
        int duration = json.optInt("duration", 0);

        String ownerName = "";
        JSONObject owner = json.optJSONObject("owner");
        if (owner != null) {
            ownerName = owner.optString("name", "");
        }

        // Playlist images can be an array or nested
        String artworkUrl = null;
        JSONArray images = json.optJSONArray("images");
        if (images != null && images.length() > 0) {
            artworkUrl = firstNonNullString(images);
        }
        if (artworkUrl == null) {
            // Some playlists have images300
            JSONArray images300 = json.optJSONArray("images300");
            if (images300 != null && images300.length() > 0) {
                artworkUrl = firstNonNullString(images300);
            }
        }
        if (artworkUrl == null) {
            artworkUrl = extractImageUrl(json.optJSONObject("image"));
        }

        return new QobuzModels.QobuzPlaylist(id, name, description, ownerName,
                tracksCount, duration, artworkUrl);
    }

    private QobuzModels.ImageSet parseImageSet(JSONObject json) {
        if (json == null) return null;
        return new QobuzModels.ImageSet(
                optStringOrNull(json, "small"),
                optStringOrNull(json, "thumbnail"),
                optStringOrNull(json, "large"),
                optStringOrNull(json, "extralarge"),
                optStringOrNull(json, "mega"),
                optStringOrNull(json, "back")
        );
    }

    /**
     * JSONObject.optString(name, null) returns the literal string "null" when the
     * underlying JSON value is null (because JSONObject.NULL.toString() == "null").
     * This helper returns Java null in that case so callers can rely on != null checks.
     */
    private static String optStringOrNull(JSONObject json, String key) {
        if (json.isNull(key)) return null;
        String s = json.optString(key, null);
        return (s == null || s.isEmpty()) ? null : s;
    }

    /** Same null-safety guard as {@link #optStringOrNull} but for JSONArray entries. */
    private static String firstNonNullString(JSONArray array) {
        for (int i = 0; i < array.length(); i++) {
            if (array.isNull(i)) continue;
            String s = array.optString(i, null);
            if (s != null && !s.isEmpty()) return s;
        }
        return null;
    }

    private String extractImageUrl(JSONObject imageJson) {
        if (imageJson == null) return null;
        QobuzModels.ImageSet set = parseImageSet(imageJson);
        return set != null ? set.best() : null;
    }
}
