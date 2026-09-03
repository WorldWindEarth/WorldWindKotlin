package earth.worldwind.formats.geotiff

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage

/**
 * Header + Image File Directory parser for classic TIFF **and** BigTIFF, reading through a
 * [TiffDataSource] so only tag data is touched at open time — the raster stays on disk.
 *
 * BigTIFF (magic 43) matters here because it's what `gdal_translate` emits once a raster
 * passes 4 GB, exactly the size class that makes a tiled pyramid worth having: 8-byte
 * offsets, 8-byte counts, and 20-byte directory entries instead of 4/4/12.
 */
internal class TiffStructure private constructor(
    private val source: TiffDataSource,
    private val isLittleEndian: Boolean,
    private val isBigTiff: Boolean,
) {
    /** Bytes an inline entry value may occupy before it is stored out-of-line. */
    private val inlineSize get() = if (isBigTiff) 8 else 4

    private class Entry(val tag: Int, val type: Int, val count: Long, val valueOffset: Long, val inline: ByteArray?)

    /** Decoded values of one entry as signed 64-bit integers (offsets, counts, enums). */
    private fun longs(entry: Entry): LongArray {
        val view = valueView(entry) ?: return LongArray(0)
        val n = entry.count.toInt()
        val unit = typeSize(entry.type)
        return LongArray(n) { i ->
            val at = i * unit
            when (entry.type) {
                TiffConstants.Type.BYTE, TiffConstants.Type.ASCII, TiffConstants.Type.UNDEFINED -> view.getUint8(at).toLong()
                TiffConstants.Type.SBYTE -> view.getInt8(at).toLong()
                TiffConstants.Type.SHORT -> view.getUint16(at, isLittleEndian).toLong()
                TiffConstants.Type.SSHORT -> view.getInt16(at, isLittleEndian).toLong()
                TiffConstants.Type.LONG, TYPE_IFD -> view.getUint32(at, isLittleEndian)
                TiffConstants.Type.SLONG -> view.getInt32(at, isLittleEndian).toLong()
                TiffConstants.Type.FLOAT -> view.getFloat32(at, isLittleEndian).toLong()
                TiffConstants.Type.DOUBLE -> view.getFloat64(at, isLittleEndian).toLong()
                TiffConstants.Type.RATIONAL -> {
                    val den = view.getUint32(at + 4, isLittleEndian)
                    if (den == 0L) 0L else view.getUint32(at, isLittleEndian) / den
                }
                TiffConstants.Type.SRATIONAL -> {
                    val den = view.getInt32(at + 4, isLittleEndian)
                    if (den == 0) 0L else (view.getInt32(at, isLittleEndian) / den).toLong()
                }
                TYPE_LONG8, TYPE_IFD8, TYPE_SLONG8 -> view.getInt64(at, isLittleEndian)
                else -> 0L
            }
        }
    }

    /** Decoded values of one entry as doubles (pixel scales, tie points, transformations). */
    private fun doubles(entry: Entry): DoubleArray {
        val view = valueView(entry) ?: return DoubleArray(0)
        val n = entry.count.toInt()
        val unit = typeSize(entry.type)
        return DoubleArray(n) { i ->
            val at = i * unit
            when (entry.type) {
                TiffConstants.Type.DOUBLE -> view.getFloat64(at, isLittleEndian)
                TiffConstants.Type.FLOAT -> view.getFloat32(at, isLittleEndian).toDouble()
                TiffConstants.Type.RATIONAL -> {
                    val den = view.getUint32(at + 4, isLittleEndian)
                    if (den == 0L) 0.0 else view.getUint32(at, isLittleEndian).toDouble() / den
                }
                TiffConstants.Type.SRATIONAL -> {
                    val den = view.getInt32(at + 4, isLittleEndian)
                    if (den == 0) 0.0 else view.getInt32(at, isLittleEndian).toDouble() / den
                }
                else -> longs(entry).getOrElse(i) { 0L }.toDouble()
            }
        }
    }

    private fun ascii(entry: Entry): String {
        val view = valueView(entry) ?: return ""
        val n = entry.count.toInt()
        val sb = StringBuilder(n)
        for (i in 0 until n) {
            val c = view.getUint8(i)
            if (c == 0) continue // NUL terminators separate the concatenated GeoAscii strings
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    /** Bytes backing an entry's value — inline for short values, a ranged read otherwise.
     *  Returns `null` for an implausible count so a corrupt tag can't drive a huge read. */
    private fun valueView(entry: Entry): BinaryDataView? {
        val unit = typeSize(entry.type)
        if (unit <= 0) return null
        val total = entry.count * unit
        if (entry.count <= 0 || total > MAX_VALUE_BYTES) return null
        entry.inline?.let { return BinaryDataView(it) }
        val bytes = source.read(entry.valueOffset, total.toInt())
        return if (bytes.size.toLong() < total) null else BinaryDataView(bytes)
    }

    private fun firstLong(entries: Map<Int, Entry>, tag: Int, fallback: Long) =
        entries[tag]?.let { longs(it).firstOrNull() } ?: fallback

    private fun firstInt(entries: Map<Int, Entry>, tag: Int, fallback: Int) =
        firstLong(entries, tag, fallback.toLong()).toInt()

    private fun intArray(entries: Map<Int, Entry>, tag: Int) =
        entries[tag]?.let { e -> longs(e).let { arr -> IntArray(arr.size) { arr[it].toInt() } } } ?: IntArray(0)

    private fun longArray(entries: Map<Int, Entry>, tag: Int) = entries[tag]?.let { longs(it) } ?: LongArray(0)

    private fun doubleArray(entries: Map<Int, Entry>, tag: Int) = entries[tag]?.let { doubles(it) } ?: DoubleArray(0)

    /** Parse the IFD at [offset] into a [TiffDirectory], returning it with the next IFD's offset. */
    private fun readDirectory(offset: Long): Pair<TiffDirectory, Long>? {
        val headerSize = if (isBigTiff) 8 else 2
        val entrySize = if (isBigTiff) 20 else 12
        val offsetSize = if (isBigTiff) 8 else 4
        val head = source.read(offset, headerSize)
        if (head.size < headerSize) return null
        val headView = BinaryDataView(head)
        val entryCount = if (isBigTiff) headView.getInt64(0, isLittleEndian) else
            headView.getUint16(0, isLittleEndian).toLong()
        if (entryCount <= 0 || entryCount > MAX_ENTRIES) return null
        val bodySize = entryCount.toInt() * entrySize + offsetSize
        val body = source.read(offset + headerSize, bodySize)
        if (body.size < bodySize) return null
        val view = BinaryDataView(body)

        val entries = HashMap<Int, Entry>(entryCount.toInt())
        for (i in 0 until entryCount.toInt()) {
            val at = i * entrySize
            val tag = view.getUint16(at, isLittleEndian)
            val type = view.getUint16(at + 2, isLittleEndian)
            val count = if (isBigTiff) view.getInt64(at + 4, isLittleEndian) else view.getUint32(at + 4, isLittleEndian)
            val valueAt = at + if (isBigTiff) 12 else 8
            val unit = typeSize(type)
            val total = if (unit <= 0) Long.MAX_VALUE else count * unit
            val entry = if (total <= inlineSize) {
                Entry(tag, type, count, 0L, body.copyOfRange(valueAt, valueAt + inlineSize))
            } else {
                val valueOffset = if (isBigTiff) view.getInt64(valueAt, isLittleEndian)
                else view.getUint32(valueAt, isLittleEndian)
                Entry(tag, type, count, valueOffset, null)
            }
            entries[tag] = entry
        }
        val nextOffset = view.let {
            val at = entryCount.toInt() * entrySize
            if (isBigTiff) it.getInt64(at, isLittleEndian) else it.getUint32(at, isLittleEndian)
        }

        val imageWidth = firstInt(entries, TiffConstants.IFDTag.IMAGE_WIDTH, 0)
        val imageHeight = firstInt(entries, TiffConstants.IFDTag.IMAGE_LENGTH, 0)
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val samplesPerPixel = firstInt(entries, TiffConstants.IFDTag.SAMPLES_PER_PIXEL, 1).coerceAtLeast(1)
        val bitsPerSample = intArray(entries, TiffConstants.IFDTag.BITS_PER_SAMPLE)
            .takeIf { it.isNotEmpty() } ?: IntArray(samplesPerPixel) { 8 }
        val sampleFormat = intArray(entries, TiffConstants.IFDTag.SAMPLE_FORMAT)
            .takeIf { it.isNotEmpty() } ?: IntArray(samplesPerPixel) { TiffConstants.SampleFormat.UNSIGNED }
        val tileWidth = firstInt(entries, TiffConstants.IFDTag.TILE_WIDTH, 0)
        val tileHeight = firstInt(entries, TiffConstants.IFDTag.TILE_LENGTH, 0)
        val isTiled = tileWidth > 0 && tileHeight > 0 && entries.containsKey(TiffConstants.IFDTag.TILE_OFFSETS)
        // RowsPerStrip defaults to "all rows in one strip" (2^32-1 in the spec).
        val rowsPerStrip = firstLong(entries, TiffConstants.IFDTag.ROWS_PER_STRIP, imageHeight.toLong())
            .coerceIn(1L, imageHeight.toLong()).toInt()
        val blockOffsets = longArray(entries, if (isTiled) TiffConstants.IFDTag.TILE_OFFSETS else TiffConstants.IFDTag.STRIP_OFFSETS)
        val blockCounts = longArray(entries, if (isTiled) TiffConstants.IFDTag.TILE_BYTE_COUNTS else TiffConstants.IFDTag.STRIP_BYTE_COUNTS)
        if (blockOffsets.isEmpty()) return null

        val noDataEntry = entries[GeoTiffConstants.GDAL_NODATA]
        val noData = noDataEntry?.let {
            if (it.type == TiffConstants.Type.ASCII) ascii(it).trim().toDoubleOrNull()
            else doubles(it).firstOrNull()
        }

        val directory = TiffDirectory(
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            blockWidth = if (isTiled) tileWidth else imageWidth,
            blockHeight = if (isTiled) tileHeight else rowsPerStrip,
            isTiled = isTiled,
            blockOffsets = blockOffsets,
            blockByteCounts = blockCounts,
            samplesPerPixel = samplesPerPixel,
            bitsPerSample = bitsPerSample,
            sampleFormat = sampleFormat,
            compression = firstInt(entries, TiffConstants.IFDTag.COMPRESSION, TiffConstants.Compression.UNCOMPRESSED),
            predictor = firstInt(entries, TiffConstants.IFDTag.PREDICTOR, 1),
            photometricInterpretation = firstInt(
                entries, TiffConstants.IFDTag.PHOTOMETRIC_INTERPRETATION,
                TiffConstants.PhotometricInterpretation.BLACK_IS_ZERO
            ),
            planarConfiguration = firstInt(entries, TiffConstants.IFDTag.PLANAR_CONFIGURATION, TiffConstants.PlanarConfiguration.CHUNKY),
            colorMap = intArray(entries, TiffConstants.IFDTag.COLOR_MAP).takeIf { it.isNotEmpty() },
            extraSamples = intArray(entries, TiffConstants.IFDTag.EXTRA_SAMPLES),
            noData = noData,
            subfileType = firstInt(entries, TiffConstants.IFDTag.NEW_SUBFILE_TYPE, 0),
            modelPixelScale = doubleArray(entries, GeoTiffConstants.MODEL_PIXEL_SCALE),
            modelTiepoint = doubleArray(entries, GeoTiffConstants.MODEL_TIEPOINT),
            modelTransformation = doubleArray(entries, GeoTiffConstants.MODEL_TRANSFORMATION),
            geoKeyDirectory = intArray(entries, GeoTiffConstants.GEO_KEY_DIRECTORY),
            geoDoubleParams = doubleArray(entries, GeoTiffConstants.GEO_DOUBLE_PARAMS),
            geoAsciiParams = entries[GeoTiffConstants.GEO_ASCII_PARAMS]?.let { ascii(it) } ?: "",
        )
        return directory to nextOffset
    }

    private fun readAllDirectories(firstOffset: Long): List<TiffDirectory> {
        val result = mutableListOf<TiffDirectory>()
        val visited = HashSet<Long>()
        var offset = firstOffset
        while (offset > 0L && offset < source.size && visited.add(offset) && result.size < MAX_DIRECTORIES) {
            val (directory, next) = readDirectory(offset) ?: break
            result.add(directory)
            offset = next
        }
        if (result.isEmpty()) log(WARN, "GeoTIFF contains no readable image file directory")
        return result
    }

    companion object {
        // BigTIFF-only field types, absent from the classic-TIFF TiffConstants.Type table.
        private const val TYPE_IFD = 13
        private const val TYPE_LONG8 = 16
        private const val TYPE_SLONG8 = 17
        private const val TYPE_IFD8 = 18
        /** Tag values larger than this are treated as corrupt rather than read. */
        private const val MAX_VALUE_BYTES = 64L * 1024 * 1024
        private const val MAX_ENTRIES = 4096L
        /** Overview ladders are a handful of IFDs deep; anything longer is a malformed chain. */
        private const val MAX_DIRECTORIES = 128

        private fun typeSize(type: Int) = when (type) {
            TiffConstants.Type.BYTE, TiffConstants.Type.ASCII,
            TiffConstants.Type.SBYTE, TiffConstants.Type.UNDEFINED -> 1
            TiffConstants.Type.SHORT, TiffConstants.Type.SSHORT -> 2
            TiffConstants.Type.LONG, TiffConstants.Type.SLONG, TiffConstants.Type.FLOAT, TYPE_IFD -> 4
            TiffConstants.Type.RATIONAL, TiffConstants.Type.SRATIONAL, TiffConstants.Type.DOUBLE,
            TYPE_LONG8, TYPE_SLONG8, TYPE_IFD8 -> 8
            else -> -1
        }

        /** Parse [source]'s header and full IFD chain. Throws when the bytes aren't a TIFF at all. */
        fun read(source: TiffDataSource): TiffLayout {
            val header = source.read(0, 16)
            require(header.size >= 8) {
                logMessage(ERROR, "TiffStructure", "read", "File is too short to be a TIFF")
            }
            val view = BinaryDataView(header)
            val isLittleEndian = when (view.getUint16(0, false)) {
                0x4949 -> true
                0x4D4D -> false
                else -> error(logMessage(ERROR, "TiffStructure", "read", "Invalid TIFF byte order"))
            }
            val magic = view.getUint16(2, isLittleEndian)
            val isBigTiff = when (magic) {
                42 -> false
                43 -> true
                else -> error(logMessage(ERROR, "TiffStructure", "read", "Invalid TIFF magic number $magic"))
            }
            val firstOffset = if (isBigTiff) {
                require(header.size >= 16 && view.getUint16(4, isLittleEndian) == 8) {
                    logMessage(ERROR, "TiffStructure", "read", "Unsupported BigTIFF offset size")
                }
                view.getInt64(8, isLittleEndian)
            } else view.getUint32(4, isLittleEndian)
            val directories = TiffStructure(source, isLittleEndian, isBigTiff).readAllDirectories(firstOffset)
            return TiffLayout(directories, isLittleEndian)
        }
    }
}

/** Parsed container layout: the IFD ladder plus the byte order the block decoder unpacks samples with. */
internal class TiffLayout(val directories: List<TiffDirectory>, val isLittleEndian: Boolean)
