package earth.worldwind.ogc.wfs

import earth.worldwind.geom.Sector
import earth.worldwind.ogc.WfsLayerFactory
import earth.worldwind.ogc.wmts.OwsConstraint
import earth.worldwind.ogc.wmts.OwsOperation
import earth.worldwind.ogc.wmts.OwsOperationsMetadata
import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.test.*

class WfsCapabilitiesTest {
    companion object {
        private const val DELTA = 1e-9
    }

    private lateinit var wfsCapabilities: WfsCapabilities

    @BeforeTest
    fun setup() {
        val inputStream = javaClass.classLoader!!
            .getResourceAsStream("test_worldwind_wfs_capabilities_v2_0_0_spec.xml")!!
        wfsCapabilities = XML { defaultPolicy { ignoreUnknownChildren() } }
            .decodeFromString(inputStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun testVersion() {
        assertEquals("2.0.0", wfsCapabilities.version, "Capabilities version")
    }

    @Test
    fun testServiceIdentification() {
        val si = wfsCapabilities.serviceIdentification!!
        assertEquals("World example Web Feature Service", si.title, "Service Identification Title")
        assertEquals("WFS", si.serviceType, "Service Type")
        assertEquals("2.0.0", si.serviceTypeVersions[0], "Service Type Version")
        assertEquals("none", si.fees, "Fees")
        assertEquals("none", si.accessConstraints[0], "Access Constraints")
        assertContentEquals(listOf("World", "Coastlines", "Boundaries"), si.keywords, "Keywords")
    }

    @Test
    fun testServiceProvider() {
        val sp = wfsCapabilities.serviceProvider!!
        assertEquals("Example Provider", sp.providerName, "Provider Name")
        assertEquals("http://example.org/wfs", sp.providerSiteUrl, "Provider Site URL")
        assertEquals("Jane Doe", sp.serviceContact.individualName, "Contact Name")
        assertEquals(
            "jane@example.org",
            sp.serviceContact.contactInfo?.address?.electronicMailAddresses?.firstOrNull(),
            "Contact Email",
        )
    }

    @Test
    fun testOperationsMetadata_GetFeature() {
        val ops = wfsCapabilities.operationsMetadata!!.operations
        val getFeature = ops.firstOrNull { it.name == "GetFeature" }
        assertNotNull(getFeature, "GetFeature operation present")
        val getUrl = getFeature.dcps[0].getMethods[0].url
        assertEquals("http://example.org/wfs?", getUrl, "GetFeature URL")
    }

    @Test
    fun testFeatureTypeCount() {
        assertEquals(2, wfsCapabilities.featureTypes.size, "Feature type count")
    }

    @Test
    fun testFeatureTypeOne_Metadata() {
        val ft = wfsCapabilities.getFeatureType("topp:coastlines")!!
        assertEquals("World Coastlines", ft.title, "Title")
        assertEquals("World coastlines at 1:10M scale", ft.abstract, "Abstract")
        assertEquals("urn:ogc:def:crs:EPSG::4326", ft.defaultCrs, "Default CRS")
        assertContentEquals(listOf("urn:ogc:def:crs:EPSG::3857"), ft.otherCrs, "Other CRS")
        assertContentEquals(listOf("coast", "shoreline"), ft.keywords, "Keywords")
        assertEquals(2, ft.outputFormats.size, "Output format count")
        assertTrue(ft.outputFormats.contains("application/json"), "Advertises GeoJSON")
        assertEquals(
            "http://example.org/metadata/coastlines.xml",
            ft.metadataUrls.firstOrNull()?.url,
            "Metadata URL",
        )
    }

    @Test
    fun testFeatureTypeOne_BoundingBox() {
        val sector = wfsCapabilities.getFeatureType("topp:coastlines")!!.wgs84BoundingBox!!.sector!!
        assertEquals(-180.0, sector.minLongitude.inDegrees, DELTA, "MinLon")
        assertEquals(180.0, sector.maxLongitude.inDegrees, DELTA, "MaxLon")
        assertEquals(-90.0, sector.minLatitude.inDegrees, DELTA, "MinLat")
        assertEquals(90.0, sector.maxLatitude.inDegrees, DELTA, "MaxLat")
    }

    @Test
    fun testFeatureTypeTwo_GmlOnlyOutputFormat() {
        val ft = wfsCapabilities.getFeatureType("topp:countries")!!
        assertEquals(listOf("application/gml+xml; version=3.2"), ft.outputFormats, "GML only")
        assertEquals("urn:ogc:def:crs:EPSG::4326", ft.defaultCrs, "Default CRS")
        assertTrue(ft.otherCrs.isEmpty(), "No other CRS")
    }

    @Test
    fun testFeatureTypeCapabilitiesBackReference() {
        val ft = wfsCapabilities.getFeatureType("topp:coastlines")!!
        assertSame(wfsCapabilities, ft.capabilities, "Back-reference to capabilities")
    }

    @Test
    fun testSelectGeoJsonFormat_FromFeatureType() {
        val ft = wfsCapabilities.getFeatureType("topp:coastlines")!!
        assertEquals("application/json", WfsLayerFactory.selectGeoJsonFormat(ft), "Feature type advertises JSON")
    }

    @Test
    fun testSelectGeoJsonFormat_FallsBackToOperationParameter() {
        // Feature type advertises only GML, but the GetFeature operation's
        // <ows:Parameter name="outputFormat"> lists application/json
        val ft = wfsCapabilities.getFeatureType("topp:countries")!!
        assertEquals(
            "application/json",
            WfsLayerFactory.selectGeoJsonFormat(ft),
            "Falls back to GetFeature outputFormat parameter",
        )
    }

    @Test
    fun testCountFeaturesInResponse_GeoJson() {
        val body = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"Point","coordinates":[0,0]},"properties":{}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[1,1]},"properties":{}},
            {"type":"Feature","geometry":{"type":"Point","coordinates":[2,2]},"properties":{}}
        ]}"""
        assertEquals(3, WfsLayerFactory.countFeaturesInResponse(body, isGml = false))
    }

    @Test
    fun testCountFeaturesInResponse_GeoJsonEmpty() {
        assertEquals(0, WfsLayerFactory.countFeaturesInResponse("""{"type":"FeatureCollection","features":[]}""", isGml = false))
    }

    @Test
    fun testCountFeaturesInResponse_Gml32CountsMembers() {
        val body = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0">
              <wfs:member><Feature/></wfs:member>
              <wfs:member><Feature/></wfs:member>
              <wfs:member><Feature/></wfs:member>
            </wfs:FeatureCollection>"""
        assertEquals(3, WfsLayerFactory.countFeaturesInResponse(body, isGml = true))
    }

    @Test
    fun testCountFeaturesInResponse_Gml31CountsFeatureMembers() {
        val body = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs" xmlns:gml="http://www.opengis.net/gml">
              <gml:featureMember><Feature/></gml:featureMember>
              <gml:featureMember><Feature/></gml:featureMember>
            </wfs:FeatureCollection>"""
        assertEquals(2, WfsLayerFactory.countFeaturesInResponse(body, isGml = true))
    }

    @Test
    fun testCountFeaturesInResponse_GmlIgnoresNumberMatchedAttribute() {
        // GeoServer wraps responses with numberMatched/numberReturned attrs — those must
        // not be miscounted as feature members.
        val body = """<wfs:FeatureCollection xmlns:wfs="http://www.opengis.net/wfs/2.0"
              numberMatched="2" numberReturned="2">
              <wfs:member><Feature/></wfs:member>
              <wfs:member><Feature/></wfs:member>
            </wfs:FeatureCollection>"""
        assertEquals(2, WfsLayerFactory.countFeaturesInResponse(body, isGml = true))
    }

    @Test
    fun testDecideIsGml_RespectsContentTypeWhenItDisagrees() {
        // Advertised GeoJSON, server actually returned GML
        assertTrue(WfsLayerFactory.decideIsGml(advertisedIsGml = false, contentType = "application/gml+xml; version=3.2"))
        // Advertised GML, server actually returned JSON
        assertFalse(WfsLayerFactory.decideIsGml(advertisedIsGml = true, contentType = "application/json; charset=utf-8"))
    }

    @Test
    fun testDecideIsGml_FallsBackToAdvertisedWhenHeaderIsAmbiguous() {
        assertTrue(WfsLayerFactory.decideIsGml(advertisedIsGml = true, contentType = null))
        assertFalse(WfsLayerFactory.decideIsGml(advertisedIsGml = false, contentType = null))
        assertTrue(WfsLayerFactory.decideIsGml(advertisedIsGml = true, contentType = "text/plain"))
    }

    @Test
    fun testBuildGetFeatureParams_Wfs20() {
        val resolved = WfsLayerFactory.WfsResolved(
            version = "2.0.0",
            displayName = "X",
            getFeatureUrl = "http://example.org/wfs",
            outputFormat = "application/json",
            isGml = false,
            typeNameParam = "TYPENAMES",
            countParam = "COUNT",
        )
        val params = WfsLayerFactory.buildGetFeatureParams(
            resolved = resolved,
            typeName = "topp:states",
            sector = Sector.fromDegrees(-10.0, 20.0, 30.0, 50.0),
            maxFeatures = 500,
            cqlFilter = "POP > 1000",
        )
        assertEquals("WFS", params["SERVICE"])
        assertEquals("2.0.0", params["VERSION"])
        assertEquals("GetFeature", params["REQUEST"])
        assertEquals("topp:states", params["TYPENAMES"])
        assertEquals("application/json", params["OUTPUTFORMAT"])
        assertEquals("500", params["COUNT"])
        assertEquals("POP > 1000", params["CQL_FILTER"])
        assertEquals("-10.0,20.0,20.0,70.0,urn:ogc:def:crs:EPSG::4326", params["BBOX"])
        assertEquals("urn:ogc:def:crs:EPSG::4326", params["SRSNAME"])
    }

    @Test
    fun testBuildGetFeatureParams_Wfs11UsesTypeNameAndMaxFeatures() {
        val resolved = WfsLayerFactory.WfsResolved(
            version = "1.1.0",
            displayName = "X",
            getFeatureUrl = "http://example.org/wfs",
            outputFormat = "application/json",
            isGml = false,
            typeNameParam = "TYPENAME",
            countParam = "MAXFEATURES",
        )
        val params = WfsLayerFactory.buildGetFeatureParams(
            resolved, "topp:states", sector = null, maxFeatures = 100, cqlFilter = null,
        )
        assertEquals("topp:states", params["TYPENAME"], "WFS 1.1 uses TYPENAME (singular)")
        assertEquals("100", params["MAXFEATURES"], "WFS 1.1 uses MAXFEATURES")
        assertFalse(params.containsKey("BBOX"), "BBOX omitted when sector is null")
        assertFalse(params.containsKey("CQL_FILTER"), "CQL_FILTER omitted when null")
    }

    @Test
    fun testSelectGeoJsonFormat_FallsBackToOperationConstraint() {
        // Synthetic capabilities where GetFeature advertises outputFormat via <ows:Constraint>
        // instead of <ows:Parameter> — the alternative encoding permitted by OWS 1.1.
        val ft = WfsFeatureType(name = "topp:onlygml", outputFormats = listOf("application/gml+xml; version=3.2"))
        val caps = WfsCapabilities(
            operationsMetadata = OwsOperationsMetadata(
                operations = listOf(
                    OwsOperation(
                        name = "GetFeature",
                        constraints = listOf(OwsConstraint(name = "outputFormat", allowedValues = listOf("application/json")))
                    )
                )
            ),
            featureTypeList = WfsFeatureTypeList(featureTypes = listOf(ft))
        )
        assertEquals("application/json", WfsLayerFactory.selectGeoJsonFormat(caps.featureTypes[0]))
    }

    @Test
    fun testFeatureType_SupportedCrs_OrdersDefaultFirst() {
        val ft = wfsCapabilities.getFeatureType("topp:coastlines")!!
        assertContentEquals(
            listOf("urn:ogc:def:crs:EPSG::4326", "urn:ogc:def:crs:EPSG::3857"),
            ft.supportedCrs,
            "DefaultCRS appears first, followed by OtherCRS",
        )
    }

    @Test
    fun testFeatureType_SupportedCrs_DefaultOnly() {
        val ft = wfsCapabilities.getFeatureType("topp:countries")!!
        assertContentEquals(listOf("urn:ogc:def:crs:EPSG::4326"), ft.supportedCrs)
    }

    @Test
    fun testCheckForOwsException_ParsesOws11ExceptionReport() {
        val body = """<?xml version="1.0" encoding="UTF-8"?>
            <ows:ExceptionReport xmlns:ows="http://www.opengis.net/ows/1.1" version="2.0.0">
              <ows:Exception exceptionCode="InvalidParameterValue" locator="typeNames">
                <ows:ExceptionText>Feature type 'bogus' is not available</ows:ExceptionText>
              </ows:Exception>
            </ows:ExceptionReport>"""
        val ex = assertFailsWith<WfsServiceException> { WfsLayerFactory.checkForOwsException(body) }
        assertEquals("InvalidParameterValue", ex.exceptionCode)
        assertEquals("typeNames", ex.locator)
        assertEquals("Feature type 'bogus' is not available", ex.exceptionText)
    }

    @Test
    fun testCheckForOwsException_ParsesUnprefixedExceptionReport() {
        val body = """<ExceptionReport xmlns="http://www.opengis.net/ows" version="1.0.0">
              <Exception exceptionCode="NoApplicableCode">
                <ExceptionText>something blew up</ExceptionText>
              </Exception>
            </ExceptionReport>"""
        val ex = assertFailsWith<WfsServiceException> { WfsLayerFactory.checkForOwsException(body) }
        assertEquals("NoApplicableCode", ex.exceptionCode)
        assertEquals("something blew up", ex.exceptionText)
    }

    @Test
    fun testCheckForOwsException_IgnoresNonExceptionBody() {
        WfsLayerFactory.checkForOwsException("""{"type":"FeatureCollection","features":[]}""")
        WfsLayerFactory.checkForOwsException("""<wfs:WFS_Capabilities version="2.0.0"/>""")
    }

    @Test
    fun testSanitizeGeoJson_StripsGeoServerExtensions() {
        val raw = """{"type":"FeatureCollection","totalFeatures":12,"numberMatched":12,""" +
                """"numberReturned":1,"timeStamp":"2025-01-01T00:00:00Z","crs":null,""" +
                """"features":[{"type":"Feature","id":"a.1",""" +
                """"geometry":{"type":"Point","coordinates":[1.0,2.0]},""" +
                """"geometry_name":"the_geom","properties":{"name":"X"}}]}"""
        val sanitized = WfsLayerFactory.sanitizeGeoJson(raw)
        assertFalse(sanitized.contains("geometry_name"), "geometry_name removed")
        assertFalse(sanitized.contains("totalFeatures"), "totalFeatures removed")
        assertFalse(sanitized.contains("numberMatched"), "numberMatched removed")
        assertFalse(sanitized.contains("timeStamp"), "timeStamp removed")
        assertTrue(sanitized.contains("\"type\":\"Point\""), "Geometry preserved")
        assertTrue(sanitized.contains("\"name\":\"X\""), "Properties preserved")
        assertTrue(sanitized.contains("\"id\":\"a.1\""), "Feature id preserved")
        assertTrue(sanitized.contains("\"type\":\"FeatureCollection\""), "FeatureCollection type preserved")
    }

    @Test
    fun testSanitizeGeoJson_AliasesUppercaseNameIntoName() {
        val raw = """{"type":"FeatureCollection","features":[{"type":"Feature","id":"c.1",""" +
                """"geometry":{"type":"Point","coordinates":[0.0,0.0]},""" +
                """"properties":{"NAME":"Atlantis","POP":42}}]}"""
        val sanitized = WfsLayerFactory.sanitizeGeoJson(raw)
        assertTrue(sanitized.contains("\"name\":\"Atlantis\""), "Lowercase name alias added")
        assertTrue(sanitized.contains("\"NAME\":\"Atlantis\""), "Original NAME preserved")
    }

    @Test
    fun testSanitizeGeoJson_DoesNotOverrideExistingName() {
        val raw = """{"type":"FeatureCollection","features":[{"type":"Feature",""" +
                """"geometry":{"type":"Point","coordinates":[0.0,0.0]},""" +
                """"properties":{"name":"already-set","NAME":"different"}}]}"""
        val sanitized = WfsLayerFactory.sanitizeGeoJson(raw)
        assertTrue(sanitized.contains("\"name\":\"already-set\""), "Existing name retained")
    }

    @Test
    fun testOperationsMetadata_GetFeatureOutputFormatParameter() {
        val getFeature = wfsCapabilities.operationsMetadata!!.operations.first { it.name == "GetFeature" }
        val outputFormat = getFeature.parameters.firstOrNull { it.name == "outputFormat" }
        assertNotNull(outputFormat, "outputFormat parameter present on GetFeature")
        assertTrue(
            outputFormat.allowedValues.contains("application/json"),
            "outputFormat advertises application/json",
        )
    }
}
