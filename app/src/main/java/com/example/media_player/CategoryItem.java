package com.example.media_player;

public class CategoryItem {
    public final String key;
    public final String title;
    public final String subtitle;
    public final int trackCount;

    public CategoryItem(String key, String title, String subtitle, int trackCount) {
        this.key = key;
        this.title = title;
        this.subtitle = subtitle;
        this.trackCount = trackCount;
    }
}
