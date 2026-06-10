package earth.worldwind.formats.dted

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class DtedBilinearReadTest {

    @Test
    fun read_1to1_isExactAndCorrectlyOriented() {
        val dir = createTempDirectory("dted-bl").toFile()
        val w = 4; val h = 4
        val file = File(dir, "n45e010.dt1")
        // elevation(col, latIndex) = col*1000 + latIndex   (latIndex 0 = south)
        file.writeBytes(synthDted(45, 10, width = w, height = h, level = 1) { col, lat -> (col * 1000 + lat).toShort() })
        val out = DTED.read(file, 0.0, 1.0, 0.0, 1.0, w, h) // north-up rows, 1:1 → exact
        for (r in 0 until h) for (c in 0 until w) {
            assertEquals((c * 1000 + (h - 1 - r)).toShort(), out[r * w + c], "r=$r c=$c")
        }
        dir.deleteRecursively()
    }

    @Test
    fun readOverview_isCorrectlyOrientedAndStrided() {
        val dir = createTempDirectory("dted-ov").toFile()
        val w = 5; val h = 5
        val file = File(dir, "n45e010.dt2")
        // elevation(col, latIndex) = col*1000 + latIndex   (latIndex 0 = south)
        file.writeBytes(synthDted(45, 10, width = w, height = h, level = 2) { col, lat -> (col * 1000 + lat).toShort() })
        val n = 3
        val out = DTED.readOverview(file, n) // n×n north-up, nearest-sampled at evenly-spaced posts (0,2,4)
        // row 0 = north (latIndex h-1=4), row 2 = south (latIndex 0); col tx → src col 0,2,4.
        val srcCol = intArrayOf(0, 2, 4)
        val srcLat = intArrayOf(4, 2, 0) // north-up
        for (ty in 0 until n) for (tx in 0 until n) {
            assertEquals((srcCol[tx] * 1000 + srcLat[ty]).toShort(), out[ty * n + tx], "ty=$ty tx=$tx")
        }
        dir.deleteRecursively()
    }

    @Test
    fun read_and_readOverview_decodeNegativeElevations_signMagnitude() {
        // Sign-magnitude negatives must decode to their value, not a hole (the two's-complement bug); flat
        // in latitude so both 1:1 bilinear and strided overview are exact: col c -> -(c*100).
        val dir = createTempDirectory("dted-neg").toFile()
        val file = File(dir, "s01w001.dt1")
        file.writeBytes(synthDted(-1, -1, width = 3, height = 3, level = 1) { col, _ -> (-(col * 100)).toShort() })
        val win = DTED.read(file, 0.0, 1.0, 0.0, 1.0, 3, 3)
        for (r in 0 until 3) for (c in 0 until 3) assertEquals((-(c * 100)).toShort(), win[r * 3 + c], "win r=$r c=$c")
        val ov = DTED.readOverview(file, 3)
        for (r in 0 until 3) for (c in 0 until 3) assertEquals((-(c * 100)).toShort(), ov[r * 3 + c], "ov r=$r c=$c")
        dir.deleteRecursively()
    }

    @Test
    fun read_upsampled_interpolates_noTerraces() {
        val dir = createTempDirectory("dted-bl2").toFile()
        // 2x2 cell: a pure west→east gradient (0 at west col, 100 at east col), flat in latitude.
        val file = File(dir, "n45e010.dt1")
        file.writeBytes(synthDted(45, 10, width = 2, height = 2, level = 1) { col, _ -> (col * 100).toShort() })
        // Upsample to 5 columns across the full cell → expect a smooth ramp 0,25,50,75,100 (not 0,0,0,100,100).
        val out = DTED.read(file, 0.0, 1.0, 0.0, 1.0, 5, 1)
        assertEquals(0, out[0].toInt())
        assertEquals(25, out[1].toInt())
        assertEquals(50, out[2].toInt())
        assertEquals(75, out[3].toInt())
        assertEquals(100, out[4].toInt())
        dir.deleteRecursively()
    }
}
