package com.example.media_player;

public class CategoryItem {
    public final String key;
    public final String title;
    public final String subtitle;
    public final int trackCount;
    public final String artworkKey;
    /** Weighted-average sample rate across all tracks in this category (Hz). */
    public final int avgSampleRate;
    /** True if any track in this category is DSD (DSF/DFF). */
    public final boolean hasDsd;

    public CategoryItem(String key, String title, String subtitle, int trackCount, String artworkKey) {
        this(key, title, subtitle, trackCount, artworkKey, 0, false);
    }

    public CategoryItem(String key, String title, String subtitle, int trackCount, String artworkKey, int avgSampleRate, boolean hasDsd) {
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.trackCount = trackCount;
        this.artworkKey = artworkKey;
        this.avgSampleRate = avgSampleRate;
        this.hasDsd = hasDsd;
    }
}
