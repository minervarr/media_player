package com.example.media_player;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;

import android.os.Process;

import java.nio.ByteBuffer;

public class AudioEngine {

    private static final String TAG = "AudioEngine";
    private static final int PCM_BUFFER_SIZE = 1024 * 1024; // 1MB ring buffer

    public interface OnPreparedListener {
        void onPrepared(AudioEngine engine);
    }

    public interface OnCompletionListener {
        void onCompletion(AudioEngine engine);
    }

    public interface OnErrorListener {
        void onError(AudioEngine engine, String message);
    }

    private OnPreparedListener onPreparedListener;
    private OnCompletionListener onCompletionListener;
    private OnErrorListener onErrorListener;

    private MediaExtractor extractor;
    private MediaCodec codec;
    private AudioOutput output;
    private PcmBuffer pcmBuffer;
    private final Object outputLock = new Object();
    private final Object pauseLock = new Object();

    private Thread decodeThread;
    private Thread outputThread;

    private volatile boolean playing;
    private volatile boolean stopped;
    private volatile boolean released;
    private volatile boolean seeking;
    private volatile boolean prepared;
    private volatile boolean inputDone;
    private volatile boolean outputFailed;

    private int sampleRate;
    private int channelCount;
    private int encoding;
    private long durationUs;

    private volatile long currentPositionUs;
    private volatile long seekTargetUs = -1;
    private volatile long playbackBaseUs;
    private volatile long bytesWritten;

    public void setOnPreparedListener(OnPreparedListener listener) {
        this.onPreparedListener = listener;
    }

    public void setOnCompletionListener(OnCompletionListener listener) {
        this.onCompletionListener = listener;
    }

    public void setOnErrorListener(OnErrorListener listener) {
        this.onErrorListener = listener;
    }

    public int getSampleRate() {
        return sampleRate;
    }

    public int getChannelCount() {
        return channelCount;
    }

    public int getEncoding() {
        return encoding;
    }

    public AudioOutput getOutput() {
        synchronized (outputLock) {
            return output;
        }
    }

    public void play(Context context, Uri uri) {
        Log.d(TAG, "play: " + uri);
        stop();

        pcmBuffer = new PcmBuffer(PCM_BUFFER_SIZE);
        stopped = false;
        seeking = false;
        prepared = false;
        outputFailed = false;
        currentPositionUs = 0;
        seekTargetUs = -1;
        playbackBaseUs = 0;
        bytesWritten = 0;

        try {
            extractor = new MediaExtractor();
            extractor.setDataSource(context, uri, null);

            int audioTrackIndex = -1;
            String mime = null;
            MediaFormat format = null;

            for (int i = 0; i < extractor.getTrackCount(); i++) {
                format = extractor.getTrackFormat(i);
                mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrackIndex = i;
                    break;
                }
            }

            if (audioTrackIndex < 0) {
                notifyError("No audio track found");
                return;
            }

            extractor.selectTrack(audioTrackIndex);

            sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
            channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            durationUs = format.containsKey(MediaFormat.KEY_DURATION)
                    ? format.getLong(MediaFormat.KEY_DURATION) : 0;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            // Detect actual output encoding from codec
            MediaFormat outputFormat = codec.getOutputFormat();
            if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                encoding = outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
            } else {
                encoding = AudioFormat.ENCODING_PCM_16BIT;
            }

            String encName;
            switch (encoding) {
                case AudioFormat.ENCODING_PCM_FLOAT: encName = "PCM_FLOAT"; break;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED: encName = "PCM_24BIT"; break;
                case AudioFormat.ENCODING_PCM_32BIT: encName = "PCM_32BIT"; break;
                default: encName = "PCM_16BIT"; break;
            }
            Log.d(TAG, "play: configured " + sampleRate + "Hz/" + channelCount + "ch/" + encName + " duration=" + (durationUs / 1000) + "ms");

            synchronized (outputLock) {
                if (output == null) {
                    output = new AudioTrackOutput();
                }
                output.configure(sampleRate, channelCount, encoding);
                output.start();
            }

            playing = true;
            prepared = true;

            startDecodeThread();
            startOutputThread();

            if (onPreparedListener != null) {
                onPreparedListener.onPrepared(this);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error setting up playback", e);
            notifyError("Could not play track");
        }
    }

    /**
     * Switch to a new audio output. Returns true on success.
     * On failure, automatically falls back to AudioTrackOutput.
     */
    public boolean switchOutput(AudioOutput newOutput) {
        Log.d(TAG, "switchOutput: " + newOutput.getClass().getSimpleName());
        synchronized (outputLock) {
            outputFailed = false;
            boolean wasPlaying = playing;
            if (wasPlaying && output != null) {
                output.pause();
            }
            if (output != null) {
                output.flush();
                output.release();
            }
            output = newOutput;
            if (prepared) {
                if (!output.configure(sampleRate, channelCount, encoding)) {
                    Log.e(TAG, "New output configure failed, falling back to speaker");
                    output.release();
                    output = new AudioTrackOutput();
                    output.configure(sampleRate, channelCount, encoding);
                    output.start();
                    if (!wasPlaying) output.pause();
                    return false;
                }
                if (!output.start()) {
                    Log.e(TAG, "New output start failed, falling back to speaker");
                    output.release();
                    output = new AudioTrackOutput();
                    output.configure(sampleRate, channelCount, encoding);
                    output.start();
                    if (!wasPlaying) output.pause();
                    return false;
                }
                if (!wasPlaying) {
                    output.pause();
                }
            }
            Log.d(TAG, "switchOutput: success");
            return true;
        }
    }

    private int bytesPerSample() {
        switch (encoding) {
            case AudioFormat.ENCODING_PCM_FLOAT: return 4;
            case AudioFormat.ENCODING_PCM_32BIT: return 4;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED: return 3;
            default: return 2;
        }
    }

    private void startDecodeThread() {
        Log.d(TAG, "decode thread starting");
        decodeThread = new Thread(() -> {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            inputDone = false;
            boolean outputDone = false;

            while (!stopped && !outputDone) {
                if (seeking) {
                    try { Thread.sleep(5); } catch (InterruptedException e) { break; }
                    continue;
                }

                synchronized (pauseLock) {
                    while (!playing && !stopped) {
                        try { pauseLock.wait(); } catch (InterruptedException e) { break; }
                    }
                }
                if (stopped) break;

                if (!inputDone) {
                    int inputIndex = codec.dequeueInputBuffer(10000);
                    if (inputIndex >= 0) {
                        ByteBuffer inputBuf = codec.getInputBuffer(inputIndex);
                        int sampleSize = extractor.readSampleData(inputBuf, 0);
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outputIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outputIndex >= 0) {
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                        codec.releaseOutputBuffer(outputIndex, false);
                        pcmBuffer.signalEnd();
                    } else if (info.size > 0) {
                        ByteBuffer outputBuf = codec.getOutputBuffer(outputIndex);
                        byte[] pcm = new byte[info.size];
                        outputBuf.get(pcm);
                        codec.releaseOutputBuffer(outputIndex, false);

                        try {
                            pcmBuffer.write(pcm, 0, pcm.length);
                        } catch (InterruptedException e) {
                            break;
                        }
                    } else {
                        codec.releaseOutputBuffer(outputIndex, false);
                    }
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat newFormat = codec.getOutputFormat();
                    int newRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    int newEncoding = encoding;
                    if (newFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        newEncoding = newFormat.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    if (newRate != sampleRate || newChannels != channelCount || newEncoding != encoding) {
                        sampleRate = newRate;
                        channelCount = newChannels;
                        encoding = newEncoding;
                    }
                }
            }
        }, "AudioEngine-Decode");
        decodeThread.start();
    }

    private void startOutputThread() {
        Log.d(TAG, "output thread starting");
        outputThread = new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);
            byte[] readBuf = new byte[8192];

            while (!stopped) {
                synchronized (pauseLock) {
                    while (!playing && !stopped) {
                        try { pauseLock.wait(); } catch (InterruptedException e) { break; }
                    }
                }
                if (stopped) break;

                try {
                    int read = pcmBuffer.read(readBuf, 0, readBuf.length);
                    if (read == -1) {
                        if (!stopped && onCompletionListener != null) {
                            onCompletionListener.onCompletion(this);
                        }
                        break;
                    } else if (read == -2) {
                        continue;
                    } else if (read > 0) {
                        int pos = 0;
                        while (pos < read && !stopped && !seeking) {
                            int w;
                            synchronized (outputLock) {
                                if (output != null) {
                                    w = output.write(readBuf, pos, read - pos);
                                } else {
                                    break;
                                }
                            }
                            if (w > 0) {
                                pos += w;
                                outputFailed = false;
                            } else if (w == 0) {
                                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
                            } else {
                                // w < 0: output died (e.g. USB transfers failed)
                                if (!outputFailed) {
                                    outputFailed = true;
                                    Log.e(TAG, "Output write failed (returned " + w + ")");
                                    if (onErrorListener != null) {
                                        onErrorListener.onError(this, "OUTPUT_FAILED");
                                    }
                                }
                                // Sleep and retry -- the main thread may switch outputs
                                try { Thread.sleep(10); } catch (InterruptedException e) { break; }
                            }
                        }
                        if (pos > 0) {
                            bytesWritten += pos;
                            int frameSize = channelCount * bytesPerSample();
                            if (frameSize > 0) {
                                currentPositionUs = playbackBaseUs + (bytesWritten / frameSize) * 1_000_000L / sampleRate;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "AudioEngine-Output");
        outputThread.start();
    }

    public void pause() {
        synchronized (outputLock) {
            if (output != null && playing) {
                output.pause();
                playing = false;
            }
        }
    }

    public void resume() {
        synchronized (outputLock) {
            if (output != null && !playing && prepared) {
                output.resume();
                playing = true;
            }
        }
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public void seekTo(int positionMs) {
        if (extractor == null || codec == null || pcmBuffer == null) return;

        seeking = true;
        long targetUs = (long) positionMs * 1000;
        seekTargetUs = targetUs;

        pcmBuffer.flush();
        synchronized (outputLock) {
            if (output != null) {
                output.pause();
                output.flush();
                output.resume();
            }
        }
        codec.flush();
        extractor.seekTo(targetUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
        inputDone = false;
        playbackBaseUs = targetUs;
        bytesWritten = 0;
        currentPositionUs = targetUs;

        seeking = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
    }

    public void stop() {
        Log.d(TAG, "stop");
        stopped = true;
        playing = false;
        prepared = false;
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }

        if (pcmBuffer != null) {
            pcmBuffer.flush();
        }

        if (decodeThread != null) {
            decodeThread.interrupt();
            try { decodeThread.join(500); } catch (InterruptedException ignored) {}
            decodeThread = null;
        }

        if (outputThread != null) {
            outputThread.interrupt();
            try { outputThread.join(500); } catch (InterruptedException ignored) {}
            outputThread = null;
        }

        synchronized (outputLock) {
            if (output != null) {
                output.stop();
                output.release();
                output = null;
            }
        }

        if (codec != null) {
            try {
                codec.stop();
                codec.release();
            } catch (IllegalStateException ignored) {}
            codec = null;
        }

        if (extractor != null) {
            extractor.release();
            extractor = null;
        }

        pcmBuffer = null;
    }

    public void release() {
        Log.d(TAG, "release");
        stop();
        released = true;
    }

    public boolean isPlaying() {
        return playing;
    }

    public int getCurrentPosition() {
        return (int) (currentPositionUs / 1000);
    }

    public int getDuration() {
        return (int) (durationUs / 1000);
    }

    private void notifyError(String message) {
        Log.e(TAG, message);
        if (onErrorListener != null) {
            onErrorListener.onError(this, message);
        }
    }
}
