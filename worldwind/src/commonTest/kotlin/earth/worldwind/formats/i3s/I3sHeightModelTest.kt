package earth.worldwind.formats.i3s

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [usesGravityRelatedHeights]: `heightModelInfo.heightModel` decides when declared, then the
 *  vertical CRS wkid, then a `VERTCS`/`VERTCRS` in the CRS text, then the `vertCRS` name. */
class I3sHeightModelTest {
    @Test fun heightModelDeclarationWins() {
        assertTrue(doc(heightModel = "gravity_related_height").usesGravityRelatedHeights())
        // Explicit ellipsoidal declaration overrides a gravity-datum vcsWkid.
        assertFalse(doc(heightModel = "ellipsoidal", vcsWkid = 5773).usesGravityRelatedHeights())
    }

    @Test fun vcsWkidFallbackWhenHeightModelAbsent() {
        // EPSG vertical CRSs: EGM96, EGM2008, NAVD88, MSL height - all gravity-related.
        for (wkid in intArrayOf(5773, 3855, 5703, 5714, 105700)) {
            assertTrue(doc(vcsWkid = wkid).usesGravityRelatedHeights(), "wkid $wkid")
        }
        // Esri per-datum ellipsoidal-height block: WGS_1984, ETRS_1989, NAD_1983.
        for (wkid in intArrayOf(115700, 115701, 115702, 115999)) {
            assertFalse(doc(vcsWkid = wkid).usesGravityRelatedHeights(), "wkid $wkid")
        }
        // No vertical CRS at all means ellipsoidal.
        assertFalse(doc(vcsWkid = 0).usesGravityRelatedHeights())
        assertFalse(SceneLayerDoc().usesGravityRelatedHeights())
    }

    @Test fun latestVcsWkidUsedWhenVcsWkidAbsent() {
        assertTrue(SceneLayerDoc(spatialReference = SpatialReferenceDoc(wkid = 4326, latestVcsWkid = 5773))
            .usesGravityRelatedHeights())
        assertFalse(SceneLayerDoc(spatialReference = SpatialReferenceDoc(wkid = 4326, latestVcsWkid = 115700))
            .usesGravityRelatedHeights())
    }

    @Test fun wktVerticalBlockClassifiedByDatumKind() {
        // Gravity-related: the vertical block carries a VDATUM.
        assertTrue(wktDoc(
            """GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]]],""" +
                """VERTCS["EGM96_Geoid",VDATUM["EGM96_Geoid"],PARAMETER["Vertical_Shift",0.0],UNIT["Meter",1.0]]"""
        ).usesGravityRelatedHeights())
        // OGC WKT1 and WKT2 spellings of the same block.
        assertTrue(wktDoc("""VERT_CS["EGM2008 height",VERT_DATUM["EGM2008 geoid",2005]]""").usesGravityRelatedHeights())
        assertTrue(wktDoc("""VERTCRS["NAVD88 height",VDATUM["North American Vertical Datum 1988"]]""").usesGravityRelatedHeights())
        // Ellipsoidal: Esri spells the ellipsoidal-height VCS with a spheroid instead of a VDATUM.
        assertFalse(wktDoc(
            """GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]]],""" +
                """VERTCS["WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]],""" +
                """PARAMETER["Vertical_Shift",0.0],UNIT["Meter",1.0]]"""
        ).usesGravityRelatedHeights())
        // Horizontal-only text has no vertical block, so the spheroid there must not classify it.
        assertFalse(wktDoc("""GEOGCS["GCS_WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]]]""")
            .usesGravityRelatedHeights())
        // A wkid-declared vertical CRS outranks the text.
        assertTrue(SceneLayerDoc(spatialReference = SpatialReferenceDoc(
            vcsWkid = 5773, wkt = """VERTCS["WGS_1984",DATUM["D_WGS_1984",SPHEROID["WGS_1984",6378137.0,298.257223563]]]"""
        )).usesGravityRelatedHeights())
    }

    @Test fun vertCrsNameIsTheLastResort() {
        assertTrue(SceneLayerDoc(heightModelInfo = HeightModelInfoDoc(vertCRS = "EGM96_height")).usesGravityRelatedHeights())
        assertTrue(SceneLayerDoc(heightModelInfo = HeightModelInfoDoc(vertCRS = "NAVD88 height")).usesGravityRelatedHeights())
        assertFalse(SceneLayerDoc(heightModelInfo = HeightModelInfoDoc(vertCRS = "WGS_1984")).usesGravityRelatedHeights())
    }

    @Test fun heightUnitScalesToMeters() {
        assertEquals(1.0, SceneLayerDoc().heightUnitToMeters())
        assertEquals(1.0, unitDoc("meter").heightUnitToMeters())
        assertEquals(0.3048, unitDoc("foot").heightUnitToMeters())
        assertEquals(1000.0, unitDoc("kilometer").heightUnitToMeters())
        assertEquals(0.01, unitDoc("centimeter").heightUnitToMeters())
        // US survey foot is 2 ppm longer than the international foot - the two must not collapse.
        assertEquals(0.3048006096012192, unitDoc("us-foot").heightUnitToMeters())
        // Enum values are lower-case in the spec; tolerate any casing a writer emits.
        assertEquals(0.3048, unitDoc("Foot").heightUnitToMeters())
        // Unknown or absent unit falls back to metres rather than rejecting the package.
        assertEquals(1.0, unitDoc("furlong").heightUnitToMeters())
        // Derived US units stay consistent with the us-foot factor.
        assertEquals(unitDoc("us-foot").heightUnitToMeters() * 3, unitDoc("us-yard").heightUnitToMeters(), 1e-12)
        assertEquals(unitDoc("us-foot").heightUnitToMeters() / 12, unitDoc("us-inch").heightUnitToMeters(), 1e-12)
        assertEquals(unitDoc("us-foot").heightUnitToMeters() * 5280, unitDoc("us-mile").heightUnitToMeters(), 1e-9)
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

    /** Metashape/ContextCapture style: horizontal wkid only, no vertical CRS anywhere. */
    @Test fun parsesEllipsoidalPackageWithoutVerticalCrs() {
        val doc = I3sSceneLayer.parseSceneLayer("""{
            "spatialReference":{"wkid":4326,"latestWkid":4326},
            "store":{"rootNode":"0","version":"1.8"}
        }""")
        assertFalse(doc.usesGravityRelatedHeights())
    }

    private fun doc(heightModel: String? = null, vcsWkid: Int = 0) = SceneLayerDoc(
        heightModelInfo = heightModel?.let { HeightModelInfoDoc(heightModel = it) },
        spatialReference = if (vcsWkid != 0) SpatialReferenceDoc(wkid = 4326, vcsWkid = vcsWkid) else null,
    )

    private fun wktDoc(wkt: String) = SceneLayerDoc(spatialReference = SpatialReferenceDoc(wkt = wkt))

    private fun unitDoc(heightUnit: String) = SceneLayerDoc(heightModelInfo = HeightModelInfoDoc(heightUnit = heightUnit))
}
