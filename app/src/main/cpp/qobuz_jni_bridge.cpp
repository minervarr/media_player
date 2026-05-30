#include <jni.h>
#include <android/log.h>
#include <string>

#define LOG_TAG "QobuzJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {
    void qobuz_init_android(void* vm, void* context);
    char* qobuz_get_stream_url(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token,
        const char* track_id, const char* format_id
    );
    char* qobuz_get_favorite_albums(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token
    );
    char* qobuz_get_favorite_tracks(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token
    );
    char* qobuz_get_user_playlists(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token
    );
    char* qobuz_get_album_tracks(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token,
        const char* album_id
    );
    char* qobuz_get_playlist_tracks(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token,
        const char* playlist_id
    );
    char* qobuz_search_albums(
        const char* app_id, const char* app_secret,
        const char* user_id, const char* auth_token,
        const char* query
    );
    void qobuz_free_string(char* s);
}

static std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* p = env->GetStringUTFChars(s, nullptr);
    std::string r(p);
    env->ReleaseStringUTFChars(s, p);
    return r;
}

static jstring rust_call(JNIEnv* env, char* raw, const char* tag) {
    if (raw && std::string(raw).rfind("error:", 0) == 0)
        LOGE("%s error: %s", tag, raw);
    else
        LOGI("%s ok", tag);
    jstring result = env->NewStringUTF(raw ? raw : "");
    qobuz_free_string(raw);
    return result;
}

// Package: com.example.media_player.qobuz, class: QobuzNative
#define JNI_METHOD(name) \
    Java_com_example_media_1player_qobuz_QobuzNative_##name

extern "C" JNIEXPORT void JNICALL
JNI_METHOD(nativeInitAndroid)(JNIEnv* env, jclass /*cls*/, jobject context)
{
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);
    qobuz_init_android(vm, context);
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeGetStreamUrl)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken,
        jstring trackId, jstring formatId)
{
    char* raw = qobuz_get_stream_url(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str(),
        jstr(env, trackId).c_str(), jstr(env, formatId).c_str()
    );
    return rust_call(env, raw, "getStreamUrl");
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeGetFavoriteAlbums)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken)
{
    char* raw = qobuz_get_favorite_albums(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str()
    );
    return rust_call(env, raw, "getFavoriteAlbums");
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeGetFavoriteTracks)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken)
{
    char* raw = qobuz_get_favorite_tracks(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str()
    );
    return rust_call(env, raw, "getFavoriteTracks");
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeGetUserPlaylists)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken)
{
    char* raw = qobuz_get_user_playlists(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str()
    );
    return rust_call(env, raw, "getUserPlaylists");
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeGetAlbumTracks)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken,
        jstring albumId)
{
    char* raw = qobuz_get_album_tracks(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str(),
        jstr(env, albumId).c_str()
    );
    return rust_call(env, raw, "getAlbumTracks");
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeGetPlaylistTracks)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken,
        jstring playlistId)
{
    char* raw = qobuz_get_playlist_tracks(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str(),
        jstr(env, playlistId).c_str()
    );
    return rust_call(env, raw, "getPlaylistTracks");
}

extern "C" JNIEXPORT jstring JNICALL
JNI_METHOD(nativeSearchAlbums)(
        JNIEnv* env, jclass /*cls*/,
        jstring appId, jstring appSecret,
        jstring userId, jstring authToken,
        jstring query)
{
    char* raw = qobuz_search_albums(
        jstr(env, appId).c_str(), jstr(env, appSecret).c_str(),
        jstr(env, userId).c_str(), jstr(env, authToken).c_str(),
        jstr(env, query).c_str()
    );
    return rust_call(env, raw, "searchAlbums");
}
