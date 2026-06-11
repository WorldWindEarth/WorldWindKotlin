// JNI bridge from earth.worldwind.formats.gltf.draco.NativeDraco (Kotlin) into Google's
// open-source libdraco decoder. Statically linked at build time via CMake's
// FetchContent.
//
// nativeDecode() allocates and returns an opaque handle (pointer to DecodedHandle) that
// the per-attribute getters drain. nativeRelease() frees the handle. Each decode call
// gets its own handle, so concurrent decodes from multiple JVM threads are safe.

#include <jni.h>
#include <memory>
#include <vector>
#include <cstring>
#include <cstdio>

#include "draco/compression/decode.h"
#include "draco/mesh/mesh.h"
#include "draco/point_cloud/point_cloud.h"
#include "draco/attributes/geometry_attribute.h"

// Logging — Android logcat on Android, stderr everywhere else. Same source file builds
// for both via the JVM (worldwind-draco/src/jvmMain/cpp/CMakeLists.txt) and Android NDK
// (worldwind-draco/src/androidMain/cpp/CMakeLists.txt) build chains.
#ifdef __ANDROID__
#include <android/log.h>
#define LOG_TAG "draco_bridge"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#define LOGW(fmt, ...) do { fprintf(stderr, "[draco_bridge] WARN: " fmt "\n", ##__VA_ARGS__); } while (0)
#define LOGE(fmt, ...) do { fprintf(stderr, "[draco_bridge] ERROR: " fmt "\n", ##__VA_ARGS__); } while (0)
#endif

namespace {

// Owns the decoded arrays so the per-attribute getters can extract on demand without
// re-decoding. Allocated in nativeDecode, freed in nativeRelease.
struct DecodedHandle {
    std::vector<int32_t> indices;
    std::vector<float>   positions;
    std::vector<float>   texCoords;
    std::vector<float>   colors;
};

// Pull a float-typed attribute (POSITION / TEX_COORD / COLOR) into a flat std::vector.
// Returns false if the attribute is absent in the source mesh — Draco's
// `GetNamedAttribute` returns nullptr for absent semantics.
bool extractFromAttribute(const draco::PointCloud& pc,
                          const draco::PointAttribute& attr,
                          uint8_t expectedComponents,
                          std::vector<float>& out) {
    const uint32_t numPoints = pc.num_points();
    const uint8_t components = attr.num_components();
    // The attribute may have fewer or more components than we expected (e.g. COLOR can
    // be vec3 or vec4). We pull exactly `expectedComponents` per vertex, padding
    // missing components with 0 (or, for alpha, with 1).
    const uint8_t copyComponents = components < expectedComponents ? components : expectedComponents;

    out.resize(numPoints * expectedComponents);
    std::vector<float> scratch(expectedComponents);
    for (uint32_t i = 0; i < numPoints; ++i) {
        const draco::AttributeValueIndex idx = attr.mapped_index(draco::PointIndex(i));
        // Default to 0, with alpha defaulting to 1 if missing.
        for (uint8_t c = 0; c < expectedComponents; ++c) {
            scratch[c] = (c == 3 && expectedComponents == 4) ? 1.0f : 0.0f;
        }
        attr.ConvertValue<float>(idx, copyComponents, scratch.data());
        std::memcpy(&out[i * expectedComponents], scratch.data(),
                    expectedComponents * sizeof(float));
    }
    return true;
}

bool extractFloatAttribute(const draco::PointCloud& pc,
                           draco::GeometryAttribute::Type type,
                           uint8_t expectedComponents,
                           std::vector<float>& out) {
    const draco::PointAttribute* attr = pc.GetNamedAttribute(type);
    if (attr == nullptr) return false;
    return extractFromAttribute(pc, *attr, expectedComponents, out);
}

// Last-ditch: scan generics when COLOR semantic + properties uniqueId both miss. CesiumJS
// PNTS encoder writes RGB as a GENERIC uint8 attr. Prefer uint8 vec3/4, fall back to any.
bool extractFirstGenericColor(const draco::PointCloud& pc,
                              uint8_t expectedComponents,
                              std::vector<float>& out) {
    const int32_t numAttrs = pc.num_attributes();
    const draco::PointAttribute* fallback = nullptr;
    for (int32_t i = 0; i < numAttrs; ++i) {
        const draco::PointAttribute* attr = pc.attribute(i);
        if (attr->attribute_type() != draco::GeometryAttribute::GENERIC) continue;
        const uint8_t cc = attr->num_components();
        if (cc != 3 && cc != 4) continue;
        if (attr->data_type() == draco::DT_UINT8) {
            return extractFromAttribute(pc, *attr, expectedComponents, out);
        }
        if (fallback == nullptr) fallback = attr;
    }
    if (fallback != nullptr) return extractFromAttribute(pc, *fallback, expectedComponents, out);
    return false;
}

void extractTriangleIndices(const draco::Mesh& mesh, std::vector<int32_t>& out) {
    const uint32_t numFaces = mesh.num_faces();
    out.resize(numFaces * 3);
    for (uint32_t f = 0; f < numFaces; ++f) {
        const draco::Mesh::Face& face = mesh.face(draco::FaceIndex(f));
        out[f * 3 + 0] = static_cast<int32_t>(face[0].value());
        out[f * 3 + 1] = static_cast<int32_t>(face[1].value());
        out[f * 3 + 2] = static_cast<int32_t>(face[2].value());
    }
}

} // namespace

namespace {
bool extractByUniqueId(const draco::PointCloud& pc,
                      int32_t colorsUniqueId,
                      uint8_t expectedComponents,
                      std::vector<float>& out) {
    if (colorsUniqueId < 0) return false;
    const draco::PointAttribute* attr = pc.GetAttributeByUniqueId(static_cast<uint32_t>(colorsUniqueId));
    if (attr == nullptr) return false;
    return extractFromAttribute(pc, *attr, expectedComponents, out);
}
} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeDecode(JNIEnv* env, jobject /*thiz*/,
                                                                 jbyteArray bytes,
                                                                 jint colorsUniqueId) {
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) {
        LOGW("nativeDecode: empty buffer");
        return 0;
    }

    // Pin the JVM byte array for zero-copy decoder input.
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    if (raw == nullptr) {
        LOGE("nativeDecode: GetByteArrayElements returned null");
        return 0;
    }

    draco::DecoderBuffer buffer;
    buffer.Init(reinterpret_cast<const char*>(raw), static_cast<size_t>(len));

    draco::Decoder decoder;
    auto type_or = draco::Decoder::GetEncodedGeometryType(&buffer);
    if (!type_or.ok()) {
        LOGW("nativeDecode: GetEncodedGeometryType failed: %s", type_or.status().error_msg());
        env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
        return 0;
    }

    auto handle = std::make_unique<DecodedHandle>();

    if (type_or.value() == draco::TRIANGULAR_MESH) {
        auto mesh_or = decoder.DecodeMeshFromBuffer(&buffer);
        env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
        if (!mesh_or.ok()) {
            LOGW("nativeDecode: DecodeMeshFromBuffer failed: %s", mesh_or.status().error_msg());
            return 0;
        }
        std::unique_ptr<draco::Mesh> mesh = std::move(mesh_or).value();
        extractTriangleIndices(*mesh, handle->indices);
        extractFloatAttribute(*mesh, draco::GeometryAttribute::POSITION,  3, handle->positions);
        extractFloatAttribute(*mesh, draco::GeometryAttribute::TEX_COORD, 2, handle->texCoords);
        extractFloatAttribute(*mesh, draco::GeometryAttribute::COLOR,     4, handle->colors);
    } else {
        // Point cloud — no faces, only POSITION + COLOR are typical.
        auto pc_or = decoder.DecodePointCloudFromBuffer(&buffer);
        env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
        if (!pc_or.ok()) {
            LOGW("nativeDecode: DecodePointCloudFromBuffer failed: %s", pc_or.status().error_msg());
            return 0;
        }
        std::unique_ptr<draco::PointCloud> pc = std::move(pc_or).value();
        extractFloatAttribute(*pc, draco::GeometryAttribute::POSITION,  3, handle->positions);
        extractFloatAttribute(*pc, draco::GeometryAttribute::TEX_COORD, 2, handle->texCoords);
        // Colors lookup: uniqueId (PNTS properties map) → COLOR semantic → first generic.
        if (!extractByUniqueId(*pc, colorsUniqueId, 4, handle->colors)) {
            if (!extractFloatAttribute(*pc, draco::GeometryAttribute::COLOR, 4, handle->colors)) {
                extractFirstGenericColor(*pc, 4, handle->colors);
            }
        }
    }

    return reinterpret_cast<jlong>(handle.release());
}

JNIEXPORT void JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeRelease(JNIEnv*, jobject,
                                                                  jlong handle) {
    delete reinterpret_cast<DecodedHandle*>(handle);
}

namespace {

jintArray emitIntArray(JNIEnv* env, const std::vector<int32_t>& src) {
    const jsize n = static_cast<jsize>(src.size());
    jintArray arr = env->NewIntArray(n);
    if (arr == nullptr) return nullptr;
    if (n > 0) env->SetIntArrayRegion(arr, 0, n, src.data());
    return arr;
}

jfloatArray emitFloatArray(JNIEnv* env, const std::vector<float>& src) {
    const jsize n = static_cast<jsize>(src.size());
    jfloatArray arr = env->NewFloatArray(n);
    if (arr == nullptr) return nullptr;
    if (n > 0) env->SetFloatArrayRegion(arr, 0, n, src.data());
    return arr;
}

} // namespace

JNIEXPORT jintArray JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeGetIndices(JNIEnv* env, jobject,
                                                                     jlong handle) {
    return emitIntArray(env, reinterpret_cast<DecodedHandle*>(handle)->indices);
}

JNIEXPORT jfloatArray JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeGetPositions(JNIEnv* env, jobject,
                                                                       jlong handle) {
    return emitFloatArray(env, reinterpret_cast<DecodedHandle*>(handle)->positions);
}

JNIEXPORT jfloatArray JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeGetTexCoords(JNIEnv* env, jobject,
                                                                       jlong handle) {
    return emitFloatArray(env, reinterpret_cast<DecodedHandle*>(handle)->texCoords);
}

JNIEXPORT jfloatArray JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeGetColors(JNIEnv* env, jobject,
                                                                    jlong handle) {
    return emitFloatArray(env, reinterpret_cast<DecodedHandle*>(handle)->colors);
}

// Fill-into-pre-allocated variants for the JVM/Android ThreadLocal pools. Returns count
// written, or negative-needed-size when capacity is insufficient.
JNIEXPORT jint JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeGetColorsCount(JNIEnv*, jobject,
                                                                         jlong handle) {
    return static_cast<jint>(reinterpret_cast<DecodedHandle*>(handle)->colors.size());
}

JNIEXPORT jint JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeFillColors(JNIEnv* env, jobject,
                                                                     jlong handle,
                                                                     jfloatArray out,
                                                                     jint capacity) {
    auto* h = reinterpret_cast<DecodedHandle*>(handle);
    const jint needed = static_cast<jint>(h->colors.size());
    if (needed == 0) return 0;
    if (capacity < needed) return -needed;
    env->SetFloatArrayRegion(out, 0, needed, h->colors.data());
    return needed;
}

JNIEXPORT jint JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeGetPositionsCount(JNIEnv*, jobject,
                                                                            jlong handle) {
    return static_cast<jint>(reinterpret_cast<DecodedHandle*>(handle)->positions.size());
}

JNIEXPORT jint JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeFillPositions(JNIEnv* env, jobject,
                                                                        jlong handle,
                                                                        jfloatArray out,
                                                                        jint capacity) {
    auto* h = reinterpret_cast<DecodedHandle*>(handle);
    const jint needed = static_cast<jint>(h->positions.size());
    if (needed == 0) return 0;
    if (capacity < needed) return -needed;
    env->SetFloatArrayRegion(out, 0, needed, h->positions.data());
    return needed;
}

// Batched PNTS fast path. Two calls per tile (decode+probe, fill+release) instead of the
// eight per-attribute calls the generic API requires. Caller passes pre-sized pool buffers
// after reading the sizes filled into sizesOut[0]=positions, sizesOut[1]=colors.
JNIEXPORT jlong JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeDecodeProbePoints(
        JNIEnv* env, jobject, jbyteArray bytes, jint colorsUniqueId, jintArray sizesOut) {
    const jsize len = env->GetArrayLength(bytes);
    if (len <= 0) return 0;
    jbyte* raw = env->GetByteArrayElements(bytes, nullptr);
    if (raw == nullptr) return 0;

    draco::DecoderBuffer buffer;
    buffer.Init(reinterpret_cast<const char*>(raw), static_cast<size_t>(len));
    draco::Decoder decoder;
    auto pc_or = decoder.DecodePointCloudFromBuffer(&buffer);
    env->ReleaseByteArrayElements(bytes, raw, JNI_ABORT);
    if (!pc_or.ok()) {
        LOGW("nativeDecodeProbePoints: DecodePointCloudFromBuffer failed: %s",
             pc_or.status().error_msg());
        return 0;
    }
    std::unique_ptr<draco::PointCloud> pc = std::move(pc_or).value();

    auto handle = std::make_unique<DecodedHandle>();
    extractFloatAttribute(*pc, draco::GeometryAttribute::POSITION, 3, handle->positions);
    if (!extractByUniqueId(*pc, colorsUniqueId, 4, handle->colors)) {
        if (!extractFloatAttribute(*pc, draco::GeometryAttribute::COLOR, 4, handle->colors)) {
            extractFirstGenericColor(*pc, 4, handle->colors);
        }
    }

    jint sizes[2] = {
        static_cast<jint>(handle->positions.size()),
        static_cast<jint>(handle->colors.size()),
    };
    env->SetIntArrayRegion(sizesOut, 0, 2, sizes);
    return reinterpret_cast<jlong>(handle.release());
}

JNIEXPORT void JNICALL
Java_earth_worldwind_formats_gltf_draco_NativeDraco_nativeFillReleasePoints(
        JNIEnv* env, jobject, jlong handle, jfloatArray posOut, jfloatArray colorsOut) {
    auto* h = reinterpret_cast<DecodedHandle*>(handle);
    if (h == nullptr) return;
    const jint posSize = static_cast<jint>(h->positions.size());
    const jint colorsSize = static_cast<jint>(h->colors.size());
    if (posSize > 0) env->SetFloatArrayRegion(posOut, 0, posSize, h->positions.data());
    if (colorsSize > 0) env->SetFloatArrayRegion(colorsOut, 0, colorsSize, h->colors.data());
    delete h;
}

} // extern "C"
