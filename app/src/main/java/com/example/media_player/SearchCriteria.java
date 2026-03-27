package com.example.media_player;

import org.json.JSONException;
import org.json.JSONObject;

/** Structured search criteria for advanced track search. */
public class SearchCriteria {

    public String freeText;
    public String artist;
    public String composer;
    public String genre;
    public Integer yearFrom;
    public Integer yearTo;
    public Integer source;     // null=any, 0=LOCAL, 1=TIDAL
    public String format;
    public Integer minBitrate;
    public Integer minSampleRate;
    public String albumArtist;

    public boolean isEmpty() {
        return freeText == null && artist == null && composer == null
                && genre == null && yearFrom == null && yearTo == null
                && source == null && format == null && minBitrate == null
                && minSampleRate == null && albumArtist == null;
    }

    /** Serialize to JSON for smart playlist storage. */
    public String toJson() {
        try {
            JSONObject obj = new JSONObject();
            if (freeText != null) obj.put("freeText", freeText);
            if (artist != null) obj.put("artist", artist);
            if (composer != null) obj.put("composer", composer);
            if (genre != null) obj.put("genre", genre);
            if (yearFrom != null) obj.put("yearFrom", yearFrom);
            if (yearTo != null) obj.put("yearTo", yearTo);
            if (source != null) obj.put("source", source);
            if (format != null) obj.put("format", format);
            if (minBitrate != null) obj.put("minBitrate", minBitrate);
            if (minSampleRate != null) obj.put("minSampleRate", minSampleRate);
            if (albumArtist != null) obj.put("albumArtist", albumArtist);
            return obj.toString();
        } catch (JSONException e) {
            return "{}";
        }
    }

    /** Deserialize from JSON. Returns empty criteria on parse failure. */
    public static SearchCriteria fromJson(String json) {
        SearchCriteria c = new SearchCriteria();
        if (json == null || json.isEmpty()) return c;
        try {
            JSONObject obj = new JSONObject(json);
            if (obj.has("freeText")) c.freeText = obj.getString("freeText");
            if (obj.has("artist")) c.artist = obj.getString("artist");
            if (obj.has("composer")) c.composer = obj.getString("composer");
            if (obj.has("genre")) c.genre = obj.getString("genre");
            if (obj.has("yearFrom")) c.yearFrom = obj.getInt("yearFrom");
            if (obj.has("yearTo")) c.yearTo = obj.getInt("yearTo");
            if (obj.has("source")) c.source = obj.getInt("source");
            if (obj.has("format")) c.format = obj.getString("format");
            if (obj.has("minBitrate")) c.minBitrate = obj.getInt("minBitrate");
            if (obj.has("minSampleRate")) c.minSampleRate = obj.getInt("minSampleRate");
            if (obj.has("albumArtist")) c.albumArtist = obj.getString("albumArtist");
        } catch (JSONException ignored) {
        }
        return c;
    }
}
