package earth.worldwind.formats.shapefile

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.geom.Angle
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.WARN

/**
 * Parser for an ESRI shapefile (`.shp`). Reads the entire input eagerly into a
 * [List]<[ShapefileRecord]> at construction time; iteration is then in-memory and
 * allocation-free. Mirrors the behavior of WebWorldWind's `Shapefile` class.
 *
 * Optional sidecar files can be supplied:
 *  * [projection] — `.prj` content; only the geographic-vs-projected distinction is used,
 *    matching the reference. Projected shapefiles are not supported (see [PrjFile]).
 *  * [attributes] — `.dbf` content; rows are matched to records by their 1-based index.
 *
 * Spec: ESRI Shapefile Technical Description (July 1998), pages 4–5 (file header /
 * record headers), pages 5–13 (per-shape-type record contents).
 */
class Shapefile(
    shpBytes: ByteArray,
    val projection: PrjFile? = null,
    val attributes: DBaseFile? = null,
) {
    val shapeType: ShapefileShapeType
    val version: Int
    /** File length in bytes (the on-disk header value is in 16-bit words; doubled here). */
    val fileLength: Int
    /** File bounding rectangle as `[minY, maxY, minX, maxX]` (latitude, longitude for geographic shapefiles). */
    val boundingRectangle: DoubleArray
    val records: List<ShapefileRecord>

    init {
        val view = BinaryDataView(shpBytes)
        if (view.byteLength < HEADER_LENGTH) {
            error("Shapefile header is truncated (need $HEADER_LENGTH bytes, have ${view.byteLength})")
        }

        // ---- 100-byte file header ----------------------------------------------------
        // First six big-endian Int32s: file code, 5 unused.
        val fileCode = view.getInt32(0, littleEndian = false)
        if (fileCode != FILE_CODE) {
            error("Shapefile header is invalid: file code 0x${fileCode.toString(16)} != 0x${FILE_CODE.toString(16)}")
        }
        // Skip indices 1..5 (unused). File length at offset 24, big-endian, in 16-bit words.
        fileLength = view.getInt32(24, littleEndian = false) * 2

        // Remaining header is little-endian.
        version = view.getInt32(28, littleEndian = true)
        val typeCode = view.getInt32(32, littleEndian = true)
        shapeType = ShapefileShapeType.fromCode(typeCode)
            ?: error("Shapefile type is unsupported: $typeCode")

        // Bounding rectangle order on disk: minX, minY, maxX, maxY (offsets 36..67).
        val rect = readBoundingRectangleAt(view, 36)
        boundingRectangle = rect

        // Offsets 68..83 are Z range, 84..99 are M range (both ignored at file level).

        // ---- Records ----------------------------------------------------------------
        val parsed = mutableListOf<ShapefileRecord>()
        var offset = HEADER_LENGTH
        val limit = view.byteLength
        // `fileLength` reflects what the writer thought; trust whichever ends sooner.
        val end = if (fileLength in HEADER_LENGTH..limit) fileLength else limit

        while (offset <= end - RECORD_HEADER_LENGTH) {
            // Record header: big-endian. recordNumber + contentLength (in 16-bit words).
            val recordNumber = view.getInt32(offset, littleEndian = false)
            val contentLengthWords = view.getInt32(offset + 4, littleEndian = false)
            val contentLengthBytes = contentLengthWords * 2

            val contentStart = offset + RECORD_HEADER_LENGTH
            val contentEnd = contentStart + contentLengthBytes
            if (contentEnd > end) {
                // Truncated record — stop here rather than reading past the end.
                Logger.log(WARN, "Shapefile record $recordNumber extends past file end; truncating")
                break
            }

            val record = readRecord(view, contentStart, contentEnd, recordNumber)
            if (record != null && !record.isNullRecord) {
                parsed.add(record)
            }
            offset = contentEnd
        }

        // ---- DBF attribute join -----------------------------------------------------
        if (attributes != null) {
            val rows = attributes.records
            for (record in parsed) {
                val idx = record.recordNumber - 1
                if (idx in rows.indices) {
                    record.attributes = rows[idx].values
                }
            }
        }

        records = parsed
    }

    private fun readRecord(view: BinaryDataView, start: Int, end: Int, recordNumber: Int): ShapefileRecord? {
        // Per-record shape type lives in the contents, little-endian.
        if (end - start < INT32_SIZE) return null
        val typeCode = view.getInt32(start, littleEndian = true)
        val type = ShapefileShapeType.fromCode(typeCode) ?: run {
            Logger.log(WARN, "Skipping shapefile record $recordNumber with unsupported type $typeCode")
            return null
        }
        // Per spec, every record matches the file's shape type or is NULL.
        if (type != ShapefileShapeType.NULL && type != shapeType) {
            Logger.log(WARN, "Shapefile record $recordNumber type $type does not match file type $shapeType")
            return null
        }

        val contentStart = start + INT32_SIZE
        return when (type) {
            ShapefileShapeType.NULL ->
                ShapefileRecord(type, recordNumber, parts = emptyList(), boundingRectangle = null)

            ShapefileShapeType.POINT, ShapefileShapeType.POINT_Z, ShapefileShapeType.POINT_M ->
                readPointRecord(view, contentStart, end, recordNumber)

            ShapefileShapeType.MULTI_POINT, ShapefileShapeType.MULTI_POINT_Z, ShapefileShapeType.MULTI_POINT_M ->
                readMultiPointRecord(view, contentStart, end, recordNumber)

            ShapefileShapeType.POLYLINE, ShapefileShapeType.POLYLINE_Z, ShapefileShapeType.POLYLINE_M,
            ShapefileShapeType.POLYGON, ShapefileShapeType.POLYGON_Z, ShapefileShapeType.POLYGON_M ->
                readPolyRecord(view, contentStart, end, recordNumber)
        }
    }

    private fun readPointRecord(view: BinaryDataView, start: Int, end: Int, recordNumber: Int): ShapefileRecord {
        val x = view.getFloat64(start, littleEndian = true)
        val y = view.getFloat64(start + DOUBLE_SIZE, littleEndian = true)
        val part = doubleArrayOf(x, y)
        val bbox = doubleArrayOf(y, y, x, x)

        var pos = start + 2 * DOUBLE_SIZE
        var zRange: DoubleArray? = null
        var zValues: DoubleArray? = null
        if (shapeType.isZ) {
            val z = view.getFloat64(pos, littleEndian = true)
            zRange = doubleArrayOf(z, z)
            zValues = doubleArrayOf(z)
            pos += DOUBLE_SIZE
        }
        var mRange: DoubleArray? = null
        var mValues: DoubleArray? = null
        if (shapeType.isMeasure && pos + DOUBLE_SIZE <= end) {
            val m = view.getFloat64(pos, littleEndian = true)
            mRange = doubleArrayOf(m, m)
            mValues = doubleArrayOf(m)
        }

        return ShapefileRecord(
            shapeType, recordNumber,
            parts = listOf(part),
            boundingRectangle = bbox,
            zRange = zRange, zValues = zValues, mRange = mRange, mValues = mValues
        )
    }

    private fun readMultiPointRecord(view: BinaryDataView, start: Int, end: Int, recordNumber: Int): ShapefileRecord {
        val bbox = readBoundingRectangleAt(view, start)
        var pos = start + 4 * DOUBLE_SIZE

        val numPoints = view.getInt32(pos, littleEndian = true)
        pos += INT32_SIZE

        val xy = readDoubleArray(view, pos, numPoints * 2)
        normalizeLocations(xy)
        pos += numPoints * 2 * DOUBLE_SIZE

        val (zRange, zValues, mRange, mValues) = readOptionalZAndM(view, pos, end, numPoints, isPoint = false)

        return ShapefileRecord(
            shapeType, recordNumber,
            parts = listOf(xy),
            boundingRectangle = bbox,
            zRange = zRange, zValues = zValues, mRange = mRange, mValues = mValues
        )
    }

    private fun readPolyRecord(view: BinaryDataView, start: Int, end: Int, recordNumber: Int): ShapefileRecord {
        val bbox = readBoundingRectangleAt(view, start)
        var pos = start + 4 * DOUBLE_SIZE

        val numParts = view.getInt32(pos, littleEndian = true); pos += INT32_SIZE
        val numPoints = view.getInt32(pos, littleEndian = true); pos += INT32_SIZE

        val parts = mutableListOf<DoubleArray>()
        if (numParts > 0 && numPoints > 0) {
            val partOffsets = IntArray(numParts)
            for (i in 0 until numParts) {
                partOffsets[i] = view.getInt32(pos, littleEndian = true)
                pos += INT32_SIZE
            }
            for (i in 0 until numParts) {
                val start0 = partOffsets[i]
                val end0 = if (i == numParts - 1) numPoints else partOffsets[i + 1]
                val count = end0 - start0
                val xy = readDoubleArray(view, pos, count * 2)
                normalizeLocations(xy)
                parts.add(xy)
                pos += count * 2 * DOUBLE_SIZE
            }
        }

        val (zRange, zValues, mRange, mValues) = readOptionalZAndM(view, pos, end, numPoints, isPoint = false)

        return ShapefileRecord(
            shapeType, recordNumber,
            parts = parts,
            boundingRectangle = bbox,
            zRange = zRange, zValues = zValues, mRange = mRange, mValues = mValues
        )
    }

    private data class ZAndM(
        val zRange: DoubleArray?, val zValues: DoubleArray?,
        val mRange: DoubleArray?, val mValues: DoubleArray?,
    )

    private fun readOptionalZAndM(
        view: BinaryDataView, startPos: Int, end: Int, numPoints: Int, isPoint: Boolean,
    ): ZAndM {
        var pos = startPos
        var zRange: DoubleArray? = null
        var zValues: DoubleArray? = null
        if (shapeType.isZ) {
            // 2 doubles range + N doubles values.
            val needed = (2 + numPoints) * DOUBLE_SIZE
            if (pos + needed <= end) {
                zRange = readDoubleArray(view, pos, 2); pos += 2 * DOUBLE_SIZE
                zValues = readDoubleArray(view, pos, numPoints); pos += numPoints * DOUBLE_SIZE
            }
        }

        var mRange: DoubleArray? = null
        var mValues: DoubleArray? = null
        if (shapeType.isMeasure) {
            // Measures are optional even for *_M / *_Z files. WebWW checks remaining size.
            val needed = (2 + numPoints) * DOUBLE_SIZE
            if (pos + needed <= end) {
                mRange = readDoubleArray(view, pos, 2); pos += 2 * DOUBLE_SIZE
                mValues = readDoubleArray(view, pos, numPoints)
            }
        }
        return ZAndM(zRange, zValues, mRange, mValues)
    }

    /** Reads a bbox stored as `minX, minY, maxX, maxY` and returns `[minY, maxY, minX, maxX]`. */
    private fun readBoundingRectangleAt(view: BinaryDataView, offset: Int): DoubleArray {
        val minX = view.getFloat64(offset, littleEndian = true)
        val minY = view.getFloat64(offset + DOUBLE_SIZE, littleEndian = true)
        val maxX = view.getFloat64(offset + 2 * DOUBLE_SIZE, littleEndian = true)
        val maxY = view.getFloat64(offset + 3 * DOUBLE_SIZE, littleEndian = true)
        return doubleArrayOf(minY, maxY, minX, maxX)
    }

    private fun readDoubleArray(view: BinaryDataView, offset: Int, count: Int): DoubleArray {
        val out = DoubleArray(count)
        for (i in 0 until count) out[i] = view.getFloat64(offset + i * DOUBLE_SIZE, littleEndian = true)
        return out
    }

    /**
     * Wrap longitudes/latitudes back into `[-180, 180]` / `[-90, 90]`. Shapefile coords
     * are stored as longitude, latitude; this matches WebWorldWind's
     * `ShapefileRecord.normalizeLocations`.
     */
    private fun normalizeLocations(interleavedXY: DoubleArray) {
        var i = 0
        while (i + 1 < interleavedXY.size) {
            interleavedXY[i] = Angle.normalizeLongitude(interleavedXY[i])
            interleavedXY[i + 1] = Angle.normalizeLatitude(interleavedXY[i + 1])
            i += 2
        }
    }

    companion object {
        /** ESRI shapefile magic number ("9994" in big-endian). */
        const val FILE_CODE: Int = 0x0000270A
        const val HEADER_LENGTH: Int = 100
        const val RECORD_HEADER_LENGTH: Int = 8
        private const val INT32_SIZE = 4
        private const val DOUBLE_SIZE = 8
    }
}
