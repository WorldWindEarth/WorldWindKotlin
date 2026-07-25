package earth.worldwind.formats.i3s

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [I3sWebMercator] detection + conversions. Reference values cross-checked against a real
 *  package pair: the same photogrammetry exported as EPSG:3857 and as WGS84 (centers match). */
class I3sWebMercatorTest {
    @Test fun detectsWebMercatorFromSpatialReference() {
        assertTrue(I3sWebMercator.isWebMercator(doc(SpatialReferenceDoc(wkid = 3857, latestWkid = 3857))))
        assertTrue(I3sWebMercator.isWebMercator(doc(SpatialReferenceDoc(wkid = 102100, latestWkid = 3857))))
        assertFalse(I3sWebMercator.isWebMercator(doc(SpatialReferenceDoc(wkid = 4326, latestWkid = 4326))))
        assertFalse(I3sWebMercator.isWebMercator(doc(null)))
    }

    @Test fun detectsWebMercatorFromStoreCrsUrls() {
        val mercator = SceneLayerDoc(store = StoreDoc(vertexCRS = "http://www.opengis.net/def/crs/EPSG/0/3857"))
        assertTrue(I3sWebMercator.isWebMercator(mercator))
        val geographic = SceneLayerDoc(store = StoreDoc(vertexCRS = "http://www.opengis.net/def/crs/EPSG/0/4326"))
        assertFalse(I3sWebMercator.isWebMercator(geographic))
        // Explicit spatialReference wins over the store URLs.
        assertFalse(I3sWebMercator.isWebMercator(mercator.copy(spatialReference = SpatialReferenceDoc(wkid = 4326))))
    }

    @Test fun convertsMetersToGeographicDegrees() {
        // EPSG:3857 center of a real package; its WGS84 twin stores [34.745628797681434, 50.82173746817897].
        assertEquals(34.745628797681434, I3sWebMercator.xToLongitudeDegrees(3867865.705049999), 1e-12)
        assertEquals(50.821738801385955, I3sWebMercator.yToLatitudeDegrees(6589821.75), 1e-12)
    }

    @Test fun forwardAndInverseYRoundTrip() {
        for (latDeg in intArrayOf(-85, -50, 0, 30, 50, 85)) {
            val latRad = latDeg * PI / 180.0
            assertEquals(latRad, I3sWebMercator.yToLatitudeRadians(I3sWebMercator.latitudeRadiansToY(latRad)), 1e-12)
        }
    }

    private fun doc(sr: SpatialReferenceDoc?) = SceneLayerDoc(spatialReference = sr)
}
