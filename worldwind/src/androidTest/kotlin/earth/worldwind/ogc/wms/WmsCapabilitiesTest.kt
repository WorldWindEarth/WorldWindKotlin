package earth.worldwind.ogc.wms

import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.test.*

class WmsCapabilitiesTest {
    private lateinit var wmsCapabilities130: WmsCapabilities

    @BeforeTest
    fun setup() {
        val inputStream = javaClass.classLoader!!.getResourceAsStream("test_worldwind_wms_capabilities_v1_3_0_spec.xml")!!
        wmsCapabilities130 = XML().decodeFromString(inputStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun testGetVersion_Version130() {
        assertEquals("1.3.0", wmsCapabilities130.version, "Version")
    }

    @Test
    fun testGetImageFormats_Version130() {
        val expectedValues = listOf("image/gif", "image/png", "image/jpeg")
        val actualValues = wmsCapabilities130.capability.request.getMap.formats
        assertContentEquals(expectedValues, actualValues, "Image Formats")
    }

    @Test
    fun testGetServiceInformation_GetAbstract_Version130() {
        val expectedValue = "Map Server maintained by Acme Corporation. Contact: webmaster@wmt.acme.com. High-quality maps showing\n" +
                "            roadrunner nests and possible ambush locations.\n" + "        "
        val serviceInformation = wmsCapabilities130.service
        val serviceAbstract = serviceInformation.abstract
        assertEquals(expectedValue, serviceAbstract, "Service Abstract")
    }

    @Test
    fun testGetServiceInformation_GetName_Version130() {
        val expectedValue = "WMS"
        val serviceName = wmsCapabilities130.service.name
        assertEquals(expectedValue, serviceName, "Service Name")
    }

    @Test
    fun testGetServiceInformation_GetTitle_Version130() {
        val expectedValue = "Acme Corp. Map Server"
        val serviceTitle = wmsCapabilities130.service.title
        assertEquals(expectedValue, serviceTitle, "Service Title")
    }

    @Test
    fun testGetServiceInformation_GetKeywords_Version130() {
        val expectedKeywords = listOf("bird", "roadrunner", "ambush")
        val keywords = wmsCapabilities130.service.keywordList
        assertContentEquals(expectedKeywords, keywords, "Service Keywords")
    }

    @Test
    fun testGetServiceInformation_GetOnlineResource_Version130() {
        val expectedLink = "http://hostname/"
        val serviceInformation = wmsCapabilities130.service
        val link = serviceInformation.url
        assertEquals(expectedLink, link, "Service Online Resource Link")
    }

    @Test
    fun testGetServiceInformation_GetContactPersonPrimary_Version130() {
        val expectedPerson = "Jeff Smith"
        val expectedOrganization = "NASA"
        val contactInformation = wmsCapabilities130.service.contactInformation
        val person = contactInformation?.contactPersonPrimary?.contactPerson
        val organization = contactInformation?.contactPersonPrimary?.contactOrganization
        assertEquals(expectedPerson, person, "Service Contact Information Person Primary")
        assertEquals(expectedOrganization, organization, "Service Contact Information Organization")
    }

    @Test
    fun testGetServiceInformation_GetContactAddress_Version130() {
        val expectedAddressType = "postal"
        val expectedAddress = "NASA Goddard Space Flight Center"
        val expectedCity = "Greenbelt"
        val expectedState = "MD"
        val expectedPostCode = "20771"
        val expectedCountry = "USA"
        val contactAddress = wmsCapabilities130.service.contactInformation?.contactAddress
        val addressType = contactAddress?.addressType
        val address = contactAddress?.address
        val city = contactAddress?.city
        val state = contactAddress?.stateOrProvince
        val postCode = contactAddress?.postCode
        val country = contactAddress?.country
        assertEquals(expectedAddressType, addressType, "Service Contact Address Type")
        assertEquals(expectedAddress, address, "Service Contact Address")
        assertEquals(expectedCity, city, "Service Contact Address City")
        assertEquals(expectedState, state, "Service Contact Address State")
        assertEquals(expectedPostCode, postCode, "Service Contact Address Post Code")
        assertEquals(expectedCountry, country, "Service Contact Address Country")
    }

    @Test
    fun testGetServiceInformation_GetPhone_Version130() {
        val expectedValue = "+1 301 555-1212"
        val voiceTelephone = wmsCapabilities130.service.contactInformation?.voiceTelephone
        assertEquals(expectedValue, voiceTelephone, "Service Phone")
    }

    @Test
    fun testGetServiceInformation_GetEmail_Version130() {
        val expectedValue = "user@host.com"
        val fees = wmsCapabilities130.service.contactInformation?.electronicMailAddress
        assertEquals(expectedValue, fees, "Service Email")
    }

    @Test
    fun testGetServiceInformation_GetFees_Version130() {
        val expectedValue = "none"
        val fees = wmsCapabilities130.service.fees
        assertEquals(expectedValue, fees, "Service Fees")
    }

    @Test
    fun testGetServiceInformation_GetAccessConstraints_Version130() {
        val expectedValue = "none"
        val accessConstraints = wmsCapabilities130.service.accessConstraints
        assertEquals(expectedValue, accessConstraints, "Service Fees")
    }

    @Test
    fun testGetServiceInformation_GetLayerLimit_Version130() {
        val expectedValue = 16
        val layerLimit = wmsCapabilities130.service.layerLimit!!
        assertEquals(expectedValue, layerLimit, "Service Layer Limit")
    }

    @Test
    fun testGetServiceInformation_GetMaxHeightWidth_Version130() {
        val expectedHeight = 2048
        val expectedWidth = 2048
        val maxHeight = wmsCapabilities130.service.maxHeight
        val maxWidth = wmsCapabilities130.service.maxWidth
        assertEquals(expectedHeight, maxHeight, "Service Max Height")
        assertEquals(expectedWidth, maxWidth, "Service Max Width")
    }

    @Test
    fun testGetLayerByName_Version130() {
        val layersToTest = listOf(
            "ROADS_RIVERS", "ROADS_1M", "RIVERS_1M", "Clouds", "Temperature",
            "Pressure", "ozone_image", "population"
        )
        for (layer in layersToTest) {
            val wmsLayer = wmsCapabilities130.getNamedLayer(layer)
            assertNotNull(wmsLayer, "Get Layer By Name $layer")
        }
    }

    @Test
    fun testGetNamedLayers_Version130() {
        val expectedLayers = listOf(
            "ROADS_RIVERS", "ROADS_1M", "RIVERS_1M", "Clouds", "Temperature",
            "Pressure", "ozone_image", "population"
        )
        val initialSize = expectedLayers.size
        val layers = wmsCapabilities130.namedLayers
        var foundCount = 0
        for (layer in layers) if (expectedLayers.contains(layer.name)) foundCount++
        assertEquals(initialSize, layers.size, "Get Named Layers Count")
        assertEquals(initialSize, foundCount, "Get Named Layers Content")
    }

    @Test
    fun testNamedLayerProperties_GetAttribution_Version130() {
        val expectedAttributionTitle = "State College University"
        val expectedAttributionUrl = "http://www.university.edu/"
        val expectedAttributionLogoFormat = "image/gif"
        val expectedAttributionLogoUrl = "http://www.university.edu/icons/logo.gif"
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val attribution = wmsLayer?.attribution
        assertEquals(expectedAttributionTitle, attribution?.title, "Layer Attributions Title")
        assertEquals(expectedAttributionUrl, attribution?.url, "Layer Attributions Url")
        assertEquals(expectedAttributionLogoFormat, attribution?.logoURL?.formats?.iterator()?.next(), "Layer Attributions Logo Format")
        assertEquals(expectedAttributionLogoUrl, attribution?.logoURL?.url, "Layer Attributions Logo Url")
    }

    @Test
    fun testNamedLayerProperties_GetTitleAbstract_Version130() {
        val expectedTitle = "Roads at 1:1M scale"
        val expectedAbstract = "Roads at a scale of 1 to 1 million."
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val title = wmsLayer?.title
        val layerAbstract = wmsLayer?.abstract
        assertEquals(expectedTitle, title, "Layer Title")
        assertEquals(expectedAbstract, layerAbstract, "Layer Abstract")
    }

    @Test
    fun testNamedLayerProperties_GetKeywords_Version130() {
        val expectedKeywords = listOf("road", "transportation", "atlas")
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val keywords = wmsLayer?.keywordList
        assertContentEquals(expectedKeywords, keywords, "Layer Keywords")
    }

    @Test
    fun testNamedLayerProperties_GetIdentities_Version130() {
        val expectedIdentities = 1
        val expectedAuthority = "DIF_ID"
        val expectedIdentifier = "123456"
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val identities = wmsLayer?.identifiers!!
        val authority = identities[0].authority
        val identifier = identities[0].identifier
        assertEquals(expectedIdentities, identities.size, "Layer Identifier Count")
        assertEquals(expectedAuthority, authority, "Layer Authority")
        assertEquals(expectedIdentifier, identifier, "Layer Identifier")
    }

    @Test
    fun testNamedLayerProperties_GetMetadataUrls_Version130() {
        val expectedMetadataUrls = 2
        val expectedMetadataUrlFormats = listOf("text/plain", "text/xml")
        val expectedMetadataUrlTypes = listOf("FGDC:1998", "ISO19115:2003")
        val expectedMetadataUrlUrls = listOf(
            "http://www.university.edu/metadata/roads.txt",
            "http://www.university.edu/metadata/roads.xml"
        )
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val metadataUrls = wmsLayer?.metadataUrls!!
        for (metadataUrl in metadataUrls) {
            assertTrue(expectedMetadataUrlFormats.contains(metadataUrl.formats.iterator().next()), "Layer MetadataUrl Format")
            assertTrue(expectedMetadataUrlTypes.contains(metadataUrl.type), "Layer MetadataUrl Type")
            assertTrue(expectedMetadataUrlUrls.contains(metadataUrl.url), "Layer MetadataUrl Url")
        }
        assertEquals(expectedMetadataUrls, metadataUrls.size, "Layer MetadataUrl Count")
    }

    @Test
    fun testNamedLayerProperties_GetStyles_Version130() {
        val expectedStyles = 2
        val expectedStyleNames = listOf("ATLAS", "USGS")
        val expectedStyleTitles = listOf("Road atlas style", "USGS Topo Map Style")
        val expectedStyleLegendUrl = listOf(
            "http://www.university.edu/legends/atlas.gif",
            "http://www.university.edu/legends/usgs.gif"
        )
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val styles = wmsLayer?.styles!!
        for (style in styles) {
            assertTrue(expectedStyleNames.contains(style.name), "Layer Style Names")
            assertTrue(expectedStyleTitles.contains(style.title), "Layer Style Titles")
            val legendUrl = style.legendUrls.iterator().next().url
            assertTrue(expectedStyleLegendUrl.contains(legendUrl), "Layer Style Legend Url")
        }
        assertEquals(expectedStyles, styles.size, "Layer Style Count")
    }

    @Test
    fun testNamedLayerProperties_GetReferenceSystems_Version130() {
        val expectedCrsValues = listOf("EPSG:26986", "CRS:84")
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val referenceSystems = wmsLayer?.referenceSystems
        assertEquals(expectedCrsValues, referenceSystems, "Layer Reference System")
    }

    @Test
    fun testNamedLayerProperties_GetGeographicBoundingBox_Version130() {
        val expectedGeographicBoundingBoxWestLong = -71.63
        val expectedGeographicBoundingBoxEastLong = -70.78
        val expectedGeographicBoundingBoxSouthLat = 41.75
        val expectedGeographicBoundingBoxNorthLat = 42.90
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val sector = wmsLayer?.geographicBoundingBox!!
        assertEquals(expectedGeographicBoundingBoxWestLong, sector.minLongitude.degrees, 0.0, "Layer Geographic Bounding Box West")
        assertEquals(expectedGeographicBoundingBoxEastLong, sector.maxLongitude.degrees, 0.0, "Layer Geographic Bounding Box East")
        assertEquals(expectedGeographicBoundingBoxNorthLat, sector.maxLatitude.degrees, 0.0, "Layer Geographic Bounding Box North")
        assertEquals(expectedGeographicBoundingBoxSouthLat, sector.minLatitude.degrees, 0.0, "Layer Geographic Bounding Box South")
    }

    @Test
    fun testNamedLayerProperties_GetBoundingBox_Version130() {
        val expectedCrs84BoundingBoxMinx = -71.63
        val expectedCrs84BoundingBoxMiny = 41.75
        val expectedCrs84BoundingBoxMaxx = -70.78
        val expectedCrs84BoundingBoxMaxy = 42.90
        val expectedEpsgBoundingBoxMinx = 189000.0
        val expectedEpsgBoundingBoxMiny = 834000.0
        val expectedEpsgBoundingBoxMaxx = 285000.0
        val expectedEpsgBoundingBoxMaxy = 962000.0
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val boxes = wmsLayer?.boundingBoxes!!
        for (box in boxes) {
            val minx = box.minx
            val miny = box.miny
            val maxx = box.maxx
            val maxy = box.maxy
            when (box.CRS) {
                "CRS:84" -> {
                    assertEquals(expectedCrs84BoundingBoxMinx, minx, 0.0, "Layer Bounding Box CRS:84 Minx")
                    assertEquals(expectedCrs84BoundingBoxMiny, miny, 0.0, "Layer Bounding Box CRS:84 Miny")
                    assertEquals(expectedCrs84BoundingBoxMaxx, maxx, 0.0, "Layer Bounding Box CRS:84 Maxx")
                    assertEquals(expectedCrs84BoundingBoxMaxy, maxy, 0.0, "Layer Bounding Box CRS:84 Maxy")
                }
                "EPSG:26986" -> {
                    assertEquals(expectedEpsgBoundingBoxMinx, minx, 0.0, "Layer Bounding Box EPSG:26986 Minx")
                    assertEquals(expectedEpsgBoundingBoxMiny, miny, 0.0, "Layer Bounding Box EPSG:26986 Miny")
                    assertEquals(expectedEpsgBoundingBoxMaxx, maxx, 0.0, "Layer Bounding Box EPSG:26986 Maxx")
                    assertEquals(expectedEpsgBoundingBoxMaxy, maxy, 0.0, "Layer Bounding Box EPSG:26986 Maxy")
                }
                else -> fail("Unexpected Layer Coordinate System")
            }
        }
        assertEquals(2, boxes.size, "Layer Bounding Box Count")
    }

    @Test
    fun testServiceCapabilities() {
        val wmsLayer = wmsCapabilities130.getNamedLayer("ROADS_1M")
        val wmsCapabilities = wmsLayer?.capability?.capabilities
        assertEquals(wmsCapabilities130, wmsCapabilities, "Layer Service Capabilities")
    }

    @Test
    fun testGetCapabilitiesURL_Version130() {
        val getCapabilities = wmsCapabilities130.capability.request.getCapabilities
        val expectedUrl = "http://hostname/path?"
        val url = getCapabilities.getUrl
        assertEquals(expectedUrl, url, "GetCapabilities URL")
    }

    @Test
    fun testGetMapURL_Version130() {
        val getMap = wmsCapabilities130.capability.request.getMap
        val expectedUrl = "http://hostname/path?"
        val url = getMap.getUrl
        assertEquals(expectedUrl, url, "GetMap URL")
    }

    @Test
    fun testGetFeatureInfoURL_Version130() {
        val getFeatureInfo = wmsCapabilities130.capability.request.getFeatureInfo
        val expectedUrl = "http://hostname/path?"
        val url = getFeatureInfo?.getUrl
        assertEquals(expectedUrl, url, "GetFeatureInfo URL")
    }
}