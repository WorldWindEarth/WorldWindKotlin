package earth.worldwind.layer.ogc3d.tileset

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TilesetParserUriTest {

    private fun base(uri: String): TilesetParser.ResolvedBase = TilesetParser.parseBase(uri)

    @Test
    fun resolvesRelativeChildAgainstSiblingDirectory() {
        val b = base("https://tile.googleapis.com/v1/3dtiles/datasets/foo/root.json?session=abc")
        assertEquals(
            "https://tile.googleapis.com/v1/3dtiles/datasets/foo/child.b3dm",
            TilesetParser.resolveUri(b, "child.b3dm"),
        )
    }

    @Test
    fun preservesChildQueryStringInline() {
        val b = base("https://tile.googleapis.com/v1/3dtiles/datasets/foo/root.json?session=abc")
        // Google child URIs already carry their own session; we must leave it intact.
        assertEquals(
            "https://tile.googleapis.com/v1/3dtiles/datasets/foo/child.json?session=xyz",
            TilesetParser.resolveUri(b, "child.json?session=xyz"),
        )
    }

    @Test
    fun rootRelativeChildKeepsAuthorityDropsParentDir() {
        val b = base("https://tile.googleapis.com/v1/3dtiles/datasets/foo/root.json")
        assertEquals(
            "https://tile.googleapis.com/v2/elsewhere.b3dm",
            TilesetParser.resolveUri(b, "/v2/elsewhere.b3dm"),
        )
    }

    @Test
    fun absoluteChildPassesThroughUnchanged() {
        val b = base("https://tile.googleapis.com/v1/3dtiles/datasets/foo/root.json")
        assertEquals(
            "https://cdn.example.com/static.b3dm?token=1",
            TilesetParser.resolveUri(b, "https://cdn.example.com/static.b3dm?token=1"),
        )
        assertEquals(
            "data:application/octet-stream;base64,AAA",
            TilesetParser.resolveUri(b, "data:application/octet-stream;base64,AAA"),
        )
    }

    @Test
    fun rootAtAuthorityRootUsesSlashDir() {
        val b = base("https://example.com/tileset.json")
        assertEquals(
            "https://example.com/child.b3dm",
            TilesetParser.resolveUri(b, "child.b3dm"),
        )
    }

    @Test
    fun collapsesDotSegmentsFromNestedTilesetChildren() {
        // ArcGIS nested tilesets reference siblings via ../.. — servers 403 raw dot segments
        // when the HTTP stack (iOS Darwin) sends the path verbatim.
        val b = base("https://tiles.example.com/svc/3DTilesServer/L11/UP/tileset.json")
        assertEquals(
            "https://tiles.example.com/svc/3DTilesServer/L8/UP/tile.b3dm",
            TilesetParser.resolveUri(b, "../../L8/UP/tile.b3dm"),
        )
        assertEquals(
            "https://tiles.example.com/svc/3DTilesServer/L11/UP/tile.b3dm?session=x",
            TilesetParser.resolveUri(b, "./tile.b3dm?session=x"),
        )
        assertEquals(
            "https://tiles.example.com/svc/other.b3dm",
            TilesetParser.resolveUri(b, "/svc/3DTilesServer/../other.b3dm"),
        )
    }

    @Test
    fun fallsBackForOpaqueBaseUri() {
        // No scheme://authority/ — should still produce something usable via the Uri-library fallback.
        val b = base("file:///tmp/local/root.json")
        // Just check it doesn't crash and includes the child name. The exact form
        // depends on the Uri library, so we don't pin it here.
        val out = TilesetParser.resolveUri(b, "tile.b3dm")
        assertTrue(out.endsWith("tile.b3dm"))
    }
}
