package com.example.media_player.qobuz;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST client for the Qobuz API — backed by the native KawusapiCC library.
 * All methods are synchronous -- call from background threads.
 */
public class QobuzApi {

    private static final String TAG = "QobuzApi";

    private final QobuzClient client;

    public QobuzApi(QobuzAuth auth) {
        this.client = auth.getClient();
    }

    private String apiGet(String endpoint, Map<String, String> params) throws Exception {
        return client.apiGet(endpoint, params);
    }

    // ── Favorites ────────────────────────────────────────────────────────────

    public List<QobuzModels.QobuzAlbum> getFavoriteAlbums() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", "albums");
        p.put("limit", "500");
        JSONObject json = new JSONObject(apiGet("/favorite/getUserFavorites", p));
        JSONArray items = json.getJSONObject("albums").getJSONArray("items");
        List<QobuzModels.QobuzAlbum> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) result.add(parseApiAlbum(items.getJSONObject(i)));
        return result;
    }

    public List<QobuzModels.QobuzTrack> getFavoriteTracks() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("type", "tracks");
        p.put("limit", "500");
        JSONObject json = new JSONObject(apiGet("/favorite/getUserFavorites", p));
        JSONArray items = json.getJSONObject("tracks").getJSONArray("items");
        List<QobuzModels.QobuzTrack> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) result.add(parseApiTrack(items.getJSONObject(i)));
        return result;
    }

    // ── Playlists ────────────────────────────────────────────────────────────

    public List<QobuzModels.QobuzPlaylist> getUserPlaylists() throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("limit", "500");
        JSONObject json = new JSONObject(apiGet("/playlist/getUserPlaylists", p));
        JSONArray items = json.getJSONObject("playlists").getJSONArray("items");
        List<QobuzModels.QobuzPlaylist> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) result.add(parseApiPlaylist(items.getJSONObject(i)));
        return result;
    }

    // ── Album / Playlist tracks ──────────────────────────────────────────────

    public List<QobuzModels.QobuzTrack> getAlbumTracks(String albumId) throws Exception {
        // /album/get rejects extra=tracks on signed requests; tracks are
        // included in the default response.
        Map<String, String> p = new LinkedHashMap<>();
        p.put("album_id", albumId);
        JSONObject json = new JSONObject(apiGet("/album/get", p));
        String albumTitle = json.optString("title", "");
        String artist = optNestedString(json, "artist", "name", "");
        String artworkUrl = QobuzAuth.coverOrgUrl(optImageUrl(json.optJSONObject("image")));
        JSONArray items = json.getJSONObject("tracks").getJSONArray("items");
        List<QobuzModels.QobuzTrack> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            result.add(parseApiTrackInAlbum(items.getJSONObject(i),
                    albumTitle, albumId, artist, artworkUrl));
        }
        return result;
    }

    public List<QobuzModels.QobuzTrack> getPlaylistTracks(long playlistId) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("playlist_id", String.valueOf(playlistId));
        p.put("extra", "tracks");
        JSONObject json = new JSONObject(apiGet("/playlist/get", p));
        JSONArray items = json.getJSONObject("tracks").getJSONArray("items");
        List<QobuzModels.QobuzTrack> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) result.add(parseApiTrack(items.getJSONObject(i)));
        return result;
    }

    // ── Search ───────────────────────────────────────────────────────────────

    public List<QobuzModels.QobuzAlbum> searchAlbums(String query, int limit) throws Exception {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("query", query);
        p.put("limit", String.valueOf(limit));
        JSONObject json = new JSONObject(apiGet("/album/search", p));
        JSONArray items = json.getJSONObject("albums").getJSONArray("items");
        List<QobuzModels.QobuzAlbum> result = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) result.add(parseApiAlbum(items.getJSONObject(i)));
        return result;
    }

    // ── Streaming ────────────────────────────────────────────────────────────

    public QobuzModels.StreamUrl getStreamUrl(long trackId, int formatId) throws Exception {
        JSONObject json = new JSONObject(client.getTrackFileUrl(trackId, formatId));
        String url = json.optString("url", "");
        if (url.isEmpty()) throw new IOException("No stream URL returned for track " + trackId);
        return new QobuzModels.StreamUrl(
                url,
                json.optInt("format_id", formatId),
                json.optString("mime_type", ""),
                json.optDouble("sampling_rate", 0),
                json.has("bit_depth") && !json.isNull("bit_depth")
                        ? json.optInt("bit_depth") : null,
                trackId
        );
    }

    /** Try preferred quality first, then fall back through lower qualities. */
    public QobuzModels.StreamUrl getStreamUrlWithFallback(long trackId, int preferredFormat)
            throws Exception {
        int[] fallback = QobuzModels.QUALITY_FALLBACK;
        int startIdx = 0;
        for (int i = 0; i < fallback.length; i++) {
            if (fallback[i] == preferredFormat) { startIdx = i; break; }
        }
        Exception lastError = null;
        for (int i = startIdx; i < fallback.length; i++) {
            try {
                QobuzModels.StreamUrl result = getStreamUrl(trackId, fallback[i]);
                if (result.url != null && !result.url.isEmpty()) {
                    if (fallback[i] != preferredFormat)
                        Log.d(TAG, "Quality fallback: requested " + preferredFormat + " -> got " + fallback[i]);
                    return result;
                }
            } catch (Exception e) {
                lastError = e;
                Log.d(TAG, "Format " + fallback[i] + " failed: " + e.getMessage());
            }
        }
        throw lastError != null ? lastError
                : new IOException("No quality available for track " + trackId);
    }

    // ── Qobuz API JSON parsers ────────────────────────────────────────────────

    private static QobuzModels.QobuzAlbum parseApiAlbum(JSONObject j) {
        String artist = optNestedString(j, "artist", "name", "");
        String artworkUrl = QobuzAuth.coverOrgUrl(optImageUrl(j.optJSONObject("image")));
        QobuzModels.ImageSet image = artworkUrl.isEmpty() ? null
                : new QobuzModels.ImageSet(null, null, artworkUrl, null, null, null);
        return new QobuzModels.QobuzAlbum(
                j.optString("id", ""),
                j.optString("title", ""),
                artist,
                image,
                j.optInt("tracks_count", 0),
                0, false
        );
    }

    private static QobuzModels.QobuzTrack parseApiTrack(JSONObject t) {
        String artist = optNestedString(t, "performer", "name", "");
        if (artist.isEmpty()) artist = optNestedString(t, "album", "artist", "name", "");
        JSONObject album = t.optJSONObject("album");
        String albumTitle = album != null ? album.optString("title", "") : "";
        String albumId    = album != null ? album.optString("id", "") : "";
        String artworkUrl = QobuzAuth.coverOrgUrl(
                album != null ? optImageUrl(album.optJSONObject("image")) : "");
        return new QobuzModels.QobuzTrack(
                t.optLong("id", 0),
                t.optString("title", ""),
                artist,
                t.optInt("duration", 0),
                t.optInt("track_number", 0),
                albumTitle,
                albumId,
                t.optBoolean("streamable", true),
                false, 0, 0,
                artworkUrl
        );
    }

    private static QobuzModels.QobuzTrack parseApiTrackInAlbum(JSONObject t,
            String albumTitle, String albumId, String fallbackArtist, String artworkUrl) {
        String artist = optNestedString(t, "performer", "name", fallbackArtist);
        return new QobuzModels.QobuzTrack(
                t.optLong("id", 0),
                t.optString("title", ""),
                artist,
                t.optInt("duration", 0),
                t.optInt("track_number", 0),
                albumTitle,
                albumId,
                t.optBoolean("streamable", true),
                false, 0, 0,
                artworkUrl
        );
    }

    private static QobuzModels.QobuzPlaylist parseApiPlaylist(JSONObject j) {
        String ownerName = optNestedString(j, "owner", "name", "");
        if (ownerName.isEmpty()) ownerName = optNestedString(j, "creator", "display_name", "");
        String artworkUrl = "";
        JSONObject images = j.optJSONObject("images");
        if (images != null) artworkUrl = images.optString("large", images.optString("small", ""));
        if (artworkUrl.isEmpty()) {
            JSONArray rect = j.optJSONArray("image_rectangle");
            if (rect != null && rect.length() > 0) artworkUrl = rect.optString(0, "");
        }
        long id = j.optLong("id", 0);
        if (id == 0) {
            try { id = Long.parseLong(j.optString("id", "0")); } catch (NumberFormatException ignored) {}
        }
        return new QobuzModels.QobuzPlaylist(
                id,
                j.optString("name", ""),
                "",
                ownerName,
                j.optInt("tracks_count", 0),
                0,
                artworkUrl
        );
    }

    // ── JSON helpers ─────────────────────────────────────────────────────────

    private static String optNestedString(JSONObject obj, String key1, String key2, String def) {
        if (obj == null) return def;
        JSONObject inner = obj.optJSONObject(key1);
        return inner != null ? inner.optString(key2, def) : def;
    }

    /** Three-level lookup: obj[key1][key2][key3] */
    private static String optNestedString(JSONObject obj, String key1, String key2,
                                           String key3, String def) {
        if (obj == null) return def;
        JSONObject inner = obj.optJSONObject(key1);
        if (inner == null) return def;
        JSONObject inner2 = inner.optJSONObject(key2);
        return inner2 != null ? inner2.optString(key3, def) : def;
    }

    private static String optImageUrl(JSONObject image) {
        if (image == null) return "";
        String url = image.optString("large", "");
        if (url.isEmpty()) url = image.optString("small", "");
        return url;
    }
}
