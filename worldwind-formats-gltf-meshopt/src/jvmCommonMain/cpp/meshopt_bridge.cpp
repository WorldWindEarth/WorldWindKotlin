// JNI / C bridge to meshoptimizer's decoder. Pure decode-only path — calls
// meshopt_decodeVertexBuffer / meshopt_decodeIndexBuffer / meshopt_decodeIndexSequence +
// the optional meshopt_decodeFilter* family of post-processing transforms.
//
// Shared between Android (JNI) and JVM (same JNI symbols, different build host).

#include <jni.h>
#include <cstdio>
#include <cstdint>
#include <cstring>
#include <vector>

#include "meshoptimizer.h"

#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "meshopt_bridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#else
#define LOGW(fmt, ...) do { fprintf(stderr, "[meshopt_bridge] WARN: " fmt "\n", ##__VA_ARGS__); } while (0)
#endif

namespace {

// Mode + filter codes must match the Kotlin enums (see MeshoptDecoder.kt). The Kotlin
// `.ordinal` is what arrives over JNI.
constexpr int MODE_ATTRIBUTES = 0;
constexpr int MODE_TRIANGLES  = 1;
constexpr int MODE_INDICES    = 2;

constexpr int FILTER_NONE        = -1;
constexpr int FILTER_OCTAHEDRAL  = 0;
constexpr int FILTER_QUATERNION  = 1;
constexpr int FILTER_EXPONENTIAL = 2;

} // namespace

extern "C" {

JNIEXPORT jbyteArray JNICALL
Java_earth_worldwind_formats_gltf_meshopt_NativeMeshopt_nativeDecode(
    JNIEnv* env, jobject,
    jbyteArray compressed, jint count, jint byteStride, jint mode, jint filter
) {
    const jsize compressedLen = env->GetArrayLength(compressed);
    jbyte* compressedRaw = env->GetByteArrayElements(compressed, nullptr);
    if (compressedRaw == nullptr) return nullptr;

    const size_t outBytes = static_cast<size_t>(count) * static_cast<size_t>(byteStride);
    std::vector<uint8_t> out(outBytes);

    int status = -1;
    switch (mode) {
        case MODE_ATTRIBUTES:
            status = meshopt_decodeVertexBuffer(
                out.data(), static_cast<size_t>(count), static_cast<size_t>(byteStride),
                reinterpret_cast<const unsigned char*>(compressedRaw),
                static_cast<size_t>(compressedLen));
            break;
        case MODE_TRIANGLES:
            // Triangle index buffer — three indices per face.
            status = meshopt_decodeIndexBuffer(
                out.data(), static_cast<size_t>(count), static_cast<size_t>(byteStride),
                reinterpret_cast<const unsigned char*>(compressedRaw),
                static_cast<size_t>(compressedLen));
            break;
        case MODE_INDICES:
            // General index buffer (non-triangle topology).
            status = meshopt_decodeIndexSequence(
                out.data(), static_cast<size_t>(count), static_cast<size_t>(byteStride),
                reinterpret_cast<const unsigned char*>(compressedRaw),
                static_cast<size_t>(compressedLen));
            break;
        default:
            LOGW("unknown decode mode %d", mode);
            break;
    }
    env->ReleaseByteArrayElements(compressed, compressedRaw, JNI_ABORT);

    if (status != 0) {
        LOGW("meshopt_decode* failed: status=%d, mode=%d, count=%d, stride=%d",
             status, mode, count, byteStride);
        return nullptr;
    }

    // Apply optional post-decode filter.
    switch (filter) {
        case FILTER_OCTAHEDRAL:
            meshopt_decodeFilterOct(out.data(), static_cast<size_t>(count), static_cast<size_t>(byteStride));
            break;
        case FILTER_QUATERNION:
            meshopt_decodeFilterQuat(out.data(), static_cast<size_t>(count), static_cast<size_t>(byteStride));
            break;
        case FILTER_EXPONENTIAL:
            meshopt_decodeFilterExp(out.data(), static_cast<size_t>(count), static_cast<size_t>(byteStride));
            break;
        case FILTER_NONE:
        default:
            break;
    }

    jbyteArray result = env->NewByteArray(static_cast<jsize>(out.size()));
    if (result == nullptr) return nullptr;
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(out.size()),
                            reinterpret_cast<const jbyte*>(out.data()));
    return result;
}

} // extern "C"
