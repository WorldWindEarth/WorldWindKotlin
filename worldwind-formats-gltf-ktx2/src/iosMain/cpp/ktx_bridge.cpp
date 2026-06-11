#include "ktx_bridge.h"

#include <cstring>
#include <memory>
#include <vector>

#include "ktx.h"

struct wwktx_handle {
    std::vector<uint8_t> rgba;
    int32_t width;
    int32_t height;
};

extern "C" {

wwktx_handle* wwktx_decode(const uint8_t* bytes, size_t len) {
    if (bytes == nullptr || len == 0) return nullptr;
    ktxTexture2* texture = nullptr;
    KTX_error_code result = ktxTexture2_CreateFromMemory(
        bytes, len, KTX_TEXTURE_CREATE_LOAD_IMAGE_DATA_BIT, &texture);
    if (result != KTX_SUCCESS || texture == nullptr) return nullptr;
    if (ktxTexture2_NeedsTranscoding(texture)) {
        result = ktxTexture2_TranscodeBasis(texture, KTX_TTF_RGBA32, 0);
        if (result != KTX_SUCCESS) {
            ktxTexture_Destroy(ktxTexture(texture));
            return nullptr;
        }
    }
    auto h = std::make_unique<wwktx_handle>();
    h->width = static_cast<int32_t>(texture->baseWidth);
    h->height = static_cast<int32_t>(texture->baseHeight);
    ktx_size_t imageSize = ktxTexture_GetImageSize(ktxTexture(texture), 0);
    ktx_size_t imageOffset = 0;
    if (ktxTexture_GetImageOffset(ktxTexture(texture), 0, 0, 0, &imageOffset) != KTX_SUCCESS) {
        ktxTexture_Destroy(ktxTexture(texture));
        return nullptr;
    }
    h->rgba.assign(texture->pData + imageOffset, texture->pData + imageOffset + imageSize);
    ktxTexture_Destroy(ktxTexture(texture));
    return h.release();
}

void wwktx_release(wwktx_handle* h) { delete h; }

int32_t wwktx_width(const wwktx_handle* h)  { return h ? h->width  : 0; }
int32_t wwktx_height(const wwktx_handle* h) { return h ? h->height : 0; }

size_t wwktx_copy_rgba(const wwktx_handle* h, uint8_t* out, size_t capacity) {
    if (h == nullptr || out == nullptr || capacity == 0) return 0;
    const size_t n = h->rgba.size() < capacity ? h->rgba.size() : capacity;
    std::memcpy(out, h->rgba.data(), n);
    return n;
}

} // extern "C"
