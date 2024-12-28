package earth.worldwind.ogc

import earth.worldwind.geom.Ellipsoid
import earth.worldwind.geom.Sector
import earth.worldwind.geom.Sector.Companion.fromDegrees
import earth.worldwind.shape.TiledSurfaceImage
import earth.worldwind.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlin.test.*

class WmsLayerTest {
    @BeforeTest
    fun setup() {
        mockkStatic(Logger::class)
        every { Logger.logMessage(any(), any(), any(), any()) } returns ""
    }

    /**
     * Test default constructor of [WmsLayer].
     */
    @Test
    fun testConstructor() {
        val wmsLayer = WmsLayer()
        assertNotNull(wmsLayer, "assure WmsLayer creation")
    }

    /**
     * Test the three parameter constructor including throwing of [IllegalArgumentException] when null is provided
     * for required parameters.
     */
    @Test
    fun testConstructor_ThreeParameter() {
        // Create mock objects for testing
        val sector = Sector() // mockk<Sector>(relaxed = true)
        val metersPerPixel = 0.5 // notional value
        val wmsLayerConfig = WmsLayerConfig("testServiceAddress", "testLayerList")

        // Test invalid submissions throw exceptions
        try {
            WmsLayer(sector, -metersPerPixel, wmsLayerConfig)
            fail("submitted illegal parameters")
        } catch (ex: IllegalArgumentException) {
            assertNotNull(ex, "null exception thrown")
        }
        val wmsLayer = WmsLayer(sector, metersPerPixel, wmsLayerConfig)
        assertNotNull(wmsLayer, "object creation")
    }

    /**
     * Test the four parameter constructor including throwing of [IllegalArgumentException] when null is provided
     * for required parameters.
     */
    @Test
    fun testConstructor_FourParameter() {
        // Test Values
        val mockRadius = 700000.0 // notional for testing only
        val metersPerPixel = 0.5 // notional for testing only
        // The anticipated levels reflect the altered radius of the mocked Globe object provided. The WGS84 globe would
        // provide 18 levels versus the 15 with the mocked radius.
        val anticipatedLevels = 15

        // Create mock objects for testing
        val sector = Sector() // mockk<Sector>(relaxed = true)
        val ellipsoid = mockk<Ellipsoid>(relaxed = true)
        every { ellipsoid.semiMajorAxis } returns mockRadius
        val wmsLayerConfig = WmsLayerConfig("testServiceAddress", "testLayerList")

        // Test invalid submissions throw exceptions
        try {
            WmsLayer(sector, -metersPerPixel, wmsLayerConfig, ellipsoid)
            fail("provided illegal parameters")
        } catch (ex: IllegalArgumentException) {
            assertNotNull(ex, "null exception thrown")
        }
        val wmsLayer = WmsLayer(sector, metersPerPixel, wmsLayerConfig, ellipsoid)

        // check that the layer was created by the constructor
        assertNotNull(wmsLayer, "layer created")

        // check the mock radius is providing the resolution level
        assertEquals(anticipatedLevels, (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels, "detail levels")
    }

    /**
     * Test the three parameter setConfiguration method for throwing of [IllegalArgumentException] when null is
     * provided for required parameters.
     */
    @Test
    fun testSetConfiguration_ThreeParameter_NullParameters() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5

        // initial object for testing method
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig)

        // test invalid submissions throw exceptions
        try {
            wmsLayer.setConfiguration(initialSector, -metersPerPixel, initialWmsLayerConfig)
            fail("provided invalid argument")
        } catch (ex: IllegalArgumentException) {
            assertNotNull(ex, "null exception thrown")
        }
    }

    /**
     * Test the three parameter `setConfiguration` method updates when the [Sector] is changed.
     */
    @Test
    fun testSetConfiguration_ThreeParameter_SectorUpdate() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig)
        val alternativeLatMin = -45.0
        val alternativeLonMin = 50.0
        val alternativeDeltaLat = 5.0
        val alternativeDeltaLon = 2.0
        val alternativeSector = fromDegrees(
            alternativeLatMin, alternativeLonMin, alternativeDeltaLat, alternativeDeltaLon
        )
        wmsLayer.setConfiguration(alternativeSector, metersPerPixel, initialWmsLayerConfig)
        val sector = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.sector
        assertEquals(alternativeSector, sector, "sector updated")
    }

    /**
     * Test the three parameter `setConfiguration` method when the meters per pixel parameter has been changed.
     */
    @Test
    fun testSetConfiguration_ThreeParameter_MetersPerPixelUpdate() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig)
        val alternativeMetersPerPixel = 10.0
        val originalNumberOfLevels = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels
        wmsLayer.setConfiguration(initialSector, alternativeMetersPerPixel, initialWmsLayerConfig)
        val numberOfLevels = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels

        // assertEquals is not used as the determination of the number of levels is a function of LevelSetConfig
        assertNotEquals(originalNumberOfLevels, numberOfLevels, "levels updated")
    }

    /**
     * Test the four parameter setConfiguration method for throwing of [IllegalArgumentException] when null is
     * provided for required parameters.
     */
    @Test
    fun testSetConfiguration_FourParameter_NullParameters() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val notionalGlobeRadius = 3000000.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialEllipsoid = mockk<Ellipsoid>(relaxed = true)
        every { initialEllipsoid.semiMajorAxis } returns notionalGlobeRadius
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig, initialEllipsoid)

        // test invalid submissions throw exceptions
        try {
            wmsLayer.setConfiguration(initialSector, -metersPerPixel, initialWmsLayerConfig, initialEllipsoid)
            fail("provided invalid argument")
        } catch (ex: IllegalArgumentException) {
            assertNotNull(ex)
        }
    }

    /**
     * Test the four parameter `setConfiguration` method updates when the [Sector] is changed.
     */
    @Test
    fun testSetConfiguration_FourParameter_SectorUpdate() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val notionalGlobeRadius = 3000000.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialEllipsoid = mockk<Ellipsoid>(relaxed = true)
        every { initialEllipsoid.semiMajorAxis } returns notionalGlobeRadius
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig, initialEllipsoid)
        val alternativeLatMin = -45.0
        val alternativeLonMin = 50.0
        val alternativeDeltaLat = 5.0
        val alternativeDeltaLon = 2.0
        val alternativeSector = fromDegrees(
            alternativeLatMin, alternativeLonMin, alternativeDeltaLat, alternativeDeltaLon
        )
        wmsLayer.setConfiguration(
            alternativeSector, metersPerPixel, initialWmsLayerConfig, initialEllipsoid
        )
        val sector = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.sector
        assertEquals(alternativeSector, sector, "sector updated")
    }

    /**
     * Test the four parameter `setConfiguration` method updates when the [Ellipsoid] is changed.
     */
    @Test
    fun testSetConfiguration_FourParameter_GlobeUpdate() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val notionalGlobeRadius = 3000000.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialEllipsoid = mockk<Ellipsoid>(relaxed = true)
        every { initialEllipsoid.semiMajorAxis } returns notionalGlobeRadius
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig, initialEllipsoid)
        val initialLayers = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels
        val alternativeEllipsoid = mockk<Ellipsoid>(relaxed = true)
        every { alternativeEllipsoid.semiMajorAxis } returns 2 * notionalGlobeRadius
        wmsLayer.setConfiguration(initialSector, metersPerPixel, initialWmsLayerConfig, alternativeEllipsoid)
        val numberOfLevels = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels
        assertNotEquals(initialLayers, numberOfLevels, "layer levels updated by globe object change")
    }

    /**
     * Test the four parameter `setConfiguration` method updates when the meters per pixel is changed.
     */
    @Test
    fun testSetConfiguration_FourParameter_MetersPerPixelUpdate() {
        // Mocked objects facilitating testing
        val minLat = 10.0
        val deltaLat = 1.0
        val minLon = -95.0
        val deltaLon = 2.0
        val notionalGlobeRadius = 3000000.0
        val initialSector = fromDegrees(minLat, minLon, deltaLat, deltaLon)
        val initialEllipsoid = mockk<Ellipsoid>(relaxed = true)
        every { initialEllipsoid.semiMajorAxis } returns notionalGlobeRadius
        val initialNotionalServiceAddress = "notionalServiceAddress"
        val initialNotionalLayerList = "notionalLayerList"
        val initialWmsLayerConfig = WmsLayerConfig(initialNotionalServiceAddress, initialNotionalLayerList)
        val metersPerPixel = 0.5
        val wmsLayer = WmsLayer(initialSector, metersPerPixel, initialWmsLayerConfig, initialEllipsoid)
        val alternativeMetersPerPixel = 10.0
        val originalNumberOfLevels = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels
        wmsLayer.setConfiguration(
            initialSector, alternativeMetersPerPixel, initialWmsLayerConfig, initialEllipsoid
        )
        val numberOfLevels = (wmsLayer.getRenderable(0) as TiledSurfaceImage).levelSet.numLevels
        assertNotEquals(originalNumberOfLevels, numberOfLevels, "levels updated")
    }
}