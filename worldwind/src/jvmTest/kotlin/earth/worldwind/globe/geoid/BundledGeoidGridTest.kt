package earth.worldwind.globe.geoid

import earth.worldwind.assets.MR as AssetsMR
import earth.worldwind.geom.Angle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Guards the resource split: the EGM96 grid ships in the `worldwind-assets` artifact while the code
 * that reads it lives here, so the lookup crosses a module boundary. [EGM96GeoidTest] only checks
 * that interpolation agrees with the grid points, which holds trivially when nothing is loaded —
 * these tests fail if the grid stops resolving.
 */
class BundledGeoidGridTest {
    @Test
    fun gridIsOnTheClasspath() {
        val offsets = AssetsMR.assets.EGM96_dat
        val bytes = with(offsets) {
            resourcesClassLoader.getResourceAsStream(filePath)?.use { it.readBytes() }
        }
        assertNotNull(bytes, "EGM96 grid not on the classpath at ${offsets.filePath}")
        // 721 rows x 1440 columns of 2-byte integers, per the NGA specification.
        assertEquals(721 * 1440 * 2, bytes.size, "unexpected EGM96 grid size")
    }

    @Test
    fun defaultGeoidLoadsGridAndComputesOffset() = runTest {
        val geoid = EGM96Geoid(scope = this)

        geoid.awaitDataLoaded()

        // Boulder, Colorado. EGM96 undulation there is about -17 m; assert a band rather than an
        // exact value so the test survives interpolation changes but still fails on a missing grid,
        // which would read back as a flat 0.
        val offset = geoid.getOffset(Angle.fromDegrees(40.0), Angle.fromDegrees(-105.0))
        assertTrue(offset < -10f && offset > -25f, "expected roughly -17 m, got $offset")
    }
}
