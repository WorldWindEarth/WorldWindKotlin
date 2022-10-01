package earth.worldwind.formats.tiff

import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import java.nio.ByteBuffer
import java.nio.ByteOrder

open class Tiff(
    /**
     * [ByteBuffer] facilitating the view of the underlying Tiff data buffer.
     */
    val buffer: ByteBuffer
) {
    /**
     * The [Subfile] contained within this Tiff.
     */
    private val _subfiles = mutableListOf<Subfile>()
    val subfiles get(): List<Subfile> {
        if (_subfiles.isEmpty()) {
            buffer.position(4)
            val ifdOffset = readLimitedDWord(buffer)
            parseSubfiles(ifdOffset)
        }
        return _subfiles
    }

    init {
        this.checkAndSetByteOrder()
    }

    protected open fun checkAndSetByteOrder() {
        // check byte order
        buffer.clear()
        val posOne = buffer.get().toInt().toChar()
        val posTwo = buffer.get().toInt().toChar()
        if (posOne == 'I' && posTwo == 'I') buffer.order(ByteOrder.LITTLE_ENDIAN)
        else if (posOne == 'M' && posTwo == 'M') buffer.order(ByteOrder.BIG_ENDIAN)
        else throw RuntimeException(
            logMessage(
                ERROR, "Tiff", "checkAndSetByteOrder", "Tiff byte order incompatible"
            )
        )

        // check the version
        val version = readWord(buffer)
        if (version != 42) throw RuntimeException(
            logMessage(
                ERROR, "Tiff", "checkAndSetByteOrder", "Tiff version incompatible"
            )
        )
    }

    protected open fun parseSubfiles(offset: Int) {
        buffer.position(offset)
        val ifd = Subfile(this, offset)
        _subfiles.add(ifd)

        // check if there are more IFDs
        val nextIfdOffset = readLimitedDWord(buffer)
        if (nextIfdOffset != 0) {
            buffer.position(nextIfdOffset)
            parseSubfiles(nextIfdOffset)
        }
    }

    companion object {
        /**
         * Tiff tags are the integer definitions of individual Image File Directories (IFDs) and set by the Tiff 6.0
         * specification. The tags defined here are a minimal set and not inclusive of the complete 6.0 specification.
         */
        const val NEW_SUBFILE_TYPE_TAG = 254
        const val IMAGE_WIDTH_TAG = 256
        const val IMAGE_LENGTH_TAG = 257
        const val BITS_PER_SAMPLE_TAG = 258
        const val COMPRESSION_TAG = 259
        const val PHOTOMETRIC_INTERPRETATION_TAG = 262
        const val SAMPLES_PER_PIXEL_TAG = 277
        const val X_RESOLUTION_TAG = 282
        const val Y_RESOLUTION_TAG = 283
        const val PLANAR_CONFIGURATION_TAG = 284
        const val RESOLUTION_UNIT_TAG = 296
        const val STRIP_OFFSETS_TAG = 273
        const val STRIP_BYTE_COUNTS_TAG = 279
        const val ROWS_PER_STRIP_TAG = 278
        const val COMPRESSION_PREDICTOR_TAG = 317
        const val TILE_OFFSETS_TAG = 324
        const val TILE_BYTE_COUNTS_TAG = 325
        const val TILE_WIDTH_TAG = 322
        const val TILE_LENGTH_TAG = 323
        const val SAMPLE_FORMAT_TAG = 339
        /**
         * Tiff sample formats
         */
        const val UNSIGNED_INT = 1
        const val TWOS_COMP_SIGNED_INT = 2
        const val FLOATING_POINT = 3
        const val UNDEFINED = 4

        internal fun readWord(buffer: ByteBuffer) = buffer.short.toInt() and 0xFFFF

        internal fun readDWord(buffer: ByteBuffer) = buffer.int.toLong() and 0xFFFFFFFFL

        internal fun readLimitedDWord(buffer: ByteBuffer): Int {
            val value = readDWord(buffer)
            require(value <= Int.MAX_VALUE) {
                logMessage(
                    ERROR, "Tiff", "readLimitedDWord", "value exceeds signed integer range"
                )
            }
            return value.toInt()
        }
    }
}