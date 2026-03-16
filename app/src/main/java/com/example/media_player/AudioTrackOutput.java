package com.example.media_player;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.util.Log;

public class AudioTrackOutput implements AudioOutput {

    private static final String TAG = "AudioTrackOutput";
    private AudioTrack audioTrack;

    @Override
    public boolean configure(int sampleRate, int channelCount, int encoding) {
        release();

        try {
            int channelMask = channelCount == 1
                    ? AudioFormat.CHANNEL_OUT_MONO
                    : AudioFormat.CHANNEL_OUT_STEREO;

            int minBufSize = AudioTrack.getMinBufferSize(sampleRate, channelMask, encoding);
            int bufSize = Math.max(minBufSize * 4, 16384);

            Log.d(TAG, "configure: " + sampleRate + "Hz/" + channelCount + "ch enc=" + encoding + " buf=" + bufSize);

            audioTrack = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setChannelMask(channelMask)
                            .setEncoding(encoding)
                            .build())
                    .setBufferSizeInBytes(bufSize)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "configure failed", e);
            return false;
        }
    }

    @Override
    public boolean start() {
        if (audioTrack != null) {
            try {
                audioTrack.play();
                Log.d(TAG, "start");
                return true;
            } catch (Exception e) {
                Log.e(TAG, "start failed", e);
                return false;
            }
        }
        return false;
    }

    @Override
    public int write(byte[] data, int offset, int length) {
        if (audioTrack != null) {
            return audioTrack.write(data, offset, length);
        }
        return -1;
    }

    @Override
    public void pause() {
        if (audioTrack != null) {
            audioTrack.pause();
        }
    }

    @Override
    public void resume() {
        if (audioTrack != null) {
            audioTrack.play();
        }
    }

    @Override
    public void flush() {
        if (audioTrack != null) {
            audioTrack.flush();
        }
    }

    @Override
    public void stop() {
        if (audioTrack != null) {
            Log.d(TAG, "stop");
            try {
                audioTrack.stop();
            } catch (IllegalStateException ignored) {}
        }
    }

    @Override
    public void release() {
        if (audioTrack != null) {
            Log.d(TAG, "release");
            try {
                audioTrack.stop();
            } catch (IllegalStateException ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
    }
}
