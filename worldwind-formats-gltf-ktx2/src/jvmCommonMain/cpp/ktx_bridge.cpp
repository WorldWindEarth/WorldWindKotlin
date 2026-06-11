// JNI / C bridge to libktx's KTX2 + Basis Universal transcoder. Shared between Android
// (JNI symbols) and JVM (same JNI symbols). On iOS the same source is compiled but with
// the iOS bridge that exposes plain C functions for cinterop.
//
// libktx is pulled via CMake FetchContent and statically linked into libktx_bridge.so.

#include <jni.h>
#include <cstdio>
#include <cstring>
#include <memory>
#include <vector>

#include "ktx.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "ktx_bridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGW(fmt, ...) do { fprintf(stderr, "[ktx_bridge] WARN: " fmt "\n", ##__VA_ARGS__); } while (0)
#define LOGE(fmt, ...) do { fprintf(stderr, "[ktx_bridge] ERROR: " fmt "\n", ##__VA_ARGS__); } while (0)
#endif

namespace {

// Owns the decoded RGBA pixels + dimensions. JNI returns an opaque jlong handle that the
// Kotlin side drains via the per-field native getters before calling nativeRelease.
struct DecodedKtx {
    std::vector<uint8_t> rgba;
    int32_t width;
    int32_t height;
};

DecodedKtx* decode_to_rgba(const uint8_t* bytes, size_t len) {
    ktxTexture2* texture = nullptr;
    KTX_error_code result = ktxTexture2_CreateFromMemory(
        bytes, len, KTX_TEXTURE_CREATE_LOAD_IMAGE_DATA_BIT, &texture);
    if (result != KTX_SUCCESS || texture == nullptr) {
        LOGW("ktxTexture2_CreateFromMemory failed: %s", ktxErrorString(result));
        return nullptr;
    }

    if (ktxTexture2_NeedsTranscoding(texture)) {
        // Transcode to plain RGBA8 — the downstream worldwind texture upload expects
        // 8-bit-per-channel RGBA regardless of source compression.
        result = ktxTexture2_TranscodeBasis(texture, KTX_TTF_RGBA32, 0);
        if (result != KTX_SUCCESS) {
            LOGW("ktxTexture2_TranscodeBasis failed: %s", ktxErrorString(result));
            ktxTexture_Destroy(ktxTexture(texture));
            return nullptr;
        }
    }

    auto base = std::make_unique<DecodedKtx>();
    base->width = static_cast<int32_t>(texture->baseWidth);
    base->height = static_cast<int32_t>(texture->baseHeight);

    // Mip level 0 only — the worldwind texture pipeline regenerates mipmaps itself if it
    // wants them. KTX2 files often ship pre-baked mip chains, but we ignore everything
    // past the base level for now.
    ktx_size_t imageSize = ktxTexture_GetImageSize(ktxTexture(texture), 0);
    ktx_size_t imageOffset = 0;
    result = ktxTexture_GetImageOffset(ktxTexture(texture), 0, 0, 0, &imageOffset);
    if (result != KTX_SUCCESS) {
        LOGW("ktxTexture_GetImageOffset failed: %s", ktxErrorString(result));
        ktxTexture_Destroy(ktxTexture(texture));
        return nullptr;
    }
    base->rgba.assign(
        texture->pData + imageOffset,
        texture->pData + imageOffset + imageSize);

    ktxTexture_Destroy(ktxTexture(texture));
    return base.release();
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_earth_worldwind_formats_gltf_ktx2_NativeKtx2_nativeDecode(JNIEnv* env, jobject,
                                                                jbyteArray bytes) {
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) {
        LOGW("nativeDecode: empty buffer");
        return 0;
    }
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    if (raw == nullptr) return 0;
    DecodedKtx* handle = decode_to_rgba(
        reinterpret_cast<const uint8_t*>(raw), static_cast<size_t>(len));
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    return reinterpret_cast<jlong>(handle);
}

JNIEXPORT void JNICALL
Java_earth_worldwind_formats_gltf_ktx2_NativeKtx2_nativeRelease(JNIEnv*, jobject,
                                                                 jlong handle) {
    delete reinterpret_cast<DecodedKtx*>(handle);
}

JNIEXPORT jint JNICALL
Java_earth_worldwind_formats_gltf_ktx2_NativeKtx2_nativeGetWidth(JNIEnv*, jobject,
                                                                  jlong handle) {
    return reinterpret_cast<DecodedKtx*>(handle)->width;
}

JNIEXPORT jint JNICALL
Java_earth_worldwind_formats_gltf_ktx2_NativeKtx2_nativeGetHeight(JNIEnv*, jobject,
                                                                   jlong handle) {
    return reinterpret_cast<DecodedKtx*>(handle)->height;
}

JNIEXPORT jbyteArray JNICALL
Java_earth_worldwind_formats_gltf_ktx2_NativeKtx2_nativeGetRgba(JNIEnv* env, jobject,
                                                                 jlong handle) {
    auto* h = reinterpret_cast<DecodedKtx*>(handle);
    const jsize n = static_cast<jsize>(h->rgba.size());
    jbyteArray arr = env->NewByteArray(n);
    if (arr == nullptr) return nullptr;
    if (n > 0) {
        env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte*>(h->rgba.data()));
    }
    return arr;
}

} // extern "C"
