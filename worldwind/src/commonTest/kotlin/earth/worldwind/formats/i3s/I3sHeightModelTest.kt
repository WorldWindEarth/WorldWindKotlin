package earth.worldwind.formats.i3s

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [usesGravityRelatedHeights]: `heightModelInfo.heightModel` decides when declared; the vertical
 *  CRS wkid (5773 = EGM96) is the fallback for packages that omit the block. */
class I3sHeightModelTest {
    @Test fun heightModelDeclarationWins() {
        assertTrue(doc(heightModel = "gravity_related_height").usesGravityRelatedHeights())
        // Explicit ellipsoidal declaration overrides a gravity-datum vcsWkid.
        assertFalse(doc(heightModel = "ellipsoidal", vcsWkid = 5773).usesGravityRelatedHeights())
    }

    @Test fun vcsWkidFallbackWhenHeightModelAbsent() {
        assertTrue(doc(vcsWkid = 5773).usesGravityRelatedHeights())
        assertFalse(doc(vcsWkid = 0).usesGravityRelatedHeights())
        assertFalse(SceneLayerDoc().usesGravityRelatedHeights())
    }

    @Test fun parsesRealPackageDeclaration() {
        val doc = I3sSceneLayer.parseSceneLayer("""{
            "spatialReference":{"wkid":3857,"latestWkid":3857,"vcsWkid":5773,"latestVcsWkid":5773},
            "heightModelInfo":{"heightModel":"gravity_related_height","vertCRS":"EGM96_height","heightUnit":"meter"},
            "store":{"rootNode":"0","version":"1.10"}
        }""")
        assertTrue(doc.usesGravityRelatedHeights())
        assertTrue(I3sWebMercator.isWebMercator(doc))
    }

    private fun doc(heightModel: String? = null, vcsWkid: Int = 0) = SceneLayerDoc(
        heightModelInfo = heightModel?.let { HeightModelInfoDoc(heightModel = it) },
        spatialReference = if (vcsWkid != 0) SpatialReferenceDoc(wkid = 4326, vcsWkid = vcsWkid) else null,
    )
}
