package earth.worldwind.ogc.wmts

import kotlinx.serialization.decodeFromString
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.test.*

class WmtsCapabilitiesTest {
    companion object {
        private const val DELTA = 1e-9
    }

    private lateinit var wmtsCapabilities: WmtsCapabilities

    @BeforeTest
    fun setup() {
        val inputStream = javaClass.classLoader!!.getResourceAsStream("test_worldwind_wmts_capabilities_spec.xml")!!
        wmtsCapabilities = XML().decodeFromString(inputStream.bufferedReader().use { it.readText() })
    }

    @Test
    fun testGetServiceIdentification_Title() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = "World example Web Map Tile Service"
        val actual = serviceIdentification?.title
        assertEquals(expected, actual, "Service Identification Title")
    }

    @Test
    fun testGetServiceIdentification_Abstract() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = "Example service that contrains some world layers in the\n" +
                "            urn:ogc:def:wkss:OGC:1.0:GlobalCRS84Pixel Well-known scale set\n" + "        "
        val actual = serviceIdentification?.abstract
        assertEquals(expected, actual, "Service Identification Abstract")
    }

    @Test
    fun testGetServiceIdentification_Keywords() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = listOf("World", "Global", "Digital Elevation Model", "Administrative Boundaries")
        val actual = mutableListOf<String?>()
        for (keyword in serviceIdentification!!.keywords) actual.add(keyword)
        assertContentEquals(expected, actual, "Service Identification Keywords")
    }

    @Test
    fun testGetServiceIdentification_ServiceType() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = "OGC WMTS"
        val actual = serviceIdentification?.serviceType
        assertEquals(expected, actual, "Service Identification Type")
    }

    @Test
    fun testGetServiceIdentification_ServiceTypeVersion() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = "1.0.0"
        val actual = serviceIdentification!!.serviceTypeVersions[0]
        assertEquals(expected, actual, "Service Identification Type Version")
    }

    @Test
    fun testGetServiceIdentification_Fees() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = "none"
        val actual = serviceIdentification?.fees
        assertEquals(expected, actual, "Service Identification Fees")
    }

    @Test
    fun testGetServiceIdentification_AccessConstraints() {
        val serviceIdentification = wmtsCapabilities.serviceIdentification
        val expected = "none"
        val actual = serviceIdentification!!.accessConstraints[0]
        assertEquals(expected, actual, "Service Identification Access Constraints")
    }

    @Test
    fun testGetServiceProvider_Name() {
        val serviceProvider = wmtsCapabilities.serviceProvider
        val expected = "UAB-CREAF-MiraMon"
        val actual = serviceProvider?.providerName
        assertEquals(expected, actual, "Service Provider Name")
    }

    @Test
    fun testGetServiceProvider_Site() {
        val serviceProvider = wmtsCapabilities.serviceProvider
        val expected = "http://www.creaf.uab.es/miramon"
        val actual = serviceProvider?.providerSiteUrl
        assertEquals(expected, actual, "Service Provider Site Link")
    }

    @Test
    fun testGetServiceProvider_Contact_Name() {
        val serviceProvider = wmtsCapabilities.serviceProvider
        val expected = "Joan Maso Pau"
        val actual = serviceProvider?.serviceContact?.individualName
        assertEquals(expected, actual, "Service Provider Contact Individual Name")
    }

    @Test
    fun testGetServiceProvider_Contact_Position() {
        val serviceProvider = wmtsCapabilities.serviceProvider
        val expected = "Senior Software Engineer"
        val actual = serviceProvider?.serviceContact?.positionName
        assertEquals(expected, actual, "Service Provider Contact Position Name")
    }

    @Test
    fun testGetServiceProvider_Contact_InfoPhone() {
        val contactInfo = wmtsCapabilities.serviceProvider?.serviceContact?.contactInfo
        val expectedVoice = "+34 93 581 1312"
        val expectedFax = "+34 93 581 4151"
        val actualVoice = contactInfo?.phone?.voice?.get(0)
        val actualFax = contactInfo?.phone?.fax?.get(0)
        assertEquals(expectedVoice, actualVoice, "Service Provider Contact Phone Voice")
        assertEquals(expectedFax, actualFax, "Service Provider Contact Phone Fax")
    }

    @Test
    fun testGetServiceProvider_Contact_InfoAddress() {
        val contactInfo = wmtsCapabilities.serviceProvider?.serviceContact?.contactInfo
        val expectedDeliveryPoint = "Fac Ciencies UAB"
        val expectedCity = "Bellaterra"
        val expectedAdministrativeArea = "Barcelona"
        val expectedPostalCode = "08193"
        val expectedCountry = "Spain"
        val expectedEmail = "joan.maso@uab.es"
        val actualDeliveryPoint = contactInfo?.address?.deliveryPoints!![0]
        val actualCity = contactInfo.address?.city
        val actualAdministrativeArea = contactInfo.address?.administrativeArea
        val actualPostalCode = contactInfo.address?.postalCode
        val actualCountry = contactInfo.address?.country
        val actualEmail = contactInfo.address?.electronicMailAddresses!![0]
        assertEquals(expectedDeliveryPoint, actualDeliveryPoint, "Service Provider Contact Address Delivery Point")
        assertEquals(expectedCity, actualCity, "Service Provider Contact Address City")
        assertEquals(expectedAdministrativeArea, actualAdministrativeArea, "Service Provider Contact Address Admin Area")
        assertEquals(expectedPostalCode, actualPostalCode, "Service Provider Contact Address Postal Code")
        assertEquals(expectedCountry, actualCountry, "Service Provider Contact Address Country")
        assertEquals(expectedEmail, actualEmail, "Service Provider Contact Address Email")
    }

    @Test
    fun testGetOperationsMetadata_GetCapabilities() {
        val getCapabilities = wmtsCapabilities.operationsMetadata?.getCapabilities
        val expectedName = "GetCapabilities"
        val expectedLink = "http://www.opengis.uab.es/cgi-bin/world/MiraMon5_0.cgi?"
        val actualName = getCapabilities?.name
        val actualLink = getCapabilities!!.dcps[0].getMethods[0].url
        assertEquals(expectedName, actualName, "Operations Metadata GetCapabilities Name")
        assertEquals(expectedLink, actualLink, "Operations Metadata GetCapabilities Link")
    }

    @Test
    fun testGetOperationsMetadata_GetTile() {
        val getTile = wmtsCapabilities.operationsMetadata?.getTile
        val expectedName = "GetTile"
        val expectedLink = "http://www.opengis.uab.es/cgi-bin/world/MiraMon5_0.cgi?"
        val actualName = getTile?.name
        val actualLink = getTile!!.dcps[0].getMethods[0].url
        assertEquals(expectedName, actualName, "Operations Metadata GetTile Name")
        assertEquals(expectedLink, actualLink, "Operations Metadata GetTile Link")
    }

    @Test
    fun testGetLayer_Title() {
        val layer = wmtsCapabilities.contents.layers
        val expectedTitleOne = "etopo2"
        val expectedTitleTwo = "Administrative Boundaries"
        val actualTitleOne = layer[0].title
        val actualTitleTwo = layer[1].title
        assertEquals(expectedTitleOne, actualTitleOne, "Layer Title One")
        assertEquals(expectedTitleTwo, actualTitleTwo, "Layer Title Two")
    }

    @Test
    fun testGetLayer_Abstract() {
        val layer = wmtsCapabilities.contents.layers
        val expectedInAbstractOne = "1. The seafloor data between latitudes 64— North and 72— South"
        val expectedInAbstractTwo = " at scales to about 1:10,000,000. The data were ge"
        val actualAbstractOne = layer[0].abstract
        val actualAbstractTwo = layer[1].abstract
        assertTrue(actualAbstractOne?.contains(expectedInAbstractOne) == true, "Layer Title One")
        assertTrue(actualAbstractTwo?.contains(expectedInAbstractTwo) == true, "Layer Title Two")
    }

    @Test
    fun testGetLayer_WGS84BoundingBox() {
        val layers = wmtsCapabilities.contents.layers
        val expectedMinXOne = -180.0
        val expectedMaxXOne = 180.0
        val expectedMinYOne = -90.0
        val expectedMaxYOne = 90.0
        val expectedMinXTwo = -180.0
        val expectedMaxXTwo = 180.0
        val expectedMinYTwo = -90.0
        val expectedMaxYTwo = 84.0
        var layer = layers[0]
        val actualMinXOne = layer.wgs84BoundingBox?.sector?.minLongitude?.inDegrees!!
        val actualMaxXOne = layer.wgs84BoundingBox?.sector?.maxLongitude?.inDegrees!!
        val actualMinYOne = layer.wgs84BoundingBox?.sector?.minLatitude?.inDegrees!!
        val actualMaxYOne = layer.wgs84BoundingBox?.sector?.maxLatitude?.inDegrees!!
        layer = layers[1]
        val actualMinXTwo = layer.wgs84BoundingBox?.sector?.minLongitude?.inDegrees!!
        val actualMaxXTwo = layer.wgs84BoundingBox?.sector?.maxLongitude?.inDegrees!!
        val actualMinYTwo = layer.wgs84BoundingBox?.sector?.minLatitude?.inDegrees!!
        val actualMaxYTwo = layer.wgs84BoundingBox?.sector?.maxLatitude?.inDegrees!!
        assertEquals(expectedMinXOne, actualMinXOne, DELTA, "Layer Bounding Box MinX Layer One")
        assertEquals(expectedMaxXOne, actualMaxXOne, DELTA, "Layer Bounding Box MaxX Layer One")
        assertEquals(expectedMinYOne, actualMinYOne, DELTA, "Layer Bounding Box MinY Layer One")
        assertEquals(expectedMaxYOne, actualMaxYOne, DELTA, "Layer Bounding Box MaxY Layer One")
        assertEquals(expectedMinXTwo, actualMinXTwo, DELTA, "Layer Bounding Box MinX Layer Two")
        assertEquals(expectedMaxXTwo, actualMaxXTwo, DELTA, "Layer Bounding Box MaxX Layer Two")
        assertEquals(expectedMinYTwo, actualMinYTwo, DELTA, "Layer Bounding Box MinY Layer Two")
        assertEquals(expectedMaxYTwo, actualMaxYTwo, DELTA, "Layer Bounding Box MaxY Layer Two")
    }

    @Test
    fun testGetLayer_Identifier() {
        val layer = wmtsCapabilities.contents.layers
        val expectedIdentifierOne = "etopo2"
        val expectedIdentifierTwo = "AdminBoundaries"
        val actualIdentifierOne = layer[0].identifier
        val actualIdentifierTwo = layer[1].identifier
        assertEquals(expectedIdentifierOne, actualIdentifierOne, "Layer Identifier One")
        assertEquals(expectedIdentifierTwo, actualIdentifierTwo, "Layer Identifier Two")
    }

    @Test
    fun testGetLayer_Metadata() {
        val layer = wmtsCapabilities.contents.layers
        val expectedHrefOne = "http://www.opengis.uab.es/SITiled/world/etopo2/metadata.htm"
        val expectedHrefTwo = "http://www.opengis.uab.es/SITiled/world/AdminBoundaries/metadata.htm"
        val actualHrefOne = layer[0].metadata[0].url
        val actualHrefTwo = layer[1].metadata[0].url
        assertEquals(expectedHrefOne, actualHrefOne, "Layer Metadata Href One")
        assertEquals(expectedHrefTwo, actualHrefTwo, "Layer Metadata Href Two")
    }

    @Test
    fun testGetLayer_Styles() {
        val layer = wmtsCapabilities.contents.layers
        val expectedTitleOne = "default"
        val expectedTitleTwo = "default"
        val expectedIdentifierOne = "default"
        val expectedIdentifierTwo = "default"
        val actualTitleOne = layer[0].styles[0].title
        val actualTitleTwo = layer[1].styles[0].title
        val actualIdentifierOne = layer[0].styles[0].identifier
        val actualIdentifierTwo = layer[1].styles[0].identifier
        val actualIsDefaultOne = layer[0].styles[0].isDefault
        val actualIsDefaultTwo = layer[0].styles[0].isDefault
        assertEquals(expectedTitleOne, actualTitleOne, "Layer Style Title One")
        assertEquals(expectedTitleTwo, actualTitleTwo, "Layer Style Title Two")
        assertEquals(expectedIdentifierOne, actualIdentifierOne, "Layer Style Identifier One")
        assertEquals(expectedIdentifierTwo, actualIdentifierTwo, "Layer Style Identifier Two")
        assertTrue(actualIsDefaultOne, "Layer Style IsDefault One")
        assertTrue(actualIsDefaultTwo, "Layer Style IsDefault Two")
    }

    @Test
    fun testGetLayer_Formats() {
        val layer = wmtsCapabilities.contents.layers
        val expectedFormatOne = "image/png"
        val expectedFormatTwo = "image/png"
        val expectedFormatSizeOne = 1
        val expectedFormatSizeTwo = 1
        val actualFormatsOne = layer[0].formats
        val actualFormatOne = actualFormatsOne[0]
        val actualFormatSizeOne = actualFormatsOne.size
        val actualFormatsTwo = layer[1].formats
        val actualFormatTwo = actualFormatsTwo.iterator().next()
        val actualFormatSizeTwo = actualFormatsTwo.size
        assertEquals(expectedFormatOne, actualFormatOne, "Layer Format One")
        assertEquals(expectedFormatSizeOne, actualFormatSizeOne, "Layer Formats Size One")
        assertEquals(expectedFormatTwo, actualFormatTwo, "Layer Format Two")
        assertEquals(expectedFormatSizeTwo, actualFormatSizeTwo, "Layer Formats Size Two")
    }

    @Test
    fun testGetLayer_TileMatrixSets() {
        val layer = wmtsCapabilities.contents.layers
        val expectedTileMatrixSetOne = "WholeWorld_CRS_84"
        val expectedTileMatrixSetTwo = "World84-90_CRS_84"
        val expectedTileMatrixSetSizeOne = 1
        val expectedTileMatrixSetSizeTwo = 1
        val actualTileMatrixSetsOne = layer[0].tileMatrixSetLinks
        val actualTileMatrixSetOne = actualTileMatrixSetsOne[0].identifier
        val actualTileMatrixSetSizeOne = actualTileMatrixSetsOne.size
        val actualTileMatrixSetsTwo = layer[1].tileMatrixSetLinks
        val actualTileMatrixSetTwo = actualTileMatrixSetsTwo[0].identifier
        val actualTileMatrixSetSizeTwo = actualTileMatrixSetsTwo.size
        assertEquals(expectedTileMatrixSetOne, actualTileMatrixSetOne, "Layer TileMatrixSet One")
        assertEquals(expectedTileMatrixSetSizeOne, actualTileMatrixSetSizeOne, "Layer TileMatrixSets Size One")
        assertEquals(expectedTileMatrixSetTwo, actualTileMatrixSetTwo, "Layer TileMatrixSet Two")
        assertEquals(expectedTileMatrixSetSizeTwo, actualTileMatrixSetSizeTwo, "Layer TileMatrixSets Size Two")
    }

    @Test
    fun testGetLayer_ResourceURLs_One() {
        val layer = wmtsCapabilities.contents.layers
        val expectedResourceUrlFormatOne = "image/png"
        val expectedResourceUrlFormatTwo = "application/gml+xml; version=3.1"
        val expectedResourceUrlResourceTypeOne = "tile"
        val expectedResourceUrlResourceTypeTwo = "FeatureInfo"
        val expectedResourceUrlTemplateOne = "http://www.opengis.uab.es/SITiled/world/etopo2/default/WholeWorld_CRS_84/{TileMatrix}/{TileRow}/{TileCol}.png"
        val expectedResourceUrlTemplateTwo = "http://www.opengis.uab.es/SITiled/world/etopo2/default/WholeWorld_CRS_84/{TileMatrix}/{TileRow}/{TileCol}/{J}/{I}.xml"
        val resourceOne = layer[0].resourceUrls[0]
        val actualResourceUrlFormatOne = resourceOne.format
        val actualResourceUrlResourceTypeOne = resourceOne.resourceType
        val actualResourceUrlTemplateOne = resourceOne.template
        val resourceTwo = layer[0].resourceUrls[1]
        val actualResourceUrlFormatTwo = resourceTwo.format
        val actualResourceUrlResourceTypeTwo = resourceTwo.resourceType
        val actualResourceUrlTemplateTwo = resourceTwo.template
        assertEquals(expectedResourceUrlFormatOne, actualResourceUrlFormatOne, "Layer One ResourceURL One Format")
        assertEquals(expectedResourceUrlResourceTypeOne, actualResourceUrlResourceTypeOne, "Layer One ResourceURL One ResourceType")
        assertEquals(expectedResourceUrlTemplateOne, actualResourceUrlTemplateOne, "Layer One ResourceURL One Template")
        assertEquals(expectedResourceUrlFormatTwo, actualResourceUrlFormatTwo, "Layer One ResourceURL Two Format")
        assertEquals(expectedResourceUrlResourceTypeTwo, actualResourceUrlResourceTypeTwo, "Layer One ResourceURL Two ResourceType")
        assertEquals(expectedResourceUrlTemplateTwo, actualResourceUrlTemplateTwo, "Layer One ResourceURL Two Template")
    }

    @Test
    fun testGetLayer_ResourceURLs_Two() {
        val layer = wmtsCapabilities.contents.layers
        val expectedResourceUrlFormatOne = "image/png"
        val expectedResourceUrlFormatTwo = "application/gml+xml; version=3.1"
        val expectedResourceUrlResourceTypeOne = "tile"
        val expectedResourceUrlResourceTypeTwo = "FeatureInfo"
        val expectedResourceUrlTemplateOne = "http://www.opengis.uab.es/SITiled/world/AdminBoundaries/default/World84-90_CRS_84/{TileMatrix}/{TileRow}/{TileCol}.png"
        val expectedResourceUrlTemplateTwo = "http://www.opengis.uab.es/SITiled/world/AdminBoundaries/default/World84-90_CRS_84/{TileMatrix}/{TileRow}/{TileCol}/{J}/{I}.xml"
        val resourceOne = layer[1].resourceUrls[0]
        val actualResourceUrlFormatOne = resourceOne.format
        val actualResourceUrlResourceTypeOne = resourceOne.resourceType
        val actualResourceUrlTemplateOne = resourceOne.template
        val resourceTwo = layer[1].resourceUrls[1]
        val actualResourceUrlFormatTwo = resourceTwo.format
        val actualResourceUrlResourceTypeTwo = resourceTwo.resourceType
        val actualResourceUrlTemplateTwo = resourceTwo.template
        assertEquals(expectedResourceUrlFormatOne, actualResourceUrlFormatOne, "Layer Two ResourceURL One Format")
        assertEquals(expectedResourceUrlResourceTypeOne, actualResourceUrlResourceTypeOne, "Layer Two ResourceURL One ResourceType")
        assertEquals(expectedResourceUrlTemplateOne, actualResourceUrlTemplateOne, "Layer Two ResourceURL One Template")
        assertEquals(expectedResourceUrlFormatTwo, actualResourceUrlFormatTwo, "Layer Two ResourceURL Two Format")
        assertEquals(expectedResourceUrlResourceTypeTwo, actualResourceUrlResourceTypeTwo, "Layer Two ResourceURL Two ResourceType")
        assertEquals(expectedResourceUrlTemplateTwo, actualResourceUrlTemplateTwo, "Layer Two ResourceURL Two Template")
    }

    @Test
    fun testGetTileMatrixSets_OverallSets() {
        val expectedCount = 2
        val matrixSetOne = wmtsCapabilities.contents.tileMatrixSets[0]
        val matrixSetTwo = wmtsCapabilities.contents.tileMatrixSets[1]
        val actualCount = wmtsCapabilities.contents.tileMatrixSets.size
        assertNotNull(matrixSetOne, "TileMatrixSet One")
        assertNotNull(matrixSetTwo, "TileMatrixSet Two")
        assertEquals(expectedCount, actualCount, "TileMatrixSet Count")
    }

    @Test
    fun testGetTileMatrixSets_MatrixSetZero() {
        val wmtsTileMatrixSet = wmtsCapabilities.contents.tileMatrixSets[0]
        val expectedIdentifier = "WholeWorld_CRS_84"
        val expectedSupportedCRS = "urn:ogc:def:crs:OGC:1.3:CRS84"
        val expectedWellKnownScaleSet = "urn:ogc:def:wkss:OGC:1.0:GlobalCRS84Pixel"
        val expectedTileMatrixCount = 7
        val actualIdentifier = wmtsTileMatrixSet.identifier
        val actualSupportedCRS = wmtsTileMatrixSet.supportedCrs
        val actualWellKnownScaleSet = wmtsTileMatrixSet.wellKnownScaleSet
        val actualTileMatrixCount = wmtsTileMatrixSet.tileMatrices.size
        assertEquals(expectedIdentifier, actualIdentifier, "TileMatrixSet One Identifier")
        assertEquals(expectedSupportedCRS, actualSupportedCRS, "TileMatrixSet One SupportedCRS")
        assertEquals(expectedWellKnownScaleSet, actualWellKnownScaleSet, "TileMatrixSet One WellKnownScaleSet")
        assertEquals(expectedTileMatrixCount, actualTileMatrixCount, "TileMatrixSet One Count")
    }

    @Test
    fun testGetTileMatrixSets_MatrixSetOne() {
        val wmtsTileMatrixSet = wmtsCapabilities.contents.tileMatrixSets[1]
        val expectedIdentifier = "World84-90_CRS_84"
        val expectedSupportedCRS = "urn:ogc:def:crs:OGC:1.3:CRS84"
        val expectedWellKnownScaleSet = "urn:ogc:def:wkss:OGC:1.0:GlobalCRS84Pixel"
        val expectedTileMatrixCount = 7
        val actualIdentifier = wmtsTileMatrixSet.identifier
        val actualSupportedCRS = wmtsTileMatrixSet.supportedCrs
        val actualWellKnownScaleSet = wmtsTileMatrixSet.wellKnownScaleSet
        val actualTileMatrixCount = wmtsTileMatrixSet.tileMatrices.size
        assertEquals(expectedIdentifier, actualIdentifier, "TileMatrixSet Two Identifier")
        assertEquals(expectedSupportedCRS, actualSupportedCRS, "TileMatrixSet Two SupportedCRS")
        assertEquals(expectedWellKnownScaleSet, actualWellKnownScaleSet, "TileMatrixSet Two WellKnownScaleSet")
        assertEquals(expectedTileMatrixCount, actualTileMatrixCount, "TileMatrixSet Two Count")
    }

    @Test
    fun testGetTileMatrixSets_TileMatrixSetZero_TileMatrixZero() {
        val wmtsTileMatrix = wmtsCapabilities.contents.tileMatrixSets[0].tileMatrices[0]
        val expectedIdentifier = "2g"
        val expectedScaleDenominator = 795139219.951954
        val expectedTopLeftCorner = "-180 90"
        val expectedTileWidth = 320
        val expectedTileHeight = 200
        val expectedMatrixWidth = 1
        val expectedMatrixHeight = 1
        val actualIdentifier = wmtsTileMatrix.identifier
        val actualScaleDenominator = wmtsTileMatrix.scaleDenominator
        val actualTopLeftCorner = wmtsTileMatrix.topLeftCorner
        val actualTileWidth = wmtsTileMatrix.tileWidth
        val actualTileHeight = wmtsTileMatrix.tileHeight
        val actualMatrixWidth = wmtsTileMatrix.matrixWidth
        val actualMatrixHeight = wmtsTileMatrix.matrixHeight
        assertEquals(expectedIdentifier, actualIdentifier, "TileMatrixSet One TileMatrix One Identifier")
        assertEquals(expectedScaleDenominator, actualScaleDenominator, DELTA, "TileMatrixSet One TileMatrix One ScaleDenominator")
        assertEquals(expectedTopLeftCorner, actualTopLeftCorner, "TileMatrixSet One TileMatrix One TopLeftCorner")
        assertEquals(expectedTileWidth, actualTileWidth, "TileMatrixSet One TileMatrix One TileWidth")
        assertEquals(expectedTileHeight, actualTileHeight, "TileMatrixSet One TileMatrix One TileHeight")
        assertEquals(expectedMatrixWidth, actualMatrixWidth, "TileMatrixSet One TileMatrix One MatrixWidth")
        assertEquals(expectedMatrixHeight, actualMatrixHeight, "TileMatrixSet One TileMatrix One MatrixHeight")
    }

    @Test
    fun testGetTileMatrixSets_TileMatrixSetZero_TileMatrixOne() {
        val wmtsTileMatrix = wmtsCapabilities.contents.tileMatrixSets[0].tileMatrices[1]
        val expectedIdentifier = "1g"
        val expectedScaleDenominator = 397569609.975977
        val expectedTopLeftCorner = "-180 90"
        val expectedTileWidth = 320
        val expectedTileHeight = 200
        val expectedMatrixWidth = 2
        val expectedMatrixHeight = 1
        val actualIdentifier = wmtsTileMatrix.identifier
        val actualScaleDenominator = wmtsTileMatrix.scaleDenominator
        val actualTopLeftCorner = wmtsTileMatrix.topLeftCorner
        val actualTileWidth = wmtsTileMatrix.tileWidth
        val actualTileHeight = wmtsTileMatrix.tileHeight
        val actualMatrixWidth = wmtsTileMatrix.matrixWidth
        val actualMatrixHeight = wmtsTileMatrix.matrixHeight
        assertEquals(expectedIdentifier, actualIdentifier, "TileMatrixSet One TileMatrix Two Identifier")
        assertEquals(expectedScaleDenominator, actualScaleDenominator, DELTA, "TileMatrixSet One TileMatrix Two ScaleDenominator")
        assertEquals(expectedTopLeftCorner, actualTopLeftCorner, "TileMatrixSet One TileMatrix Two TopLeftCorner")
        assertEquals(expectedTileWidth, actualTileWidth, "TileMatrixSet One TileMatrix Two TileWidth")
        assertEquals(expectedTileHeight, actualTileHeight, "TileMatrixSet One TileMatrix Two TileHeight")
        assertEquals(expectedMatrixWidth, actualMatrixWidth, "TileMatrixSet One TileMatrix Two MatrixWidth")
        assertEquals(expectedMatrixHeight, actualMatrixHeight, "TileMatrixSet One TileMatrix Two MatrixHeight")
    }

    @Test
    fun testGetTileMatrixSets_TileMatrixSetOne_TileMatrixZero() {
        val wmtsTileMatrix = wmtsCapabilities.contents.tileMatrixSets[1].tileMatrices[0]
        val expectedIdentifier = "2g"
        val expectedScaleDenominator = 795139219.951954
        val expectedTopLeftCorner = "-180 84"
        val expectedTileWidth = 320
        val expectedTileHeight = 200
        val expectedMatrixWidth = 1
        val expectedMatrixHeight = 1
        val actualIdentifier = wmtsTileMatrix.identifier
        val actualScaleDenominator = wmtsTileMatrix.scaleDenominator
        val actualTopLeftCorner = wmtsTileMatrix.topLeftCorner
        val actualTileWidth = wmtsTileMatrix.tileWidth
        val actualTileHeight = wmtsTileMatrix.tileHeight
        val actualMatrixWidth = wmtsTileMatrix.matrixWidth
        val actualMatrixHeight = wmtsTileMatrix.matrixHeight
        assertEquals(expectedIdentifier, actualIdentifier, "TileMatrixSet Two TileMatrix One Identifier")
        assertEquals(expectedScaleDenominator, actualScaleDenominator, DELTA, "TileMatrixSet Two TileMatrix One ScaleDenominator")
        assertEquals(expectedTopLeftCorner, actualTopLeftCorner, "TileMatrixSet Two TileMatrix One TopLeftCorner")
        assertEquals(expectedTileWidth, actualTileWidth, "TileMatrixSet Two TileMatrix One TileWidth")
        assertEquals(expectedTileHeight, actualTileHeight, "TileMatrixSet Two TileMatrix One TileHeight")
        assertEquals(expectedMatrixWidth, actualMatrixWidth, "TileMatrixSet Two TileMatrix One MatrixWidth")
        assertEquals(expectedMatrixHeight, actualMatrixHeight, "TileMatrixSet Two TileMatrix One MatrixHeight")
    }

    @Test
    fun testGetTileMatrixSets_TileMatrixSetOne_TileMatrixOne() {
        val wmtsTileMatrix = wmtsCapabilities.contents.tileMatrixSets[1].tileMatrices[1]
        val expectedIdentifier = "1g"
        val expectedScaleDenominator = 397569609.975977
        val expectedTopLeftCorner = "-180 84"
        val expectedTileWidth = 320
        val expectedTileHeight = 200
        val expectedMatrixWidth = 2
        val expectedMatrixHeight = 1
        val actualIdentifier = wmtsTileMatrix.identifier
        val actualScaleDenominator = wmtsTileMatrix.scaleDenominator
        val actualTopLeftCorner = wmtsTileMatrix.topLeftCorner
        val actualTileWidth = wmtsTileMatrix.tileWidth
        val actualTileHeight = wmtsTileMatrix.tileHeight
        val actualMatrixWidth = wmtsTileMatrix.matrixWidth
        val actualMatrixHeight = wmtsTileMatrix.matrixHeight
        assertEquals(expectedIdentifier, actualIdentifier, "TileMatrixSet Two TileMatrix Two Identifier")
        assertEquals(expectedScaleDenominator, actualScaleDenominator, DELTA, "TileMatrixSet Two TileMatrix Two ScaleDenominator")
        assertEquals(expectedTopLeftCorner, actualTopLeftCorner, "TileMatrixSet Two TileMatrix Two TopLeftCorner")
        assertEquals(expectedTileWidth, actualTileWidth, "TileMatrixSet Two TileMatrix Two TileWidth")
        assertEquals(expectedTileHeight, actualTileHeight, "TileMatrixSet Two TileMatrix Two TileHeight")
        assertEquals(expectedMatrixWidth, actualMatrixWidth, "TileMatrixSet Two TileMatrix Two MatrixWidth")
        assertEquals(expectedMatrixHeight, actualMatrixHeight, "TileMatrixSet Two TileMatrix One MatrixHeight")
    }

    @Test
    fun testGetThemes_ParentTheme() {
        val parentTheme = wmtsCapabilities.themes[0]
        val expectedTitle = "Foundation"
        val expectedAbstract = "World reference data"
        val expectedIdentifier = "Foundation"
        val actualTitle = parentTheme.title
        val actualAbstract = parentTheme.abstract
        val actualIdentifier = parentTheme.identifier
        assertEquals(expectedTitle, actualTitle, "Parent Theme Title")
        assertEquals(expectedAbstract, actualAbstract, "Parent Theme Abstract")
        assertEquals(expectedIdentifier, actualIdentifier, "Parent Theme Identifier")
    }

    @Test
    fun testGetThemes_ChildThemeOne() {
        val theme = wmtsCapabilities.themes[0].themes[0]
        val expectedTitle = "Digital Elevation Model"
        val expectedLayerRef = "etopo2"
        val expectedIdentifier = "DEM"
        val actualTitle = theme.title
        val actualLayerRef = theme.layerRefs.iterator().next()
        val actualIdentifier = theme.identifier
        assertEquals(expectedTitle, actualTitle, "Child One Theme Title")
        assertEquals(expectedLayerRef, actualLayerRef, "Child One Theme LayerRef")
        assertEquals(expectedIdentifier, actualIdentifier, "Child One Theme Identifier")
    }

    @Test
    fun testGetThemes_ChildThemeTwo() {
        val theme = wmtsCapabilities.themes[0].themes[1]
        val expectedTitle = "Administrative Boundaries"
        val expectedLayerRef = "AdminBoundaries"
        val expectedIdentifier = "AdmBoundaries"
        val actualTitle = theme.title
        val actualLayerRef = theme.layerRefs.iterator().next()
        val actualIdentifier = theme.identifier
        assertEquals(expectedTitle, actualTitle, "Child Two Theme Title")
        assertEquals(expectedLayerRef, actualLayerRef, "Child Two Theme LayerRef")
        assertEquals(expectedIdentifier, actualIdentifier, "Child Two Theme Identifier")
    }

    @Test
    fun testGetServiceMetadataUrl() {
        val serviceMetadataUrl = wmtsCapabilities.serviceMetadataUrls.iterator().next()
        val expectedHref = "http://www.opengis.uab.es/SITiled/world/1.0.0/WMTSCapabilities.xml"
        val actualHref = serviceMetadataUrl.url
        assertEquals(expectedHref, actualHref, "ServiceMetadataURL Href")
    }

    @Test
    fun testGetTileDcpSupportsKVP() {
        val operation = wmtsCapabilities.operationsMetadata?.getTile
        val actualValue = operation?.dcps!![0].getMethods[0].constraints[0].allowedValues.contains("KVP")
        assertTrue(actualValue, "DCP Register KVP Support")
    }

    @Test
    fun testTileMatrixSetLimits_Count() {
        val tileMatrixSetLimits = wmtsCapabilities.contents.layers[0].tileMatrixSetLinks[0].tileMatrixSetLimits
        val expectedTileMatrixLimits = 22
        val actualTileMatrixLimits = tileMatrixSetLimits.size
        assertEquals(expectedTileMatrixLimits, actualTileMatrixLimits, "TileMatrixLimits Count")
    }

    @Test
    fun testTileMatrixSetLimits_TileMatixLimitZero() {
        val tileMatrixLimits = wmtsCapabilities.contents.layers[0].tileMatrixSetLinks[0].tileMatrixSetLimits[0]
        val expectedTileMatrixIdentifier = "EPSG:4326:0"
        val expectedMinTileRow = 0
        val expectedMaxTileRow = 0
        val expectedMinTileCol = 0
        val expectedMaxTileCol = 1
        val actualTileMatrixIdentifier = tileMatrixLimits.tileMatrixIdentifier
        val actualMinTileRow = tileMatrixLimits.minTileRow
        val actualMaxTileRow = tileMatrixLimits.maxTileRow
        val actualMinTileCol = tileMatrixLimits.minTileCol
        val actualMaxTileCol = tileMatrixLimits.maxTileCol
        assertEquals(expectedTileMatrixIdentifier, actualTileMatrixIdentifier, "TileMatrixLimit 0 Identifier")
        assertEquals(expectedMinTileRow, actualMinTileRow, "TileMatrixLimit 0 MinTileRow")
        assertEquals(expectedMaxTileRow, actualMaxTileRow, "TileMatrixLimit 0 MaxTileRow")
        assertEquals(expectedMinTileCol, actualMinTileCol, "TileMatrixLimit 0 MinTileCol")
        assertEquals(expectedMaxTileCol, actualMaxTileCol, "TileMatrixLimit 0 MaxTileCol")
    }

    @Test
    fun testTileMatrixSetLimits_TileMatixLimitOne() {
        val tileMatrixLimits = wmtsCapabilities.contents.layers[0].tileMatrixSetLinks[0].tileMatrixSetLimits[1]
        val expectedTileMatrixIdentifier = "EPSG:4326:1"
        val expectedMinTileRow = 0
        val expectedMaxTileRow = 1
        val expectedMinTileCol = 0
        val expectedMaxTileCol = 3
        val actualTileMatrixIdentifier = tileMatrixLimits.tileMatrixIdentifier
        val actualMinTileRow = tileMatrixLimits.minTileRow
        val actualMaxTileRow = tileMatrixLimits.maxTileRow
        val actualMinTileCol = tileMatrixLimits.minTileCol
        val actualMaxTileCol = tileMatrixLimits.maxTileCol
        assertEquals(expectedTileMatrixIdentifier, actualTileMatrixIdentifier, "TileMatrixLimit 0 Identifier")
        assertEquals(expectedMinTileRow, actualMinTileRow, "TileMatrixLimit 0 MinTileRow")
        assertEquals(expectedMaxTileRow, actualMaxTileRow, "TileMatrixLimit 0 MaxTileRow")
        assertEquals(expectedMinTileCol, actualMinTileCol, "TileMatrixLimit 0 MinTileCol")
        assertEquals(expectedMaxTileCol, actualMaxTileCol, "TileMatrixLimit 0 MaxTileCol")
    }

    @Test
    fun testTileMatrixSetLimits_TileMatixLimitNine() {
        val tileMatrixLimits = wmtsCapabilities.contents.layers[0].tileMatrixSetLinks[0].tileMatrixSetLimits[9]
        val expectedTileMatrixIdentifier = "EPSG:4326:9"
        val expectedMinTileRow = 18
        val expectedMaxTileRow = 511
        val expectedMinTileCol = 0
        val expectedMaxTileCol = 1023
        val actualTileMatrixIdentifier = tileMatrixLimits.tileMatrixIdentifier
        val actualMinTileRow = tileMatrixLimits.minTileRow
        val actualMaxTileRow = tileMatrixLimits.maxTileRow
        val actualMinTileCol = tileMatrixLimits.minTileCol
        val actualMaxTileCol = tileMatrixLimits.maxTileCol
        assertEquals(expectedTileMatrixIdentifier, actualTileMatrixIdentifier, "TileMatrixLimit 0 Identifier")
        assertEquals(expectedMinTileRow, actualMinTileRow, "TileMatrixLimit 0 MinTileRow")
        assertEquals(expectedMaxTileRow, actualMaxTileRow, "TileMatrixLimit 0 MaxTileRow")
        assertEquals(expectedMinTileCol, actualMinTileCol, "TileMatrixLimit 0 MinTileCol")
        assertEquals(expectedMaxTileCol, actualMaxTileCol, "TileMatrixLimit 0 MaxTileCol")
    }
}