package earth.worldwind.layer.cache

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.geotiff.GeoTiffReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * iOS-side wire-format decoder that produces [ElevationTileBuffer] for the cross-platform
 * cache transcoder. Mirrors the format dispatch in
 * `TiledElevationCoverage.decodeBytes` but preserves source precision — Float32 sources
 * come back as [ElevationTileBuffer.Floats] so the codec's Float→Int packing path can run
 * without an intermediate `ShortArray` rounding step.
 */
internal object IosElevationNetworkDecoder : NetworkBytesDecoder {
    override suspend fun decodeNetworkBytes(bytes: ByteArray, contentType: String): ElevationTileBuffer? =
        withContext(Dispatchers.Default) {
            when {
                contentType.equals("image/bil", true) ||
                    contentType.equals("application/bil", true) ||
                    contentType.equals("application/bil16", true) ->
                    ElevationTileBuffer.Shorts(bilToShortArray(bytes))
                contentType.equals("application/bil32", true) ->
                    ElevationTileBuffer.Floats(bil32ToFloatArray(bytes))
                contentType.equals("image/tiff", true) ||
                    contentType.equals("image/geotiff", true) ||
                    contentType.equals("application/geotiff", true) ||
                    contentType.equals("geotiff", true) ||
                    contentType.equals("tiff", true) ->
                    ElevationTileBuffer.Floats(GeoTiffReader(bytes).createElevationFloatArray())
                contentType.equals("application/dted", true) ||
                    contentType.equals("application/dted0", true) ||
                    contentType.equals("application/dted1", true) ||
                    contentType.equals("application/dted2", true) ->
                    ElevationTileBuffer.Shorts(DTED(bytes).elevations)
                else -> null
            }
        }

    private fun bilToShortArray(bytes: ByteArray): ShortArray {
        val count = bytes.size / 2
        val out = ShortArray(count)
        var i = 0
        var b = 0
        while (i < count) {
            val lo = bytes[b].toInt() and 0xFF
            val hi = bytes[b + 1].toInt() and 0xFF
            out[i] = ((hi shl 8) or lo).toShort()
            i++; b += 2
        }
        return out
    }

    private fun bil32ToFloatArray(bytes: ByteArray): FloatArray {
        val count = bytes.size / 4
        val out = FloatArray(count)
        var i = 0
        var b = 0
        while (i < count) {
            val raw = (bytes[b].toInt() and 0xFF) or
                ((bytes[b + 1].toInt() and 0xFF) shl 8) or
                ((bytes[b + 2].toInt() and 0xFF) shl 16) or
                ((bytes[b + 3].toInt() and 0xFF) shl 24)
            out[i] = Float.fromBits(raw)
            i++; b += 4
        }
        return out
    }
}
