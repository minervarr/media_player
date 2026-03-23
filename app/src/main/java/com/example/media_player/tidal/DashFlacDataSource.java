package com.example.media_player.tidal;

import android.media.MediaDataSource;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Progressive fMP4-to-FLAC streaming data source for TIDAL HI_RES_LOSSLESS.
 *
 * Downloads DASH segments in a background thread, remuxes fMP4 data to standard
 * FLAC on-the-fly, and serves readAt() from the growing temp file.
 * MediaExtractor sees a normal FLAC stream and plays it immediately.
 */
public class DashFlacDataSource extends MediaDataSource {

    private static final String TAG = "DashFlacDataSource";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 30000;
    private static final int DOWNLOAD_BUF_SIZE = 64 * 1024;
    private static final int PREFETCH_AHEAD = 4;

    private final String[] segmentUrls;
    private final File tempFile;
    private final long estimatedSize;

    private volatile long writtenBytes;
    private volatile boolean downloadComplete;
    private volatile String downloadError;
    private volatile boolean closed;
    private final Object lock = new Object();
    private Thread downloadThread;
    private ExecutorService prefetchPool;
    private RandomAccessFile raf;

    public DashFlacDataSource(String[] segmentUrls, File tempFile, long estimatedSize) {
        this.segmentUrls = segmentUrls;
        this.tempFile = tempFile;
        this.estimatedSize = estimatedSize;
        startDownload();
    }

    private void startDownload() {
        downloadThread = new Thread(() -> {
            try (RandomAccessFile out = new RandomAccessFile(tempFile, "rw")) {
                out.setLength(0);

                // Step 1: Download and parse init segment
                byte[] initData = downloadSegmentBytes(segmentUrls[0]);
                if (closed) return;
                byte[] streamInfo = parseInitSegment(initData);
                Log.d(TAG, "Init segment parsed: STREAMINFO " + streamInfo.length + " bytes");

                // Step 2: Write FLAC file header (42 bytes)
                // "fLaC" magic + metadata block header (last-block, STREAMINFO, 34 bytes) + STREAMINFO
                byte[] flacHeader = new byte[42];
                flacHeader[0] = 'f';
                flacHeader[1] = 'L';
                flacHeader[2] = 'a';
                flacHeader[3] = 'C';
                // Metadata block header: 0x80 = last block flag | type 0 (STREAMINFO), length = 34
                flacHeader[4] = (byte) 0x80;
                flacHeader[5] = 0x00;
                flacHeader[6] = 0x00;
                flacHeader[7] = 0x22; // 34
                System.arraycopy(streamInfo, 0, flacHeader, 8, 34);
                out.write(flacHeader);
                updateWritten(out.getFilePointer());

                // Step 3: Process media segments with parallel prefetch
                prefetchPool = Executors.newFixedThreadPool(PREFETCH_AHEAD);
                @SuppressWarnings("unchecked")
                Future<byte[]>[] futures = new Future[segmentUrls.length];

                // Submit initial prefetch batch
                for (int p = 1; p < Math.min(1 + PREFETCH_AHEAD, segmentUrls.length); p++) {
                    final int idx = p;
                    futures[idx] = prefetchPool.submit(() -> downloadSegmentBytes(segmentUrls[idx]));
                }

                for (int i = 1; i < segmentUrls.length; i++) {
                    if (closed) break;

                    // Submit next prefetch if available
                    int ahead = i + PREFETCH_AHEAD;
                    if (ahead < segmentUrls.length) {
                        final int idx = ahead;
                        futures[idx] = prefetchPool.submit(() -> downloadSegmentBytes(segmentUrls[idx]));
                    }

                    // Get this segment (already downloading or done)
                    byte[] segData;
                    if (futures[i] != null) {
                        segData = futures[i].get();
                        futures[i] = null;
                    } else {
                        segData = downloadSegmentBytes(segmentUrls[i]);
                    }
                    if (closed) break;

                    byte[] flacFrames = parseMediaSegment(segData);
                    out.write(flacFrames);
                    updateWritten(out.getFilePointer());
                    if (i % 10 == 0 || i == segmentUrls.length - 1) {
                        Log.d(TAG, "Segment " + i + "/" + (segmentUrls.length - 1)
                                + " -> " + (writtenBytes / 1024) + " KB written");
                    }
                }

                prefetchPool.shutdownNow();
                prefetchPool = null;

                synchronized (lock) {
                    downloadComplete = true;
                    lock.notifyAll();
                }
                Log.d(TAG, "Download complete: " + (writtenBytes / 1024) + " KB FLAC from "
                        + segmentUrls.length + " segments");

            } catch (Exception e) {
                if (!closed) {
                    Log.e(TAG, "Download/remux failed at " + (writtenBytes / 1024) + " KB", e);
                    synchronized (lock) {
                        downloadError = e.getMessage();
                        lock.notifyAll();
                    }
                }
            }
        }, "DashFlacDownload");
        downloadThread.setDaemon(true);
        downloadThread.start();
    }

    private void updateWritten(long pos) {
        synchronized (lock) {
            writtenBytes = pos;
            lock.notifyAll();
        }
    }

    private byte[] downloadSegmentBytes(String segUrl) throws IOException {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(segUrl).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT);
            conn.setReadTimeout(READ_TIMEOUT);
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " downloading segment");
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            InputStream is = conn.getInputStream();
            byte[] buf = new byte[DOWNLOAD_BUF_SIZE];
            int read;
            while ((read = is.read(buf)) > 0) {
                if (closed) return new byte[0];
                bos.write(buf, 0, read);
            }
            is.close();
            return bos.toByteArray();
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    // --- MP4 box parsing ---

    private static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static String readType(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.US_ASCII);
    }

    /**
     * Find a child box of the given type within [start, end).
     * Returns the offset of the box start, or -1 if not found.
     */
    private static int findBox(byte[] data, int start, int end, String type) {
        int pos = start;
        while (pos + 8 <= end) {
            int boxSize = readInt(data, pos);
            if (boxSize < 8) break;
            String boxType = readType(data, pos + 4);
            if (boxType.equals(type)) return pos;
            pos += boxSize;
        }
        return -1;
    }

    /**
     * Parse init segment to extract 34-byte STREAMINFO.
     * Path: moov > trak > mdia > minf > stbl > stsd > (fLaC sample entry) > dfLa > STREAMINFO
     */
    private byte[] parseInitSegment(byte[] data) throws IOException {
        int end = data.length;

        // Find moov
        int moov = findBox(data, 0, end, "moov");
        if (moov < 0) throw new IOException("No moov box in init segment");
        int moovSize = readInt(data, moov);
        int moovEnd = moov + moovSize;

        // moov > trak
        int trak = findBox(data, moov + 8, moovEnd, "trak");
        if (trak < 0) throw new IOException("No trak box");
        int trakEnd = trak + readInt(data, trak);

        // trak > mdia
        int mdia = findBox(data, trak + 8, trakEnd, "mdia");
        if (mdia < 0) throw new IOException("No mdia box");
        int mdiaEnd = mdia + readInt(data, mdia);

        // mdia > minf
        int minf = findBox(data, mdia + 8, mdiaEnd, "minf");
        if (minf < 0) throw new IOException("No minf box");
        int minfEnd = minf + readInt(data, minf);

        // minf > stbl
        int stbl = findBox(data, minf + 8, minfEnd, "stbl");
        if (stbl < 0) throw new IOException("No stbl box");
        int stblEnd = stbl + readInt(data, stbl);

        // stbl > stsd
        int stsd = findBox(data, stbl + 8, stblEnd, "stsd");
        if (stsd < 0) throw new IOException("No stsd box");
        int stsdSize = readInt(data, stsd);

        // stsd payload starts after 8 (box header) + 4 (version/flags) + 4 (entry count) = +16
        int entryStart = stsd + 16;

        // The entry is a fLaC sample entry.
        // Audio sample entry layout: 8 (box header) + 6 (reserved) + 2 (data_ref_idx)
        //   + 8 (reserved) + 2 (channelcount) + 2 (samplesize) + 4 (reserved) + 4 (samplerate)
        //   = 36 bytes of standard audio fields total (8 header + 28 body)
        // Then child boxes follow (dfLa).
        int entrySize = readInt(data, entryStart);
        String entryType = readType(data, entryStart + 4);
        Log.d(TAG, "Sample entry type: " + entryType + " size=" + entrySize);

        // Skip to child boxes: entry header (8) + reserved (6) + data_ref_idx (2) +
        // reserved (8) + channelcount (2) + samplesize (2) + reserved (4) + samplerate (4) = 36
        int childStart = entryStart + 36;
        int entryEnd = entryStart + entrySize;

        // Find dfLa box
        int dfla = findBox(data, childStart, entryEnd, "dfLa");
        if (dfla < 0) throw new IOException("No dfLa box in sample entry");
        int dflaSize = readInt(data, dfla);

        // dfLa payload: 8 (box header) + 4 (version/flags) + 4 (metadata block header) + 34 (STREAMINFO)
        // We want the 34-byte STREAMINFO which starts after the metadata block header
        int streamInfoOffset = dfla + 8 + 4 + 4; // skip header + version + block header
        if (streamInfoOffset + 34 > dfla + dflaSize) {
            throw new IOException("dfLa box too small for STREAMINFO");
        }

        byte[] streamInfo = new byte[34];
        System.arraycopy(data, streamInfoOffset, streamInfo, 0, 34);
        return streamInfo;
    }

    /**
     * Parse a media segment to extract raw FLAC frame data.
     * Structure: moof + mdat. Frames are in mdat, sizes come from trun (or tfhd default).
     */
    private byte[] parseMediaSegment(byte[] data) throws IOException {
        int end = data.length;

        // Find moof
        int moof = findBox(data, 0, end, "moof");
        if (moof < 0) throw new IOException("No moof box in media segment");
        int moofSize = readInt(data, moof);
        int moofEnd = moof + moofSize;

        // Find traf in moof
        int traf = findBox(data, moof + 8, moofEnd, "traf");
        if (traf < 0) throw new IOException("No traf box");
        int trafEnd = traf + readInt(data, traf);

        // Parse tfhd for default_sample_size
        int defaultSampleSize = 0;
        int tfhd = findBox(data, traf + 8, trafEnd, "tfhd");
        if (tfhd >= 0) {
            // tfhd: 8 (header) + 1 (version) + 3 (flags)
            int tfhdFlags = ((data[tfhd + 9] & 0xFF) << 16)
                    | ((data[tfhd + 10] & 0xFF) << 8)
                    | (data[tfhd + 11] & 0xFF);
            int tfhdPos = tfhd + 12; // after header + version/flags
            tfhdPos += 4; // skip track_ID
            if ((tfhdFlags & 0x01) != 0) tfhdPos += 8; // base_data_offset
            if ((tfhdFlags & 0x02) != 0) tfhdPos += 4; // sample_description_index
            if ((tfhdFlags & 0x08) != 0) tfhdPos += 4; // default_sample_duration
            if ((tfhdFlags & 0x10) != 0) {
                defaultSampleSize = readInt(data, tfhdPos);
            }
        }

        // Parse trun for sample count and per-sample sizes
        int trun = findBox(data, traf + 8, trafEnd, "trun");
        if (trun < 0) throw new IOException("No trun box");

        int trunFlags = ((data[trun + 9] & 0xFF) << 16)
                | ((data[trun + 10] & 0xFF) << 8)
                | (data[trun + 11] & 0xFF);
        int sampleCount = readInt(data, trun + 12);
        int trunPos = trun + 16; // after header + version/flags + sample_count

        // Optional fields before per-sample data
        if ((trunFlags & 0x01) != 0) trunPos += 4; // data_offset
        if ((trunFlags & 0x04) != 0) trunPos += 4; // first_sample_flags

        boolean hasDuration = (trunFlags & 0x100) != 0;
        boolean hasSize = (trunFlags & 0x200) != 0;
        boolean hasFlags = (trunFlags & 0x400) != 0;
        boolean hasCTO = (trunFlags & 0x800) != 0;

        int[] sampleSizes = new int[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            if (hasDuration) trunPos += 4;
            if (hasSize) {
                sampleSizes[i] = readInt(data, trunPos);
                trunPos += 4;
            } else {
                sampleSizes[i] = defaultSampleSize;
            }
            if (hasFlags) trunPos += 4;
            if (hasCTO) trunPos += 4;
        }

        // Find mdat
        int mdat = findBox(data, 0, end, "mdat");
        if (mdat < 0) throw new IOException("No mdat box in media segment");
        int mdatSize = readInt(data, mdat);
        int mdatPayload = mdat + 8; // skip box header

        // Calculate total frame data size
        int totalFrameBytes = 0;
        for (int s : sampleSizes) totalFrameBytes += s;

        // Sanity check: frame data should fit in mdat payload
        int mdatPayloadSize = mdatSize - 8;
        if (totalFrameBytes > mdatPayloadSize) {
            Log.w(TAG, "Frame data (" + totalFrameBytes + ") > mdat payload ("
                    + mdatPayloadSize + "), clamping");
            totalFrameBytes = mdatPayloadSize;
        }

        // Copy FLAC frames from mdat
        byte[] frames = new byte[totalFrameBytes];
        System.arraycopy(data, mdatPayload, frames, 0, totalFrameBytes);
        return frames;
    }

    // --- MediaDataSource interface ---

    @Override
    public int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
        if (closed) return -1;

        // Wait for data if needed
        synchronized (lock) {
            while (position >= writtenBytes && !downloadComplete && downloadError == null && !closed) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    return -1;
                }
            }
        }

        if (downloadError != null) throw new IOException("Download failed: " + downloadError);
        if (closed) return -1;
        if (position >= writtenBytes) return -1; // EOF

        long available = writtenBytes - position;
        int toRead = (int) Math.min(size, available);

        synchronized (this) {
            if (raf == null) {
                raf = new RandomAccessFile(tempFile, "r");
            }
            raf.seek(position);
            return raf.read(buffer, offset, toRead);
        }
    }

    @Override
    public long getSize() {
        if (downloadComplete) return writtenBytes;
        return estimatedSize > 0 ? estimatedSize : -1;
    }

    @Override
    public void close() throws IOException {
        closed = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (downloadThread != null) {
            downloadThread.interrupt();
        }
        if (prefetchPool != null) {
            prefetchPool.shutdownNow();
        }
        synchronized (this) {
            if (raf != null) {
                try { raf.close(); } catch (IOException ignored) {}
                raf = null;
            }
        }
        if (tempFile.exists()) {
            tempFile.delete();
        }
    }
}
