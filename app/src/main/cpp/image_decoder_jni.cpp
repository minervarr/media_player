#include <jni.h>
#include "image_decoder.h"
#include <android/log.h>
#include <android/bitmap.h>
#include <algorithm>
#include <cstdio>
#include <cstring>

#define LOG_TAG "NativeImageDecoder"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Cache Bitmap.createBitmap and Bitmap.Config.ARGB_8888 references
static jclass bitmapClass = nullptr;
static jmethodID createBitmapMethod = nullptr;
static jobject argb8888Config = nullptr;

static bool initBitmapJni(JNIEnv* env) {
    if (bitmapClass) return true;

    jclass localBitmapClass = env->FindClass("android/graphics/Bitmap");
    if (!localBitmapClass) return false;
    bitmapClass = (jclass)env->NewGlobalRef(localBitmapClass);
    env->DeleteLocalRef(localBitmapClass);

    createBitmapMethod = env->GetStaticMethodID(bitmapClass, "createBitmap",
        "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
    if (!createBitmapMethod) return false;

    jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
    if (!configClass) return false;
    jfieldID argb8888Field = env->GetStaticFieldID(configClass, "ARGB_8888",
        "Landroid/graphics/Bitmap$Config;");
    if (!argb8888Field) return false;
    jobject localConfig = env->GetStaticObjectField(configClass, argb8888Field);
    argb8888Config = env->NewGlobalRef(localConfig);
    env->DeleteLocalRef(localConfig);
    env->DeleteLocalRef(configClass);

    return true;
}

static jobject createBitmapFromPixels(JNIEnv* env, DecodedImage* img) {
    if (!initBitmapJni(env)) {
        LOGE("Failed to init Bitmap JNI");
        return nullptr;
    }

    jobject bitmap = env->CallStaticObjectMethod(bitmapClass, createBitmapMethod,
        img->width, img->height, argb8888Config);
    if (!bitmap) return nullptr;

    void* bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, bitmap, &bitmapPixels) != 0) {
        env->DeleteLocalRef(bitmap);
        return nullptr;
    }

    // Get actual stride from bitmap info
    AndroidBitmapInfo bitmapInfo;
    AndroidBitmap_getInfo(env, bitmap, &bitmapInfo);
    int srcPitch = img->width * 4;
    if ((int)bitmapInfo.stride == srcPitch) {
        memcpy(bitmapPixels, img->pixels, srcPitch * img->height);
    } else {
        // Copy row by row if strides differ
        for (int y = 0; y < img->height; y++) {
            memcpy((uint8_t*)bitmapPixels + y * bitmapInfo.stride,
                   img->pixels + y * srcPitch, srcPitch);
        }
    }

    AndroidBitmap_unlockPixels(env, bitmap);
    return bitmap;
}

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_example_media_1player_NativeImageDecoder_nativeDecodeJpeg(JNIEnv* env, jclass,
        jbyteArray data, jint targetSize) {
    jsize length = env->GetArrayLength(data);
    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return nullptr;

    DecodedImage* img = NativeImageDecoder::decodeJpeg(
        reinterpret_cast<const uint8_t*>(bytes), length, targetSize);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);

    if (!img) return nullptr;

    jobject bitmap = createBitmapFromPixels(env, img);
    delete img;
    return bitmap;
}

JNIEXPORT jobject JNICALL
Java_com_example_media_1player_NativeImageDecoder_nativeDecodeJpegFile(JNIEnv* env, jclass,
        jstring path, jint targetSize) {
    const char* pathStr = env->GetStringUTFChars(path, nullptr);
    if (!pathStr) return nullptr;

    FILE* f = fopen(pathStr, "rb");
    env->ReleaseStringUTFChars(path, pathStr);
    if (!f) return nullptr;

    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);

    if (size <= 0 || size > 50 * 1024 * 1024) { // 50MB sanity limit
        fclose(f);
        return nullptr;
    }

    uint8_t* data = (uint8_t*)malloc(size);
    if (!data) { fclose(f); return nullptr; }

    size_t read = fread(data, 1, size, f);
    fclose(f);

    if ((long)read != size) {
        free(data);
        return nullptr;
    }

    DecodedImage* img = NativeImageDecoder::decodeJpeg(data, (int)size, targetSize);
    free(data);

    if (!img) return nullptr;

    jobject bitmap = createBitmapFromPixels(env, img);
    delete img;
    return bitmap;
}

JNIEXPORT jboolean JNICALL
Java_com_example_media_1player_NativeImageDecoder_nativeIsJpeg(JNIEnv* env, jclass,
        jbyteArray data, jint length) {
    if (length < 3) return JNI_FALSE;

    jbyte* bytes = env->GetByteArrayElements(data, nullptr);
    if (!bytes) return JNI_FALSE;

    int checkLen = std::min((int)length, (int)env->GetArrayLength(data));
    bool result = NativeImageDecoder::isJpeg(reinterpret_cast<const uint8_t*>(bytes), checkLen);
    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
