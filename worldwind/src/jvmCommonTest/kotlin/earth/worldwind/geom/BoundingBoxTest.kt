package earth.worldwind.geom

import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.globe.Globe
import kotlin.test.*

class BoundingBoxTest {
    companion object {
        /**
         * Creates Sector with a centroid set to the specified latitude and longitude.
         *
         * @param centroidLatDegrees Centroid latitude
         * @param centroidLonDegrees Centroid Longitude
         * @param deltaLatDegrees Delta latitude
         * @param deltaLonDegrees Delta longitude
         *
         * @return Sector from centroid set
         */
        private fun sectorFromCentroid(
            centroidLatDegrees: Double, centroidLonDegrees: Double, deltaLatDegrees: Double, deltaLonDegrees: Double
        ): Sector {
            return fromDegrees(
                centroidLatDegrees - deltaLatDegrees / 2,
                centroidLonDegrees - deltaLonDegrees / 2,
                deltaLatDegrees, deltaLonDegrees
            )
        }
    }

    /**
     * The globe used in the tests, created in setUp(), released in tearDown().
     */
    private lateinit var globe: Globe

    @BeforeTest
    fun setUp() {
        // Create the globe object used by the test
        globe = Globe()
    }

    @Test
    fun testConstructor() {
        val bb = BoundingBox()
        assertNotNull(bb)
    }

    @Test
    fun testSetToSector() {
        val boundingBox = BoundingBox()
        val centerLat = ZERO
        val centerLon = ZERO
        // Create a very, very small sector.
        val smallSector = sectorFromCentroid(centerLat.inDegrees, centerLon.inDegrees, 0.0001, 0.0001)
        // Create a large sector.
        val largeSector = sectorFromCentroid(centerLat.inDegrees, centerLon.inDegrees, 1.0, 1.0)
        // Create a point coincident with the sectors' centroids
        val point = globe.geographicToCartesian(centerLat, centerLon, 0.0, Vec3())

        // Set the bounding box to the small sector with no elevation.
        // We expect the center of the bounding box to be very close to our point and the
        // z value should be half of the min/max elevation delta.
        var minElevation = 0f
        var maxElevation = 100f
        boundingBox.setToSector(smallSector, globe, minElevation, maxElevation)
        assertEquals(point.x, boundingBox.center.x, 1e-1, "small center x")
        assertEquals(point.y, boundingBox.center.y, 1e-1, "small center y")
        assertEquals(point.z + maxElevation / 2, boundingBox.center.z, 1e-1, "small center z")

        // Set the bounding box to the large sector with no elevation.
        // We expect the center x,y of the bounding box to be close to the point
        // whereas the z value will be less due to the curvature of the sector's surface.
        minElevation = 0f
        maxElevation = 0f
        boundingBox.setToSector(largeSector, globe, minElevation, maxElevation)
        assertEquals(point.x, boundingBox.center.x, 1e-1, "large center x")
        assertEquals(point.y, boundingBox.center.y, 1e-1, "large center y")
        assertEquals(point.z, boundingBox.center.z, 300.0, "large center z")
    }

    @Ignore
    @Test
    fun testIntersectsFrustum() {
        val boundingBox = BoundingBox()
        val minElevation = 0f
        val maxElevation = 1000f
        val sector = fromDegrees(-0.5, -0.5, 1.0, 1.0)
        boundingBox.setToSector(sector, globe, minElevation, maxElevation)

        // TODO: create and transform a frustum compatible with modelView
        fail("The test case is a stub.")
    }

    @Test
    fun testDistanceTo() {
        val boundingBox = BoundingBox()
        val radius = globe.equatorialRadius
        val minElevation = 0f
        val maxElevation = 1000f
        val sector = fromDegrees(-0.5, -0.5, 1.0, 1.0)
        boundingBox.setToSector(sector, globe, minElevation, maxElevation)
        val point = globe.geographicToCartesian(ZERO, ZERO, 0.0, Vec3())
        val result = boundingBox.distanceTo(point)
        assertEquals(boundingBox.center.z - radius, result, 1e-3)
    }
}