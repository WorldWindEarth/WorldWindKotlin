package earth.worldwind.ogc

import earth.worldwind.geom.Sector
import earth.worldwind.ogc.wms.*
import earth.worldwind.ogc.wms.WmsLayer
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlin.test.*

class WmsLayerFactoryTest {

    private lateinit var mainScope: CoroutineScope

    @BeforeTest
    fun setup() {
        mainScope = MainScope()
    }

    @AfterTest
    fun destroy() {
        mainScope.cancel()
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_Nominal() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        val wmsLayerConfig = layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
        assertEquals(DEFAULT_VERSION, wmsLayerConfig.wmsVersion, "Version")
        assertEquals(DEFAULT_LAYER_NAME, wmsLayerConfig.layerNames, "Layer Name")
        assertEquals(DEFAULT_REQUEST_URL, wmsLayerConfig.serviceAddress, "Request URL")
        assertEquals("EPSG:4326", wmsLayerConfig.coordinateSystem, "Reference Systems")
        assertEquals("image/png", wmsLayerConfig.imageFormat, "Image Format")
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_InvalidVersion() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        // Invalid WMS version
        every { wmsCapabilities.version } returns "1.2.1"
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        try {
            layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
            fail("Invalid Version")
        } catch (e: RuntimeException) {
            assertNotNull(e, "Invalid Version")
        }
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_InvalidCapability() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        // Invalid Capability
        every { wmsCapabilities.capability.layers[0].capability } returns null
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        try {
            layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
            fail("Invalid Request URL")
        } catch (e: RuntimeException) {
            assertNotNull(e, "Invalid Request URL")
        }
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_OtherCoordinateSystem() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val modifiedReferenceSystems = listOf("CRS:84")
        every { wmsLayer.referenceSystems } returns modifiedReferenceSystems
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        val wmsLayerConfig = layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
        assertEquals("CRS:84", wmsLayerConfig.coordinateSystem, "Alternate Coordinate System")
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_InvalidCoordinateSystem() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val modifiedReferenceSystems = listOf("EPSG:1234")
        every { wmsLayer.referenceSystems } returns modifiedReferenceSystems
        val layerCapabilities: List<WmsLayer> = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        try {
            layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
            fail("Invalid Coordinate System")
        } catch (e: RuntimeException) {
            assertNotNull(e, "Invalid Coordinate System")
        }
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_OtherImageFormat() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val modifiedImageFormats = listOf("image/dds", "image/jpg")
        every { wmsCapabilities.capability.request.getMap.formats } returns modifiedImageFormats
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        val wmsLayerConfig = layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
        assertEquals("image/jpg", wmsLayerConfig.imageFormat, "Alternate Image Format")
    }

    @Test
    fun testGetLayerConfigFromWmsCapabilities_InvalidImageFormat() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val modifiedImageFormats = listOf("image/dds", "image/never")
        every { wmsCapabilities.capability.request.getMap.formats } returns modifiedImageFormats
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        try {
            layerFactory.getLayerConfigFromWmsCapabilities(layerCapabilities)
            fail("Invalid Image Format")
        } catch (e: RuntimeException) {
            assertTrue(true, "Invalid Image Format")
        }
    }

    @Test
    fun testGetLevelSetConfigFromWmsCapabilities_Nominal() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        val levelSetConfig = layerFactory.getLevelSetConfigFromWmsCapabilities(layerCapabilities)
        assertEquals(Sector().setFullSphere(), levelSetConfig.sector, "Bounding Box")
        assertEquals(47, levelSetConfig.numLevels, "Number of Levels")
    }

    @Test
    fun testGetLevelSetConfigFromWmsCapabilities_CoarseScaleDenominator() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        every { wmsLayer.minScaleDenominator } returns 1e13
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        val levelSetConfig = layerFactory.getLevelSetConfigFromWmsCapabilities(layerCapabilities)
        assertEquals(1, levelSetConfig.numLevels, "Number of Levels")
    }

    @Test
    fun testGetLevelSetConfigFromWmsCapabilities_NullScaleDenominator() {
        val wmsCapabilities = boilerPlateWmsCapabilities
        val wmsLayer = wmsCapabilities.getNamedLayer(DEFAULT_LAYER_NAME)!!
        every { wmsLayer.minScaleDenominator } returns null
        val layerCapabilities = listOf(wmsLayer)
        val layerFactory = WmsLayerFactory(mainScope)
        val levelSetConfig = layerFactory.getLevelSetConfigFromWmsCapabilities(layerCapabilities)
        assertEquals(20, levelSetConfig.numLevels, "Number of Levels")
    }

    companion object {
        private const val DEFAULT_LAYER_NAME = "LayerName"
        private const val DEFAULT_REQUEST_URL = "http://example.com"
        private val DEFAULT_REFERENCE_SYSTEMS = listOf("CRS:84", "EPSG:4326")
        private val DEFAULT_IMAGE_FORMATS = listOf("image/png", "image/jpeg")
        private const val DEFAULT_MIN_SCALE_DENOMINATOR = 1e-6
        private const val DEFAULT_VERSION = "1.3.0"
        private const val DEFAULT_TITLE = "Layer Title"
        private val DEFAULT_SECTOR = Sector().setFullSphere()
        private val boilerPlateWmsCapabilities: WmsCapabilities
            get() {
                val mockedRequestOperation = mockk<WmsRequestOperation>(relaxed = true)
                every { mockedRequestOperation.getUrl } returns DEFAULT_REQUEST_URL
                every { mockedRequestOperation.formats } returns DEFAULT_IMAGE_FORMATS
                val mockedRequest = mockk<WmsRequest>(relaxed = true)
                every { mockedRequest.getMap } returns mockedRequestOperation
                val mockedCapability = mockk<WmsCapability>(relaxed = true)
                every { mockedCapability.request } returns mockedRequest
                val mockedLayer = mockk<WmsLayer>(relaxed = true)
                every { mockedLayer.name } returns DEFAULT_LAYER_NAME
                every { mockedLayer.referenceSystems } returns DEFAULT_REFERENCE_SYSTEMS
                every { mockedLayer.minScaleDenominator } returns DEFAULT_MIN_SCALE_DENOMINATOR
                every { mockedLayer.geographicBoundingBox } returns DEFAULT_SECTOR
                every { mockedLayer.title } returns DEFAULT_TITLE
                every { mockedLayer.capability } returns mockedCapability
                val mockedCapabilities = mockk<WmsCapabilities>(relaxed = true)
                every { mockedCapabilities.version } returns DEFAULT_VERSION
                every { mockedCapabilities.capability } returns mockedCapability
                every { mockedCapabilities.getNamedLayer(DEFAULT_LAYER_NAME) } returns mockedLayer
                every { mockedCapability.capabilities } returns mockedCapabilities
                return mockedCapabilities
            }
    }
}