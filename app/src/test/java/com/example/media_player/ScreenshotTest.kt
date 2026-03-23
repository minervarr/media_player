package com.example.media_player

import android.view.View
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

class ScreenshotTest {

    companion object {
        // 1080p: 1080x1920 at 420dpi (~411dp wide, phone-like)
        val CONFIG_1080P = DeviceConfig.PIXEL_5.copy(
            screenWidth = 1080,
            screenHeight = 1920,
            xdpi = 420,
            ydpi = 420,
            orientation = ScreenOrientation.PORTRAIT,
            density = Density.XXHIGH
        )

        // 4K: 2160x3840 at 840dpi (same dp dimensions as 1080p, 2x pixels)
        val CONFIG_4K = DeviceConfig.PIXEL_5.copy(
            screenWidth = 2160,
            screenHeight = 3840,
            xdpi = 840,
            ydpi = 840,
            orientation = ScreenOrientation.PORTRAIT,
            density = Density.XXXHIGH
        )

        // 8K (4320x7680) causes OOM in layoutlib -- upscale 4K via ffmpeg instead
    }

    @get:Rule
    val paparazzi = Paparazzi(
        deviceConfig = CONFIG_1080P,
        theme = "Theme.Media_player",
        useDeviceResolution = true
    )

    // -- activity_main --

    private fun inflateMain(): View {
        val view = paparazzi.inflate<View>(R.layout.activity_main)
        view.findViewById<TextView>(R.id.tv_now_playing_title)?.text = "Windowlicker"
        view.findViewById<TextView>(R.id.tv_now_playing_artist)?.text = "Aphex Twin"
        view.findViewById<TextView>(R.id.tv_current_time)?.text = "2:37"
        view.findViewById<TextView>(R.id.tv_total_time)?.text = "6:07"
        return view
    }

    @Test fun activityMain_1080p() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_1080P)
        paparazzi.snapshot(inflateMain(), "activity_main_1080p")
    }

    @Test fun activityMain_4K() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_4K)
        paparazzi.snapshot(inflateMain(), "activity_main_4K")
    }

    // -- activity_settings --

    @Test fun activitySettings_1080p() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_1080P)
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_settings), "activity_settings_1080p")
    }

    @Test fun activitySettings_4K() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_4K)
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_settings), "activity_settings_4K")
    }

    // -- activity_artwork --

    @Test fun activityArtwork_1080p() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_1080P)
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_artwork), "activity_artwork_1080p")
    }

    @Test fun activityArtwork_4K() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_4K)
        paparazzi.snapshot(paparazzi.inflate<View>(R.layout.activity_artwork), "activity_artwork_4K")
    }

    // -- item_track --

    private fun inflateTrackItem(): View {
        val view = paparazzi.inflate<View>(R.layout.item_track)
        view.findViewById<TextView>(R.id.tv_track_number)?.text = "03"
        view.findViewById<TextView>(R.id.tv_title)?.text = "Avril 14th"
        view.findViewById<TextView>(R.id.tv_artist)?.text = "Aphex Twin"
        view.findViewById<TextView>(R.id.tv_duration)?.text = "2:05"
        return view
    }

    @Test fun itemTrack_1080p() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_1080P)
        paparazzi.snapshot(inflateTrackItem(), "item_track_1080p")
    }

    @Test fun itemTrack_4K() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_4K)
        paparazzi.snapshot(inflateTrackItem(), "item_track_4K")
    }

    // -- item_category_grid --

    private fun inflateCategoryGrid(): View {
        val view = paparazzi.inflate<View>(R.layout.item_category_grid)
        view.findViewById<TextView>(R.id.tv_category_title)?.text = "Selected Ambient Works 85-92"
        view.findViewById<TextView>(R.id.tv_category_subtitle)?.text = "Aphex Twin"
        return view
    }

    @Test fun itemCategoryGrid_1080p() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_1080P)
        paparazzi.snapshot(inflateCategoryGrid(), "item_category_grid_1080p")
    }

    @Test fun itemCategoryGrid_4K() {
        paparazzi.unsafeUpdateConfig(deviceConfig = CONFIG_4K)
        paparazzi.snapshot(inflateCategoryGrid(), "item_category_grid_4K")
    }
}
