package com.example.media_player;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

public class MatrixPlayerDatabase extends SQLiteOpenHelper {

    private static final String TAG = "MatrixPlayerDB";
    private static final String DB_NAME = "matrix_player.db";
    private static final int DB_VERSION = 4;

    private static volatile MatrixPlayerDatabase instance;

    // FTS availability: 0 = not checked, 1 = fts5, 2 = fts4, -1 = none
    static volatile int ftsMode = 0;

    // -- tracks --

    static final String TABLE_TRACKS = "tracks";
    static final String SQL_CREATE_TRACKS =
            "CREATE TABLE tracks ("
            + "id                INTEGER PRIMARY KEY,"
            + "title             TEXT NOT NULL,"
            + "artist            TEXT NOT NULL DEFAULT 'Unknown',"
            + "album             TEXT NOT NULL DEFAULT 'Unknown',"
            + "album_id          INTEGER NOT NULL,"
            + "duration_ms       INTEGER NOT NULL DEFAULT 0,"
            + "track_number      INTEGER NOT NULL DEFAULT 0,"
            + "disc_number       INTEGER NOT NULL DEFAULT 1,"
            + "year              INTEGER NOT NULL DEFAULT 0,"
            + "uri               TEXT,"
            + "folder_path       TEXT NOT NULL DEFAULT '',"
            + "folder_name       TEXT NOT NULL DEFAULT '',"
            + "source            INTEGER NOT NULL DEFAULT 0,"
            + "tidal_track_id    TEXT,"
            + "artwork_url       TEXT,"
            + "album_artist      TEXT,"
            + "genre             TEXT,"
            + "composer          TEXT,"
            + "bitrate           INTEGER,"
            + "sample_rate       INTEGER,"
            + "bit_depth         INTEGER,"
            + "channels          INTEGER,"
            + "format            TEXT,"
            + "file_path         TEXT,"
            + "file_size         INTEGER DEFAULT 0,"
            + "file_last_modified INTEGER DEFAULT 0,"
            + "scan_timestamp    INTEGER DEFAULT 0,"
            + "tidal_quality     TEXT,"
            + "tidal_album_id    INTEGER,"
            + "release_type      INTEGER DEFAULT 0,"
            + "is_remix          INTEGER DEFAULT 0,"
            + "created_at        INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),"
            + "updated_at        INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- artists --

    static final String TABLE_ARTISTS = "artists";
    static final String SQL_CREATE_ARTISTS =
            "CREATE TABLE artists ("
            + "id          INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name        TEXT NOT NULL UNIQUE COLLATE NOCASE,"
            + "sort_name   TEXT,"
            + "created_at  INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- track_artists --

    static final String TABLE_TRACK_ARTISTS = "track_artists";
    static final String SQL_CREATE_TRACK_ARTISTS =
            "CREATE TABLE track_artists ("
            + "track_id    INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,"
            + "artist_id   INTEGER NOT NULL REFERENCES artists(id) ON DELETE CASCADE,"
            + "role        INTEGER NOT NULL DEFAULT 0,"
            + "PRIMARY KEY (track_id, artist_id, role)"
            + ")";

    // -- albums --

    static final String TABLE_ALBUMS = "albums";
    static final String SQL_CREATE_ALBUMS =
            "CREATE TABLE albums ("
            + "album_id        INTEGER PRIMARY KEY,"
            + "name            TEXT NOT NULL,"
            + "artist          TEXT NOT NULL,"
            + "track_count     INTEGER NOT NULL DEFAULT 0,"
            + "total_duration  INTEGER NOT NULL DEFAULT 0,"
            + "year            INTEGER DEFAULT 0,"
            + "release_type    INTEGER DEFAULT 0,"
            + "genre           TEXT,"
            + "artwork_key     TEXT,"
            + "source          INTEGER NOT NULL DEFAULT 0,"
            + "tidal_album_id  INTEGER,"
            + "tidal_quality   TEXT,"
            + "created_at      INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),"
            + "updated_at      INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- play_history --

    static final String TABLE_PLAY_HISTORY = "play_history";
    static final String SQL_CREATE_PLAY_HISTORY =
            "CREATE TABLE play_history ("
            + "id              INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "track_id        INTEGER NOT NULL,"
            + "source          INTEGER NOT NULL,"
            + "started_at      INTEGER NOT NULL,"
            + "duration_played INTEGER NOT NULL DEFAULT 0,"
            + "completed       INTEGER NOT NULL DEFAULT 0,"
            + "sample_rate     INTEGER,"
            + "bit_depth       INTEGER,"
            + "output_device   TEXT,"
            + "tidal_quality   TEXT"
            + ")";

    // -- track_stats --

    static final String TABLE_TRACK_STATS = "track_stats";
    static final String SQL_CREATE_TRACK_STATS =
            "CREATE TABLE track_stats ("
            + "track_id        INTEGER PRIMARY KEY,"
            + "play_count      INTEGER NOT NULL DEFAULT 0,"
            + "skip_count      INTEGER NOT NULL DEFAULT 0,"
            + "total_listen_ms INTEGER NOT NULL DEFAULT 0,"
            + "last_played_at  INTEGER,"
            + "first_played_at INTEGER,"
            + "rating          INTEGER DEFAULT 0"
            + ")";

    // -- playback_queue --

    static final String TABLE_PLAYBACK_QUEUE = "playback_queue";
    static final String SQL_CREATE_PLAYBACK_QUEUE =
            "CREATE TABLE playback_queue ("
            + "position    INTEGER PRIMARY KEY,"
            + "track_id    INTEGER NOT NULL,"
            + "added_at    INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- playback_state --

    static final String TABLE_PLAYBACK_STATE = "playback_state";
    static final String SQL_CREATE_PLAYBACK_STATE =
            "CREATE TABLE playback_state ("
            + "id              INTEGER PRIMARY KEY CHECK (id = 1),"
            + "current_index   INTEGER NOT NULL DEFAULT -1,"
            + "position_ms     INTEGER NOT NULL DEFAULT 0,"
            + "is_playing      INTEGER NOT NULL DEFAULT 0,"
            + "updated_at      INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- search_index (FTS5 with FTS4 fallback) --

    static final String TABLE_SEARCH_INDEX = "search_index";

    // FTS5 (preferred -- has built-in BM25 ranking)
    private static final String SQL_CREATE_SEARCH_INDEX_FTS5 =
            "CREATE VIRTUAL TABLE search_index USING fts5("
            + "title, artist, album, composer, genre, album_artist, folder_name,"
            + "content='tracks',"
            + "content_rowid='id',"
            + "tokenize='unicode61 remove_diacritics 2'"
            + ")";

    private static final String SQL_TRIGGER_AI_FTS5 =
            "CREATE TRIGGER tracks_ai AFTER INSERT ON tracks BEGIN"
            + " INSERT INTO search_index(rowid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES (new.id, new.title, new.artist, new.album, COALESCE(new.composer,''), COALESCE(new.genre,''), COALESCE(new.album_artist,''), new.folder_name);"
            + " END";

    private static final String SQL_TRIGGER_AD_FTS5 =
            "CREATE TRIGGER tracks_ad AFTER DELETE ON tracks BEGIN"
            + " INSERT INTO search_index(search_index, rowid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES ('delete', old.id, old.title, old.artist, old.album, COALESCE(old.composer,''), COALESCE(old.genre,''), COALESCE(old.album_artist,''), old.folder_name);"
            + " END";

    private static final String SQL_TRIGGER_AU_FTS5 =
            "CREATE TRIGGER tracks_au AFTER UPDATE OF title, artist, album, composer, genre, album_artist, folder_name ON tracks BEGIN"
            + " INSERT INTO search_index(search_index, rowid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES ('delete', old.id, old.title, old.artist, old.album, COALESCE(old.composer,''), COALESCE(old.genre,''), COALESCE(old.album_artist,''), old.folder_name);"
            + " INSERT INTO search_index(rowid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES (new.id, new.title, new.artist, new.album, COALESCE(new.composer,''), COALESCE(new.genre,''), COALESCE(new.album_artist,''), new.folder_name);"
            + " END";

    // FTS4 (fallback -- universally available, uses docid instead of rowid)
    private static final String SQL_CREATE_SEARCH_INDEX_FTS4 =
            "CREATE VIRTUAL TABLE search_index USING fts4("
            + "content=\"tracks\","
            + "title, artist, album, composer, genre, album_artist, folder_name,"
            + "tokenize=unicode61"
            + ")";

    private static final String SQL_TRIGGER_AI_FTS4 =
            "CREATE TRIGGER tracks_ai AFTER INSERT ON tracks BEGIN"
            + " INSERT INTO search_index(docid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES (new.id, new.title, new.artist, new.album, COALESCE(new.composer,''), COALESCE(new.genre,''), COALESCE(new.album_artist,''), new.folder_name);"
            + " END";

    private static final String SQL_TRIGGER_AD_FTS4 =
            "CREATE TRIGGER tracks_ad AFTER DELETE ON tracks BEGIN"
            + " INSERT INTO search_index(search_index, docid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES ('delete', old.id, old.title, old.artist, old.album, COALESCE(old.composer,''), COALESCE(old.genre,''), COALESCE(old.album_artist,''), old.folder_name);"
            + " END";

    private static final String SQL_TRIGGER_AU_FTS4 =
            "CREATE TRIGGER tracks_au AFTER UPDATE OF title, artist, album, composer, genre, album_artist, folder_name ON tracks BEGIN"
            + " INSERT INTO search_index(search_index, docid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES ('delete', old.id, old.title, old.artist, old.album, COALESCE(old.composer,''), COALESCE(old.genre,''), COALESCE(old.album_artist,''), old.folder_name);"
            + " INSERT INTO search_index(docid, title, artist, album, composer, genre, album_artist, folder_name)"
            + " VALUES (new.id, new.title, new.artist, new.album, COALESCE(new.composer,''), COALESCE(new.genre,''), COALESCE(new.album_artist,''), new.folder_name);"
            + " END";

    // -- tidal_cache --

    static final String TABLE_TIDAL_CACHE = "tidal_cache";
    static final String SQL_CREATE_TIDAL_CACHE =
            "CREATE TABLE tidal_cache ("
            + "cache_key   TEXT PRIMARY KEY,"
            + "data        TEXT NOT NULL,"
            + "fetched_at  INTEGER NOT NULL,"
            + "expires_at  INTEGER NOT NULL"
            + ")";

    // -- playlists --

    static final String TABLE_PLAYLISTS = "playlists";
    static final String SQL_CREATE_PLAYLISTS =
            "CREATE TABLE playlists ("
            + "id              INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "name            TEXT NOT NULL,"
            + "description     TEXT,"
            + "is_smart        INTEGER NOT NULL DEFAULT 0,"
            + "smart_query     TEXT,"
            + "sort_order      TEXT DEFAULT 'title ASC',"
            + "track_count     INTEGER NOT NULL DEFAULT 0,"
            + "total_duration  INTEGER NOT NULL DEFAULT 0,"
            + "created_at      INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),"
            + "updated_at      INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- playlist_tracks --

    static final String TABLE_PLAYLIST_TRACKS = "playlist_tracks";
    static final String SQL_CREATE_PLAYLIST_TRACKS =
            "CREATE TABLE playlist_tracks ("
            + "playlist_id INTEGER NOT NULL REFERENCES playlists(id) ON DELETE CASCADE,"
            + "track_id    INTEGER NOT NULL REFERENCES tracks(id) ON DELETE CASCADE,"
            + "position    INTEGER NOT NULL,"
            + "added_at    INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),"
            + "PRIMARY KEY (playlist_id, position)"
            + ")";

    // -- bluetooth_configs --

    static final String TABLE_BLUETOOTH_CONFIGS = "bluetooth_configs";
    static final String SQL_CREATE_BLUETOOTH_CONFIGS =
            "CREATE TABLE bluetooth_configs ("
            + "mac_address  TEXT PRIMARY KEY,"
            + "device_name  TEXT,"
            + "codec_type   INTEGER,"
            + "sample_rate  INTEGER,"
            + "bit_rate     INTEGER,"
            + "channel_mode INTEGER,"
            + "config_json  TEXT NOT NULL,"
            + "updated_at   INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000)"
            + ")";

    // -- eq_assignments --

    static final String TABLE_EQ_ASSIGNMENTS = "eq_assignments";
    static final String SQL_CREATE_EQ_ASSIGNMENTS =
            "CREATE TABLE eq_assignments ("
            + "id             INTEGER PRIMARY KEY AUTOINCREMENT,"
            + "entity_type    INTEGER NOT NULL,"
            + "entity_id      INTEGER NOT NULL,"
            + "profile_name   TEXT NOT NULL,"
            + "profile_source TEXT NOT NULL DEFAULT '',"
            + "profile_form   TEXT NOT NULL DEFAULT '',"
            + "created_at     INTEGER NOT NULL DEFAULT (strftime('%s','now') * 1000),"
            + "UNIQUE(entity_type, entity_id)"
            + ")";

    // -- artwork_cache --

    static final String TABLE_ARTWORK_CACHE = "artwork_cache";
    static final String SQL_CREATE_ARTWORK_CACHE =
            "CREATE TABLE artwork_cache ("
            + "cache_key   TEXT PRIMARY KEY,"
            + "file_path   TEXT NOT NULL,"
            + "width       INTEGER NOT NULL,"
            + "height      INTEGER NOT NULL,"
            + "file_size   INTEGER NOT NULL,"
            + "fetched_at  INTEGER NOT NULL,"
            + "expires_at  INTEGER"
            + ")";

    // -- Indexes --

    private static final String[] SQL_CREATE_INDEXES = {
        "CREATE UNIQUE INDEX idx_tracks_file_path ON tracks(file_path) WHERE file_path IS NOT NULL",
        "CREATE INDEX idx_tracks_album_id ON tracks(album_id)",
        "CREATE INDEX idx_tracks_artist ON tracks(artist COLLATE NOCASE)",
        "CREATE INDEX idx_tracks_folder ON tracks(folder_path)",
        "CREATE INDEX idx_tracks_source ON tracks(source)",
        "CREATE INDEX idx_tracks_year ON tracks(year) WHERE year > 0",
        "CREATE INDEX idx_tracks_genre ON tracks(genre COLLATE NOCASE) WHERE genre IS NOT NULL",
        "CREATE INDEX idx_tracks_composer ON tracks(composer COLLATE NOCASE) WHERE composer IS NOT NULL",
        "CREATE INDEX idx_tracks_format ON tracks(format) WHERE format IS NOT NULL",
        "CREATE INDEX idx_tracks_release_type ON tracks(release_type)",
        "CREATE INDEX idx_albums_release_type ON albums(release_type)",
        "CREATE INDEX idx_track_stats_play_count ON track_stats(play_count DESC)",
        "CREATE INDEX idx_track_stats_last_played ON track_stats(last_played_at DESC)",
        "CREATE INDEX idx_play_history_started ON play_history(started_at DESC)",
        "CREATE INDEX idx_play_history_track ON play_history(track_id)",
        "CREATE INDEX idx_track_artists_artist ON track_artists(artist_id)",
        "CREATE INDEX idx_playlist_tracks_track ON playlist_tracks(track_id)",
        "CREATE INDEX idx_eq_assignments_profile ON eq_assignments(profile_name)",
        "CREATE INDEX idx_artwork_cache_fetched ON artwork_cache(fetched_at ASC)",
    };

    public static MatrixPlayerDatabase getInstance(Context context) {
        if (instance == null) {
            synchronized (MatrixPlayerDatabase.class) {
                if (instance == null) {
                    instance = new MatrixPlayerDatabase(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private MatrixPlayerDatabase(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
        db.execSQL("PRAGMA foreign_keys = ON");
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        detectFtsMode(db);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        Log.d(TAG, "onCreate: creating schema v" + DB_VERSION);

        db.execSQL(SQL_CREATE_TRACKS);
        db.execSQL(SQL_CREATE_ARTISTS);
        db.execSQL(SQL_CREATE_TRACK_ARTISTS);
        db.execSQL(SQL_CREATE_ALBUMS);
        db.execSQL(SQL_CREATE_PLAY_HISTORY);
        db.execSQL(SQL_CREATE_TRACK_STATS);
        db.execSQL(SQL_CREATE_PLAYBACK_QUEUE);
        db.execSQL(SQL_CREATE_PLAYBACK_STATE);
        createFtsIndex(db);
        db.execSQL(SQL_CREATE_TIDAL_CACHE);
        db.execSQL(SQL_CREATE_PLAYLISTS);
        db.execSQL(SQL_CREATE_PLAYLIST_TRACKS);
        db.execSQL(SQL_CREATE_BLUETOOTH_CONFIGS);
        db.execSQL(SQL_CREATE_EQ_ASSIGNMENTS);
        db.execSQL(SQL_CREATE_ARTWORK_CACHE);

        for (String idx : SQL_CREATE_INDEXES) {
            db.execSQL(idx);
        }

        // Insert singleton playback state row
        db.execSQL("INSERT INTO playback_state (id, current_index, position_ms, is_playing) VALUES (1, -1, 0, 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.d(TAG, "onUpgrade: " + oldVersion + " -> " + newVersion);
        if (oldVersion < 2) {
            migrate_v1_to_v2(db);
        }
        if (oldVersion < 3) {
            migrate_v2_to_v3(db);
        }
        if (oldVersion < 4) {
            migrate_v3_to_v4(db);
        }
    }

    /**
     * Try FTS5, fall back to FTS4, or skip FTS entirely.
     * Sets ftsMode: 1 = fts5, 2 = fts4, -1 = unavailable.
     */
    private void createFtsIndex(SQLiteDatabase db) {
        // Try FTS5
        try {
            db.execSQL(SQL_CREATE_SEARCH_INDEX_FTS5);
            db.execSQL(SQL_TRIGGER_AI_FTS5);
            db.execSQL(SQL_TRIGGER_AD_FTS5);
            db.execSQL(SQL_TRIGGER_AU_FTS5);
            ftsMode = 1;
            Log.d(TAG, "FTS5 search index created");
            return;
        } catch (Exception e) {
            Log.w(TAG, "FTS5 not available: " + e.getMessage());
        }

        // Try FTS4
        try {
            db.execSQL(SQL_CREATE_SEARCH_INDEX_FTS4);
            db.execSQL(SQL_TRIGGER_AI_FTS4);
            db.execSQL(SQL_TRIGGER_AD_FTS4);
            db.execSQL(SQL_TRIGGER_AU_FTS4);
            ftsMode = 2;
            Log.d(TAG, "FTS4 search index created (fallback)");
            return;
        } catch (Exception e) {
            Log.w(TAG, "FTS4 not available: " + e.getMessage());
        }

        ftsMode = -1;
        Log.w(TAG, "No FTS support -- full-text search disabled");
    }

    /** Returns true if FTS (5 or 4) is available. */
    static boolean isFtsAvailable() {
        return ftsMode > 0;
    }

    /** Returns true if FTS5 specifically is active (supports ORDER BY rank). */
    static boolean isFts5() {
        return ftsMode == 1;
    }

    /**
     * Detect FTS mode on an existing database (upgrade path or reopened DB).
     * Called once after the DB is first opened.
     */
    private static void detectFtsMode(SQLiteDatabase db) {
        if (ftsMode != 0) return;
        try {
            db.rawQuery("SELECT * FROM search_index LIMIT 0", null).close();
            // Table exists -- check if it's FTS5 by probing rank
            try {
                db.rawQuery("SELECT rank FROM search_index LIMIT 0", null).close();
                ftsMode = 1;
            } catch (Exception e) {
                ftsMode = 2;
            }
        } catch (Exception e) {
            ftsMode = -1;
        }
        Log.d(TAG, "detectFtsMode: " + ftsMode);
    }

    private void migrate_v1_to_v2(SQLiteDatabase db) {
        Log.d(TAG, "migrate_v1_to_v2: adding playlists + playlist_tracks tables");
        db.execSQL(SQL_CREATE_PLAYLISTS);
        db.execSQL(SQL_CREATE_PLAYLIST_TRACKS);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_playlist_tracks_track ON playlist_tracks(track_id)");
    }

    private void migrate_v3_to_v4(SQLiteDatabase db) {
        Log.d(TAG, "migrate_v3_to_v4: recreating FTS triggers with column-scoped UPDATE OF");
        // Drop old triggers that fired on ALL column updates (caused crash
        // when AlbumDao.updateTrackReleaseTypes updated non-indexed columns)
        db.execSQL("DROP TRIGGER IF EXISTS tracks_ai");
        db.execSQL("DROP TRIGGER IF EXISTS tracks_ad");
        db.execSQL("DROP TRIGGER IF EXISTS tracks_au");

        // Force re-detection since ftsMode may not be set yet during onUpgrade
        ftsMode = 0;
        detectFtsMode(db);
        if (ftsMode == 1) {
            db.execSQL(SQL_TRIGGER_AI_FTS5);
            db.execSQL(SQL_TRIGGER_AD_FTS5);
            db.execSQL(SQL_TRIGGER_AU_FTS5);
            Log.d(TAG, "migrate_v3_to_v4: FTS5 triggers recreated");
        } else if (ftsMode == 2) {
            db.execSQL(SQL_TRIGGER_AI_FTS4);
            db.execSQL(SQL_TRIGGER_AD_FTS4);
            db.execSQL(SQL_TRIGGER_AU_FTS4);
            Log.d(TAG, "migrate_v3_to_v4: FTS4 triggers recreated");
        } else {
            Log.d(TAG, "migrate_v3_to_v4: no FTS -- no triggers to recreate");
        }
    }

    private void migrate_v2_to_v3(SQLiteDatabase db) {
        Log.d(TAG, "migrate_v2_to_v3: adding bluetooth_configs, eq_assignments, artwork_cache");
        db.execSQL(SQL_CREATE_BLUETOOTH_CONFIGS);
        db.execSQL(SQL_CREATE_EQ_ASSIGNMENTS);
        db.execSQL(SQL_CREATE_ARTWORK_CACHE);
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_eq_assignments_profile ON eq_assignments(profile_name)");
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_artwork_cache_fetched ON artwork_cache(fetched_at ASC)");
    }
}
