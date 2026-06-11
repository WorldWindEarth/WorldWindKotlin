// C implementation backed by Google's libdraco. Exported with extern "C" linkage so
// Kotlin/Native cinterop can call it directly. Shares the underlying decode logic with
// the JNI bridge at src/jvmCommonMain/cpp/draco_bridge.cpp — same attribute walk, same
// triangle extraction — but the surface area is C functions rather than JNI methods.

#include "draco_bridge.h"

#include <cstring>
#include <memory>
#include <vector>

#include "draco/compression/decode.h"
#include "draco/mesh/mesh.h"
#include "draco/point_cloud/point_cloud.h"
#include "draco/attributes/geometry_attribute.h"

struct wwdraco_handle {
    std::vector<int32_t> indices;
    std::vector<float>   positions;
    std::vector<float>   texCoords;
    std::vector<float>   colors;
};

namespace {

bool extractFromAttribute(const draco::PointCloud& pc,
                          const draco::PointAttribute& attr,
                          uint8_t expectedComponents,
                          std::vector<float>& out) {
    const uint32_t numPoints = pc.num_points();
    const uint8_t components = attr.num_components();
    const uint8_t copy = components < expectedComponents ? components : expectedComponents;
    out.resize(numPoints * expectedComponents);
    std::vector<float> scratch(expectedComponents);
    for (uint32_t i = 0; i < numPoints; ++i) {
        const draco::AttributeValueIndex idx = attr.mapped_index(draco::PointIndex(i));
        for (uint8_t c = 0; c < expectedComponents; ++c) {
            scratch[c] = (c == 3 && expectedComponents == 4) ? 1.0f : 0.0f;
        }
        attr.ConvertValue<float>(idx, copy, scratch.data());
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

// Last-ditch: scan generics when uniqueId + COLOR semantic both miss. PNTS RGB is uint8.
bool extractFirstGenericColor(const draco::PointCloud& pc,
                              uint8_t expectedComponents,
                              std::vector<float>& out) {
    const int32_t numAttrs = pc.num_attributes();
    for (int32_t i = 0; i < numAttrs; ++i) {
        const draco::PointAttribute* attr = pc.attribute(i);
        if (attr->attribute_type() != draco::GeometryAttribute::GENERIC) continue;
        const uint8_t cc = attr->num_components();
        if (cc == 3 || cc == 4) {
            return extractFromAttribute(pc, *attr, expectedComponents, out);
        }
    }
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

wwdraco_handle* wwdraco_decode(const uint8_t* bytes, size_t len, int32_t colorsUniqueId) {
    if (len == 0 || bytes == nullptr) return nullptr;
    draco::DecoderBuffer buffer;
    buffer.Init(reinterpret_cast<const char*>(bytes), len);

    draco::Decoder decoder;
    auto type_or = draco::Decoder::GetEncodedGeometryType(&buffer);
    if (!type_or.ok()) return nullptr;

    auto handle = std::make_unique<wwdraco_handle>();
    if (type_or.value() == draco::TRIANGULAR_MESH) {
        auto mesh_or = decoder.DecodeMeshFromBuffer(&buffer);
        if (!mesh_or.ok()) return nullptr;
        std::unique_ptr<draco::Mesh> mesh = std::move(mesh_or).value();
        extractTriangleIndices(*mesh, handle->indices);
        extractFloatAttribute(*mesh, draco::GeometryAttribute::POSITION,  3, handle->positions);
        extractFloatAttribute(*mesh, draco::GeometryAttribute::TEX_COORD, 2, handle->texCoords);
        extractFloatAttribute(*mesh, draco::GeometryAttribute::COLOR,     4, handle->colors);
    } else {
        auto pc_or = decoder.DecodePointCloudFromBuffer(&buffer);
        if (!pc_or.ok()) return nullptr;
        std::unique_ptr<draco::PointCloud> pc = std::move(pc_or).value();
        extractFloatAttribute(*pc, draco::GeometryAttribute::POSITION,  3, handle->positions);
        extractFloatAttribute(*pc, draco::GeometryAttribute::TEX_COORD, 2, handle->texCoords);
        if (!extractByUniqueId(*pc, colorsUniqueId, 4, handle->colors)) {
            if (!extractFloatAttribute(*pc, draco::GeometryAttribute::COLOR, 4, handle->colors)) {
                extractFirstGenericColor(*pc, 4, handle->colors);
            }
        }
    }
    return handle.release();
}

void wwdraco_release(wwdraco_handle* handle) {
    delete handle;
}

size_t wwdraco_indices_count(const wwdraco_handle* h)   { return h ? h->indices.size()   : 0; }
size_t wwdraco_positions_count(const wwdraco_handle* h) { return h ? h->positions.size() : 0; }
size_t wwdraco_texcoords_count(const wwdraco_handle* h) { return h ? h->texCoords.size() : 0; }
size_t wwdraco_colors_count(const wwdraco_handle* h)    { return h ? h->colors.size()    : 0; }

static size_t copyClamped(const void* src, size_t srcCount, void* dst, size_t capacity, size_t elemSize) {
    if (src == nullptr || dst == nullptr || srcCount == 0 || capacity == 0) return 0;
    const size_t n = srcCount < capacity ? srcCount : capacity;
    std::memcpy(dst, src, n * elemSize);
    return n;
}

size_t wwdraco_get_indices(const wwdraco_handle* h, int32_t* out, size_t capacity) {
    if (h == nullptr) return 0;
    return copyClamped(h->indices.data(), h->indices.size(), out, capacity, sizeof(int32_t));
}

size_t wwdraco_get_positions(const wwdraco_handle* h, float* out, size_t capacity) {
    if (h == nullptr) return 0;
    return copyClamped(h->positions.data(), h->positions.size(), out, capacity, sizeof(float));
}

size_t wwdraco_get_texcoords(const wwdraco_handle* h, float* out, size_t capacity) {
    if (h == nullptr) return 0;
    return copyClamped(h->texCoords.data(), h->texCoords.size(), out, capacity, sizeof(float));
}

size_t wwdraco_get_colors(const wwdraco_handle* h, float* out, size_t capacity) {
    if (h == nullptr) return 0;
    return copyClamped(h->colors.data(), h->colors.size(), out, capacity, sizeof(float));
}

} // extern "C"
