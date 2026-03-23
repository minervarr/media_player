package com.example.media_player.tidal;

import android.util.Base64;
import android.util.Log;
import android.util.Xml;

import org.json.JSONArray;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * REST client for the unofficial TIDAL API.
 * All methods are synchronous -- call from background threads.
 */
public class TidalApi {

    private static final String TAG = "TidalApi";
    private static final String API_BASE = "https://api.tidal.com/v1";

    private final TidalAuth auth;

    public TidalApi(TidalAuth auth) {
        this.auth = auth;
    }

    private String authenticatedGet(String url) throws Exception {
        try {
            return TidalAuth.httpGet(url, auth.getAccessToken());
        } catch (TidalAuth.HttpException e) {
            if (e.code == 401) {
                auth.forceRefresh();
                return TidalAuth.httpGet(url, auth.getAccessToken());
            }
            throw e;
        }
    }

    private interface PageItemParser<T> {
        T parse(JSONObject item) throws Exception;
    }

    private <T> List<T> fetchAllPages(String baseUrl, int pageSize, PageItemParser<T> parser) throws Exception {
        List<T> all = new ArrayList<>();
        int offset = 0;
        int total = Integer.MAX_VALUE;
        while (offset < total) {
            String sep = baseUrl.contains("?") ? "&" : "?";
            String url = baseUrl + sep + "limit=" + pageSize + "&offset=" + offset;
            String response = authenticatedGet(url);
            JSONObject json = new JSONObject(response);
            total = json.optInt("totalNumberOfItems", 0);
            JSONArray items = json.getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                all.add(parser.parse(items.getJSONObject(i)));
            }
            if (items.length() == 0) break;
            offset += items.length();
        }
        return all;
    }

    public List<TidalModels.TidalAlbum> getFavoriteAlbums() throws Exception {
        long userId = auth.getUserId();
        String baseUrl = API_BASE + "/users/" + userId + "/favorites/albums"
                + "?countryCode=" + auth.getCountryCode();
        return fetchAllPages(baseUrl, 100, item -> {
            JSONObject unwrapped = item.has("item") ? item.getJSONObject("item") : item;
            return parseAlbum(unwrapped);
        });
    }

    public List<TidalModels.TidalTrack> getFavoriteTracks() throws Exception {
        long userId = auth.getUserId();
        String baseUrl = API_BASE + "/users/" + userId + "/favorites/tracks"
                + "?countryCode=" + auth.getCountryCode();
        return fetchAllPages(baseUrl, 200, item -> {
            JSONObject unwrapped = item.has("item") ? item.getJSONObject("item") : item;
            return parseTrack(unwrapped);
        });
    }

    public List<TidalModels.TidalPlaylist> getPlaylists() throws Exception {
        long userId = auth.getUserId();
        String baseUrl = API_BASE + "/users/" + userId + "/playlists"
                + "?countryCode=" + auth.getCountryCode();
        return fetchAllPages(baseUrl, 100, this::parsePlaylist);
    }

    public List<TidalModels.TidalTrack> getAlbumTracks(long albumId) throws Exception {
        String baseUrl = API_BASE + "/albums/" + albumId + "/tracks"
                + "?countryCode=" + auth.getCountryCode();
        return fetchAllPages(baseUrl, 100, this::parseTrack);
    }

    public List<TidalModels.TidalTrack> getPlaylistTracks(String uuid) throws Exception {
        String baseUrl = API_BASE + "/playlists/" + uuid + "/tracks"
                + "?countryCode=" + auth.getCountryCode();
        return fetchAllPages(baseUrl, 200, this::parseTrack);
    }

    public List<TidalModels.TidalAlbum> searchAlbums(String query, int limit) throws Exception {
        String encoded = URLEncoder.encode(query, "UTF-8");
        String url = API_BASE + "/search/albums"
                + "?query=" + encoded + "&limit=" + limit
                + "&countryCode=" + auth.getCountryCode();
        String response = authenticatedGet(url);
        JSONObject json = new JSONObject(response);
        JSONArray items = json.getJSONArray("items");
        List<TidalModels.TidalAlbum> albums = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            albums.add(parseAlbum(items.getJSONObject(i)));
        }
        return albums;
    }

    public TidalModels.TidalLyrics getLyrics(long trackId) throws Exception {
        String url = API_BASE + "/tracks/" + trackId + "/lyrics"
                + "?countryCode=" + auth.getCountryCode();
        try {
            String response = authenticatedGet(url);
            JSONObject json = new JSONObject(response);
            String lyrics = json.optString("lyrics", null);
            String subtitles = json.optString("subtitles", null);
            if ((lyrics == null || lyrics.isEmpty()) && (subtitles == null || subtitles.isEmpty())) {
                return null;
            }
            return new TidalModels.TidalLyrics(trackId, lyrics, subtitles);
        } catch (Exception e) {
            // 404 = no lyrics available
            if (e.getMessage() != null && e.getMessage().contains("404")) {
                Log.d(TAG, "No lyrics for track " + trackId);
                return null;
            }
            throw e;
        }
    }

    public List<TidalModels.TidalTrack> searchTracks(String query, int limit) throws Exception {
        String encoded = URLEncoder.encode(query, "UTF-8");
        String url = API_BASE + "/search/tracks"
                + "?query=" + encoded + "&limit=" + limit
                + "&countryCode=" + auth.getCountryCode();
        String response = authenticatedGet(url);
        JSONObject json = new JSONObject(response);
        JSONArray items = json.getJSONArray("items");
        List<TidalModels.TidalTrack> tracks = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            tracks.add(parseTrack(items.getJSONObject(i)));
        }
        return tracks;
    }

    /**
     * Get stream URL for a track. Synchronous -- call from playback thread.
     * Falls back to LOSSLESS if the requested quality is unavailable.
     */
    public TidalModels.StreamInfo getStreamInfo(long trackId, String quality) throws Exception {
        try {
            return fetchStreamInfo(trackId, quality);
        } catch (Exception e) {
            if (!"LOSSLESS".equals(quality)) {
                Log.w(TAG, "Quality " + quality + " failed (" + e.getClass().getSimpleName()
                        + "): " + e.getMessage() + ", falling back to LOSSLESS");
                return fetchStreamInfo(trackId, "LOSSLESS");
            }
            throw e;
        }
    }

    private TidalModels.StreamInfo fetchStreamInfo(long trackId, String quality) throws Exception {
        String url = API_BASE + "/tracks/" + trackId
                + "/playbackinfopostpaywall"
                + "?audioquality=" + quality
                + "&playbackmode=STREAM"
                + "&assetpresentation=FULL"
                + "&countryCode=" + auth.getCountryCode();
        Log.d(TAG, "fetchStreamInfo: quality=" + quality + " countryCode=" + auth.getCountryCode());
        String response = authenticatedGet(url);
        // Strip BOM and whitespace before checking
        String cleaned = response;
        if (cleaned.length() > 0 && cleaned.charAt(0) == '\uFEFF') {
            cleaned = cleaned.substring(1);
        }
        cleaned = cleaned.trim();
        if (cleaned.startsWith("<")) {
            Log.w(TAG, "XML response for " + quality + ": hex0=0x"
                    + Integer.toHexString(response.charAt(0)));
            throw new Exception("Unexpected response (track may be unavailable in " + quality + ")");
        }
        JSONObject json = new JSONObject(cleaned);

        String manifestBase64 = json.getString("manifest");
        String manifestMimeType = json.optString("manifestMimeType", "");
        String actualQuality = json.optString("audioQuality", quality);
        int bitDepth = json.optInt("bitDepth", 16);
        int sampleRate = json.optInt("sampleRate", 44100);

        Log.d(TAG, "fetchStreamInfo: manifestMimeType=" + manifestMimeType
                + " actualQuality=" + actualQuality);

        if (!actualQuality.equals(quality)) {
            Log.w(TAG, "Server-side quality downgrade: requested " + quality
                    + " but got " + actualQuality);
        }

        // Decode the manifest
        String manifestStr = new String(Base64.decode(manifestBase64, Base64.DEFAULT), StandardCharsets.UTF_8);

        String streamUrl;
        String codec;
        long fileSize;

        String[] dashSegmentUrls = null;

        long estimatedDashSize = 0;

        if (manifestMimeType.contains("dash+xml")) {
            // DASH MPD manifest (used for HI_RES_LOSSLESS)
            DashManifest dash = parseDashManifest(manifestStr);
            streamUrl = dash.initUrl; // init segment as primary URL for logging
            codec = dash.codecs;
            fileSize = 0;
            dashSegmentUrls = dash.allSegmentUrls();
            if (dash.bandwidth > 0 && dash.durationSec > 0) {
                estimatedDashSize = (long) (dash.bandwidth * dash.durationSec / 8);
            }
            Log.d(TAG, "DASH: " + dashSegmentUrls.length + " segments (1 init + "
                    + dash.segmentCount + " media), codecs=" + codec
                    + " bandwidth=" + dash.bandwidth + " duration=" + dash.durationSec
                    + "s estimatedSize=" + (estimatedDashSize / 1024) + "KB");
        } else if (manifestMimeType.contains("vnd.tidal.bts") || manifestMimeType.isEmpty()) {
            // BTS JSON manifest (used for LOSSLESS and others)
            JSONObject manifest = new JSONObject(manifestStr);
            JSONArray urls = manifest.optJSONArray("urls");
            if (urls != null && urls.length() > 0) {
                streamUrl = urls.getString(0);
            } else {
                streamUrl = manifest.optString("url", "");
            }
            codec = manifest.optString("codecs", manifest.optString("mimeType", "flac"));
            fileSize = manifest.optLong("fileSize", 0);
        } else {
            throw new Exception("Unknown manifest type: " + manifestMimeType);
        }

        // If fileSize not in manifest, try HEAD request
        if (fileSize <= 0 && dashSegmentUrls == null) {
            fileSize = getContentLength(streamUrl);
        }

        Log.d(TAG, "Stream: " + actualQuality + " " + codec + " " + bitDepth + "/" + sampleRate
                + " size=" + fileSize
                + (dashSegmentUrls != null ? " DASH(" + dashSegmentUrls.length + " segs)" : "")
                + " url=" + streamUrl.substring(0, Math.min(80, streamUrl.length())) + "...");

        return new TidalModels.StreamInfo(streamUrl, codec, bitDepth, sampleRate,
                fileSize, actualQuality, quality, dashSegmentUrls, estimatedDashSize);
    }

    private static class DashManifest {
        String initUrl;
        String mediaUrlTemplate;
        int startNumber;
        int segmentCount;
        String codecs;
        long bandwidth;
        double durationSec;

        /** Returns [initUrl, seg1Url, seg2Url, ...] */
        String[] allSegmentUrls() {
            String[] urls = new String[1 + segmentCount];
            urls[0] = initUrl;
            for (int i = 0; i < segmentCount; i++) {
                urls[1 + i] = mediaUrlTemplate.replace("$Number$",
                        String.valueOf(startNumber + i));
            }
            return urls;
        }
    }

    /**
     * Parse a DASH MPD manifest with SegmentTemplate.
     * Extracts init URL, media URL template, segment count, and codecs.
     */
    private DashManifest parseDashManifest(String xml) throws Exception {
        DashManifest result = new DashManifest();
        result.codecs = "flac";
        result.startNumber = 1;

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(new StringReader(xml));

        int totalSegments = 0;
        int eventType = parser.getEventType();

        while (eventType != XmlPullParser.END_DOCUMENT) {
            if (eventType == XmlPullParser.START_TAG) {
                String tag = parser.getName();
                if ("MPD".equals(tag)) {
                    String dur = parser.getAttributeValue(null, "mediaPresentationDuration");
                    if (dur != null) {
                        result.durationSec = parseIso8601Duration(dur);
                    }
                } else if ("Representation".equals(tag)) {
                    String c = parser.getAttributeValue(null, "codecs");
                    if (c != null && !c.isEmpty()) {
                        result.codecs = c;
                    }
                    String bw = parser.getAttributeValue(null, "bandwidth");
                    if (bw != null) {
                        try { result.bandwidth = Long.parseLong(bw); } catch (NumberFormatException ignored) {}
                    }
                } else if ("SegmentTemplate".equals(tag)) {
                    result.initUrl = unescapeXml(
                            parser.getAttributeValue(null, "initialization"));
                    result.mediaUrlTemplate = unescapeXml(
                            parser.getAttributeValue(null, "media"));
                    String sn = parser.getAttributeValue(null, "startNumber");
                    if (sn != null) {
                        result.startNumber = Integer.parseInt(sn);
                    }
                } else if ("S".equals(tag)) {
                    // Each <S> element in SegmentTimeline: d=duration, r=repeat count
                    String r = parser.getAttributeValue(null, "r");
                    int repeat = (r != null) ? Integer.parseInt(r) : 0;
                    totalSegments += 1 + repeat; // 1 for the S itself + r repeats
                } else if ("ContentProtection".equals(tag)) {
                    Log.w(TAG, "DASH manifest contains ContentProtection (DRM) -- playback may fail");
                }
            }
            eventType = parser.next();
        }

        result.segmentCount = totalSegments;

        if (result.initUrl == null || result.mediaUrlTemplate == null) {
            throw new Exception("DASH manifest missing SegmentTemplate");
        }
        if (totalSegments == 0) {
            throw new Exception("DASH manifest has no segments");
        }

        Log.d(TAG, "parseDashManifest: codecs=" + result.codecs
                + " segments=" + totalSegments + " startNumber=" + result.startNumber);
        return result;
    }

    private static String unescapeXml(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    /** Parse ISO 8601 duration (e.g. "PT4M30.123S") to seconds. */
    private static double parseIso8601Duration(String dur) {
        // Format: PT[nH][nM][n.nS]
        double seconds = 0;
        String s = dur;
        if (s.startsWith("PT")) s = s.substring(2);
        else if (s.startsWith("P")) s = s.substring(1);

        int hIdx = s.indexOf('H');
        if (hIdx >= 0) {
            seconds += Double.parseDouble(s.substring(0, hIdx)) * 3600;
            s = s.substring(hIdx + 1);
        }
        int mIdx = s.indexOf('M');
        if (mIdx >= 0) {
            seconds += Double.parseDouble(s.substring(0, mIdx)) * 60;
            s = s.substring(mIdx + 1);
        }
        int sIdx = s.indexOf('S');
        if (sIdx >= 0) {
            seconds += Double.parseDouble(s.substring(0, sIdx));
        }
        return seconds;
    }

    private long getContentLength(String urlStr) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(urlStr).openConnection();
            conn.setRequestMethod("HEAD");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.connect();
            long len = conn.getContentLengthLong();
            conn.disconnect();
            return len > 0 ? len : 0;
        } catch (Exception e) {
            Log.w(TAG, "HEAD request failed: " + e.getMessage());
            return 0;
        }
    }

    private TidalModels.TidalAlbum parseAlbum(JSONObject json) throws Exception {
        long id = json.getLong("id");
        String title = json.optString("title", "");
        String artworkId = json.optString("cover", "");

        String artist = "";
        if (json.has("artist")) {
            artist = json.getJSONObject("artist").optString("name", "");
        } else if (json.has("artists")) {
            JSONArray artists = json.getJSONArray("artists");
            if (artists.length() > 0) {
                artist = artists.getJSONObject(0).optString("name", "");
            }
        }

        int numberOfTracks = json.optInt("numberOfTracks", 0);
        int duration = json.optInt("duration", 0);
        String quality = json.optString("audioQuality", "");

        return new TidalModels.TidalAlbum(id, title, artist, artworkId,
                numberOfTracks, duration, quality);
    }

    private TidalModels.TidalTrack parseTrack(JSONObject json) throws Exception {
        long id = json.getLong("id");
        String title = json.optString("title", "");
        long durationMs = json.optLong("duration", 0) * 1000;
        int trackNumber = json.optInt("trackNumber", 0);
        String quality = json.optString("audioQuality", "");

        String artist = "";
        if (json.has("artist")) {
            artist = json.getJSONObject("artist").optString("name", "");
        } else if (json.has("artists")) {
            JSONArray artists = json.getJSONArray("artists");
            if (artists.length() > 0) {
                artist = artists.getJSONObject(0).optString("name", "");
            }
        }

        String albumTitle = "";
        long albumId = 0;
        String artworkId = "";
        if (json.has("album")) {
            JSONObject album = json.getJSONObject("album");
            albumTitle = album.optString("title", "");
            albumId = album.optLong("id", 0);
            artworkId = album.optString("cover", "");
        }

        return new TidalModels.TidalTrack(id, title, artist, durationMs,
                trackNumber, albumTitle, albumId, artworkId, quality);
    }

    private TidalModels.TidalPlaylist parsePlaylist(JSONObject json) throws Exception {
        String uuid = json.getString("uuid");
        String title = json.optString("title", "");
        int numberOfTracks = json.optInt("numberOfTracks", 0);

        String artworkId = "";
        if (json.has("squareImage")) {
            artworkId = json.optString("squareImage", "");
        } else if (json.has("image")) {
            artworkId = json.optString("image", "");
        }

        String creator = "";
        if (json.has("creator")) {
            JSONObject creatorObj = json.getJSONObject("creator");
            creator = creatorObj.optString("name", "");
        }

        long durationMs = json.optLong("duration", 0) * 1000;

        return new TidalModels.TidalPlaylist(uuid, title, numberOfTracks,
                artworkId, creator, durationMs);
    }
}
