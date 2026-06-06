package earth.worldwind.layer.cache

import earth.worldwind.formats.dted.DTED
import earth.worldwind.formats.geotiff.GeoTiffReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Float32Array
import org.khronos.webgl.Int16Array
import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get
import org.khronos.webgl.set

/**
 * JS-side wire-format decoder that produces [ElevationTileBuffer] for the cross-platform
 * cache transcoder. Mirrors the format-dispatch in
 * `TiledElevationCoverage.decodeBytesByFormat` but preserves the source precision —
 * Float32 sources come back as [ElevationTileBuffer.Floats], Int16 sources as
 * [ElevationTileBuffer.Shorts] — so the codec's Float→Int packing path can run without
 * a lossy intermediate.
 */
internal object WebElevationNetworkDecoder : NetworkBytesDecoder {
    override suspend fun decodeNetworkBytes(bytes: ByteArray, contentType: String): ElevationTileBuffer? =
        withContext(Dispatchers.Default) {
            val arrayBuffer = bytes.toArrayBuffer()
            when {
                contentType.equals("image/bil", true) ||
                    contentType.equals("application/bil", true) ||
                    contentType.equals("application/bil16", true) ->
                    ElevationTileBuffer.Shorts(int16ArrayToShortArray(Int16Array(arrayBuffer)))
                contentType.equals("application/bil32", true) ->
                    ElevationTileBuffer.Floats(float32ArrayToFloatArray(Float32Array(arrayBuffer)))
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

    private fun ByteArray.toArrayBuffer(): ArrayBuffer {
        val u8 = Uint8Array(size)
        for (i in indices) u8[i] = this[i]
        return u8.buffer
    }

    private fun int16ArrayToShortArray(view: Int16Array): ShortArray =
        ShortArray(view.length) { view[it] }

    private fun float32ArrayToFloatArray(view: Float32Array): FloatArray =
        FloatArray(view.length) { view[it] }
}
