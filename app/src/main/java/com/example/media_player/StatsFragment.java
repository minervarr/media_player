package com.example.media_player;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StatsFragment extends Fragment
        implements PlaybackObserver, StatsAdapter.OnStatsInteractionListener {

    private TrackDataProvider dataProvider;
    private StatsDao statsDao;
    private MatrixPlayerDatabase dbHelper;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private RecyclerView recyclerStats;
    private final List<Object> statsItems = new ArrayList<>();
    private StatsAdapter statsAdapter;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        dataProvider = (TrackDataProvider) context;
        dbHelper = MatrixPlayerDatabase.getInstance(context);
        statsDao = new StatsDao(dbHelper);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        recyclerStats = view.findViewById(R.id.recycler_stats);
        statsAdapter = new StatsAdapter(statsItems, this);
        recyclerStats.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerStats.setAdapter(statsAdapter);

        DefaultItemAnimator animator = new DefaultItemAnimator();
        animator.setAddDuration(150);
        animator.setRemoveDuration(150);
        animator.setMoveDuration(150);
        animator.setChangeDuration(150);
        recyclerStats.setItemAnimator(animator);

        loadStats();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) loadStats();
    }

    private void loadStats() {
        executor.execute(() -> {
            // Summary
            long totalListenMs = statsDao.getTotalListenTime();
            int totalPlayed = getTotalPlaysCount();
            int uniqueTracks = getUniqueTracksCount();

            // Most played tracks (top 20)
            List<Track> mostPlayed = statsDao.getMostPlayed(20);
            List<int[]> mostPlayedCounts = getPlayCounts(mostPlayed);

            // Most skipped (top 10)
            List<Object[]> mostSkipped = getMostSkipped(10);

            // Top artists (top 15)
            List<StatsDao.ArtistPlayCount> topArtists = statsDao.getMostPlayedArtists(15);

            // Source breakdown
            long[] sourceBreakdown = getSourceBreakdown();

            // Rated tracks (4-5 stars)
            List<Track> rated = statsDao.getTracksByRating(4);
            List<int[]> ratedCounts = getRatings(rated);

            mainHandler.post(() -> {
                statsItems.clear();

                // Summary
                String timeStr = formatDuration(totalListenMs);
                String summaryText = timeStr + " -- "
                        + totalPlayed + " plays -- "
                        + uniqueTracks + " unique tracks";
                statsItems.add(new StatsAdapter.StatHeader(
                        getString(R.string.stats_summary), summaryText));

                // Most Played
                if (!mostPlayed.isEmpty()) {
                    statsItems.add(new StatsAdapter.StatHeader(
                            getString(R.string.stats_most_played), null));
                    for (int i = 0; i < mostPlayed.size(); i++) {
                        int count = i < mostPlayedCounts.size() ? mostPlayedCounts.get(i)[0] : 0;
                        statsItems.add(new StatsAdapter.StatTrack(
                                mostPlayed.get(i),
                                getString(R.string.stats_plays, count)));
                    }
                }

                // Most Skipped
                if (!mostSkipped.isEmpty()) {
                    statsItems.add(new StatsAdapter.StatHeader(
                            getString(R.string.stats_most_skipped), null));
                    for (Object[] row : mostSkipped) {
                        Track t = (Track) row[0];
                        int skipCount = (int) row[1];
                        statsItems.add(new StatsAdapter.StatTrack(t,
                                getString(R.string.stats_skips, skipCount)));
                    }
                }

                // Top Artists
                if (!topArtists.isEmpty()) {
                    statsItems.add(new StatsAdapter.StatHeader(
                            getString(R.string.stats_top_artists), null));
                    statsItems.addAll(topArtists);
                }

                // Source breakdown
                if (sourceBreakdown[0] > 0 || sourceBreakdown[2] > 0) {
                    String sourceSummary = "Local: " + sourceBreakdown[0] + " tracks ("
                            + formatDuration(sourceBreakdown[1]) + ")"
                            + "  |  TIDAL: " + sourceBreakdown[2] + " tracks ("
                            + formatDuration(sourceBreakdown[3]) + ")";
                    statsItems.add(new StatsAdapter.StatHeader(
                            getString(R.string.stats_by_source), sourceSummary));
                }

                // Rated tracks
                if (!rated.isEmpty()) {
                    statsItems.add(new StatsAdapter.StatHeader(
                            getString(R.string.stats_rated_tracks), null));
                    for (int i = 0; i < rated.size(); i++) {
                        int rating = i < ratedCounts.size() ? ratedCounts.get(i)[0] : 0;
                        String stars = repeatChar(rating);
                        statsItems.add(new StatsAdapter.StatTrack(rated.get(i), stars));
                    }
                }

                // Export action
                statsItems.add(new StatsAdapter.StatAction(
                        getString(R.string.stats_export), "export"));

                if (statsItems.size() == 2) {
                    // Only summary + export, no real data
                    statsItems.add(1, new StatsAdapter.StatHeader(
                            getString(R.string.stats_no_data), null));
                }

                statsAdapter.notifyDataSetChanged();
                statsAdapter.setPlayingTrackId(dataProvider.getPlayingTrackId());
            });
        });
    }

    // -- DB queries not in StatsDao --

    private int getTotalPlaysCount() {
        android.database.Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM play_history WHERE completed = 1", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    private int getUniqueTracksCount() {
        android.database.Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT COUNT(DISTINCT track_id) FROM play_history", null);
        try {
            if (c.moveToFirst()) return c.getInt(0);
        } finally {
            c.close();
        }
        return 0;
    }

    private List<int[]> getPlayCounts(List<Track> tracks) {
        List<int[]> counts = new ArrayList<>();
        for (Track t : tracks) {
            counts.add(new int[]{statsDao.getPlayCount(t.id)});
        }
        return counts;
    }

    private List<Object[]> getMostSkipped(int limit) {
        List<Object[]> results = new ArrayList<>();
        android.database.Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT t.*, ts.skip_count FROM tracks t"
                + " JOIN track_stats ts ON ts.track_id = t.id"
                + " WHERE ts.skip_count > 0"
                + " ORDER BY ts.skip_count DESC"
                + " LIMIT ?",
                new String[]{String.valueOf(limit)});
        try {
            while (c.moveToNext()) {
                Track t = TrackDao.cursorToTrack(c);
                int skipCount = c.getInt(c.getColumnIndexOrThrow("skip_count"));
                results.add(new Object[]{t, skipCount});
            }
        } finally {
            c.close();
        }
        return results;
    }

    private long[] getSourceBreakdown() {
        // [localTracks, localTimeMs, tidalTracks, tidalTimeMs]
        long[] result = new long[4];
        android.database.Cursor c = dbHelper.getReadableDatabase().rawQuery(
                "SELECT source, COUNT(*), COALESCE(SUM(duration_played), 0)"
                + " FROM play_history GROUP BY source", null);
        try {
            while (c.moveToNext()) {
                int source = c.getInt(0);
                long count = c.getLong(1);
                long time = c.getLong(2);
                if (source == 0) { result[0] = count; result[1] = time; }
                else { result[2] = count; result[3] = time; }
            }
        } finally {
            c.close();
        }
        return result;
    }

    private List<int[]> getRatings(List<Track> tracks) {
        List<int[]> ratings = new ArrayList<>();
        for (Track t : tracks) {
            ratings.add(new int[]{statsDao.getRating(t.id)});
        }
        return ratings;
    }

    // -- Formatting --

    private String formatDuration(long ms) {
        long totalMinutes = ms / 60000;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes + "m";
    }

    private String repeatChar(int count) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < count; i++) sb.append("*");
        return sb.toString();
    }

    // -- StatsAdapter.OnStatsInteractionListener --

    @Override
    public void onTrackClick(Track track, List<Track> sectionTracks) {
        dataProvider.playTrack(track, sectionTracks);
    }

    @Override
    public void onActionClick(StatsAdapter.StatAction action) {
        if ("export".equals(action.key)) {
            Toast.makeText(requireContext(), R.string.stats_export_progress, Toast.LENGTH_SHORT).show();
            executor.execute(() -> {
                try {
                    java.io.File zipFile = new StatsExporter(requireContext(), dbHelper).export();
                    mainHandler.post(() -> {
                        Toast.makeText(requireContext(), R.string.stats_export_done, Toast.LENGTH_SHORT).show();
                        shareFile(zipFile);
                    });
                } catch (Exception e) {
                    mainHandler.post(() -> Toast.makeText(requireContext(),
                            R.string.stats_export_failed, Toast.LENGTH_SHORT).show());
                }
            });
        }
    }

    private void shareFile(java.io.File file) {
        android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                requireContext(),
                requireContext().getPackageName() + ".fileprovider",
                file);
        android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_SEND);
        intent.setType("application/zip");
        intent.putExtra(android.content.Intent.EXTRA_STREAM, uri);
        intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(android.content.Intent.createChooser(intent, getString(R.string.stats_export)));
    }

    // -- PlaybackObserver --

    @Override
    public void onPlayingTrackChanged(long trackId) {
        if (statsAdapter != null) {
            statsAdapter.setPlayingTrackId(trackId);
        }
    }
}
