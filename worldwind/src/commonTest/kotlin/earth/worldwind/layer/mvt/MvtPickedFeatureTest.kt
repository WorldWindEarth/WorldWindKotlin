package earth.worldwind.layer.mvt

import earth.worldwind.PickedObject
import earth.worldwind.render.Color
import earth.worldwind.shape.ShapeAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MvtPickedFeatureTest {

    @Test fun pickedObjectRoundTripsUserObject() {
        val tile = MvtVectorLayer.TileKey(z = 12, x = 100, y = 200)
        val feature = MvtPickedFeature(
            layerName = "streets",
            geometryType = MvtGeometryType.LINESTRING,
            tile = tile,
            tags = intArrayOf(0, 0, 1, 1),
            keys = listOf("kind", "name"),
            values = listOf("motorway", "I-280"),
        )
        val po = PickedObject.fromUserObject(
            identifier = 42, userObject = feature, layer = null, useTerrainPosition = true,
        )
        assertEquals(42, po.identifier)
        val payload = assertIs<MvtPickedFeature>(po.userObject)
        assertEquals("streets", payload.layerName)
        assertEquals(MvtGeometryType.LINESTRING, payload.geometryType)
        assertEquals("motorway", payload.properties["kind"])
        assertEquals("I-280", payload.properties["name"])
        assertEquals(tile, payload.tile)
        // useTerrainPosition skips depth-readback unprojection — leaves cartesianPoint null.
        assertNull(po.cartesianPoint)
    }

    @Test fun pickIdentifierEncodesToUniqueColorAndBack() {
        // Round-trip the encoding the pick framework uses to read back hit features.
        val color = Color()
        val ids = listOf(1, 1000, 65535, 16777215)
        for (id in ids) {
            PickedObject.identifierToUniqueColor(id, color)
            assertEquals(id, PickedObject.uniqueColorToIdentifier(color))
            assertEquals(1f, color.alpha)
        }
    }

    @Test fun batchFeaturePickPayloadDefaultsNull() {
        // Callers that don't opt into picking shouldn't pay any cost — pickPayload is null.
        val attrs = ShapeAttributes()
        val poly = MvtBatchedPolygonTile.BatchFeature(
            outer = DoubleArray(0), holes = emptyList(), attributes = attrs, zOrder = 0,
        )
        assertNull(poly.pickPayload)
        val line = MvtBatchedLineTile.BatchLineFeature(
            coords = DoubleArray(0), attributes = attrs, zOrder = 0,
        )
        assertNull(line.pickPayload)
    }

    @Test fun pickPayloadPropertiesPreservesNullValues() {
        // MVT property maps can contain null entries (e.g. when a tile encodes a key
        // with no value index). Verify the payload's property map preserves them so
        // a feature with a missing "name" doesn't look the same as a feature without
        // the key at all.
        val feature = MvtPickedFeature(
            layerName = "place_labels",
            geometryType = MvtGeometryType.POINT,
            tile = MvtVectorLayer.TileKey(10, 5, 7),
            tags = intArrayOf(0, 0, 1, 1),
            keys = listOf("name", "kind"),
            values = listOf(null, "village"),
        )
        assertNotNull(feature.properties.containsKey("name"))
        assertNull(feature.properties["name"])
        assertEquals("village", feature.properties["kind"])
    }
}
