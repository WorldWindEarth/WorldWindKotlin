#include "meshopt_bridge.h"
#include "meshoptimizer.h"

extern "C" {

int wwmeshopt_decode(
    uint8_t* out, size_t outBytes,
    const uint8_t* compressed, size_t compressedLen,
    int32_t count, int32_t byteStride, int32_t mode, int32_t filter
) {
    if (out == nullptr || compressed == nullptr) return -1;
    const size_t expected = static_cast<size_t>(count) * static_cast<size_t>(byteStride);
    if (outBytes < expected) return -2;

    int status = -1;
    switch (mode) {
        case 0: // ATTRIBUTES
            status = meshopt_decodeVertexBuffer(out, count, byteStride, compressed, compressedLen);
            break;
        case 1: // TRIANGLES
            status = meshopt_decodeIndexBuffer(out, count, byteStride, compressed, compressedLen);
            break;
        case 2: // INDICES
            status = meshopt_decodeIndexSequence(out, count, byteStride, compressed, compressedLen);
            break;
        default:
            return -3;
    }
    if (status != 0) return status;

    switch (filter) {
        case 0: meshopt_decodeFilterOct(out, count, byteStride);  break;
        case 1: meshopt_decodeFilterQuat(out, count, byteStride); break;
        case 2: meshopt_decodeFilterExp(out, count, byteStride);  break;
        default: break;
    }
    return 0;
}

} // extern "C"
