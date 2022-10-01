package earth.worldwind.ogc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WmsLayerConfigTest {
    @Test
    fun testConstructor_TwoParameter() {
        val serviceAddress = "notionalAddress"
        val layerList = "layer1"
        val wmsLayerConfig = WmsLayerConfig(serviceAddress, layerList)
        assertNotNull(wmsLayerConfig, "instantiation")
        assertEquals(serviceAddress, wmsLayerConfig.serviceAddress, "service address parameter")
        assertEquals(layerList, wmsLayerConfig.layerNames, "layer list")
    }

    @Test
    fun testConstructor_AllParameters() {
        val serviceAddress = "notionalAddress"
        val wmsVersion = "1.2.0"
        val layerNames = "layer1"
        val styleNames = "style1"
        val coordinateSystem = "CRS:84"
        val imageFormat = "type/name"
        val time = "1600-ZULU"
        val wmsLayerConfig = WmsLayerConfig(serviceAddress, layerNames)
        wmsLayerConfig.wmsVersion = wmsVersion
        wmsLayerConfig.styleNames = styleNames
        wmsLayerConfig.coordinateSystem = coordinateSystem
        wmsLayerConfig.imageFormat = imageFormat
        wmsLayerConfig.isTransparent = false
        wmsLayerConfig.timeString = time
        assertNotNull(wmsLayerConfig, "instantiation")
        assertEquals(serviceAddress, wmsLayerConfig.serviceAddress, "service address")
        assertEquals(wmsVersion, wmsLayerConfig.wmsVersion, "wms version")
        assertEquals(layerNames, wmsLayerConfig.layerNames, "layer names")
        assertEquals(styleNames, wmsLayerConfig.styleNames, "style names")
        assertEquals(coordinateSystem, wmsLayerConfig.coordinateSystem, "coordinate system")
        assertEquals(imageFormat, wmsLayerConfig.imageFormat, "image format")
        assertFalse(wmsLayerConfig.isTransparent, "transparency")
        assertEquals(time, wmsLayerConfig.timeString, "time")
    }
}