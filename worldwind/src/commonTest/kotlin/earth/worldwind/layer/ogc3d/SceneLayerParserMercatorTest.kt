package earth.worldwind.layer.ogc3d

import earth.worldwind.layer.ogc3d.tileset.SceneLayerParser
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Projected-CRS (EPSG:3857) packages: [SceneLayerParser.parse] must hand [SceneLayerParser.GeographicToWorld]
 *  geographic degrees, converting Web Mercator OBB centers; geographic packages pass through unchanged. */
class SceneLayerParserMercatorTest {
    private val nodePage = """{"nodes":[{
        "index":0,"lodThreshold":100.0,
        "obb":{"center":[3867865.705049999,6589821.75,203.24],"halfSize":[2416.6,1966.2,56.9],"quaternion":[0.0,0.0,0.0,1.0]},
        "children":[],
        "mesh":{"geometry":{"definition":0,"resource":0,"vertexCount":3,"featureCount":1}}
    }]}"""

    private suspend fun centerSeenBy(sceneLayerJson: String): DoubleArray {
        val seen = DoubleArray(3)
        SceneLayerParser.parse(
            sceneLayerJson = sceneLayerJson,
            archiveId = "test.slpk",
            nodePages = { pageIndex -> if (pageIndex == 0) nodePage else null },
            geoToWorld = { lon, lat, h, result -> seen[0] = lon; seen[1] = lat; seen[2] = h; result },
            preferDracoGeometry = false,
        )
        return seen
    }

    @Test fun webMercatorCentersConvertToDegrees() = runTest {
        val seen = centerSeenBy("""{
            "spatialReference":{"wkid":3857,"latestWkid":3857,"vcsWkid":5773},
            "store":{"rootNode":"0","version":"1.10","nodePages":{"nodesPerPage":64}}
        }""")
        assertEquals(34.745628797681434, seen[0], 1e-12)
        assertEquals(50.821738801385955, seen[1], 1e-12)
        assertEquals(203.24, seen[2], 0.0)
    }

    @Test fun geographicCentersPassThroughUnchanged() = runTest {
        val seen = centerSeenBy("""{
            "spatialReference":{"wkid":4326,"latestWkid":4326},
            "store":{"rootNode":"0","version":"1.10","nodePages":{"nodesPerPage":64}}
        }""")
        assertEquals(3867865.705049999, seen[0], 0.0)
        assertEquals(6589821.75, seen[1], 0.0)
    }
}
