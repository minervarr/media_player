package com.example.media_player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LrcParser {

    private static final Pattern TIMESTAMP_PATTERN =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{2,3}))?\\]");
    private static final Pattern OFFSET_PATTERN =
            Pattern.compile("\\[offset:([+-]?\\d+)\\]", Pattern.CASE_INSENSITIVE);
    private static final Pattern META_TAG_PATTERN =
            Pattern.compile("^\\[(ti|ar|al|by|re|ve|length):.*\\]$", Pattern.CASE_INSENSITIVE);

    public static class LrcLine {
        public final long timestampMs;
        public final String text;

        public LrcLine(long timestampMs, String text) {
            this.timestampMs = timestampMs;
            this.text = text;
        }
    }

    public static class LrcResult {
        public final List<LrcLine> lines;
        public final boolean isSynced;

        public LrcResult(List<LrcLine> lines, boolean isSynced) {
            this.lines = lines;
            this.isSynced = isSynced;
        }
    }

    public static LrcResult parse(String lrcText) {
        if (lrcText == null || lrcText.trim().isEmpty()) {
            return new LrcResult(Collections.emptyList(), false);
        }

        long offsetMs = 0;
        Matcher offsetMatcher = OFFSET_PATTERN.matcher(lrcText);
        if (offsetMatcher.find()) {
            try {
                offsetMs = Long.parseLong(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }

        String[] rawLines = lrcText.split("\\r?\\n");
        List<LrcLine> result = new ArrayList<>();
        boolean hasTimestamps = false;

        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty()) continue;

            // Skip metadata tags
            if (META_TAG_PATTERN.matcher(trimmed).matches()) continue;
            // Skip offset tag (already parsed)
            if (OFFSET_PATTERN.matcher(trimmed).matches()) continue;

            // Find all timestamps on this line
            Matcher matcher = TIMESTAMP_PATTERN.matcher(trimmed);
            List<Long> timestamps = new ArrayList<>();
            int lastMatchEnd = 0;

            while (matcher.find()) {
                hasTimestamps = true;
                int minutes = Integer.parseInt(matcher.group(1));
                int seconds = Integer.parseInt(matcher.group(2));
                String msGroup = matcher.group(3);
                long ms = 0;
                if (msGroup != null) {
                    if (msGroup.length() == 2) {
                        ms = Long.parseLong(msGroup) * 10;
                    } else {
                        ms = Long.parseLong(msGroup);
                    }
                }
                long timestamp = (minutes * 60L + seconds) * 1000L + ms + offsetMs;
                timestamps.add(timestamp);
                lastMatchEnd = matcher.end();
            }

            if (!timestamps.isEmpty()) {
                String text = trimmed.substring(lastMatchEnd).trim();
                for (long ts : timestamps) {
                    result.add(new LrcLine(ts, text));
                }
            }
        }

        if (!hasTimestamps) {
            // Treat as unsynced plain text
            List<LrcLine> unsyncedLines = new ArrayList<>();
            for (String rawLine : rawLines) {
                String trimmed = rawLine.trim();
                if (trimmed.isEmpty()) continue;
                if (META_TAG_PATTERN.matcher(trimmed).matches()) continue;
                if (OFFSET_PATTERN.matcher(trimmed).matches()) continue;
                unsyncedLines.add(new LrcLine(-1, trimmed));
            }
            return new LrcResult(unsyncedLines, false);
        }

        // Sort by timestamp
        Collections.sort(result, (a, b) -> Long.compare(a.timestampMs, b.timestampMs));
        return new LrcResult(result, true);
    }
}
