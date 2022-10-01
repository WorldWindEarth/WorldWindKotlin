package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.util.Logger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import kotlin.test.*

class WmsTileFactoryTest {
    @BeforeTest
    fun setup() {
        mockkStatic(Logger::class)
        every { Logger.logMessage(any(), any(), any(), any()) } returns ""
    }

    /**
     * Tests the [WmsTileFactory] constructor that takes the different service parameters. Checks that parameters
     * which are not intended to be null throw [IllegalArgumentException].
     */
    @Test
    fun testConstructor_ServiceParameters() {
        // Check parameters are passed successfully and utilized
        val wmsFactory = WmsTileFactory(COMMON_SERVICE_ADDRESS, COMMON_WMS_VERSION, COMMON_LAYER_NAMES, null)
        assertEquals(COMMON_SERVICE_ADDRESS, wmsFactory.serviceAddress, "Service Address Match")
        assertEquals(COMMON_WMS_VERSION, wmsFactory.wmsVersion, "WMS Version Match")
        assertEquals(COMMON_LAYER_NAMES, wmsFactory.layerNames, "Layer Name Match")
        assertNull(wmsFactory.styleNames, "Null Style Names")
    }

    /**
     * Tests the [WmsTileFactory] constructor which takes a single non-null [WmsLayerConfig] object. Checks
     * that a null [WmsLayerConfig] will throw an [IllegalArgumentException].
     */
    @Test
    fun testConstructor_Config() {
        // The config constructor incorporates the same pattern as the service parameters constructor
        // Test all null checks conducted by the config constructor
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val wmsFactory = WmsTileFactory(layerConfig)
        assertEquals(layerConfig.serviceAddress, wmsFactory.serviceAddress, "service address match")
        assertEquals(layerConfig.wmsVersion, wmsFactory.wmsVersion, "wms version match")
        assertEquals(layerConfig.layerNames, wmsFactory.layerNames, "layer name match")
        assertEquals(layerConfig.coordinateSystem, wmsFactory.coordinateSystem, "coordinate system match")
        assertNull(wmsFactory.styleNames, "null style names")
        assertNull(wmsFactory.imageFormat, "null image format")
    }

    /**
     * Test the `setServiceAddress` method. Testing includes ensuring null submissions throw an [ ].
     */
    @Test
    fun testSetServiceAddress() {
        val alteredServiceAddress = "testAddress" // notional address
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.serviceAddress = alteredServiceAddress
        val serviceAddress = standardWmsMapFactory.serviceAddress
        assertEquals(alteredServiceAddress, serviceAddress, "update service address")
    }

    /**
     * Test the `getServiceAddress` method.
     */
    @Test
    fun testGetServiceAddress() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val serviceAddress = standardWmsMapFactory.serviceAddress
        assertEquals(COMMON_SERVICE_ADDRESS, serviceAddress, "update service address")
    }

    /**
     * Test the `setWmsVersion` method. Testing includes ensuring null submissions throw an [ ].
     */
    @Test
    fun testSetWmsVersion() {
        val updatedWmsVersion = "1.4.0" // notional versioning
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.wmsVersion = updatedWmsVersion
        val wmsVersion = standardWmsMapFactory.wmsVersion
        assertEquals(updatedWmsVersion, wmsVersion, "update wms version")
    }

    /**
     * Test the `getWmsVersion` method.
     */
    @Test
    fun testGetWmsVersion() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val wmsVersion = standardWmsMapFactory.wmsVersion
        assertEquals(COMMON_WMS_VERSION, wmsVersion, "wms version")
    }

    /**
     * Test the `setLayerNames` method. Testing includes ensuring null submissions throw an [ ].
     */
    @Test
    fun testSetLayerNames() {
        val updatedLayerNames = "layer1,layer2" // notional
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.layerNames = updatedLayerNames
        val layerNames = standardWmsMapFactory.layerNames
        assertEquals(updatedLayerNames, layerNames, "update layer names")
    }

    /**
     * Test the `getLayerNames` method.
     */
    @Test
    fun testGetLayerNames() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val layerNames = standardWmsMapFactory.layerNames
        assertEquals(COMMON_LAYER_NAMES, layerNames, "layer names")
    }

    /**
     * Test the `setStyleNames` method.
     */
    @Test
    fun testSetStyleNames() {
        val updatedStyleNames = "style1,style2" // notional
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.styleNames = updatedStyleNames
        val styleNames = standardWmsMapFactory.styleNames
        assertEquals(updatedStyleNames, styleNames, "update style names")
    }

    /**
     * Test the `getStyleNames` method. A default instantiation of a [WmsTileFactory] will null style names
     * and this setting is tested in this test.
     */
    @Test
    fun testGetStyleNames_Null() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val styleNames = standardWmsMapFactory.styleNames
        assertNull(styleNames, "default null style names")
    }

    /**
     * Test the `getStyleNames` method. A default instantiation of a [WmsTileFactory] will null style names.
     * This test sets a style name, then proceeds with the test.
     */
    @Test
    fun testGetStyleNames_NotNull() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val notionalStyleNames = "notionalstyle1,notionalstyle2"
        standardWmsMapFactory.styleNames = notionalStyleNames
        val styleNames = standardWmsMapFactory.styleNames
        assertEquals(notionalStyleNames, styleNames, "style names")
    }

    /**
     * Test the `setCoordinateSystem` method. Testing includes ensuring null submissions throw an [ ].
     */
    @Test
    fun testSetCoordinateSystem() {
        val updatedCoordinateSystem = "system" // notional
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.coordinateSystem = updatedCoordinateSystem
        val coordinateSystem = standardWmsMapFactory.coordinateSystem
        assertEquals(updatedCoordinateSystem, coordinateSystem, "update coordinate system")
    }

    /**
     * Test the `getCoordinateSystem` method.
     */
    @Test
    fun testGetCoordinateSystem() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val coordinateSystem = standardWmsMapFactory.coordinateSystem

        // at this time the default coordinate is EPSG
        assertEquals(SYSTEM_EPSG4326, coordinateSystem, "coordinate system")
    }

    /**
     * Test the `setTransparency` method.
     */
    @Test
    fun testSetTransparency() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val previousSetting = standardWmsMapFactory.isTransparent
        standardWmsMapFactory.isTransparent = !previousSetting
        assertNotEquals(previousSetting, standardWmsMapFactory.isTransparent, "ensure transparency set")
    }

    /**
     * Test the `isTransparent` method.
     */
    @Test
    fun testIsTransparent() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val transparent = standardWmsMapFactory.isTransparent

        // default of WmsLayerConfig is transparency = true
        assertTrue(transparent, "is transparent")
    }

    /**
     * Test the `setImageFormat` method.
     */
    @Test
    fun testSetImageFormat() {
        val updatedImageFormat = "image/jpeg" // notional
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.imageFormat = updatedImageFormat
        val imageFormat = standardWmsMapFactory.imageFormat
        assertEquals(updatedImageFormat, imageFormat, "update image format")
    }

    /**
     * Test the `getImageFormat` method. A default instantiation of a [WmsTileFactory] will null the image
     * format and this setting is tested in this test.
     */
    @Test
    fun testGetImageFormat_Null() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val imageFormat = standardWmsMapFactory.imageFormat
        assertNull(imageFormat, "default null of image format")
    }

    /**
     * Test the `getImageFormat` method. A default instantiation of a [WmsTileFactory] will null the image
     * format. This test sets a notional image format, then proceeds with the test.
     */
    @Test
    fun testGetImageFormat_NotNull() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val alternativeImageFormat = "image/jpeg"
        standardWmsMapFactory.imageFormat = alternativeImageFormat
        val imageFormat = standardWmsMapFactory.imageFormat
        assertEquals(alternativeImageFormat, imageFormat, "updated time string")
    }

    /**
     * Test the `setTimeString` method.
     */
    @Test
    fun testSetTimeString() {
        val updatedTimeString = "time" // notional
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.timeString = updatedTimeString
        val time = standardWmsMapFactory.timeString
        assertEquals(updatedTimeString, time, "update time string")
    }

    /**
     * Test the `getTimeString` method. A default instantiation of a [WmsTileFactory] will null the time
     * string and this setting is tested in this test.
     */
    @Test
    fun testGetTimeString_Null() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val timeString = standardWmsMapFactory.timeString
        assertNull(timeString, "default null of time string")
    }

    /**
     * Test the `getTimeString` method. A default instantiation of a [WmsTileFactory] will null the time
     * string. This test sets a notional time string, then proceeds with the test.
     */
    @Test
    fun testGetTimeString_NotNull() {
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        val alternativeTimeString = "1600-NOTIONAL"
        standardWmsMapFactory.timeString = alternativeTimeString
        val timeString = standardWmsMapFactory.timeString
        assertEquals(alternativeTimeString, timeString, "updated time string")
    }

    /**
     * Test that null submission to required parameters result in a thrown [IllegalArgumentException].
     */
    @Test
    fun testUrlForTile_ParameterCheck() {
        // check nulls are not permitted
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val sector = mockk<Sector>(relaxed = true)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        try {
            standardWmsMapFactory.urlForTile(sector, 0, 0)
            fail("null Parameters Pushed to urlForTile")
        } catch (ex: IllegalArgumentException) {
            assertNotNull(ex, "null exception thrown")
        }
    }

    /**
     * Test that the query delimiter is properly placed in the url. This test ensures that a base url, defined as an url
     * which does not include the query delimiter '?', has the delimiter appended to the url.
     */
    @Test
    fun testUrlForTile_QueryDelimiterPositioning_BaseUrl() {
        // Mocking of method object parameters - notional values
        val tileHeight = 5
        val tileWidth = 4
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON

        // Provide the method a service address without a query delimiter
        val wmsFactory = WmsTileFactory(COMMON_SERVICE_ADDRESS, COMMON_WMS_VERSION, COMMON_LAYER_NAMES, null)
        val url = wmsFactory.urlForTile(sector, tileWidth, tileHeight)
        checkQueryDelimiter(url)
    }

    /**
     * Test that the query delimiter is properly placed in the url. This test ensures an url which includes a query
     * delimiter character at the end of the address will not be changed while appending additional parameters.
     */
    @Test
    fun testUrlForTile_QueryDelimiterPositioning_DelimiterAppended() {
        // Mocking of method object parameters - notional values
        val tileHeight = 5
        val tileWidth = 4
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON

        // Provide the method a service address with a query delimiter appended
        val wmsFactory = WmsTileFactory(
            "$COMMON_SERVICE_ADDRESS?", COMMON_WMS_VERSION, COMMON_LAYER_NAMES, null
        )
        val url = wmsFactory.urlForTile(sector, tileWidth, tileHeight)
        checkQueryDelimiter(url)
    }

    /**
     * Test that the query delimiter is properly placed in the url. This test ensures the provided service address which
     * includes a query delimiter followed by parameters will only have an ampersand appended by the factory.
     */
    @Test
    fun testUrlForTile_QueryDelimiterPositioning_BareUrl_AdditionalParameters() {
        // Mocking of method object parameters - notional values
        val tileHeight = 5
        val tileWidth = 4
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON

        // Provide the method a service address with a query delimiter and existing parameters
        val wmsFactory = WmsTileFactory(
            "$COMMON_SERVICE_ADDRESS?NOTIONAL=YES", COMMON_WMS_VERSION, COMMON_LAYER_NAMES, null
        )
        val url = wmsFactory.urlForTile(sector, tileWidth, tileHeight)
        checkQueryDelimiter(url)
    }

    /**
     * Test that the query delimiter is properly placed in the url. This test ensures the provided service address which
     * includes a query delimiter followed by parameters and an ampersand be unaltered by the factory.
     */
    @Test
    fun testUrlForTile_QueryDelimiterPositioning_BareUrl_AdditionalParametersWithAmpersand() {
        // Mocking of method object parameters - notional values
        val tileHeight = 5
        val tileWidth = 4
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON

        // Provide the method a service address with a query delimiter and existing parameters
        val wmsFactory = WmsTileFactory(
            "$COMMON_SERVICE_ADDRESS?NOTIONAL=YES&", COMMON_WMS_VERSION, COMMON_LAYER_NAMES, null
        )
        val url = wmsFactory.urlForTile(sector, tileWidth, tileHeight)
        checkQueryDelimiter(url)
    }

    /**
     * Tests the generated url parameters match the properties of the [WmsTileFactory] used to generate the url.
     * This test evaluates the configuration: WMS version 1.3.0 using the EPSG:4326 coordinate format.
     * <br></br>
     *
     * This is part of test suite detailed below:
     * <br></br>
     *
     * Essentially three different formats for describing the bounding box to the WMS servier. A four and fifth test
     * case provide for testing the optional STYLE and TIME parameters. 1. WMS Version 1.3.0 and EPSG:4326 2. WMS
     * Version 1.3.0 and CRS:84 3. Other WMS Version 4. Optional Styles Parameter 5. Optional Time Parameter
     */
    @Test
    fun testUrlForTile_Parameters_Wms130_EPSG4326() {
        // Create mocked supporting objects
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.serviceAddress = COMMON_SERVICE_ADDRESS
        standardWmsMapFactory.wmsVersion = COMMON_WMS_VERSION
        standardWmsMapFactory.coordinateSystem = SYSTEM_EPSG4326
        val url = standardWmsMapFactory.urlForTile(sector, NOTIONAL_WIDTH, NOTIONAL_HEIGHT)
        checkUrl(url, standardWmsMapFactory)
    }

    /**
     * Tests the generated url parameters match the properties of the [WmsTileFactory] used to generate the url.
     * This test evaluates the configuration: WMS version 1.3.0 using the CRS:84 coordinate format.
     * <br></br>
     *
     * This is part of test suite detailed below:
     * <br></br>
     *
     * Essentially three different formats for describing the bounding box to the WMS servier. A four and fifth test
     * case provide for testing the optional STYLE and TIME parameters. 1. WMS Version 1.3.0 and EPSG:4326 2. WMS
     * Version 1.3.0 and CRS:84 3. Other WMS Version 4. Optional Styles Parameter 5. Optional Time Parameter
     */
    @Test
    fun testUrlForTile_Parameters_Wms130_CRS84() {
        // Create mocked supporting objects
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.serviceAddress = COMMON_SERVICE_ADDRESS
        standardWmsMapFactory.wmsVersion = COMMON_WMS_VERSION
        standardWmsMapFactory.coordinateSystem = SYSTEM_CRS84
        val url = standardWmsMapFactory.urlForTile(sector, NOTIONAL_WIDTH, NOTIONAL_HEIGHT)
        checkUrl(url, standardWmsMapFactory)
    }

    /**
     * Tests the generated url parameters match the properties of the [WmsTileFactory] used to generate the url.
     * This test evaluates the configuration: WMS Version other than 1.3.0.
     * <br></br>
     *
     * This is part of test suite detailed below:
     * <br></br>
     *
     * Essentially three different formats for describing the bounding box to the WMS servier. A four and fifth test
     * case provide for testing the optional STYLE and TIME parameters. 1. WMS Version 1.3.0 and EPSG:4326 2. WMS
     * Version 1.3.0 and CRS:84 3. Other WMS Version 4. Optional Styles Parameter 5. Optional Time Parameter
     */
    @Test
    fun testUrlForTile_Parameters_WmsNot130() {
        // Create mocked supporting objects
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.serviceAddress = COMMON_SERVICE_ADDRESS
        standardWmsMapFactory.wmsVersion = NOTIONAL_WMS_VERSION
        val url = standardWmsMapFactory.urlForTile(sector, NOTIONAL_WIDTH, NOTIONAL_HEIGHT)
        checkUrl(url, standardWmsMapFactory)
    }

    /**
     * Tests the generated url parameters match the properties of the [WmsTileFactory] used to generate the url.
     * This test evaluates the configuration: Addition of optional Style parameter.
     * <br></br>
     *
     * This is part of test suite detailed below:
     * <br></br>
     *
     * Essentially three different formats for describing the bounding box to the WMS servier. A four and fifth test
     * case provide for testing the optional STYLE and TIME parameters. 1. WMS Version 1.3.0 and EPSG:4326 2. WMS
     * Version 1.3.0 and CRS:84 3. Other WMS Version 4. Optional Styles Parameter 5. Optional Time Parameter
     */
    @Test
    fun testUrlForTile_Parameters_OptionalStyles() {
        // Create mocked supporting objects
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.styleNames = "notionalstyle1,notionalstyle2"
        val url = standardWmsMapFactory.urlForTile(sector, NOTIONAL_WIDTH, NOTIONAL_HEIGHT)
        checkUrl(url, standardWmsMapFactory)
    }

    /**
     * Tests the generated url parameters match the properties of the [WmsTileFactory] used to generate the url.
     * This test evaluates the configuration: Additional optional image format.
     * <br></br>
     *
     * This is part of test suite detailed below:
     * <br></br>
     *
     * Essentially three different formats for describing the bounding box to the WMS servier. A four and fifth test
     * case provide for testing the optional STYLE and TIME parameters. 1. WMS Version 1.3.0 and EPSG:4326 2. WMS
     * Version 1.3.0 and CRS:84 3. Other WMS Version 4. Optional Styles Parameter 5. Optional Time Parameter
     */
    @Test
    fun testUrlForTile_Parameters_OptionalImageFormat() {
        // Create mocked supporting objects
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)
        standardWmsMapFactory.imageFormat = "type/name" //notional MIME type
        val url = standardWmsMapFactory.urlForTile(sector, NOTIONAL_WIDTH, NOTIONAL_HEIGHT)
        checkUrl(url, standardWmsMapFactory)
    }

    /**
     * Tests the generated url parameters match the properties of the [WmsTileFactory] used to generate the url.
     * This test evaluates the configuration: Additional optional time parameter.
     * <br></br>
     *
     * This is part of test suite detailed below:
     * <br></br>
     *
     * Essentially three different formats for describing the bounding box to the WMS servier. A four and fifth test
     * case provide for testing the optional STYLE and TIME parameters. 1. WMS Version 1.3.0 and EPSG:4326 2. WMS
     * Version 1.3.0 and CRS:84 3. Other WMS Version 4. Optional Styles Parameter 5. Optional Time Parameter
     */
    @Test
    fun testUrlForTile_Parameters_OptionalTime() {
        // Create mocked supporting objects
        val sector = mockk<Sector>(relaxed = true)
        every { sector.minLatitude.degrees } returns NOTIONAL_MIN_LAT
        every { sector.maxLatitude.degrees } returns NOTIONAL_MAX_LAT
        every { sector.minLongitude.degrees } returns NOTIONAL_MIN_LON
        every { sector.maxLongitude.degrees } returns NOTIONAL_MAX_LON
        val layerConfig = WmsLayerConfig(COMMON_SERVICE_ADDRESS, COMMON_LAYER_NAMES)
        val standardWmsMapFactory = WmsTileFactory(layerConfig)

        // A Standard Tile to use for generating URLs
        standardWmsMapFactory.timeString = "1800-ZULU" //notional time
        val url = standardWmsMapFactory.urlForTile(sector, NOTIONAL_WIDTH, NOTIONAL_HEIGHT)
        checkUrl(url, standardWmsMapFactory)
    }

    companion object {
        /**
         * Common parameters used when creating a WMS request URL.
         */
        private const val COMMON_SERVICE_ADDRESS = "https://worldwind25.arc.nasa.gov/wms"
        private const val COMMON_SERVICE_WMS = "WMS"
        private const val COMMON_WMS_VERSION = "1.3.0"
        private const val COMMON_LAYER_NAMES = "BlueMarble-200405,esat"
        private const val SYSTEM_CRS84 = "CRS:84"
        private const val SYSTEM_EPSG4326 = "EPSG:4326"

        /**
         * Notional values used for testing.
         */
        private const val DELTA = 1e-6
        private const val NOTIONAL_MIN_LAT = -90.0
        private const val NOTIONAL_MAX_LAT = 0.0
        private const val NOTIONAL_MIN_LON = -180.0
        private const val NOTIONAL_MAX_LON = -90.0
        private const val NOTIONAL_WIDTH = 10
        private const val NOTIONAL_HEIGHT = 11
        private const val NOTIONAL_WMS_VERSION = "1.23"

        /**
         * Enumerations of a double array used internally for storing parsed values of the latitude and longitude.
         */
        private const val LAT_MIN = 0
        private const val LAT_MAX = 1
        private const val LON_MIN = 2
        private const val LON_MAX = 3

        /**
         * Patterns for checking the generated URL parameters.
         */
        private val SERVICE_P = "SERVICE=(.*?)(&|\\z)".toRegex()
        private val VERSION_P = "VERSION=(.*?)(&|\\z)".toRegex()
        private val LAYERS_P = "LAYERS=(.*?)(&|\\z)".toRegex()
        private val STYLES_P = "STYLES=(.*?)(&|\\z)".toRegex()
        private val CRS_P = "[CS]RS=(.*?)(&|\\z)".toRegex()
        private val BBOX_P = "BBOX=(.*?)(&|\\z)".toRegex()
        private val WIDTH_P = "WIDTH=(.*?)(&|\\z)".toRegex()
        private val HEIGHT_P = "HEIGHT=(.*?)(&|\\z)".toRegex()
        private val FORMAT_P = "FORMAT=(.*?)(&|\\z)".toRegex()
        private val TRANSPARENT_P = "TRANSPARENT=(.*?)(&|\\z)".toRegex()
        private val TIME_P = "TIME=(.*?)(&|\\z)".toRegex()

        /**
         * Test the provided [String] url against the [WmsTileFactory] objects properties. This method will test
         * that the parameters of [WmsTileFactory] are properly represented in the url. This method uses the [ ] methods to communicate test results.
         *
         * @param url     the generated [String] url to be evaluated
         * @param factory the [WmsTileFactory] which generated the url
         */
        private fun checkUrl(url: String, factory: WmsTileFactory) {
            // Test Service Description - WMS only at this time
            var m = SERVICE_P.find(url)
            if (m != null) assertEquals(COMMON_SERVICE_WMS, m.groups[1]?.value, "test service parameter")
            else fail("service parameter not found")

            // Test Version
            m = VERSION_P.find(url)
            if (m != null) assertEquals(factory.wmsVersion, m.groups[1]?.value, "test wms version parameter")
            else fail("wms version parameter not found")

            // Test Layers
            m = LAYERS_P.find(url)
            if (m != null) assertEquals(factory.layerNames.replace(",", "%2C"), m.groups[1]?.value, "test layer list parameter")
            else fail("layer list parameter not found")

            // Test Styles
            if (factory.styleNames != null) {
                m = STYLES_P.find(url)
                if (m != null) assertEquals(factory.styleNames?.replace(",", "%2C"), m.groups[1]?.value, "test style parameter")
                else fail("style list parameter not found")
            }

            // Test CRS/SRS System
            m = CRS_P.find(url)
            if (m != null) assertEquals(factory.coordinateSystem.replace(":", "%3A"), m.groups[1]?.value, "test coordinate system parameter")
            else fail("coordinate system parameter not found")

            // Test Bounding Box
            m = BBOX_P.find(url)
            if (m != null) {
                // Now need to split up the values and parse to doubles
                val values = requireNotNull(m.groups[1]?.value).split("%2C").toTypedArray()
                if (values.size == 4) {
                    val coords = DoubleArray(4)

                    // From this point need to proceed with knowledge of the WMS version and coordinate system in order
                    // to parse the values in the right order
                    if (factory.wmsVersion == COMMON_WMS_VERSION) {
                        if (factory.coordinateSystem == SYSTEM_CRS84) {
                            coords[LON_MIN] = values[0].toDouble()
                            coords[LAT_MIN] = values[1].toDouble()
                            coords[LON_MAX] = values[2].toDouble()
                            coords[LAT_MAX] = values[3].toDouble()
                        } else {
                            coords[LAT_MIN] = values[0].toDouble()
                            coords[LON_MIN] = values[1].toDouble()
                            coords[LAT_MAX] = values[2].toDouble()
                            coords[LON_MAX] = values[3].toDouble()
                        }
                    } else {
                        coords[LON_MIN] = values[0].toDouble()
                        coords[LAT_MIN] = values[1].toDouble()
                        coords[LON_MAX] = values[2].toDouble()
                        coords[LAT_MAX] = values[3].toDouble()
                    }

                    //Now Check the values
                    assertEquals(NOTIONAL_MIN_LAT, coords[LAT_MIN], DELTA, "test min lat")
                    assertEquals(NOTIONAL_MAX_LAT, coords[LAT_MAX], DELTA, "test max lat")
                    assertEquals(NOTIONAL_MIN_LON, coords[LON_MIN], DELTA, "test min lon")
                    assertEquals(NOTIONAL_MAX_LON, coords[LON_MAX], DELTA, "test max lon")
                } else fail("unable to delimit bounding box values")
            } else fail("unable to find bounding box values")

            // Test Width and Height
            m = WIDTH_P.find(url)
            if (m != null) assertEquals(NOTIONAL_WIDTH, requireNotNull(m.groups[1]?.value).toInt(), "width value test")
            else fail("did not find width parameter")
            m = HEIGHT_P.find(url)
            if (m != null) assertEquals(NOTIONAL_HEIGHT, requireNotNull(m.groups[1]?.value).toInt(), "height value test")
            else fail("did not find height parameter")

            // Test Format
            m = FORMAT_P.find(url)
            if (m != null)
                if (factory.imageFormat == null) assertEquals("image%2Fpng", m.groups[1]?.value, "format test (default)")
                else assertEquals(factory.imageFormat?.replace("/", "%2F"), m.groups[1]?.value, "format test")
            else fail("image format parameter not found")

            // Test Transparency
            m = TRANSPARENT_P.find(url)
            if (m != null) assertEquals(factory.isTransparent, m.groups[1]?.value.toBoolean(), "test transparency")
            else fail("transparency parameter not found")

            // Test Time, if there is any
            val timeString = factory.timeString
            if (!timeString.isNullOrEmpty()) {
                m = TIME_P.find(url)
                if (m != null) assertEquals(factory.timeString, m.groups[1]?.value, "time test")
                else fail("time did not match")
            }
        }

        /**
         * Tests the provided url [String] with four tests. The first test ensures a query delimiter is present in the
         * [String]. The second test ensures only one query delimiter is present. The third test ensures that the url
         * is not empty behind the query delimiter and contains parameters, although the validity of the parameter is not
         * checked. The fourth test ensures parameters immedietly follow the query delimiter and not the ampersand
         * character.
         *
         * @param url a [String] with the url to test for query delimiter placement
         */
        private fun checkQueryDelimiter(url: String) {
            val queryDelimiter = '?'
            val index = url.indexOf(queryDelimiter)
            assertTrue(index > 0, "added delimiter")

            // ensure only one delimiter
            val lastIndex = url.lastIndexOf(queryDelimiter)
            assertEquals(index, lastIndex, "one delimiter")

            // check parameters follow query delimiter
            assertTrue(url.length - 1 > index, "no following parameters")

            // check trailing character isn't an ampersand
            assertNotEquals('&', url[index + 1], "ampersand trailing")
        }
    }
}