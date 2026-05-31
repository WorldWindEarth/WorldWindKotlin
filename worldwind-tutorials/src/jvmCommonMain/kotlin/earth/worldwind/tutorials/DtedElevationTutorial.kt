package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.globe.elevation.coverage.DtedElevationCoverage
import earth.worldwind.globe.elevation.coverage.ElevationCoverage
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.abs

/**
 * Loads a local DTED library through [DtedElevationCoverage]. By default the tutorial
 * synthesises a small 3×3 grid of MIL-PRF-89020B-compliant DTED-0 tiles (121 × 121
 * posts each, ~30 KB per tile) into a temp directory at startup — a sharp summit
 * over a broad massif with faint ridges — so you can see the elevation pipeline
 * working without needing a real DTED CD.
 *
 * To run against a real local DTED library, set the `DTED_DIR` environment variable
 * (or system property of the same name) to point at the library root. The directory
 * is scanned recursively; tiles named per the NIMA `n45/e034.dt1` or `n45e034.dt1`
 * convention are picked up without opening any UHL headers.
 */
class DtedElevationTutorial(engine: WorldWind) : AbstractTutorial(engine) {
    private var dtedCoverage: DtedElevationCoverage? = null
    private var syntheticDir: File? = null
    // Coverages we disabled so we can re-enable them on stop().
    private val temporarilyDisabled = mutableListOf<ElevationCoverage>()

    override fun start() {
        super.start()
        val customRoot = System.getenv("DTED_DIR") ?: System.getProperty("DTED_DIR")
        val rootDir = if (customRoot != null) File(customRoot)
            else createSyntheticLibrary().also { syntheticDir = it }

        val coverage = DtedElevationCoverage(rootDir).also { dtedCoverage = it }
        engine.globe.elevationModel.apply {
            forEach { existing ->
                if (existing.isEnabled) {
                    existing.isEnabled = false
                    temporarilyDisabled += existing
                }
            }
            addCoverage(coverage)
        }
        positionCamera(rootDir, coverage)
    }

    override fun stop() {
        super.stop()
        dtedCoverage?.let { engine.globe.elevationModel.removeCoverage(it) }
        temporarilyDisabled.forEach { it.isEnabled = true }
        temporarilyDisabled.clear()
        syntheticDir?.deleteRecursively()
        syntheticDir = null
        dtedCoverage = null
    }

    private fun positionCamera(rootDir: File, coverage: DtedElevationCoverage) {
        // LookAt centred on the library's bounding sector. For the synthetic library
        // this lands on the centre tile where the peak is; we point the target *at
        // summit altitude* and pull in close so the peak fills the view instead of
        // looking like a dot on a sphere.
        val sector = coverage.tileMatrixSet.sector
        val targetLat = sector.centroidLatitude.inDegrees
        val targetLon = sector.centroidLongitude.inDegrees
        val isSynthetic = rootDir == syntheticDir
        val targetAlt = if (isSynthetic) 7500.0 else 0.0 // synth peak ~7.5 km
        val range = if (isSynthetic) 40_000.0 else 50_000.0
        engine.cameraFromLookAt(
            LookAt(
                position = Position.fromDegrees(targetLat, targetLon, targetAlt),
                altitudeMode = AltitudeMode.ABSOLUTE,
                range = range,
                heading = Angle.ZERO,
                tilt = Angle.fromDegrees(75.0),
                roll = Angle.ZERO,
            )
        )
        WorldWind.requestRedraw()
    }

    private fun createSyntheticLibrary(): File {
        // Manual temp-dir under java.io.tmpdir — portable across JVM and Android, no
        // dependency on java.nio.file.Files (which needs API 26 + core-library
        // desugaring on Android).
        val base = File(System.getProperty("java.io.tmpdir") ?: ".")
        val dir = File(base, "dted-tutorial-${System.nanoTime().toString(36)}")
        require(dir.mkdirs() || dir.isDirectory) { "Cannot create temp dir $dir" }
        // 3×3 grid centred near (45°N, 10°E). Each tile is 1°×1° with a level-0 grid.
        for (latSW in CENTRE_LAT - 1..CENTRE_LAT + 1) {
            for (lonSW in CENTRE_LON - 1..CENTRE_LON + 1) {
                val hemiLat = if (latSW >= 0) 'n' else 's'
                val hemiLon = if (lonSW >= 0) 'e' else 'w'
                val name = "${hemiLat}${kotlin.math.abs(latSW).toString().padStart(2, '0')}" +
                        "${hemiLon}${kotlin.math.abs(lonSW).toString().padStart(3, '0')}.dt0"
                File(dir, name).writeBytes(synthTile(latSW, lonSW))
            }
        }
        return dir
    }

    // Synthetic terrain: a sharp peak centred on the library plus a gentler
    // surrounding massif and some ridges. σ values are tuned for DTED-0's 30 arc-
    // second post spacing (~900 m at this latitude) so the peak resolves over a
    // handful of samples — pointier than a Gaussian "hill" but not aliased.
    private fun synthTile(latSW: Int, lonSW: Int): ByteArray {
        val w = 121
        val h = 121
        return buildDted0(latSW, lonSW, w, h) { col, latIdx ->
            val tileLat = latSW + latIdx.toDouble() / (h - 1)
            val tileLon = lonSW + col.toDouble() / (w - 1)
            val dLat = tileLat - (CENTRE_LAT + 0.5)
            val dLon = tileLon - (CENTRE_LON + 0.5)
            val r2 = dLat * dLat + dLon * dLon
            // Sharp summit: σ ≈ 0.06° (~6.7 km) so the peak spans ~7 posts FWHM.
            val sharp = 6000.0 * exp(-(r2 / (2.0 * 0.06 * 0.06)))
            // Broader base so the peak rises out of a massif, not a flat plain.
            val base = 1500.0 * exp(-(r2 / (2.0 * 0.4 * 0.4)))
            // Faint sinusoidal ridges so flanks have texture, not perfect smoothness.
            val ridge = 250.0 * sin(tileLon * PI * 4) * cos(tileLat * PI * 4)
            (sharp + base + ridge + 200.0).coerceIn(-12000.0, 9000.0).toInt().toShort()
        }
    }

    companion object {
        // Centre tile is (CENTRE_LAT, CENTRE_LON) — the camera lands here on start().
        private const val CENTRE_LAT = 45
        private const val CENTRE_LON = 10

        // ------------------------------------------------------------------------
        // Minimal MIL-PRF-89020B DTED writer for the synthetic library. Mirrors the
        // structure of the test fixture: UHL → DSI → ACC → per-column records with
        // unsigned-byte-sum checksum.
        private fun buildDted0(
            latSW: Int, lonSW: Int, width: Int, height: Int,
            elevation: (column: Int, latIndex: Int) -> Short,
        ): ByteArray {
            val recSize = 8 + height * 2 + 4
            val bytes = ByteArray(3428 + width * recSize)
            writeAscii(bytes, 0, "UHL")
            writeAscii(bytes, 4,
                abs(lonSW).toString().padStart(3, '0') + "00" + "00" +
                        (if (lonSW >= 0) 'E' else 'W'))
            writeAscii(bytes, 12,
                abs(latSW).toString().padStart(3, '0') + "00" + "00" +
                        (if (latSW >= 0) 'N' else 'S'))
            writeAscii(bytes, 32, "U  ")
            writeAscii(bytes, 47, width.toString().padStart(4, '0'))
            writeAscii(bytes, 51, height.toString().padStart(4, '0'))
            writeAscii(bytes, 80, "DSI")
            writeAscii(bytes, 83, "U")
            writeAscii(bytes, 80 + 59, "DTED0")
            writeAscii(bytes, 728, "ACC")
            var off = 3428
            for (x in 0 until width) {
                bytes[off] = 0xAA.toByte()
                for (i in 0 until height) {
                    val v = elevation(x, i).toInt()
                    bytes[off + 8 + i * 2] = ((v shr 8) and 0xFF).toByte()
                    bytes[off + 8 + i * 2 + 1] = (v and 0xFF).toByte()
                }
                var sum = 0
                val csOff = off + 8 + height * 2
                for (i in off until csOff) sum += bytes[i].toInt() and 0xFF
                bytes[csOff]     = ((sum ushr 24) and 0xFF).toByte()
                bytes[csOff + 1] = ((sum ushr 16) and 0xFF).toByte()
                bytes[csOff + 2] = ((sum ushr 8) and 0xFF).toByte()
                bytes[csOff + 3] = (sum and 0xFF).toByte()
                off += recSize
            }
            return bytes
        }

        private fun writeAscii(bytes: ByteArray, offset: Int, s: String) {
            for (i in s.indices) bytes[offset + i] = s[i].code.toByte()
        }
    }
}
