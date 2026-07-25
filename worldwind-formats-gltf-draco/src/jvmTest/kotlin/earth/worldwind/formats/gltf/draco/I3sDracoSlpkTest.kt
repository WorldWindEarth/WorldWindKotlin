package earth.worldwind.formats.gltf.draco

import earth.worldwind.formats.archive.ZipFileArchive
import earth.worldwind.formats.gltf.GltfDecoderRegistry
import earth.worldwind.formats.i3s.I3sGeometryDecoder
import earth.worldwind.layer.ogc3d.tileset.SceneLayerParser
import earth.worldwind.layer.ogc3d.tileset.Tile3d
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End-to-end check of Draco-only SLPK support against a real ArcGIS-authored package: buffer-index
 * resolution picks the Draco file, and [I3sGeometryDecoder] restores per-node `i3s-scale_x/y` so
 * positions come out as small geographic offsets. Runs only when a local package is present
 * (`-Dworldwind.test.slpk=<path>`, default `~/Downloads/FERMA_02.slpk`) — skips silently otherwise.
 */
class I3sDracoSlpkTest {
    @Test
    fun decodesDracoOnlySlpkGeometry(): Unit = runBlocking {
        val path = System.getProperty("worldwind.test.slpk")
            ?: "${System.getProperty("user.home")}/Downloads/FERMA_02.slpk"
        if (!File(path).exists()) {
            println("I3sDracoSlpkTest: skipped, no package at $path")
            return@runBlocking
        }
        installDracoDecoder()
        assertNotNull(GltfDecoderRegistry.dracoDecoder, "native Draco bridge failed to load")
        val archive = ZipFileArchive.open(path)
        try {
            val tileset = SceneLayerParser.parse(
                sceneLayerJson = assertNotNull(archive.readEntry("3dSceneLayer.json")).decodeToString(),
                archiveId = "test",
                nodePages = { i -> archive.readEntry("nodepages/$i.json")?.decodeToString() },
                geoToWorld = { _, _, _, m -> m },
            )
            val contentTiles = ArrayList<Tile3d>()
            fun collect(tile: Tile3d) {
                if (tile.contentUri != null) contentTiles.add(tile)
                tile.children.forEach(::collect)
            }
            collect(tileset.root)
            assertTrue(contentTiles.isNotEmpty(), "no content-bearing nodes in $path")

            for (tile in contentTiles) {
                val entry = assertNotNull(tile.contentUri).substringAfter("!/").substringBefore('?')
                val bytes = assertNotNull(archive.readEntry(entry), "geometry entry $entry missing")
                val model = I3sGeometryDecoder.decode(bytes)
                val prim = model.meshes.single().primitives.single()
                val vertexCount = prim.positions.size / 3
                assertTrue(vertexCount > 100, "$entry: implausible vertex count $vertexCount")

                val indices = assertNotNull(prim.indicesInt, "$entry: Draco mesh should be indexed")
                assertTrue(indices.size % 3 == 0 && indices.max() < vertexCount, "$entry: bad index buffer")

                // Metadata scales applied → x/y are degree offsets from the node origin, z metres.
                var i = 0
                while (i < prim.positions.size) {
                    assertTrue(abs(prim.positions[i]) < 1f, "$entry: x not in degrees: ${prim.positions[i]}")
                    assertTrue(abs(prim.positions[i + 1]) < 1f, "$entry: y not in degrees: ${prim.positions[i + 1]}")
                    assertTrue(abs(prim.positions[i + 2]) < 1000f, "$entry: z not in metres: ${prim.positions[i + 2]}")
                    i += 3
                }
                prim.texCoords?.forEach { assertTrue(it in -0.01f..1.01f, "$entry: uv out of range: $it") }
            }
        } finally {
            archive.close()
            releaseDracoDecoder()
        }
    }

    /** EPSG:3857 sibling package: OBB centers must reach geoToWorld converted to degrees, and Draco
     *  positions (no `i3s-scale` metadata) decode as node-relative Mercator-metre offsets. */
    @Test
    fun convertsWebMercatorSlpk(): Unit = runBlocking {
        val path = System.getProperty("worldwind.test.slpk.mercator")
            ?: "${System.getProperty("user.home")}/Downloads/FERMA_01.slpk"
        if (!File(path).exists()) {
            println("I3sDracoSlpkTest: skipped, no package at $path")
            return@runBlocking
        }
        installDracoDecoder()
        assertNotNull(GltfDecoderRegistry.dracoDecoder, "native Draco bridge failed to load")
        val archive = ZipFileArchive.open(path)
        try {
            val centers = ArrayList<DoubleArray>()
            val tileset = SceneLayerParser.parse(
                sceneLayerJson = assertNotNull(archive.readEntry("3dSceneLayer.json")).decodeToString(),
                archiveId = "test",
                nodePages = { i -> archive.readEntry("nodepages/$i.json")?.decodeToString() },
                geoToWorld = { lon, lat, h, m -> centers.add(doubleArrayOf(lon, lat, h)); m },
            )
            assertTrue(centers.isNotEmpty(), "no OBB centers seen")
            for ((lon, lat, h) in centers.map { Triple(it[0], it[1], it[2]) }) {
                assertTrue(lon in 34.0..36.0, "center lon not converted to degrees: $lon")
                assertTrue(lat in 50.0..52.0, "center lat not converted to degrees: $lat")
                assertTrue(h in -100.0..1000.0, "implausible center height: $h")
            }

            val contentTiles = ArrayList<Tile3d>()
            fun collect(tile: Tile3d) {
                if (tile.contentUri != null) contentTiles.add(tile)
                tile.children.forEach(::collect)
            }
            collect(tileset.root)
            assertTrue(contentTiles.isNotEmpty(), "no content-bearing nodes in $path")
            for (tile in contentTiles) {
                val entry = assertNotNull(tile.contentUri).substringAfter("!/").substringBefore('?')
                val bytes = assertNotNull(archive.readEntry(entry), "geometry entry $entry missing")
                val model = I3sGeometryDecoder.decode(bytes)
                val prim = model.meshes.single().primitives.single()
                // No scale metadata → raw node-relative Mercator metres, bounded by the OBB half sizes.
                var i = 0
                while (i < prim.positions.size) {
                    assertTrue(abs(prim.positions[i]) < 5000f, "$entry: x out of range: ${prim.positions[i]}")
                    assertTrue(abs(prim.positions[i + 1]) < 5000f, "$entry: y out of range: ${prim.positions[i + 1]}")
                    i += 3
                }
                val maxAbsX = (0 until prim.positions.size step 3).maxOf { abs(prim.positions[it]) }
                assertTrue(maxAbsX > 100f, "$entry: positions look like degrees, not metres: max |x| = $maxAbsX")
            }
        } finally {
            archive.close()
            releaseDracoDecoder()
        }
    }
}
