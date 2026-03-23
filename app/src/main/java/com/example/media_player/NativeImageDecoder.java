package com.example.media_player;

import android.graphics.Bitmap;

public class NativeImageDecoder {

    static {
        System.loadLibrary("media_player");
    }

    public static native Bitmap nativeDecodeJpeg(byte[] data, int targetSize);
    public static native Bitmap nativeDecodeJpegFile(String path, int targetSize);
    public static native boolean nativeIsJpeg(byte[] data, int length);
}
