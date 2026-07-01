package earth.worldwind.formats.las

import earth.worldwind.formats.BinaryDataView
import earth.worldwind.util.Logger
import earth.worldwind.util.Logger.logMessage

/**
 * Decoded standalone point cloud from a LAS/LAZ file. Positions are in the file's native CRS
 * (see [crs]); georeferencing to the globe is the [earth.worldwind.layer.pointcloud.PointCloudLayer]'s
 * job. Colours are always 8-bit RGBA — from the file's RGB channels when present, else a
 * grayscale ramp synthesized from intensity so every cloud renders visibly.
 */
class LasPointCloud internal constructor(
    val pointCount: Int,
    /** `pointCount * 3` native-CRS doubles: `[x0, y0, z0, x1, y1, z1, ...]`. */
    val positions: DoubleArray,
    /** `pointCount * 4` RGBA bytes. */
    val colors: ByteArray,
    val crs: LasCrs,
    val minX: Double, val minY: Double, val minZ: Double,
    val maxX: Double, val maxY: Double, val maxZ: Double,
)

/**
 * Parses a LAS or LAZ file into a [LasPointCloud]. Uncompressed records are read directly;
 * LAZ records are decompressed by the registered [LazChunkDecoder] into the same standard-record
 * byte layout, so one field-extraction path serves both.
 *
 * Supported point formats: 0,1,2,3 (legacy) and 6,7,8 (LAS 1.4). Waveform formats 4/5/9/10 read
 * positions + colours and ignore the wave-packet payload; extra-bytes are stepped over via the
 * record stride.
 */
object LasReader {
    suspend fun parse(fileBytes: ByteArray): LasPointCloud {
        val header = LasHeader.parse(fileBytes)
        val vlrData = LasVlrParser.parse(fileBytes, header)
        val count = header.pointCount
        require(count in 1..Int.MAX_VALUE) { "unsupported LAS point count $count" }
        val n = count.toInt()

        val recordBytes: ByteArray
        val baseOffset: Int
        val stride: Int
        if (header.isCompressed) {
            val vlr = vlrData.laszip ?: error("LAZ file missing its 'laszip encoded' VLR")
            val decoder = LasDecoderRegistry.lazDecoder ?: run {
                logMessage(
                    Logger.WARN, "LasReader", "parse",
                    "LAZ payload but no decoder registered — set LasDecoderRegistry.lazDecoder " +
                        "before opening compressed .laz files",
                )
                error("no LASzip decoder registered")
            }
            recordBytes = decoder.decode(fileBytes, header, vlr)
            baseOffset = 0
            stride = vlr.items.sumOf { it.size }
        } else {
            recordBytes = fileBytes
            baseOffset = header.offsetToPointData
            stride = header.pointDataRecordLength
        }
        require(baseOffset.toLong() + n.toLong() * stride <= recordBytes.size) {
            "point records overflow buffer: base=$baseOffset count=$n stride=$stride size=${recordBytes.size}"
        }

        return readPoints(recordBytes, baseOffset, stride, n, header, vlrData.crs)
    }

    private suspend fun readPoints(
        bytes: ByteArray, base: Int, stride: Int, count: Int, header: LasHeader, crs: LasCrs,
    ): LasPointCloud {
        val v = BinaryDataView(bytes)
        val colorOffset = rgbOffset(header.pointDataFormat)
        val hasRgb = colorOffset >= 0

        val positions = DoubleArray(count * 3)
        val colors = ByteArray(count * 4)

        // One cheap pre-scan of just the colour/intensity field decides the 16→8-bit mapping
        // (LAS stores RGB and intensity as uint16; some encoders fill only the low byte).
        val (colorShift, intensityMax) = scanColorRange(v, base, stride, count, colorOffset)

        var minX = Double.POSITIVE_INFINITY; var minY = Double.POSITIVE_INFINITY; var minZ = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY; var maxY = Double.NEGATIVE_INFINITY; var maxZ = Double.NEGATIVE_INFINITY
        val invIntensity = if (intensityMax > 0) 255f / intensityMax else 0f

        val yielder = RenderYield()
        for (i in 0 until count) {
            if (i and 0xFF == 0) yielder.tick() // yield when the frame budget is spent
            val rec = base + i * stride
            val xi = v.getInt32(rec, littleEndian = true)
            val yi = v.getInt32(rec + 4, littleEndian = true)
            val zi = v.getInt32(rec + 8, littleEndian = true)
            val x = xi * header.scaleX + header.offsetX
            val y = yi * header.scaleY + header.offsetY
            val z = zi * header.scaleZ + header.offsetZ
            positions[i * 3] = x; positions[i * 3 + 1] = y; positions[i * 3 + 2] = z
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z

            val co = i * 4
            if (hasRgb) {
                colors[co] = ((v.getUint16(rec + colorOffset, true) shr colorShift) and 0xFF).toByte()
                colors[co + 1] = ((v.getUint16(rec + colorOffset + 2, true) shr colorShift) and 0xFF).toByte()
                colors[co + 2] = ((v.getUint16(rec + colorOffset + 4, true) shr colorShift) and 0xFF).toByte()
            } else {
                // Grayscale ramp from intensity so colourless clouds are still visible.
                val g = (v.getUint16(rec + INTENSITY_OFFSET, true) * invIntensity).toInt().coerceIn(0, 255)
                colors[co] = g.toByte(); colors[co + 1] = g.toByte(); colors[co + 2] = g.toByte()
            }
            colors[co + 3] = 0xFF.toByte()
        }

        return LasPointCloud(
            pointCount = count, positions = positions, colors = colors, crs = crs,
            minX = minX, minY = minY, minZ = minZ, maxX = maxX, maxY = maxY, maxZ = maxZ,
        )
    }

    /** Returns `(colorShift, intensityMax)`. `colorShift` is 8 when RGB looks 16-bit, else 0;
     *  `intensityMax` is only computed (for the grayscale ramp) when the format has no RGB. */
    private fun scanColorRange(
        v: BinaryDataView, base: Int, stride: Int, count: Int, colorOffset: Int,
    ): Pair<Int, Int> {
        val sample = minOf(count, 8192)
        if (colorOffset >= 0) {
            var maxChannel = 0
            for (i in 0 until sample) {
                val rec = base + i * stride
                val r = v.getUint16(rec + colorOffset, true)
                val g = v.getUint16(rec + colorOffset + 2, true)
                val b = v.getUint16(rec + colorOffset + 4, true)
                if (r > maxChannel) maxChannel = r
                if (g > maxChannel) maxChannel = g
                if (b > maxChannel) maxChannel = b
            }
            return (if (maxChannel > 255) 8 else 0) to 0
        }
        var maxIntensity = 0
        for (i in 0 until sample) {
            val intensity = v.getUint16(base + i * stride + INTENSITY_OFFSET, true)
            if (intensity > maxIntensity) maxIntensity = intensity
        }
        return 0 to maxIntensity
    }

    /** Byte offset of the RGB triple within a point record, or -1 when the format has no colour. */
    private fun rgbOffset(format: Int): Int = when (format) {
        2 -> 20
        3, 5 -> 28
        7, 8, 10 -> 30
        else -> -1 // 0, 1, 4, 6, 9
    }

    private const val INTENSITY_OFFSET = 12
}
