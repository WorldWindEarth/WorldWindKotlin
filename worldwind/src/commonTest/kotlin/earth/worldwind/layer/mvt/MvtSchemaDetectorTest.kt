package earth.worldwind.layer.mvt

import kotlin.test.Test
import kotlin.test.assertEquals

class MvtSchemaDetectorTest {

    private fun tileWith(vararg layerNames: String): MvtTile = MvtTile(
        layers = layerNames.map { name ->
            MvtLayer(
                name = name, version = 2, extent = 4096,
                keys = emptyList(), values = emptyList(), features = emptyList(),
            )
        },
    )

    @Test fun emptyTileIsUnknown() {
        assertEquals(MvtSchemaDetector.Schema.UNKNOWN, MvtSchemaDetector.detect(tileWith()))
    }

    @Test fun detectsShortbreadFromSignatureLayers() {
        val tile = tileWith("streets", "water_polygons", "land", "boundaries", "buildings")
        assertEquals(MvtSchemaDetector.Schema.SHORTBREAD, MvtSchemaDetector.detect(tile))
    }

    @Test fun detectsOpenMapTilesFromSignatureLayers() {
        val tile = tileWith("transportation", "waterway", "landcover", "place", "poi")
        assertEquals(MvtSchemaDetector.Schema.OPENMAPTILES, MvtSchemaDetector.detect(tile))
    }

    @Test fun mixedSignaturesPickTheLargerMatchSet() {
        // 3 Shortbread vs 1 OpenMapTiles → Shortbread wins.
        val tile = tileWith("streets", "water_polygons", "land", "transportation")
        assertEquals(MvtSchemaDetector.Schema.SHORTBREAD, MvtSchemaDetector.detect(tile))
    }

    @Test fun unrecognisedLayerNamesReturnUnknown() {
        val tile = tileWith("custom_layer_1", "my_features", "buildings")
        // "buildings" is generic (in neither signature set) so this is UNKNOWN.
        assertEquals(MvtSchemaDetector.Schema.UNKNOWN, MvtSchemaDetector.detect(tile))
    }

    @Test fun tiedScoresResolveToUnknown() {
        // 1 vs 1.
        val tile = tileWith("streets", "transportation")
        assertEquals(MvtSchemaDetector.Schema.UNKNOWN, MvtSchemaDetector.detect(tile))
    }
}
