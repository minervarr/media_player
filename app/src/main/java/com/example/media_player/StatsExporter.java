package com.example.media_player;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Exports all listening data as a ZIP containing CSVs and a data dictionary.
 * Runs on a background thread.
 */
public class StatsExporter {

    private static final String TAG = "StatsExporter";

    private final Context context;
    private final MatrixPlayerDatabase dbHelper;

    public StatsExporter(Context context, MatrixPlayerDatabase dbHelper) {
        this.context = context;
        this.dbHelper = dbHelper;
    }

    /**
     * Generate the export ZIP. Returns the File on success.
     * Must be called from a background thread.
     */
    public File export() throws IOException {
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        File cacheDir = context.getCacheDir();
        File zipFile = new File(cacheDir, "matrix_player_export_" + date + ".zip");

        // Create temporary CSV files
        File tempDir = new File(cacheDir, "export_temp");
        if (!tempDir.exists()) tempDir.mkdirs();

        try {
            File tracksCsv = exportTracks(tempDir);
            File playHistoryCsv = exportPlayHistory(tempDir);
            File trackStatsCsv = exportTrackStats(tempDir);
            File albumsCsv = exportAlbums(tempDir);
            File artistsCsv = exportArtists(tempDir);
            File trackArtistsCsv = exportTrackArtists(tempDir);
            File dataDictionary = writeDataDictionary(tempDir);

            // Build ZIP
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(zipFile))) {
                addToZip(zos, "tracks.csv", tracksCsv);
                addToZip(zos, "play_history.csv", playHistoryCsv);
                addToZip(zos, "track_stats.csv", trackStatsCsv);
                addToZip(zos, "albums.csv", albumsCsv);
                addToZip(zos, "artists.csv", artistsCsv);
                addToZip(zos, "track_artists.csv", trackArtistsCsv);
                addToZip(zos, "DATA_DICTIONARY.md", dataDictionary);
            }

            Log.d(TAG, "Export complete: " + zipFile.getAbsolutePath()
                    + " (" + zipFile.length() + " bytes)");
            return zipFile;
        } finally {
            // Clean up temp files
            File[] temps = tempDir.listFiles();
            if (temps != null) {
                for (File f : temps) f.delete();
            }
            tempDir.delete();
        }
    }

    private File exportTracks(File dir) throws IOException {
        File file = new File(dir, "tracks.csv");
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM tracks ORDER BY id", null);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("id,title,artist,album,album_id,duration_ms,track_number,disc_number,year,"
                    + "folder_path,folder_name,source,tidal_track_id,album_artist,genre,composer,"
                    + "bitrate,sample_rate,bit_depth,channels,format,file_path,file_size,"
                    + "release_type,is_remix,created_at,updated_at");
            w.newLine();
            while (c.moveToNext()) {
                w.write(csvRow(c, "id", "title", "artist", "album", "album_id", "duration_ms",
                        "track_number", "disc_number", "year", "folder_path", "folder_name",
                        "source", "tidal_track_id", "album_artist", "genre", "composer",
                        "bitrate", "sample_rate", "bit_depth", "channels", "format",
                        "file_path", "file_size", "release_type", "is_remix",
                        "created_at", "updated_at"));
                w.newLine();
            }
        } finally {
            c.close();
        }
        return file;
    }

    private File exportPlayHistory(File dir) throws IOException {
        File file = new File(dir, "play_history.csv");
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM play_history ORDER BY id", null);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("id,track_id,source,started_at,duration_played,completed,"
                    + "sample_rate,bit_depth,output_device,tidal_quality");
            w.newLine();
            while (c.moveToNext()) {
                w.write(csvRow(c, "id", "track_id", "source", "started_at", "duration_played",
                        "completed", "sample_rate", "bit_depth", "output_device", "tidal_quality"));
                w.newLine();
            }
        } finally {
            c.close();
        }
        return file;
    }

    private File exportTrackStats(File dir) throws IOException {
        File file = new File(dir, "track_stats.csv");
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM track_stats ORDER BY track_id", null);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("track_id,play_count,skip_count,total_listen_ms,last_played_at,"
                    + "first_played_at,rating");
            w.newLine();
            while (c.moveToNext()) {
                w.write(csvRow(c, "track_id", "play_count", "skip_count", "total_listen_ms",
                        "last_played_at", "first_played_at", "rating"));
                w.newLine();
            }
        } finally {
            c.close();
        }
        return file;
    }

    private File exportAlbums(File dir) throws IOException {
        File file = new File(dir, "albums.csv");
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM albums ORDER BY album_id", null);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("album_id,name,artist,track_count,total_duration,year,release_type,"
                    + "genre,source,tidal_album_id,tidal_quality");
            w.newLine();
            while (c.moveToNext()) {
                w.write(csvRow(c, "album_id", "name", "artist", "track_count", "total_duration",
                        "year", "release_type", "genre", "source", "tidal_album_id", "tidal_quality"));
                w.newLine();
            }
        } finally {
            c.close();
        }
        return file;
    }

    private File exportArtists(File dir) throws IOException {
        File file = new File(dir, "artists.csv");
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM artists ORDER BY id", null);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("id,name,sort_name,created_at");
            w.newLine();
            while (c.moveToNext()) {
                w.write(csvRow(c, "id", "name", "sort_name", "created_at"));
                w.newLine();
            }
        } finally {
            c.close();
        }
        return file;
    }

    private File exportTrackArtists(File dir) throws IOException {
        File file = new File(dir, "track_artists.csv");
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor c = db.rawQuery("SELECT * FROM track_artists ORDER BY track_id, artist_id", null);
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("track_id,artist_id,role");
            w.newLine();
            while (c.moveToNext()) {
                w.write(csvRow(c, "track_id", "artist_id", "role"));
                w.newLine();
            }
        } finally {
            c.close();
        }
        return file;
    }

    private File writeDataDictionary(File dir) throws IOException {
        File file = new File(dir, "DATA_DICTIONARY.md");
        try (BufferedWriter w = new BufferedWriter(new FileWriter(file))) {
            w.write("# Matrix Player -- Data Dictionary\n\n");
            w.write("Export from Matrix Player for Android.\n");
            w.write("All timestamps are epoch milliseconds (Unix time * 1000).\n\n");

            w.write("## Files\n\n");
            w.write("| File | Description |\n");
            w.write("|------|-------------|\n");
            w.write("| tracks.csv | All track metadata (local + TIDAL) |\n");
            w.write("| play_history.csv | Every playback event (rawest data) |\n");
            w.write("| track_stats.csv | Per-track aggregates (play count, skip count, rating) |\n");
            w.write("| albums.csv | Album metadata |\n");
            w.write("| artists.csv | Artist table |\n");
            w.write("| track_artists.csv | Track-to-artist relationships |\n\n");

            w.write("## Value Encodings\n\n");
            w.write("| Field | Value | Meaning |\n");
            w.write("|-------|-------|---------|\n");
            w.write("| source | 0 | LOCAL |\n");
            w.write("| source | 1 | TIDAL |\n");
            w.write("| release_type | 0 | ALBUM |\n");
            w.write("| release_type | 1 | EP |\n");
            w.write("| release_type | 2 | SINGLE |\n");
            w.write("| release_type | 3 | REMIX |\n");
            w.write("| role | 0 | primary |\n");
            w.write("| role | 1 | featured |\n");
            w.write("| role | 2 | remixer |\n");
            w.write("| completed | 0 | not completed |\n");
            w.write("| completed | 1 | completed |\n");
            w.write("| rating | 0 | unrated |\n");
            w.write("| rating | 1-5 | star rating |\n\n");

            w.write("## Completion Threshold\n\n");
            w.write("- completed = 1 when >= 80% of track duration was played\n");
            w.write("- skip_count incremented when < 10 seconds played and not completed\n\n");

            w.write("## Relationships\n\n");
            w.write("- play_history.track_id -> tracks.id\n");
            w.write("- track_stats.track_id -> tracks.id\n");
            w.write("- tracks.album_id -> albums.album_id\n");
            w.write("- track_artists.track_id -> tracks.id\n");
            w.write("- track_artists.artist_id -> artists.id\n\n");

            w.write("## Column Reference\n\n");

            // tracks.csv
            w.write("### tracks.csv\n\n");
            w.write("| Column | Type | Description | Example |\n");
            w.write("|--------|------|-------------|---------|\n");
            w.write("| id | INTEGER | Unique track ID (hash of file path for local, hash of tidal:id for TIDAL) | 12345678 |\n");
            w.write("| title | TEXT | Track title | Bohemian Rhapsody |\n");
            w.write("| artist | TEXT | Track artist | Queen |\n");
            w.write("| album | TEXT | Album name | A Night at the Opera |\n");
            w.write("| album_id | INTEGER | Album identifier | -987654 |\n");
            w.write("| duration_ms | INTEGER | Track duration in milliseconds | 354000 |\n");
            w.write("| track_number | INTEGER | Track number on disc | 11 |\n");
            w.write("| disc_number | INTEGER | Disc number | 1 |\n");
            w.write("| year | INTEGER | Release year (0 if unknown) | 1975 |\n");
            w.write("| folder_path | TEXT | Absolute path to containing folder | /storage/.../Music/Queen |\n");
            w.write("| folder_name | TEXT | Folder name | Queen |\n");
            w.write("| source | INTEGER | 0=LOCAL, 1=TIDAL | 0 |\n");
            w.write("| tidal_track_id | TEXT | TIDAL track ID (NULL for local) | 12345 |\n");
            w.write("| album_artist | TEXT | Album artist (NULL if same as artist) | Queen |\n");
            w.write("| genre | TEXT | Genre tag (NULL if absent) | Rock |\n");
            w.write("| composer | TEXT | Composer tag (NULL if absent) | Freddie Mercury |\n");
            w.write("| bitrate | INTEGER | Bitrate in kbps (NULL/0 if unknown) | 320 |\n");
            w.write("| sample_rate | INTEGER | Sample rate in Hz (NULL/0 if unknown) | 44100 |\n");
            w.write("| bit_depth | INTEGER | Bit depth (NULL/0 if unknown) | 16 |\n");
            w.write("| channels | INTEGER | Channel count (NULL/0 if unknown) | 2 |\n");
            w.write("| format | TEXT | Audio format (NULL if unknown) | FLAC |\n");
            w.write("| file_path | TEXT | Absolute file path (NULL for TIDAL) | /storage/.../track.flac |\n");
            w.write("| file_size | INTEGER | File size in bytes (0 for TIDAL) | 35000000 |\n");
            w.write("| release_type | INTEGER | 0=ALBUM, 1=EP, 2=SINGLE, 3=REMIX | 0 |\n");
            w.write("| is_remix | INTEGER | 1 if detected as remix | 0 |\n");
            w.write("| created_at | INTEGER | Epoch ms when track was first scanned | 1711234567000 |\n");
            w.write("| updated_at | INTEGER | Epoch ms when track was last updated | 1711234567000 |\n\n");

            // play_history.csv
            w.write("### play_history.csv\n\n");
            w.write("| Column | Type | Description | Example |\n");
            w.write("|--------|------|-------------|---------|\n");
            w.write("| id | INTEGER | Auto-increment row ID | 1 |\n");
            w.write("| track_id | INTEGER | Foreign key to tracks.id | 12345678 |\n");
            w.write("| source | INTEGER | 0=LOCAL, 1=TIDAL | 0 |\n");
            w.write("| started_at | INTEGER | Epoch ms when playback started | 1711234567000 |\n");
            w.write("| duration_played | INTEGER | Milliseconds of audio played | 210000 |\n");
            w.write("| completed | INTEGER | 1 if >= 80% of track played | 1 |\n");
            w.write("| sample_rate | INTEGER | Sample rate during playback (NULL if unknown) | 44100 |\n");
            w.write("| bit_depth | INTEGER | Bit depth during playback (NULL if unknown) | 16 |\n");
            w.write("| output_device | TEXT | Output device: USB DAC, Bluetooth [name], or Speaker | Bluetooth [WH-1000XM4] |\n");
            w.write("| tidal_quality | TEXT | TIDAL quality tier (NULL for local) | HI_RES_LOSSLESS |\n\n");

            // track_stats.csv
            w.write("### track_stats.csv\n\n");
            w.write("| Column | Type | Description | Example |\n");
            w.write("|--------|------|-------------|---------|\n");
            w.write("| track_id | INTEGER | Primary key, foreign key to tracks.id | 12345678 |\n");
            w.write("| play_count | INTEGER | Number of completed plays | 42 |\n");
            w.write("| skip_count | INTEGER | Number of skips (<10s plays) | 3 |\n");
            w.write("| total_listen_ms | INTEGER | Total listening time in ms | 14868000 |\n");
            w.write("| last_played_at | INTEGER | Epoch ms of last play (NULL if never) | 1711234567000 |\n");
            w.write("| first_played_at | INTEGER | Epoch ms of first play (NULL if never) | 1710000000000 |\n");
            w.write("| rating | INTEGER | 0=unrated, 1-5=star rating | 5 |\n\n");

            // albums.csv
            w.write("### albums.csv\n\n");
            w.write("| Column | Type | Description | Example |\n");
            w.write("|--------|------|-------------|---------|\n");
            w.write("| album_id | INTEGER | Primary key matching tracks.album_id | -987654 |\n");
            w.write("| name | TEXT | Album name | A Night at the Opera |\n");
            w.write("| artist | TEXT | Album artist | Queen |\n");
            w.write("| track_count | INTEGER | Number of tracks | 12 |\n");
            w.write("| total_duration | INTEGER | Total duration in ms | 2580000 |\n");
            w.write("| year | INTEGER | Release year (0 if unknown) | 1975 |\n");
            w.write("| release_type | INTEGER | 0=ALBUM, 1=EP, 2=SINGLE, 3=REMIX | 0 |\n");
            w.write("| genre | TEXT | Genre (NULL if absent) | Rock |\n");
            w.write("| source | INTEGER | 0=LOCAL, 1=TIDAL | 0 |\n");
            w.write("| tidal_album_id | INTEGER | TIDAL album ID (NULL for local) | 56789 |\n");
            w.write("| tidal_quality | TEXT | TIDAL quality (NULL for local) | LOSSLESS |\n\n");

            // artists.csv
            w.write("### artists.csv\n\n");
            w.write("| Column | Type | Description | Example |\n");
            w.write("|--------|------|-------------|---------|\n");
            w.write("| id | INTEGER | Auto-increment artist ID | 1 |\n");
            w.write("| name | TEXT | Artist name (unique, case-insensitive) | Queen |\n");
            w.write("| sort_name | TEXT | Sort name (NULL if same as name) | queen |\n");
            w.write("| created_at | INTEGER | Epoch ms when artist was created | 1711234567000 |\n\n");

            // track_artists.csv
            w.write("### track_artists.csv\n\n");
            w.write("| Column | Type | Description | Example |\n");
            w.write("|--------|------|-------------|---------|\n");
            w.write("| track_id | INTEGER | Foreign key to tracks.id | 12345678 |\n");
            w.write("| artist_id | INTEGER | Foreign key to artists.id | 1 |\n");
            w.write("| role | INTEGER | 0=primary, 1=featured, 2=remixer | 0 |\n\n");

            w.write("## NULL Handling\n\n");
            w.write("- Empty CSV fields represent NULL values\n");
            w.write("- tidal_track_id is NULL for local tracks, populated for TIDAL tracks\n");
            w.write("- file_path is NULL for TIDAL tracks, populated for local tracks\n");
            w.write("- album_artist, genre, composer can be NULL (not tagged)\n");
            w.write("- bitrate, sample_rate, bit_depth, channels: 0 or NULL means unknown\n");
            w.write("- first_played_at, last_played_at: NULL means never played\n");
            w.write("- tidal_quality in play_history: NULL for local playback\n");
        }
        return file;
    }

    // -- CSV helpers --

    private String csvRow(Cursor c, String... columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.length; i++) {
            if (i > 0) sb.append(',');
            int idx = c.getColumnIndex(columns[i]);
            if (idx < 0 || c.isNull(idx)) {
                // empty field for NULL
            } else {
                String val = c.getString(idx);
                if (val != null) {
                    sb.append(escapeCsv(val));
                }
            }
        }
        return sb.toString();
    }

    private String escapeCsv(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private void addToZip(ZipOutputStream zos, String name, File file) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        byte[] buf = new byte[8192];
        try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
            int len;
            while ((len = fis.read(buf)) > 0) {
                zos.write(buf, 0, len);
            }
        }
        zos.closeEntry();
    }
}
