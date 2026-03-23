package com.example.media_player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.example.media_player.tidal.TidalApi;
import com.example.media_player.tidal.TidalAuth;
import com.example.media_player.tidal.TidalModels;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class LyricsLoader {

    private static final String TAG = "LyricsLoader";

    public interface LyricsCallback {
        void onLyricsLoaded(LrcParser.LrcResult result);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Future<?> pendingTask;

    public void loadLyrics(Context context, Track track, LyricsCallback callback) {
        cancel();
        pendingTask = executor.submit(() -> {
            LrcParser.LrcResult result = resolve(context, track);
            mainHandler.post(() -> callback.onLyricsLoaded(result));
        });
    }

    public void cancel() {
        if (pendingTask != null) {
            pendingTask.cancel(true);
            pendingTask = null;
        }
    }

    public void shutdown() {
        cancel();
        executor.shutdownNow();
    }

    private LrcParser.LrcResult resolve(Context context, Track track) {
        // 1. Sidecar .lrc file
        LrcParser.LrcResult result = trySidecar(track);
        if (result != null && !result.lines.isEmpty()) {
            Log.d(TAG, "Found sidecar LRC for: " + track.title);
            return result;
        }

        // 2. Embedded metadata
        if (track.source == Track.Source.LOCAL && track.uri != null) {
            result = tryEmbedded(track);
            if (result != null && !result.lines.isEmpty()) {
                Log.d(TAG, "Found embedded lyrics for: " + track.title);
                return result;
            }
        }

        // 3. Tidal lyrics API
        if (track.source == Track.Source.TIDAL && track.tidalTrackId != null) {
            result = tryTidal(context, track);
            if (result != null && !result.lines.isEmpty()) {
                Log.d(TAG, "Found Tidal lyrics for: " + track.title);
                return result;
            }
        }

        return null;
    }

    // -- Sidecar .lrc --

    private LrcParser.LrcResult trySidecar(Track track) {
        if (track.uri == null) return null;
        String path = track.uri.getPath();
        if (path == null) return null;

        int dot = path.lastIndexOf('.');
        if (dot < 0) return null;
        String lrcPath = path.substring(0, dot) + ".lrc";

        File lrcFile = new File(lrcPath);
        if (!lrcFile.exists() || !lrcFile.canRead()) return null;

        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(lrcFile))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            return LrcParser.parse(sb.toString());
        } catch (IOException e) {
            Log.w(TAG, "Failed to read sidecar LRC: " + lrcPath, e);
            return null;
        }
    }

    // -- Embedded metadata --

    private LrcParser.LrcResult tryEmbedded(Track track) {
        String path = track.uri.getPath();
        if (path == null) return null;

        String lower = path.toLowerCase();
        if (lower.endsWith(".mp3")) {
            return tryId3v2Uslt(path);
        } else if (lower.endsWith(".flac")) {
            return tryFlacVorbisComment(path);
        } else if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
            return tryOggVorbisComment(path);
        }
        return null;
    }

    private LrcParser.LrcResult tryId3v2Uslt(String path) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            // Read ID3v2 header
            byte[] header = new byte[10];
            if (raf.read(header) != 10) return null;
            if (header[0] != 'I' || header[1] != 'D' || header[2] != '3') return null;

            int version = header[3] & 0xFF;
            int tagSize = ((header[6] & 0x7F) << 21) | ((header[7] & 0x7F) << 14)
                    | ((header[8] & 0x7F) << 7) | (header[9] & 0x7F);

            long tagEnd = 10 + tagSize;
            long pos = 10;
            int frameHeaderSize = (version >= 3) ? 10 : 6;

            while (pos + frameHeaderSize < tagEnd) {
                raf.seek(pos);
                byte[] frameHeader = new byte[frameHeaderSize];
                if (raf.read(frameHeader) != frameHeaderSize) break;

                String frameId;
                int frameSize;
                if (version >= 3) {
                    frameId = new String(frameHeader, 0, 4, StandardCharsets.US_ASCII);
                    if (version == 4) {
                        frameSize = ((frameHeader[4] & 0x7F) << 21) | ((frameHeader[5] & 0x7F) << 14)
                                | ((frameHeader[6] & 0x7F) << 7) | (frameHeader[7] & 0x7F);
                    } else {
                        frameSize = ((frameHeader[4] & 0xFF) << 24) | ((frameHeader[5] & 0xFF) << 16)
                                | ((frameHeader[6] & 0xFF) << 8) | (frameHeader[7] & 0xFF);
                    }
                } else {
                    frameId = new String(frameHeader, 0, 3, StandardCharsets.US_ASCII);
                    frameSize = ((frameHeader[3] & 0xFF) << 16) | ((frameHeader[4] & 0xFF) << 8)
                            | (frameHeader[5] & 0xFF);
                }

                if (frameSize <= 0 || frameId.charAt(0) == 0) break;

                if ("USLT".equals(frameId) || "ULT".equals(frameId)) {
                    byte[] frameData = new byte[Math.min(frameSize, 1024 * 1024)];
                    if (raf.read(frameData) != frameData.length) break;
                    String lyrics = parseUsltFrame(frameData);
                    if (lyrics != null && !lyrics.trim().isEmpty()) {
                        return LrcParser.parse(lyrics);
                    }
                }

                pos += frameHeaderSize + frameSize;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read ID3v2 USLT from: " + path, e);
        }
        return null;
    }

    private String parseUsltFrame(byte[] data) {
        if (data.length < 4) return null;
        int encoding = data[0] & 0xFF;
        // Skip language (3 bytes)
        int offset = 4;

        // Skip content descriptor (null-terminated)
        if (encoding == 0 || encoding == 3) {
            // ISO-8859-1 or UTF-8: single-byte null terminator
            while (offset < data.length && data[offset] != 0) offset++;
            offset++; // skip null
        } else if (encoding == 1 || encoding == 2) {
            // UTF-16: double-byte null terminator
            while (offset + 1 < data.length && !(data[offset] == 0 && data[offset + 1] == 0)) offset += 2;
            offset += 2; // skip null
        }

        if (offset >= data.length) return null;
        byte[] textBytes = new byte[data.length - offset];
        System.arraycopy(data, offset, textBytes, 0, textBytes.length);

        switch (encoding) {
            case 0: return new String(textBytes, StandardCharsets.ISO_8859_1);
            case 1: return decodeUtf16WithBom(textBytes);
            case 2: return new String(textBytes, StandardCharsets.UTF_16BE);
            case 3: return new String(textBytes, StandardCharsets.UTF_8);
            default: return new String(textBytes, StandardCharsets.UTF_8);
        }
    }

    private String decodeUtf16WithBom(byte[] data) {
        if (data.length >= 2) {
            if ((data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xFE) {
                return new String(data, 2, data.length - 2, StandardCharsets.UTF_16LE);
            } else if ((data[0] & 0xFF) == 0xFE && (data[1] & 0xFF) == 0xFF) {
                return new String(data, 2, data.length - 2, StandardCharsets.UTF_16BE);
            }
        }
        return new String(data, StandardCharsets.UTF_16);
    }

    private LrcParser.LrcResult tryFlacVorbisComment(String path) {
        try (RandomAccessFile raf = new RandomAccessFile(path, "r")) {
            // FLAC signature
            byte[] sig = new byte[4];
            if (raf.read(sig) != 4) return null;
            if (sig[0] != 'f' || sig[1] != 'L' || sig[2] != 'a' || sig[3] != 'C') return null;

            // Iterate metadata blocks
            while (true) {
                int blockHeader = raf.read();
                if (blockHeader < 0) return null;
                boolean isLast = (blockHeader & 0x80) != 0;
                int blockType = blockHeader & 0x7F;

                byte[] sizeBytes = new byte[3];
                if (raf.read(sizeBytes) != 3) return null;
                int blockSize = ((sizeBytes[0] & 0xFF) << 16)
                        | ((sizeBytes[1] & 0xFF) << 8)
                        | (sizeBytes[2] & 0xFF);

                if (blockType == 4) {
                    // Vorbis comment block
                    byte[] blockData = new byte[Math.min(blockSize, 4 * 1024 * 1024)];
                    if (raf.read(blockData) != blockData.length) return null;
                    String lyrics = extractVorbisCommentLyrics(blockData);
                    if (lyrics != null && !lyrics.trim().isEmpty()) {
                        return LrcParser.parse(lyrics);
                    }
                } else {
                    raf.skipBytes(blockSize);
                }

                if (isLast) break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read FLAC Vorbis comment from: " + path, e);
        }
        return null;
    }

    private LrcParser.LrcResult tryOggVorbisComment(String path) {
        try (FileInputStream fis = new FileInputStream(path)) {
            // Read Ogg pages looking for Vorbis comment header
            byte[] pageSig = new byte[4];
            byte[] buf = new byte[64 * 1024];
            int totalRead = 0;
            int maxScan = 512 * 1024; // scan first 512KB

            while (totalRead < maxScan) {
                int b = fis.read();
                if (b < 0) break;
                totalRead++;

                if (b != 'O') continue;
                pageSig[0] = (byte) b;
                if (fis.read(pageSig, 1, 3) != 3) break;
                totalRead += 3;
                if (pageSig[1] != 'g' || pageSig[2] != 'g' || pageSig[3] != 'S') continue;

                // Found OggS page header -- skip to segment table
                byte[] pageHeader = new byte[23]; // remaining header after "OggS"
                if (fis.read(pageHeader) != 23) break;
                totalRead += 23;

                int numSegments = pageHeader[22] & 0xFF;
                byte[] segmentTable = new byte[numSegments];
                if (fis.read(segmentTable) != numSegments) break;
                totalRead += numSegments;

                int pageDataSize = 0;
                for (byte seg : segmentTable) pageDataSize += (seg & 0xFF);
                if (pageDataSize > buf.length) {
                    buf = new byte[pageDataSize];
                }
                if (fis.read(buf, 0, pageDataSize) != pageDataSize) break;
                totalRead += pageDataSize;

                // Check for Vorbis comment header (3 = comment, preceded by vorbis string)
                if (pageDataSize > 7 && buf[0] == 3
                        && buf[1] == 'v' && buf[2] == 'o' && buf[3] == 'r'
                        && buf[4] == 'b' && buf[5] == 'i' && buf[6] == 's') {
                    byte[] blockData = new byte[pageDataSize - 7];
                    System.arraycopy(buf, 7, blockData, 0, blockData.length);
                    String lyrics = extractVorbisCommentLyrics(blockData);
                    if (lyrics != null && !lyrics.trim().isEmpty()) {
                        return LrcParser.parse(lyrics);
                    }
                }

                // Check for OpusTags
                if (pageDataSize > 8 && buf[0] == 'O' && buf[1] == 'p'
                        && buf[2] == 'u' && buf[3] == 's'
                        && buf[4] == 'T' && buf[5] == 'a'
                        && buf[6] == 'g' && buf[7] == 's') {
                    byte[] blockData = new byte[pageDataSize - 8];
                    System.arraycopy(buf, 8, blockData, 0, blockData.length);
                    String lyrics = extractVorbisCommentLyrics(blockData);
                    if (lyrics != null && !lyrics.trim().isEmpty()) {
                        return LrcParser.parse(lyrics);
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read Ogg Vorbis comment from: " + path, e);
        }
        return null;
    }

    private String extractVorbisCommentLyrics(byte[] data) {
        try {
            ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
            if (bb.remaining() < 4) return null;

            // Vendor string
            int vendorLen = bb.getInt();
            if (vendorLen < 0 || vendorLen > bb.remaining()) return null;
            bb.position(bb.position() + vendorLen);

            if (bb.remaining() < 4) return null;
            int commentCount = bb.getInt();

            for (int i = 0; i < commentCount; i++) {
                if (bb.remaining() < 4) return null;
                int commentLen = bb.getInt();
                if (commentLen < 0 || commentLen > bb.remaining()) return null;

                byte[] commentBytes = new byte[commentLen];
                bb.get(commentBytes);
                String comment = new String(commentBytes, StandardCharsets.UTF_8);

                String upper = comment.toUpperCase();
                if (upper.startsWith("LYRICS=")) {
                    return comment.substring(7);
                } else if (upper.startsWith("UNSYNCEDLYRICS=")) {
                    return comment.substring(15);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to parse Vorbis comment", e);
        }
        return null;
    }

    // -- Tidal lyrics --

    private LrcParser.LrcResult tryTidal(Context context, Track track) {
        try {
            TidalAuth auth = new TidalAuth(context);
            if (!auth.isLoggedIn()) return null;

            TidalApi api = new TidalApi(auth);
            long trackId = Long.parseLong(track.tidalTrackId);
            TidalModels.TidalLyrics lyrics = api.getLyrics(trackId);
            if (lyrics == null) return null;

            // Prefer subtitles (LRC synced) over plain lyrics
            if (lyrics.subtitles != null && !lyrics.subtitles.isEmpty()) {
                return LrcParser.parse(lyrics.subtitles);
            }
            if (lyrics.lyrics != null && !lyrics.lyrics.isEmpty()) {
                return LrcParser.parse(lyrics.lyrics);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to fetch Tidal lyrics for: " + track.title, e);
        }
        return null;
    }
}
