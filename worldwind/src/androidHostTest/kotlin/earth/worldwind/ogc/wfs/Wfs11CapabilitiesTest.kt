package earth.worldwind.ogc.wfs

import earth.worldwind.ogc.WfsLayerFactory
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.test.*

class Wfs11CapabilitiesTest {
    private lateinit var capabilities: Wfs11Capabilities

    @BeforeTest
    fun setup() {
        val inputStream = javaClass.classLoader!!
            .getResourceAsStream("test_worldwind_wfs_capabilities_v1_1_0_spec.xml")!!
        capabilities = XML { defaultPolicy { ignoreUnknownChildren() } }
            .decodeFromString(inputStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun testVersion() {
        assertEquals("1.1.0", capabilities.version)
    }

    @Test
    fun testFeatureType_Metadata() {
        val ft = capabilities.getFeatureType("topp:states")!!
        assertEquals("US States", ft.title)
        assertEquals("State boundaries", ft.abstract)
        assertEquals("urn:ogc:def:crs:EPSG::4326", ft.defaultSrs)
        assertContentEquals(listOf("urn:ogc:def:crs:EPSG::3857"), ft.otherSrs)
    }

    @Test
    fun testFeatureType_OutputFormats() {
        val ft = capabilities.getFeatureType("topp:states")!!
        assertContentEquals(
            listOf("text/xml; subtype=gml/3.1.1", "application/json"),
            ft.outputFormats,
        )
    }

    @Test
    fun testFeatureType_BoundingBox() {
        val sector = capabilities.getFeatureType("topp:states")!!.wgs84BoundingBox!!.sector!!
        assertEquals(-180.0, sector.minLongitude.inDegrees, 1e-9)
        assertEquals(180.0, sector.maxLongitude.inDegrees, 1e-9)
        assertEquals(-90.0, sector.minLatitude.inDegrees, 1e-9)
        assertEquals(90.0, sector.maxLatitude.inDegrees, 1e-9)
    }

    @Test
    fun testGetFeatureOperation_DirectValuesParsedAsAllowedValues() {
        val getFeature = capabilities.operationsMetadata!!.operations.first { it.name == "GetFeature" }
        val outputFormat = getFeature.parameters.first { it.name == "outputFormat" }
        // OWS 1.0 uses bare <Value> children directly inside Parameter; allowedValues
        // is the flattened view used by the factory.
        assertTrue(outputFormat.allowedValues.contains("application/json"))
        assertTrue(outputFormat.allowedValues.contains("text/xml; subtype=gml/3.1.1"))
    }

    @Test
    fun testSelectGeoJsonFormat11_FromFeatureType() {
        val ft = capabilities.getFeatureType("topp:states")!!
        assertEquals("application/json", WfsLayerFactory.selectGeoJsonFormat11(ft))
    }

    @Test
    fun testCapabilitiesBackReference() {
        val ft = capabilities.getFeatureType("topp:states")!!
        assertSame(capabilities, ft.capabilities)
    }
}
