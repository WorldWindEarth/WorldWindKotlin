#ifndef WORLDWIND_MESHOPT_BRIDGE_H
#define WORLDWIND_MESHOPT_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

// Mode + filter codes mirror MeshoptDecoder.Mode / MeshoptDecoder.Filter in Kotlin.
// MODE_TRIANGLES = 1; MODE_INDICES = 2; FILTER_NONE = -1.

// Decode + optional filter in one call. Writes count*byteStride bytes into [out];
// caller is responsible for allocating that buffer. Returns 0 on success.
int wwmeshopt_decode(
    uint8_t* out, size_t outBytes,
    const uint8_t* compressed, size_t compressedLen,
    int32_t count, int32_t byteStride, int32_t mode, int32_t filter);

#ifdef __cplusplus
}
#endif

#endif
