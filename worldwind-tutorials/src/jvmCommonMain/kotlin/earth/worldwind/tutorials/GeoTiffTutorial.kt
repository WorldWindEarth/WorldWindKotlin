package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.formats.geotiff.GeoTiffConstants
import earth.worldwind.formats.geotiff.TiffConstants
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.globe.elevation.coverage.ElevationCoverage
import earth.worldwind.globe.elevation.coverage.geotiff.GeoTiffElevationCoverage
import earth.worldwind.layer.geotiff.GeoTiffImageLayer
import earth.worldwind.layer.geotiff.create
import earth.worldwind.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Loads a pair of local tiled GeoTIFFs — an orthophoto-style image and a DEM — through
 * [GeoTiffImageLayer] and [GeoTiffElevationCoverage], so the imagery drapes over terrain
 * that came out of the same file format.
 *
 * By default the tutorial synthesises both files into a temp directory at startup (see
 * [SyntheticGeoTiff]): a 512 × 512 Int16 DEM and a 1024 × 1024 RGB shaded-relief image of
 * the same synthetic massif, both internally tiled and Deflate-compressed, the image
 * carrying a half-scale overview. That is the layout a Cloud-Optimized GeoTIFF has, so the
 * tiles you see on screen are decoded exactly the way a real one would be: only the blocks
 * a tile lands on are read, from the overview level closest to that tile's resolution.
 *
 * To run against real data instead, set `GEOTIFF_IMAGE` and / or `GEOTIFF_DEM` (environment
 * variable or system property) to a GeoTIFF path. Each is independent — point only
 * `GEOTIFF_DEM` at a DEM and the synthetic shaded relief still drapes over it, as long as
 * the two overlap.
 *
 * @param scope the host's main-thread scope. Generating (or just opening) the rasters runs
 *   on [Dispatchers.IO] inside a job this scope owns; only attaching the finished layer and
 *   coverage touches the main thread.
 */
class GeoTiffTutorial(
    engine: WorldWind,
    private val scope: CoroutineScope,
) : AbstractTutorial(engine) {
    private var imageLayer: GeoTiffImageLayer? = null
    private var elevationCoverage: GeoTiffElevationCoverage? = null
    private var syntheticDir: File? = null
    private var job: Job? = null
    // Coverages we disabled so we can re-enable them on stop().
    private val temporarilyDisabled = mutableListOf<ElevationCoverage>()

    override fun start() {
        super.start()
        // Writing the sample rasters and opening the files is real work — hundreds of
        // milliseconds of terrain synthesis plus Deflate — so none of it may run on the
        // main thread. Only attaching the finished layer and coverage does.
        job = scope.launch {
            try {
                val (layer, coverage) = withContext(Dispatchers.IO) {
                    val imageFile = customFile("GEOTIFF_IMAGE")
                        ?: syntheticFile(IMAGE_NAME) { SyntheticGeoTiff.shadedRelief() }
                    val demFile = customFile("GEOTIFF_DEM")
                        ?: syntheticFile(DEM_NAME) { SyntheticGeoTiff.elevation() }
                    GeoTiffImageLayer.create(imageFile, "GeoTIFF imagery") to
                        GeoTiffElevationCoverage.create(demFile)
                }
                if (!isActive) {
                    // Cancelled while loading: close what was opened, nothing was attached.
                    layer?.close()
                    coverage?.close()
                    return@launch
                }
                // Terrain: the DEM's own extent becomes the coverage's tile pyramid.
                coverage?.let {
                    elevationCoverage = it
                    engine.globe.elevationModel.apply {
                        // The DEM replaces the terrain under it rather than competing with
                        // the globe's default coverage; the originals come back in stop().
                        forEach { existing ->
                            if (existing.isEnabled) {
                                existing.isEnabled = false
                                temporarilyDisabled += existing
                            }
                        }
                        addCoverage(it)
                    }
                }
                // Imagery: a TiledImageLayer whose tiles decode straight out of the file.
                layer?.let {
                    imageLayer = it
                    engine.layers.addLayer(it)
                }
                positionCamera()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Logger.log(Logger.ERROR, "GeoTIFF tutorial failed to load", e)
            }
        }
    }

    override fun stop() {
        super.stop()
        val pending = job
        job = null
        pending?.cancel()
        // Detach before closing: both types hold the file open while they are attached.
        imageLayer?.let {
            engine.layers.removeLayer(it)
            it.close()
        }
        imageLayer = null
        elevationCoverage?.let {
            engine.globe.elevationModel.removeCoverage(it)
            it.close()
        }
        elevationCoverage = null
        temporarilyDisabled.forEach { it.isEnabled = true }
        temporarilyDisabled.clear()
        // Wait for a cancelled synthesis to unwind before removing its directory — a long
        // compute only observes cancellation on return, and would otherwise re-create the
        // files just after we deleted them.
        syntheticDir?.let { dir ->
            syntheticDir = null
            scope.launch {
                pending?.join()
                withContext(Dispatchers.IO) { dir.deleteRecursively() }
            }
        }
    }

    private fun customFile(name: String) =
        (System.getenv(name) ?: System.getProperty(name))?.let { File(it) }?.takeIf { it.isFile }

    /** Write [content] into a temp directory shared by this tutorial's synthetic files. */
    private fun syntheticFile(name: String, content: () -> ByteArray): File {
        // Manual temp dir under java.io.tmpdir — portable across JVM and Android, with no
        // dependency on java.nio.file.Files (which needs API 26 + desugaring on Android).
        val dir = syntheticDir ?: run {
            val base = File(System.getProperty("java.io.tmpdir") ?: ".")
            File(base, "geotiff-tutorial-${System.nanoTime().toString(36)}").also {
                require(it.mkdirs() || it.isDirectory) { "Cannot create temp dir $it" }
                syntheticDir = it
            }
        }
        return File(dir, name).also { it.writeBytes(content()) }
    }

    private fun positionCamera() {
        val sector = imageLayer?.sector ?: elevationCoverage?.sector ?: return
        // Tilted view from the south so the draped imagery is read against the relief the
        // DEM supplies, rather than looking straight down at a flat picture.
        engine.cameraFromLookAt(
            LookAt(
                position = Position.fromDegrees(
                    sector.centroidLatitude.inDegrees, sector.centroidLongitude.inDegrees,
                    SyntheticGeoTiff.PEAK_METRES * 0.6
                ),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = 40_000.0,
                heading = Angle.ZERO,
                tilt = Angle.fromDegrees(70.0),
                roll = Angle.ZERO,
            )
        )
        WorldWind.requestRedraw()
    }

    private companion object {
        const val IMAGE_NAME = "shaded-relief.tif"
        const val DEM_NAME = "elevation.tif"
    }
}

/**
 * Generates the tutorial's sample data: a synthetic alpine massif written out as two tiled
 * GeoTIFFs — an Int16 DEM and an RGB shaded-relief image of the same terrain over the same
 * sector.
 *
 * The writer is deliberately minimal but produces the real thing: a little-endian classic
 * TIFF whose raster is stored as independently Deflate-compressed tiles, with optional
 * reduced-resolution overview IFDs chained after the full-resolution one, and GeoTIFF tags
 * placing it on WGS 84. That is the layout the engine's reader is built to stream, so the
 * tutorial exercises the same code path a real Cloud-Optimized GeoTIFF would.
 */
internal object SyntheticGeoTiff {
    /** A 0.4° patch of the central Alps — synthetic terrain, plausible setting. */
    val SECTOR: Sector = Sector.fromDegrees(46.3, 7.8, 0.4, 0.4)
    const val PEAK_METRES = 2600.0

    private const val IMAGE_SIZE = 1024
    private const val DEM_SIZE = 512
    private const val BASE_METRES = 900.0
    private const val METRES_PER_DEGREE = 111_320.0
    private const val COMPRESSION_DEFLATE = 32946

    /** Single-band Int16 elevation in metres, in 128 × 128 tiles. */
    fun elevation(): ByteArray {
        // Evaluate the terrain once per post and index it from the writer's callback: the
        // callback runs once per sample per raster (and again per overview level), so doing
        // the trigonometry inside it would repeat the same work several times over.
        val posts = ShortArray(DEM_SIZE * DEM_SIZE)
        val step = 1.0 / (DEM_SIZE - 1)
        for (y in 0 until DEM_SIZE) for (x in 0 until DEM_SIZE) {
            posts[y * DEM_SIZE + x] = terrainHeight(x * step, y * step).roundToInt().toShort()
        }
        return writeTiledGeoTiff(
            width = DEM_SIZE, height = DEM_SIZE, tileSize = 128,
            samplesPerPixel = 1, bitsPerSample = 16,
            sampleFormat = TiffConstants.SampleFormat.SIGNED,
            photometric = TiffConstants.PhotometricInterpretation.BLACK_IS_ZERO,
            overviewFactors = IntArray(0),
        ) { x, y, _ -> posts[y * DEM_SIZE + x].toInt() }
    }

    /** RGB shaded relief of the same massif — a hypsometric tint lit from the north-west —
     *  in 256 × 256 tiles with a half-scale overview, so zoomed-out tiles read the overview
     *  rather than decimating the full-resolution raster. */
    fun shadedRelief(): ByteArray {
        val n = IMAGE_SIZE
        val step = 1.0 / (n - 1)
        // Height grid padded by one pixel on each side, so every pixel's slope taps have
        // neighbours without re-evaluating the terrain function at the edges.
        val stride = n + 2
        val heights = FloatArray(stride * stride)
        for (row in 0 until stride) {
            val v = (row - 1) * step
            for (col in 0 until stride) heights[row * stride + col] = terrainHeight((col - 1) * step, v).toFloat()
        }
        // Metres per raster step, so slopes are in real units rather than per-pixel deltas.
        val ground = SECTOR.deltaLatitude.inDegrees * METRES_PER_DEGREE * step
        val rgb = IntArray(n * n)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val at = (y + 1) * stride + (x + 1)
                val slopeX = (heights[at + 1] - heights[at - 1]) / (2.0 * ground)
                val slopeY = (heights[at + stride] - heights[at - stride]) / (2.0 * ground)
                // Lambert against a north-west light 45° above the horizon — the convention
                // AbstractTutorial.sunAzimuthDegrees follows, so the relief reads the way the
                // engine's own hillshading does. Raster x runs east and y south, so the
                // direction toward the sun is (-0.5, -0.5, 0.7071) and the surface normal is
                // (-slopeX, -slopeY, 1).
                val lambert = (0.5 * slopeX + 0.5 * slopeY + 0.7071) /
                    sqrt(slopeX * slopeX + slopeY * slopeY + 1.0)
                val shade = (0.3 + 0.8 * lambert).coerceIn(0.0, 1.0)
                val tint = hypsometricTint(
                    ((heights[at] - BASE_METRES) / (PEAK_METRES + 900.0)).coerceIn(0.0, 1.0)
                )
                var packed = 0
                for (band in 0..2) {
                    packed = packed or ((tint[band] * shade * 255.0).roundToInt().coerceIn(0, 255) shl (16 - band * 8))
                }
                rgb[y * n + x] = packed
            }
        }
        return writeTiledGeoTiff(
            width = n, height = n, tileSize = 256,
            samplesPerPixel = 3, bitsPerSample = 8,
            sampleFormat = TiffConstants.SampleFormat.UNSIGNED,
            photometric = TiffConstants.PhotometricInterpretation.RGB,
            overviewFactors = intArrayOf(2),
        ) { x, y, band -> (rgb[y * n + x] shr (16 - band * 8)) and 0xFF }
    }

    /** Height in metres at fractional raster position ([u], [v]); `v = 0` is the north edge.
     *  A sharp summit on a broad massif, plus ridges so the flanks aren't glassy. */
    fun terrainHeight(u: Double, v: Double): Double {
        val du = u - 0.5
        val dv = v - 0.45
        val r2 = du * du + dv * dv
        val summit = PEAK_METRES * exp(-r2 / (2.0 * 0.07 * 0.07))
        val massif = 900.0 * exp(-r2 / (2.0 * 0.30 * 0.30))
        val ridges = 160.0 * sin(u * PI * 9.0) * cos(v * PI * 7.0)
        return BASE_METRES + summit + massif + ridges
    }

    /** Green lowlands through tan slopes to bare rock and snow. */
    private fun hypsometricTint(t: Double) = when {
        t < 0.35 -> mix(doubleArrayOf(0.32, 0.48, 0.26), doubleArrayOf(0.62, 0.58, 0.34), t / 0.35)
        t < 0.7 -> mix(doubleArrayOf(0.62, 0.58, 0.34), doubleArrayOf(0.55, 0.48, 0.44), (t - 0.35) / 0.35)
        else -> mix(doubleArrayOf(0.55, 0.48, 0.44), doubleArrayOf(0.97, 0.97, 1.0), (t - 0.7) / 0.3)
    }

    private fun mix(from: DoubleArray, to: DoubleArray, t: Double) =
        DoubleArray(3) { from[it] + (to[it] - from[it]) * t.coerceIn(0.0, 1.0) }

    // --- Minimal tiled-GeoTIFF writer ------------------------------------------

    private class Raster(val width: Int, val height: Int, val tileSize: Int, val blocks: List<ByteArray>)

    /** One directory entry, already encoded; payloads of four bytes or fewer ride inline in
     *  the IFD, longer ones are stored out of line. */
    private class Field(val tag: Int, val type: Int, val count: Int, val payload: ByteArray)

    private fun writeTiledGeoTiff(
        width: Int, height: Int, tileSize: Int, samplesPerPixel: Int, bitsPerSample: Int,
        sampleFormat: Int, photometric: Int, overviewFactors: IntArray,
        sample: (x: Int, y: Int, band: Int) -> Int,
    ): ByteArray {
        val rasters = mutableListOf(buildRaster(width, height, tileSize, samplesPerPixel, bitsPerSample, sample))
        for (factor in overviewFactors) rasters += buildRaster(
            width / factor, height / factor, tileSize, samplesPerPixel, bitsPerSample
        ) { x, y, band -> sample(x * factor, y * factor, band) }
        return assemble(rasters, samplesPerPixel, bitsPerSample, sampleFormat, photometric, width, height)
    }

    private fun buildRaster(
        width: Int, height: Int, tileSize: Int, samplesPerPixel: Int, bitsPerSample: Int,
        sample: (Int, Int, Int) -> Int,
    ): Raster {
        val bytesPerPixel = samplesPerPixel * bitsPerSample / 8
        val tilesAcross = (width + tileSize - 1) / tileSize
        val tilesDown = (height + tileSize - 1) / tileSize
        val blocks = ArrayList<ByteArray>(tilesAcross * tilesDown)
        for (ty in 0 until tilesDown) for (tx in 0 until tilesAcross) {
            // Tiles are always stored full-size; pixels past the image edge are padding.
            val raw = ByteArray(tileSize * tileSize * bytesPerPixel)
            var at = 0
            for (y in 0 until tileSize) for (x in 0 until tileSize) {
                val px = (tx * tileSize + x).coerceAtMost(width - 1)
                val py = (ty * tileSize + y).coerceAtMost(height - 1)
                for (band in 0 until samplesPerPixel) {
                    val value = sample(px, py, band)
                    raw[at++] = (value and 0xFF).toByte()
                    if (bitsPerSample == 16) raw[at++] = ((value shr 8) and 0xFF).toByte()
                }
            }
            blocks += deflate(raw)
        }
        return Raster(width, height, tileSize, blocks)
    }

    private fun deflate(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val deflater = Deflater()
        deflater.setInput(raw)
        deflater.finish()
        val buffer = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun assemble(
        rasters: List<Raster>, samplesPerPixel: Int, bitsPerSample: Int, sampleFormat: Int,
        photometric: Int, fullWidth: Int, fullHeight: Int,
    ): ByteArray {
        // Raster bytes first, then out-of-line tag values, then the chained IFDs.
        var at = 8
        val offsets = rasters.map { raster ->
            IntArray(raster.blocks.size) { i ->
                val offset = at
                at += raster.blocks[i].size
                offset
            }
        }
        val fieldsPerRaster = rasters.mapIndexed { index, raster ->
            buildFields(
                raster, offsets[index], index == 0, samplesPerPixel, bitsPerSample,
                sampleFormat, photometric, fullWidth, fullHeight
            )
        }
        val external = LinkedHashMap<Field, Int>()
        var externalAt = at
        for (fields in fieldsPerRaster) for (field in fields) if (field.payload.size > 4) {
            external[field] = externalAt
            externalAt += field.payload.size + field.payload.size % 2 // keep word alignment
        }
        val ifdOffsets = IntArray(rasters.size)
        var ifdAt = externalAt
        for (i in rasters.indices) {
            ifdOffsets[i] = ifdAt
            ifdAt += 2 + fieldsPerRaster[i].size * 12 + 4
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x49, 0x49)) // little-endian byte order
        writeShort(out, 42)
        writeInt(out, ifdOffsets[0])
        for (raster in rasters) for (block in raster.blocks) out.write(block)
        for ((field, offset) in external) {
            check(out.size() == offset) { "External value misplaced for tag ${field.tag}" }
            out.write(field.payload)
            if (field.payload.size % 2 != 0) out.write(0)
        }
        for (i in rasters.indices) {
            check(out.size() == ifdOffsets[i]) { "IFD $i misplaced" }
            val fields = fieldsPerRaster[i]
            writeShort(out, fields.size)
            for (field in fields) {
                writeShort(out, field.tag)
                writeShort(out, field.type)
                writeInt(out, field.count)
                out.write(external[field]?.let { intBytes(it) } ?: field.payload.copyOf(4))
            }
            writeInt(out, if (i + 1 < rasters.size) ifdOffsets[i + 1] else 0)
        }
        return out.toByteArray()
    }

    private fun buildFields(
        raster: Raster, offsets: IntArray, isPrimary: Boolean, samplesPerPixel: Int,
        bitsPerSample: Int, sampleFormat: Int, photometric: Int, fullWidth: Int, fullHeight: Int,
    ): List<Field> {
        val fields = mutableListOf<Field>()
        // NewSubfileType bit 0 marks a reduced-resolution overview of the primary image.
        if (!isPrimary) fields += Field(TiffConstants.IFDTag.NEW_SUBFILE_TYPE, TYPE_LONG, 1, intBytes(1))
        fields += Field(TiffConstants.IFDTag.IMAGE_WIDTH, TYPE_LONG, 1, intBytes(raster.width))
        fields += Field(TiffConstants.IFDTag.IMAGE_LENGTH, TYPE_LONG, 1, intBytes(raster.height))
        fields += Field(
            TiffConstants.IFDTag.BITS_PER_SAMPLE, TYPE_SHORT, samplesPerPixel,
            shortBytes(IntArray(samplesPerPixel) { bitsPerSample })
        )
        fields += Field(TiffConstants.IFDTag.COMPRESSION, TYPE_SHORT, 1, shortBytes(intArrayOf(COMPRESSION_DEFLATE)))
        fields += Field(
            TiffConstants.IFDTag.PHOTOMETRIC_INTERPRETATION, TYPE_SHORT, 1, shortBytes(intArrayOf(photometric))
        )
        fields += Field(TiffConstants.IFDTag.SAMPLES_PER_PIXEL, TYPE_SHORT, 1, shortBytes(intArrayOf(samplesPerPixel)))
        fields += Field(TiffConstants.IFDTag.PLANAR_CONFIGURATION, TYPE_SHORT, 1, shortBytes(intArrayOf(1)))
        fields += Field(TiffConstants.IFDTag.TILE_WIDTH, TYPE_LONG, 1, intBytes(raster.tileSize))
        fields += Field(TiffConstants.IFDTag.TILE_LENGTH, TYPE_LONG, 1, intBytes(raster.tileSize))
        fields += Field(TiffConstants.IFDTag.TILE_OFFSETS, TYPE_LONG, offsets.size, intBytes(offsets))
        fields += Field(
            TiffConstants.IFDTag.TILE_BYTE_COUNTS, TYPE_LONG, raster.blocks.size,
            intBytes(IntArray(raster.blocks.size) { raster.blocks[it].size })
        )
        fields += Field(
            TiffConstants.IFDTag.SAMPLE_FORMAT, TYPE_SHORT, samplesPerPixel,
            shortBytes(IntArray(samplesPerPixel) { sampleFormat })
        )
        if (isPrimary) {
            // Georeferencing lives on the full-resolution IFD; overviews inherit it, scaled.
            val scaleX = SECTOR.deltaLongitude.inDegrees / fullWidth
            val scaleY = SECTOR.deltaLatitude.inDegrees / fullHeight
            fields += Field(
                GeoTiffConstants.MODEL_PIXEL_SCALE, TYPE_DOUBLE, 3,
                doubleBytes(doubleArrayOf(scaleX, scaleY, 0.0))
            )
            // Raster (0,0) is the north-west corner of the image.
            fields += Field(
                GeoTiffConstants.MODEL_TIEPOINT, TYPE_DOUBLE, 6,
                doubleBytes(
                    doubleArrayOf(
                        0.0, 0.0, 0.0,
                        SECTOR.minLongitude.inDegrees, SECTOR.maxLatitude.inDegrees, 0.0
                    )
                )
            )
            // GeoKeys: geographic model, pixel-is-area, WGS 84.
            fields += Field(
                GeoTiffConstants.GEO_KEY_DIRECTORY, TYPE_SHORT, 16,
                shortBytes(intArrayOf(1, 1, 0, 3, 1024, 0, 1, 2, 1025, 0, 1, 1, 2048, 0, 1, 4326))
            )
        }
        fields.sortBy { it.tag } // TIFF requires directory entries in ascending tag order
        return fields
    }

    private fun writeShort(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Int) = out.write(intBytes(value))

    private fun intBytes(value: Int) = intBytes(intArrayOf(value))

    private fun intBytes(values: IntArray) = ByteArray(values.size * 4) {
        ((values[it / 4] shr ((it % 4) * 8)) and 0xFF).toByte()
    }

    private fun shortBytes(values: IntArray) = ByteArray(values.size * 2) {
        ((values[it / 2] shr ((it % 2) * 8)) and 0xFF).toByte()
    }

    private fun doubleBytes(values: DoubleArray) = ByteArray(values.size * 8) {
        val bits = values[it / 8].toRawBits()
        ((bits shr ((it % 8) * 8)) and 0xFFL).toByte()
    }

    private const val TYPE_SHORT = TiffConstants.Type.SHORT
    private const val TYPE_LONG = TiffConstants.Type.LONG
    private const val TYPE_DOUBLE = TiffConstants.Type.DOUBLE
}
