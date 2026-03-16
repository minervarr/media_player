package com.example.media_player;

/**
 * Thread-safe ring buffer for passing decoded PCM data from decode thread to output thread.
 */
public class PcmBuffer {

    private final byte[] buffer;
    private int readPos;
    private int writePos;
    private int available;
    private boolean endSignaled;
    private boolean flushed;
    private final Object lock = new Object();

    public PcmBuffer(int capacity) {
        buffer = new byte[capacity];
    }

    public void write(byte[] data, int offset, int length) throws InterruptedException {
        int written = 0;
        while (written < length) {
            synchronized (lock) {
                while (available == buffer.length && !flushed) {
                    lock.wait();
                }
                if (flushed) {
                    flushed = false;
                    return;
                }
                int space = buffer.length - available;
                int toWrite = Math.min(length - written, space);
                for (int i = 0; i < toWrite; i++) {
                    buffer[writePos] = data[offset + written + i];
                    writePos = (writePos + 1) % buffer.length;
                }
                available += toWrite;
                written += toWrite;
                lock.notifyAll();
            }
        }
    }

    public int read(byte[] dest, int offset, int maxLength) throws InterruptedException {
        synchronized (lock) {
            while (available == 0 && !endSignaled && !flushed) {
                lock.wait();
            }
            if (flushed) {
                flushed = false;
                return -2;
            }
            if (available == 0 && endSignaled) {
                return -1;
            }
            int toRead = Math.min(maxLength, available);
            for (int i = 0; i < toRead; i++) {
                dest[offset + i] = buffer[readPos];
                readPos = (readPos + 1) % buffer.length;
            }
            available -= toRead;
            lock.notifyAll();
            return toRead;
        }
    }

    public void flush() {
        synchronized (lock) {
            readPos = 0;
            writePos = 0;
            available = 0;
            endSignaled = false;
            flushed = true;
            lock.notifyAll();
        }
    }

    public void signalEnd() {
        synchronized (lock) {
            endSignaled = true;
            lock.notifyAll();
        }
    }

    public void reset() {
        synchronized (lock) {
            readPos = 0;
            writePos = 0;
            available = 0;
            endSignaled = false;
            flushed = false;
            lock.notifyAll();
        }
    }
}
