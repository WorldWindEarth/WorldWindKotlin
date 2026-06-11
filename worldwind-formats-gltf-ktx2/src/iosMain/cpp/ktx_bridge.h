#ifndef WORLDWIND_KTX2_BRIDGE_H
#define WORLDWIND_KTX2_BRIDGE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct wwktx_handle wwktx_handle;

// Decode a KTX2 / Basis Universal container into an RGBA8 image. Returns NULL on failure.
wwktx_handle* wwktx_decode(const uint8_t* bytes, size_t len);

void wwktx_release(wwktx_handle*);

int32_t wwktx_width(const wwktx_handle*);
int32_t wwktx_height(const wwktx_handle*);

// Copy the decoded RGBA bytes into [out]. Returns the number of bytes written
// (clamped to [capacity]).
size_t wwktx_copy_rgba(const wwktx_handle*, uint8_t* out, size_t capacity);

#ifdef __cplusplus
}
#endif

#endif
