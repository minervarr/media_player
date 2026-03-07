package com.example.media_player;

public class CategoryItem {
    public final String key;
    public final String title;
    public final String subtitle;
    public final int trackCount;
    public final String artworkKey;

    public CategoryItem(String key, String title, String subtitle, int trackCount, String artworkKey) {
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.trackCount = trackCount;
        this.artworkKey = artworkKey;
    }
}
