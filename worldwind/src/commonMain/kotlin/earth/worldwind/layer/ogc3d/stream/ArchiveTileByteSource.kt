package earth.worldwind.layer.ogc3d.stream

import earth.worldwind.formats.archive.ReadOnlyArchive
import earth.worldwind.formats.mimeForExtension

/**
 * [TileByteSource] that serves tile bytes from an open [ReadOnlyArchive] instead of the network —
 * the transport behind both the SLPK (I3S) loader and the Cesium 3D Tiles Archive (`.3dtiles`/3TZ)
 * loader.
 *
 * Two synthetic URI shapes are supported, distinguished only by how content URIs are produced:
 * - **jar-style** `slpk:{archiveId}!/{entryPath}` — used by SLPK, whose parser mints every node's
 *   content URI explicitly (no relative resolution needed).
 * - **authority-style** `3dtiles://{archiveId}/{entryPath}` — used by 3TZ, whose `tileset.json` is
 *   fed to the standard [earth.worldwind.layer.ogc3d.tileset.TilesetParser]; the `scheme://authority/`
 *   shape lets its relative-URI resolver splice child content paths with no special-casing.
 *
 * In both, `{archiveId}` only disambiguates cache keys when several archives are open at once. A miss
 * returns HTTP-404-shaped so [TileFetchQueue]'s existing non-success handling applies unchanged; no
 * auth or redirects ever occur.
 */
class ArchiveTileByteSource(
    private val archive: ReadOnlyArchive,
    private val scheme: String = SLPK_SCHEME,
) : TileByteSource {
    override fun handles(uri: String) = uri.startsWith("$scheme:")

    override suspend fun get(uri: String, headers: Map<String, String>): TileByteResponse {
        val path = entryPath(uri)
        val bytes = archive.readEntry(path)
            ?: return TileByteResponse(TileByteResponse.STATUS_NOT_FOUND, EMPTY, uri)
        return TileByteResponse(
            statusCode = TileByteResponse.STATUS_OK,
            bytes = bytes,
            finalUrl = uri,
            contentType = mimeForExtension(path),
        )
    }

    override fun close() = archive.close()

    /** Extract the archive-internal entry path from a synthetic URI. Handles jar-style
     *  `scheme:{id}!/{entry}`, authority-style `scheme://{id}/{entry}`, and a bare `scheme:{entry}`.
     *  A `?`/`#` suffix (e.g. the `?t=<texture>` the I3S parser attaches, or a 3D-Tiles query) is not
     *  part of a zip entry name, so it is stripped before lookup. */
    private fun entryPath(uri: String): String {
        val body = uri.removePrefix("$scheme:")
        val path = when {
            // jar-style: scheme:{id}!/{entry}
            body.contains("!/") -> body.substringAfter("!/")
            // authority-style: scheme://{id}/{entry}
            body.startsWith("//") -> body.substring(2).substringAfter('/', "")
            else -> body.trimStart('/')
        }
        val suffix = path.indexOfFirst { it == '?' || it == '#' }
        return if (suffix >= 0) path.substring(0, suffix) else path
    }

    companion object {
        const val SLPK_SCHEME = "slpk"

        /** Authority-form scheme for Cesium 3D Tiles Archives (`.3dtiles` / 3TZ). */
        const val THREEDTILES_SCHEME = "3dtiles"

        private val EMPTY = ByteArray(0)

        /** Build a jar-style content URI for [entryPath] within the archive identified by [archiveId]. */
        fun uriFor(archiveId: String, entryPath: String) = "$SLPK_SCHEME:$archiveId!/$entryPath"

        /** Build an authority-form (`3dtiles://{archiveId}/{entryPath}`) URI for a 3TZ entry. The
         *  `scheme://authority/` shape flows through TilesetParser's relative-URI resolver unchanged. */
        fun rootUri(archiveId: String, entryPath: String) = "$THREEDTILES_SCHEME://$archiveId/$entryPath"
    }
}
