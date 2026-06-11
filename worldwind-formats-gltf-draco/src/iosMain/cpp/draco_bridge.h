// C interface for Kotlin/Native cinterop. The implementation in draco_bridge.cpp
// (compiled as C++ with extern "C") wraps Google's libdraco decoder.
//
// Caller pattern:
//   handle = wwdraco_decode(bytes, len);          // produce handle
//   wwdraco_indices_count(handle);                // sizes
//   wwdraco_indices(handle, out, capacity);       // fill output buffer
//   ... per attribute ...
//   wwdraco_release(handle);                       // free

#ifndef WORLDWIND_DRACO_BRIDGE_H
#define WORLDWIND_DRACO_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct wwdraco_handle wwdraco_handle;

// Decode. Returns NULL on failure (malformed buffer, missing fields, etc.).
// colorsUniqueId hints at the Draco unique-id of the color attribute (PNTS
// `3DTILES_draco_point_compression.properties[RGB|RGBA]`). Pass -1 when there's no
// hint — the bridge falls back to COLOR semantic then a generic-attribute scan.
wwdraco_handle* wwdraco_decode(const uint8_t* bytes, size_t len, int32_t colorsUniqueId);

// Free a handle returned by wwdraco_decode.
void wwdraco_release(wwdraco_handle* handle);

// Sizes (elements, not bytes) of each attribute / index array. 0 if absent.
size_t wwdraco_indices_count(const wwdraco_handle*);
size_t wwdraco_positions_count(const wwdraco_handle*);
size_t wwdraco_texcoords_count(const wwdraco_handle*);
size_t wwdraco_colors_count(const wwdraco_handle*);

// Fill an output buffer. Returns number of elements written (clamped to `capacity`).
size_t wwdraco_get_indices(const wwdraco_handle*, int32_t* out, size_t capacity);
size_t wwdraco_get_positions(const wwdraco_handle*, float* out, size_t capacity);
size_t wwdraco_get_texcoords(const wwdraco_handle*, float* out, size_t capacity);
size_t wwdraco_get_colors(const wwdraco_handle*, float* out, size_t capacity);

#ifdef __cplusplus
}
#endif

#endif // WORLDWIND_DRACO_BRIDGE_H
