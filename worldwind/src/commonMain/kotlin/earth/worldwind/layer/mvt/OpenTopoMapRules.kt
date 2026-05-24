package earth.worldwind.layer.mvt

import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_BUILDING
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_LANDCOVER
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_PARK
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_BOUNDARY
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_RAIL
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_MINOR
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_MOTORWAY
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_PATH
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_PRIMARY
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_SECONDARY
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_TERTIARY
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_TRACK
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_ROAD_TRUNK
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_WATER
import earth.worldwind.layer.mvt.MvtStyle.Companion.Z_WATERWAY
import earth.worldwind.render.Color
import earth.worldwind.render.FontWeight

/**
 * [OpenTopoMapMvtStyle] re-expressed as an [MvtRuleBasedStyle]. Same palette as the
 * hand-coded object, but gains:
 *
 *   - **Zoom-gated road visibility.** Motorways from z6, primary/secondary from z9,
 *     tertiary from z11, residential / paths from z13. Country-level zooms stay clean.
 *   - **Zoom-interpolated line widths.** Roads thicken with zoom — at z14 a motorway is
 *     ~3× its z6 width. Matches how a paper map reads from far vs. close.
 *   - **Per-zoom palette shifts.** Optional; e.g. water darkens slightly when zoomed in.
 *
 * Use as a drop-in for [OpenTopoMapMvtStyle] when you want the better behavior:
 *
 * ```
 * val layer = MvtVectorLayer(
 *     source = UrlTemplateMvtTileSource("https://tiles.versatiles.org/tiles/osm/{z}/{x}/{y}"),
 *     style = OpenTopoMapRules,
 * )
 * ```
 *
 * Backed by the [mvtStyle] DSL — start here when reading how to author your own.
 */

// Palette and kind-sets MUST appear before [OpenTopoMapRules]. Kotlin initializes top-level
// vals in DECLARATION ORDER within a file — if these were defined after the rule list,
// they'd be `null` at the moment `mvtStyle { ... }` ran and every fill/lineColor would
// resolve to a null Color, NPE-ing on the first `.alpha` read at tile assembly.
private val WATER = Color(0.70f, 0.85f, 0.96f)
private val FOREST = Color(0.75f, 0.85f, 0.69f)
private val GRASS = Color(0.82f, 0.86f, 0.69f)
private val PARK = Color(0.78f, 0.86f, 0.63f)
private val URBAN = Color(0.94f, 0.94f, 0.90f)
private val LANDCOVER_DEFAULT = Color(0.91f, 0.92f, 0.86f)
private val BUILDING = Color(0.78f, 0.77f, 0.74f)
private val MOTORWAY_RED = Color(0.91f, 0.39f, 0.32f)
private val TRUNK_ORANGE = Color(0.95f, 0.55f, 0.40f)
private val PRIMARY_YELLOW = Color(0.98f, 0.84f, 0.41f)
private val SECONDARY_YELLOW = Color(0.99f, 0.91f, 0.56f)
private val TERTIARY_CREAM = Color(0.99f, 0.96f, 0.83f)
private val MINOR_WHITE = Color(1.0f, 1.0f, 1.0f)
private val SERVICE_GRAY = Color(0.92f, 0.92f, 0.90f)
private val TRACK_BROWN = Color(0.65f, 0.45f, 0.20f)
private val PATH_BROWN = Color(0.55f, 0.35f, 0.18f)
private val RAIL_BLACK = Color(0.2f, 0.2f, 0.2f)
private val BOUNDARY = Color(0.55f, 0.42f, 0.62f)
// Label palette — white text, black halo. Inverted from the usual print-map convention
// because the tutorial composites vector tiles over satellite imagery; light backgrounds
// (snow, cloud, sky) appear unpredictably and white-on-black reads more reliably across
// the whole range. Halo width stays small (~10% of font size) so the dark ring doesn't
// dominate the glyph strokes; bold weight on every label preserves stroke visibility.
// LABEL_TEXT_DIM is a slight off-white reserved for hamlet/suburb labels — visible but
// hierarchically below city/town/village.
private val LABEL_TEXT = Color(1f, 1f, 1f, 1f)
private val LABEL_TEXT_DIM = Color(0.85f, 0.85f, 0.87f, 1f)
private val LABEL_HALO = Color(0f, 0f, 0f, 1f)
private const val LABEL_HALO_W_LARGE = 2f
private const val LABEL_HALO_W_SMALL = 1.5f

private val FOREST_KINDS: Set<Any?> = setOf("wood", "forest")
private val GRASS_KINDS: Set<Any?> = setOf("grass", "meadow", "scrub", "scrubland", "farmland", "farm", "farmyard")
private val URBAN_KINDS: Set<Any?> = setOf("residential", "commercial", "industrial", "neighbourhood", "retail")
private val PARK_KINDS: Set<Any?> = setOf("park", "garden", "playground", "recreation_ground")

val OpenTopoMapRules: MvtRuleBasedStyle = mvtStyle {

    // ---- Background / land ----
    // Subclass rules first — they paint with their own palette. The generic `land` /
    // `landcover` catchalls below act as the fallback for anything not in the kind sets.
    rule("land") {
        filter = "kind" isIn FOREST_KINDS
        zOrder = Z_LANDCOVER
        paint { fill(FOREST) }
    }
    rule("land") {
        filter = "kind" isIn GRASS_KINDS
        zOrder = Z_LANDCOVER
        paint { fill(GRASS) }
    }
    rule("land") {
        filter = "kind" isIn URBAN_KINDS
        zOrder = Z_LANDCOVER
        paint { fill(URBAN) }
    }
    rule("sites") {
        filter = "kind" isIn PARK_KINDS
        zOrder = Z_PARK
        paint { fill(PARK) }
    }
    rule("land") {
        zOrder = Z_LANDCOVER
        paint { fill(LANDCOVER_DEFAULT) }
    }
    rule("landcover") {
        zOrder = Z_LANDCOVER
        paint { fill(LANDCOVER_DEFAULT) }
    }

    // ---- Water ----
    rule("water_polygons") {
        zOrder = Z_WATER
        paint { fill(WATER) }
    }
    rule("river_polygons") {
        zOrder = Z_WATER
        paint { fill(WATER) }
    }
    rule("ocean") {
        zOrder = Z_WATER
        paint { fill(WATER) }
    }
    rule("water_lines") {
        filter = "kind" isIn setOf("river", "river_centerline", "canal")
        zOrder = Z_WATERWAY
        // Rivers thicken with zoom so they read at country and city scale alike.
        paint {
            lineColor = MvtZoomInterp.constant(WATER)
            lineWidth = MvtZoomInterp.floats(6 to 0.6f, 10 to 1.2f, 14 to 2.0f)
        }
    }
    rule("water_lines") {
        filter = "kind" isIn setOf("stream", "drain", "ditch")
        // Small waterways only at city zoom — they're noise at country scale.
        minZoom = 11
        zOrder = Z_WATERWAY
        paint {
            lineColor = MvtZoomInterp.constant(WATER)
            lineWidth = MvtZoomInterp.floats(11 to 0.5f, 14 to 0.9f)
        }
    }

    // ---- Boundaries ----
    rule("boundaries") {
        filter = MvtFilter.NumericLte("admin_level", 2.0)
        zOrder = Z_BOUNDARY
        paint {
            lineColor = MvtZoomInterp.constant(BOUNDARY)
            lineWidth = MvtZoomInterp.floats(2 to 0.8f, 6 to 1.2f, 12 to 1.6f)
            lineOpacity = MvtZoomInterp.constant(0.9f)
        }
    }
    rule("boundaries") {
        filter = MvtFilter.all(
            MvtFilter.NumericGte("admin_level", 3.0),
            MvtFilter.NumericLte("admin_level", 4.0),
        )
        minZoom = 4
        zOrder = Z_BOUNDARY
        paint {
            lineColor = MvtZoomInterp.constant(BOUNDARY)
            lineWidth = MvtZoomInterp.floats(4 to 0.6f, 12 to 1.0f)
            lineOpacity = MvtZoomInterp.constant(0.7f)
        }
    }

    // ---- Roads, by class, by zoom ----
    // Order matters: more important roads paint over less important. Within a class,
    // higher z renders thicker.
    rule("streets") {
        filter = "kind" isIn setOf("motorway", "motorway_link")
        minZoom = 5
        zOrder = Z_ROAD_MOTORWAY
        paint {
            lineColor = MvtZoomInterp.constant(MOTORWAY_RED)
            // Visible thread at country zoom; full thick at city zoom.
            lineWidth = MvtZoomInterp.floats(5 to 0.8f, 8 to 1.6f, 12 to 2.6f, 16 to 4.2f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("trunk", "trunk_link")
        minZoom = 6
        zOrder = Z_ROAD_TRUNK
        paint {
            lineColor = MvtZoomInterp.constant(TRUNK_ORANGE)
            lineWidth = MvtZoomInterp.floats(6 to 0.7f, 10 to 1.4f, 14 to 2.4f, 16 to 3.6f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("primary", "primary_link")
        minZoom = 8
        zOrder = Z_ROAD_PRIMARY
        paint {
            lineColor = MvtZoomInterp.constant(PRIMARY_YELLOW)
            lineWidth = MvtZoomInterp.floats(8 to 0.6f, 12 to 1.4f, 16 to 3.0f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("secondary", "secondary_link")
        minZoom = 9
        zOrder = Z_ROAD_SECONDARY
        paint {
            lineColor = MvtZoomInterp.constant(SECONDARY_YELLOW)
            lineWidth = MvtZoomInterp.floats(9 to 0.6f, 13 to 1.4f, 16 to 2.6f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("tertiary", "tertiary_link")
        minZoom = 11
        zOrder = Z_ROAD_TERTIARY
        paint {
            lineColor = MvtZoomInterp.constant(TERTIARY_CREAM)
            lineWidth = MvtZoomInterp.floats(11 to 0.5f, 14 to 1.2f, 16 to 2.0f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("unclassified", "residential", "living_street", "road", "minor")
        minZoom = 12
        zOrder = Z_ROAD_MINOR
        paint {
            lineColor = MvtZoomInterp.constant(MINOR_WHITE)
            lineWidth = MvtZoomInterp.floats(12 to 0.5f, 14 to 1.1f, 16 to 1.8f)
        }
    }
    rule("streets") {
        filter = "kind" eq "service"
        minZoom = 13
        zOrder = Z_ROAD_MINOR
        paint {
            lineColor = MvtZoomInterp.constant(SERVICE_GRAY)
            lineWidth = MvtZoomInterp.floats(13 to 0.4f, 16 to 0.9f)
        }
    }
    rule("streets") {
        filter = "kind" eq "track"
        minZoom = 12
        zOrder = Z_ROAD_TRACK
        paint {
            lineColor = MvtZoomInterp.constant(TRACK_BROWN)
            lineWidth = MvtZoomInterp.floats(12 to 0.4f, 16 to 1.0f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("path", "footway", "pedestrian", "steps", "bridleway", "cycleway")
        minZoom = 13
        zOrder = Z_ROAD_PATH
        paint {
            lineColor = MvtZoomInterp.constant(PATH_BROWN)
            lineWidth = MvtZoomInterp.floats(13 to 0.3f, 16 to 0.7f)
        }
    }
    rule("streets") {
        filter = "kind" isIn setOf("rail", "transit", "tram", "subway", "light_rail")
        minZoom = 8
        zOrder = Z_RAIL
        paint {
            lineColor = MvtZoomInterp.constant(RAIL_BLACK)
            lineWidth = MvtZoomInterp.floats(8 to 0.4f, 14 to 1.0f, 16 to 1.4f)
        }
    }

    // ---- Buildings ----
    // Building footprints only show at building-scale zoom — drawing them globally turns a
    // metro area into one gray smudge.
    rule("buildings") {
        minZoom = 13
        zOrder = Z_BUILDING
        paint { fill(BUILDING) }
    }

    // ---- Place labels (Stage 4a) ----
    // Shortbread's `place_labels` carries (kind, name, …) — kinds run continent → country →
    // state → city → town → village → hamlet → suburb. Zoom-grade so country-level views
    // show only major cities while city-level views also reveal towns and villages.
    //
    // Sizes / zoom-ranges / weights track OpenTopoMap's Mapnik style. The print original is
    // sized in points (~1.33 px at 96 DPI); we multiply by ~1.6 for retina-density mobile,
    // round to even pixels, and keep OpenTopoMap's zoom envelopes for relative hierarchy.
    //   Reference: github.com/der-stefan/OpenTopoMap/blob/master/mapnik/opentopomap.xml
    //   TextSymbolizer entries for `country`, `town`, `city`, `village`, `hamlet`, `suburb`.
    rule("place_labels") {
        filter = "kind" eq "city"
        minZoom = 6
        zOrder = MvtStyle.Z_LABEL
        paint {
            text("name") {
                color(LABEL_TEXT)
                size = MvtZoomInterp.floats(6 to 16f, 10 to 22f, 14 to 26f)
                halo(LABEL_HALO, LABEL_HALO_W_LARGE)
                fontWeight = FontWeight.BOLD
            }
        }
    }
    rule("place_labels") {
        filter = "kind" eq "town"
        minZoom = 8
        zOrder = MvtStyle.Z_LABEL
        paint {
            text("name") {
                color(LABEL_TEXT)
                size = MvtZoomInterp.floats(8 to 14f, 13 to 18f, 16 to 22f)
                halo(LABEL_HALO, LABEL_HALO_W_LARGE)
                fontWeight = FontWeight.BOLD
            }
        }
    }
    rule("place_labels") {
        filter = "kind" eq "village"
        // Villages appear at city-area scale, not country scale.
        minZoom = 12
        zOrder = MvtStyle.Z_LABEL
        paint {
            text("name") {
                color(LABEL_TEXT)
                size = MvtZoomInterp.floats(12 to 11f, 14 to 14f, 16 to 17f)
                halo(LABEL_HALO, LABEL_HALO_W_SMALL)
                fontWeight = FontWeight.BOLD
            }
        }
    }
    rule("place_labels") {
        filter = "kind" isIn setOf("hamlet", "suburb", "neighbourhood")
        minZoom = 14
        zOrder = MvtStyle.Z_LABEL
        paint {
            text("name") {
                color(LABEL_TEXT_DIM)
                size = MvtZoomInterp.floats(14 to 10f, 16 to 12f, 18 to 14f)
                halo(LABEL_HALO, LABEL_HALO_W_SMALL)
                fontWeight = FontWeight.BOLD
            }
        }
    }

    // Road names — single label per LINESTRING placed at the midpoint, rotated along the
    // local tangent (Mapbox's `symbol-placement: line-center` equivalent). Layered by class
    // so each tier only enters when its roads become visually prominent:
    //   - motorway/trunk    from z=14  (city overview — main arterials only)
    //   - primary           from z=13
    //   - secondary         from z=15  (street-detail zoom)
    rule("streets") {
        filter = MvtFilter.all(
            "kind" isIn setOf("motorway", "trunk"),
            MvtFilter.has("name"),
        )
        geometryType = MvtGeometryType.LINESTRING
        minZoom = 14
        zOrder = MvtStyle.Z_LABEL - 1  // beneath place labels but above non-road labels
        paint {
            text("name") {
                placement = MvtStyleRule.LabelPlacement.LINE
                color(LABEL_TEXT)
                size = MvtZoomInterp.floats(14 to 12f, 17 to 16f)
                halo(LABEL_HALO, LABEL_HALO_W_SMALL)
                fontWeight = FontWeight.BOLD
            }
        }
    }

    rule("streets") {
        filter = MvtFilter.all(
            "kind" isIn setOf("primary", "primary_link"),
            MvtFilter.has("name"),
        )
        geometryType = MvtGeometryType.LINESTRING
        minZoom = 13
        zOrder = MvtStyle.Z_LABEL - 2
        paint {
            text("name") {
                placement = MvtStyleRule.LabelPlacement.LINE
                color(LABEL_TEXT)
                size = MvtZoomInterp.floats(13 to 11f, 17 to 15f)
                halo(LABEL_HALO, LABEL_HALO_W_SMALL)
                fontWeight = FontWeight.BOLD
            }
        }
    }

    rule("streets") {
        filter = MvtFilter.all(
            "kind" isIn setOf("secondary", "secondary_link"),
            MvtFilter.has("name"),
        )
        geometryType = MvtGeometryType.LINESTRING
        minZoom = 15
        zOrder = MvtStyle.Z_LABEL - 3
        paint {
            text("name") {
                placement = MvtStyleRule.LabelPlacement.LINE
                color(LABEL_TEXT)
                size = MvtZoomInterp.floats(15 to 10f, 17 to 14f)
                halo(LABEL_HALO, LABEL_HALO_W_SMALL)
                fontWeight = FontWeight.BOLD
            }
        }
    }

    // Country and state labels carried by Shortbread's `boundary_labels` (POINTs at the
    // representative centroid). Visible across a wide zoom range — country labels appear
    // before city labels so users panning a globe-scale view always have some annotation.
    rule("boundary_labels") {
        filter = MvtFilter.NumericLte("admin_level", 2.0)  // country = admin_level 2 by OSM convention
        minZoom = 2
        zOrder = MvtStyle.Z_LABEL
        paint {
            text("name") {
                color(LABEL_TEXT)
                // Sized to read at globe zoom without overpowering. Country labels would
                // otherwise span 30+ px at z=2 which looks oversized on a far-out camera.
                size = MvtZoomInterp.floats(2 to 14f, 5 to 20f, 8 to 26f)
                halo(LABEL_HALO, LABEL_HALO_W_LARGE)
                fontWeight = FontWeight.BOLD
            }
        }
    }
    // Region/state labels: admin_level 3-4. Smaller and visible across a tighter zoom range.
    rule("boundary_labels") {
        filter = MvtFilter.all(
            MvtFilter.NumericGte("admin_level", 3.0),
            MvtFilter.NumericLte("admin_level", 4.0),
        )
        minZoom = 4
        maxZoom = 10
        zOrder = MvtStyle.Z_LABEL
        paint {
            text("name") {
                color(LABEL_TEXT_DIM)
                size = MvtZoomInterp.floats(4 to 12f, 8 to 16f)
                halo(LABEL_HALO, LABEL_HALO_W_SMALL)
                fontWeight = FontWeight.BOLD
            }
        }
    }
}

