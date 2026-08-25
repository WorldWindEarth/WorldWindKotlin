package earth.worldwind.layer.ogc3d

import earth.worldwind.formats.archive.ZipFileArchive
import earth.worldwind.formats.mimeForExtension
import earth.worldwind.formats.i3s.I3sGeometryDecoder
import earth.worldwind.formats.i3s.I3sSceneLayer
import earth.worldwind.formats.i3s.I3sVertexMapper
import earth.worldwind.formats.i3s.I3sWebMercator
import earth.worldwind.formats.i3s.SlpkArchive
import earth.worldwind.formats.i3s.heightUnitToMeters
import earth.worldwind.formats.i3s.usesGravityRelatedHeights
import earth.worldwind.globe.geoid.AbstractEGM96Geoid
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import earth.worldwind.layer.ogc3d.auth.NoAuthProvider
import earth.worldwind.layer.ogc3d.stream.ArchiveTileByteSource
import earth.worldwind.layer.ogc3d.tileset.SceneLayerParser
import earth.worldwind.layer.ogc3d.tileset.Tile3d
import earth.worldwind.layer.ogc3d.tileset.Tileset

/**
 * Renders an ArcGIS **SLPK** (I3S Scene Layer Package) opened in place from a local file — no
 * extraction or copy, so 5-15 GB packages work on device (see [ZipFileArchive]).
 *
 * Reuses [Ogc3dTilesLayer]'s entire streaming/traversal/cache/GPU pipeline via its two extension
 * points: [parseRootDocument] builds the [Tileset] tree from `3dSceneLayer.json` + node pages
 * ([SceneLayerParser]) instead of a `tileset.json`, and [decodeCustomContent] transcodes each node's
 * I3S geometry buffer to a `GltfModel` ([I3sGeometryDecoder]) that the mesh path renders unchanged.
 * The [ArchiveTileByteSource] makes the fetch queue read node bytes from the zip.
 *
 * Scope: JVM + Android (needs `java.util.zip`), I3S 1.7+ mesh profiles. Create via [open].
 */
class SlpkLayer private constructor(
    private val archive: SlpkArchive,
    private val archiveId: String,
    byteSource: ArchiveTileByteSource,
    displayName: String?,
) : Ogc3dTilesLayer(
    source = TilesetSource(
        rootUri = ArchiveTileByteSource.uriFor(archiveId, "3dSceneLayer.json"),
        authProvider = NoAuthProvider,
        displayName = displayName ?: archiveId,
    ),
    displayName = displayName ?: archiveId,
    byteSource = byteSource,
) {
    /** Vertex offsets are Web Mercator metres, not degrees (projected-CRS package). Set at root
     *  parse, read by the decode mapper — @Volatile bridges the parse/decode coroutines. */
    @Volatile
    private var webMercatorVertices = false

    /** Metres per the layer's declared height unit; applied to vertex z offsets (the parser already
     *  scales node origins). Set at root parse alongside [webMercatorVertices]. */
    @Volatile
    private var heightScale = 1.0

    /** Build the tree from the I3S scene-layer document instead of a 3D-Tiles tileset.json.
     *  Node pages + geographic→Cartesian both come from local reads / the captured globe. */
    override suspend fun parseRootDocument(responseUrl: String, body: String): Tileset {
        val globe = lastGlobe ?: error("SlpkLayer: no globe captured yet (render once first)")
        val doc = I3sSceneLayer.parseSceneLayer(body)
        webMercatorVertices = I3sWebMercator.isWebMercator(doc)
        heightScale = doc.heightUnitToMeters()
        // Orthometric (MSL) packages: lift node origins by the geoid undulation so the mesh shares
        // the globe's vertical datum and rests on terrain (terrain renders elevation + geoid offset).
        val geoid = if (doc.usesGravityRelatedHeights()) globe.geoid.also {
            (it as? AbstractEGM96Geoid)?.awaitDataLoaded() // bake real offsets, not the 0 while-loading fallback
        } else null
        val tileset = SceneLayerParser.parse(
            doc = doc,
            archiveId = archiveId,
            nodePages = { pageIndex -> archive.readEntry("nodepages/$pageIndex.json")?.decodeToString() },
            // tileToWorld = pure translation to the node origin; the per-vertex mapper places vertices
            // as exact world-axis offsets (no ENU frame → no tangent-plane seams).
            geoToWorld = { lonDeg, latDeg, heightM, result ->
                val lat = latDeg.degrees
                val lon = lonDeg.degrees
                val h = heightM + (geoid?.getOffset(lat, lon)?.toDouble() ?: 0.0)
                val v = globe.geographicToCartesian(lat, lon, h, Vec3())
                result.setToTranslation(v.x, v.y, v.z)
            },
        )
        // Anchor altitudeOffset at the dataset centre so it lifts straight up — the inherited
        // auto-anchor's ECEF un-permutation is wrong for our WW-frame translation.
        if (altitudeAnchor == null) {
            val m = tileset.root.tileToWorld.m
            altitudeAnchor = globe.cartesianToGeographic(m[3], m[7], m[11], Position())
        }
        return tileset
    }

    /** Decode a node's I3S geometry (`.bin`) to a `GltfModel` and enqueue it; the sibling texture
     *  (the `?t=` query on [uri]) is read from the archive. skipYUpToZUp — I3S isn't glTF-authored;
     *  RR-cache key uses the geometry path only (query dropped). */
    override suspend fun decodeCustomContent(tile: Tile3d, bytes: ByteArray, uri: String): Boolean {
        if (!uri.startsWith("${ArchiveTileByteSource.SLPK_SCHEME}:")) return false
        // Exact placement: map each raw vertex offset through the projection to a world-axis offset
        // from the tile origin (tileToWorld's translation m3,m7,m11) → no tangent-plane seams.
        // Offsets are in the layer CRS: Δlon°/Δlat° for geographic scenes, metres for Web Mercator.
        val mapper = lastGlobe?.let { globe ->
            val m = tile.tileToWorld.m
            val cx = m[3]; val cy = m[7]; val cz = m[11]
            val c = globe.cartesianToGeographic(cx, cy, cz, Position())
            val cLatDeg = c.latitude.inDegrees; val cLonDeg = c.longitude.inDegrees; val cAlt = c.altitude
            val scratch = Vec3()
            val hScale = heightScale
            if (webMercatorVertices) {
                // Δx is linear in longitude; Δy needs the exact inverse projection about the node's y.
                val cMercY = I3sWebMercator.latitudeRadiansToY(c.latitude.inRadians)
                I3sVertexMapper { dX, dY, dH, out, o ->
                    val latDeg = I3sWebMercator.yToLatitudeDegrees(cMercY + dY)
                    val lonDeg = cLonDeg + I3sWebMercator.xToLongitudeDegrees(dX.toDouble())
                    globe.geographicToCartesian(latDeg.degrees, lonDeg.degrees, cAlt + dH * hScale, scratch)
                    out[o] = (scratch.x - cx).toFloat()
                    out[o + 1] = (scratch.y - cy).toFloat()
                    out[o + 2] = (scratch.z - cz).toFloat()
                }
            } else I3sVertexMapper { dLon, dLat, dH, out, o ->
                globe.geographicToCartesian((cLatDeg + dLat).degrees, (cLonDeg + dLon).degrees, cAlt + dH * hScale, scratch)
                out[o] = (scratch.x - cx).toFloat()
                out[o + 1] = (scratch.y - cy).toFloat()
                out[o + 2] = (scratch.z - cz).toFloat()
            }
        }
        val model = I3sGeometryDecoder.decode(
            bytes, texture = textureFor(uri),
            doubleSided = true,  // integrated-mesh winding varies; avoid back-face invisibility
            emitNormals = false, // integrated-mesh texture has baked lighting → render unlit
            vertexMapper = mapper,
        )
        decodeAndEnqueueMesh(tile, model, contentUri = uri.substringBefore('?'), skipYUpToZUp = true)
        return true
    }

    /** Read the node's base-color texture: `?t=<entry>` is metadata-derived; `?tn=<res>` means legacy —
     *  the name comes from the node's `3dNodeIndexDocument` textureData href. */
    private suspend fun textureFor(uri: String): I3sGeometryDecoder.I3sTexture? {
        uri.indexOf("?t=").let { if (it >= 0) return readTexture(uri.substring(it + 3)) }
        val marker = uri.indexOf("?tn=")
        if (marker < 0) return null
        val nodeDir = "nodes/${uri.substring(marker + 4)}"
        val body = archive.readEntry("$nodeDir/3dNodeIndexDocument.json")?.decodeToString() ?: return null
        // parseNodeIndex doesn't suspend, so runCatching can't swallow a cancellation here.
        val doc = runCatching { I3sSceneLayer.parseNodeIndex(body) }.getOrNull() ?: return null
        val href = doc.textureData.firstOrNull()?.href ?: return null
        val path = I3sSceneLayer.resolveNodeRelativeHref(nodeDir, href)
        // The href omits the extension (e.g. "./textures/0_0"); probe the encodings the decoder accepts.
        for (candidate in listOf(path, "$path.jpg", "$path.png", "$path.jpeg")) {
            readTexture(candidate)?.let { return it }
        }
        return null
    }

    private suspend fun readTexture(entryPath: String): I3sGeometryDecoder.I3sTexture? {
        val bytes = archive.readEntry(entryPath) ?: return null
        // Default an unrecognised or missing extension to JPEG — the dominant I3S texture encoding.
        val mime = mimeForExtension(entryPath) ?: "image/jpeg"
        return I3sGeometryDecoder.I3sTexture(bytes, mime)
    }

    companion object {
        /** Open the SLPK at [slpkPath] (a directly-openable filesystem path). */
        fun open(slpkPath: String, displayName: String? = null): SlpkLayer {
            val archive = ZipFileArchive.open(slpkPath)
            val id = slpkPath.substringAfterLast('/').ifEmpty { "slpk" }
            return SlpkLayer(archive, id, ArchiveTileByteSource(archive), displayName)
        }
    }
}
