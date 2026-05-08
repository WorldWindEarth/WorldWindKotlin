package earth.worldwind.formats.geotiff

import kotlin.experimental.and

/**
 * Random-access view over a byte buffer with explicit endian-aware getters. Mirrors the JS
 * `DataView` API surface used by [GeoTiffReader] / [TiffIFDEntry] / [GeoTiffUtil] so the
 * same parsing code compiles on every KMP target. Indices and read sizes are validated up
 * front; reads past the end throw [IndexOutOfBoundsException].
 */
class BinaryDataView(private val bytes: ByteArray) {
    val byteLength: Int get() = bytes.size

    fun getInt8(offset: Int): Byte = bytes[offset]

    fun getUint8(offset: Int): Int = bytes[offset].toInt() and 0xFF

    fun getInt16(offset: Int, littleEndian: Boolean): Short =
        if (littleEndian) {
            ((bytes[offset].toInt() and 0xFF) or ((bytes[offset + 1].toInt() and 0xFF) shl 8)).toShort()
        } else {
            (((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)).toShort()
        }

    fun getUint16(offset: Int, littleEndian: Boolean): Int = getInt16(offset, littleEndian).toInt() and 0xFFFF

    fun getInt32(offset: Int, littleEndian: Boolean): Int =
        if (littleEndian) {
            (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
        } else {
            ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
        }

    fun getUint32(offset: Int, littleEndian: Boolean): Long = getInt32(offset, littleEndian).toLong() and 0xFFFFFFFFL

    fun getFloat32(offset: Int, littleEndian: Boolean): Float = Float.fromBits(getInt32(offset, littleEndian))

    fun getFloat64(offset: Int, littleEndian: Boolean): Double {
        val low = getInt32(offset, littleEndian).toLong() and 0xFFFFFFFFL
        val high = getInt32(offset + 4, littleEndian).toLong() and 0xFFFFFFFFL
        val bits = if (littleEndian) (high shl 32) or low else (low shl 32) or high
        return Double.fromBits(bits)
    }

    fun setInt8(offset: Int, value: Byte) { bytes[offset] = value }

    fun setUint16(offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 1] = (value and 0xFF).toByte()
        }
    }

    fun setUint32(offset: Int, value: Int, littleEndian: Boolean) {
        if (littleEndian) {
            bytes[offset] = (value and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        } else {
            bytes[offset] = ((value ushr 24) and 0xFF).toByte()
            bytes[offset + 1] = ((value ushr 16) and 0xFF).toByte()
            bytes[offset + 2] = ((value ushr 8) and 0xFF).toByte()
            bytes[offset + 3] = (value and 0xFF).toByte()
        }
    }

    fun setFloat32(offset: Int, value: Float, littleEndian: Boolean) =
        setUint32(offset, value.toRawBits(), littleEndian)

    /** Underlying byte array (zero-copy reference). Callers receiving a writer-built
     *  buffer can use this to flush into an HTTP response / file / cache without copying. */
    fun asByteArray(): ByteArray = bytes
}
