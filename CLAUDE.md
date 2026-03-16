# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

```bash
./gradlew assembleDebug          # Build debug APK
./gradlew assembleRelease        # Build release APK
./gradlew installDebug           # Build and install on connected device
./gradlew test                   # Run unit tests
./gradlew connectedAndroidTest   # Run instrumented tests on device
./gradlew clean                  # Clean build outputs
```

## Project Overview

"Matrix Player" -- an Android music player written entirely in Java. Uses native Android MediaPlayer (no ExoPlayer or third-party playback libraries). Scans device media via MediaStore, displays tracks grouped by album/artist/folder with embedded artwork support.

- **Min SDK**: 24 (Android 7.0) | **Target/Compile SDK**: 36
- **Java version**: 11
- **Build**: Gradle 9.2.1, AGP 9.0.1, version catalog (`gradle/libs.versions.toml`)
- **View binding**: enabled
- **Native code**: CMake 3.22.1 / C++17 (stub in `app/src/main/cpp/native-lib.cpp`)

## Architecture

All source is in `app/src/main/java/com/example/media_player/`.

### Activities
- **MainActivity** -- Central hub. Owns the `MediaPlayer`, track list, playback queue, and a `TabLayout` with 7 tabs (Tracks, Albums, EPs, Singles, Remixes, Artists, Folders). Hosts fragments via manual show/hide (not ViewPager). Implements `TrackDataProvider` so fragments request data through an interface, never touching MediaPlayer directly.
- **ArtworkActivity** -- Full-screen artwork viewer with rotation and keep-screen-on controls.
- **SettingsActivity** -- Toggle for continuous playback and artwork screen-on.

### Fragments
- **TracksFragment** -- Flat RecyclerView list of all tracks.
- **GroupedFragment** -- Reusable master-detail fragment instantiated 5 times (Albums, EPs, Singles, Remixes, Artists/Folders). Shows category grid/list; clicking a category reveals a detail track list. Uses `newInstance(mode)` factory with bundle args.

### Key Interfaces
- **TrackDataProvider** -- Fragments call this to get tracks, trigger playback, and query state from MainActivity.
- **PlaybackObserver** -- Fragments implement this to receive playback state updates (track changed, play/pause).

### Data Flow
1. `MainActivity.loadTracks()` queries `MediaStore.Audio.Media`, creates `Track` objects.
2. `ArtworkCache.albumRegistry` is populated with album art URIs and folder paths.
3. Fragments receive data via `loadData()` callbacks and group/filter locally.

### Artwork Loading (ArtworkCache)
Singleton with `LruCache<String, Bitmap>` (1/8 max memory) and 4-thread `ExecutorService`. Resolution strategy (fallback chain): embedded picture -> album art content URI -> folder cover files (cover/folder/front/albumart patterns) -> single image fallback. Placeholder bitmaps are drawn via Canvas (dark gray + green border).

### Background Playback (MusicService)
Foreground service with media playback notification showing track info and embedded artwork. Started/updated by MainActivity on track changes.

### Settings (AppSettings)
SharedPreferences wrapper. Keys: `continuous_playback`, `artwork_keep_screen_on`.

### Release Classification (in GroupedFragment)
- Album: >4 tracks
- EP: 2-4 tracks
- Single: 1 track
- Remix: detected via regex on album/track names

## UI
XML layouts with RecyclerView, Material TabLayout, ConstraintLayout. Dark theme with green accent (#00C853). Custom view: `SquareImageView` (forces square aspect ratio for grid artwork).

## Dependencies
Only AndroidX (AppCompat, Material, ConstraintLayout, RecyclerView). No third-party libraries for playback, networking, or image loading.
