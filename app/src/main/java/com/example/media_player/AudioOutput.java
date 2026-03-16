package com.example.media_player;

public interface AudioOutput {
    boolean configure(int sampleRate, int channelCount, int encoding);
    boolean start();
    int write(byte[] data, int offset, int length);
    void pause();
    void resume();
    void flush();
    void stop();
    void release();
}
