package earth.worldwind.layer.ogc3d.tileset

import com.eygraber.uri.Uri
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.ogc3d.auth.TilesetAuthProvider

/**
 * Decodes a `tileset.json` document into a runtime [Tileset] tree: composes parent-to-
 * child transforms into absolute tile-to-world matrices, resolves relative content URIs
 * against the base URL, and lets the auth provider rewrite each child URI at parse time
 * so persistent state (Google's `session=` token) is attached before the fetch queue.
 */
object TilesetParser {
    fun parse(
        body: String,
        baseUri: String,
        authProvider: TilesetAuthProvider,
        parentTransform: Matrix4? = null,
        /** Refinement to inherit when the root tile omits `refine` — external sub-tilesets
         *  often expect to inherit from the host tile (Cesium Ion / OSM Buildings). */
        parentRefinement: Refinement? = null,
    ): Tileset {
        val doc = StreamingTilesetReader(body).parseTileset()
        val rootTransform = decodeTransform(doc.root.transform, Matrix4())
        // Compose parent's tile-to-world into root.transform when grafting an external
        // tileset under a host tile (3D Tiles 1.0/1.1: "If the content of a tile is another
        // tileset.json, that tileset's root.transform is applied AFTER the host tile's").
        if (parentTransform != null) {
            rootTransform.copy(Matrix4().setToMultiply(parentTransform, rootTransform))
        }
        // Precompute the base's `origin` + `dirWithSlash` once. The previous form
        // re-parsed scheme://authority/dir from `baseUri` on every child via 4-5
        // `substring()` allocations + 2-3 `indexOf` scans; simpleperf showed
        // resolveUri+firstOf at 14% of parse-pool CPU on Google Photoreal. Reused for
        // every child URI in this tileset.json.
        val base = parseBase(baseUri)
        val root = buildTile(doc.root, base, authProvider, rootTransform, parentRefinement)
        return Tileset(
            root = root,
            geometricError = doc.geometricError,
            gltfUpAxis = GltfUpAxis.fromStringOrNull(doc.asset.gltfUpAxis),
            assetVersion = doc.asset.version,
            extensionsUsed = doc.extensionsUsed,
        )
    }

    /** Precomputed parts of a tileset.json base URL; split once per parse and reused by
     *  every child URI. Empty [origin] signals a non-`scheme://authority/...` base and
     *  forces the Uri-library fallback in [resolveUri]. */
    internal class ResolvedBase(
        /** `"https://tile.googleapis.com"` — scheme + authority, no trailing slash. */
        val origin: String,
        /** Directory portion of the base path, always ending in `/`. */
        val dirWithSlash: String,
        /** Verbatim base URI; passed as `parentUri` to auth-provider callbacks. */
        val baseUri: String,
    )

    private fun buildTile(
        node: DocTile,
        base: ResolvedBase,
        authProvider: TilesetAuthProvider,
        tileToWorld: Matrix4,
        parentRefinement: Refinement?,
    ): Tile3d {
        val boundingVolume = decodeBoundingVolume(node.boundingVolume)
        val rawContentUri = node.content?.uri ?: node.content?.urlLegacy
        val contentUri = rawContentUri?.let { resolveAndRewrite(it, base, authProvider) }
        // Spec: refine required at root; omitted on non-root means "inherit from parent."
        val refinement = Refinement.fromStringOrNull(node.refine) ?: parentRefinement ?: Refinement.REPLACE
        val children = node.children.map { child ->
            // Spec: child transform is multiplied INTO the parent's. Parent's tileToWorld
            // already absolute; multiply by the child's local transform to get child's absolute.
            val childLocal = decodeTransform(child.transform, Matrix4())
            val childWorld = Matrix4().setToMultiply(tileToWorld, childLocal)
            buildTile(child, base, authProvider, childWorld, refinement)
        }
        return Tile3d(
            boundingVolume = boundingVolume,
            geometricError = node.geometricError,
            refinement = refinement,
            tileToWorld = tileToWorld,
            contentUri = contentUri,
            children = children,
            implicitTiling = node.implicitTiling,
        )
    }

    private fun decodeBoundingVolume(doc: DocBoundingVolume): BoundingVolume {
        // Region wins over box wins over sphere — 3D Tiles 1.0 says a tile defines exactly one.
        // The redundant-volume case (server emits multiple) is degenerate but harmless; we pick
        // region first because it's the cheapest to evaluate against the camera.
        return when {
            doc.region.size >= 6 -> BoundingVolume.Region(
                west = doc.region[0],
                south = doc.region[1],
                east = doc.region[2],
                north = doc.region[3],
                minHeight = doc.region[4],
                maxHeight = doc.region[5],
            )
            doc.box.size >= 12 -> BoundingVolume.Box(
                centerX = doc.box[0], centerY = doc.box[1], centerZ = doc.box[2],
                halfXx = doc.box[3], halfXy = doc.box[4], halfXz = doc.box[5],
                halfYx = doc.box[6], halfYy = doc.box[7], halfYz = doc.box[8],
                halfZx = doc.box[9], halfZy = doc.box[10], halfZz = doc.box[11],
            )
            doc.sphere.size >= 4 -> BoundingVolume.Sphere(
                centerX = doc.sphere[0],
                centerY = doc.sphere[1],
                centerZ = doc.sphere[2],
                radius = doc.sphere[3],
            )
            // Empty / malformed bounds: treat as a degenerate point sphere at the origin so the
            // tile shows up in the tree but always culls. Avoids hard-failing on legacy datasets.
            else -> BoundingVolume.Sphere(0.0, 0.0, 0.0, 0.0)
        }
    }

    /** Decode the spec's 16-double column-major transform into WW's row-major [Matrix4].
     *  Empty list yields identity (spec default). */
    private fun decodeTransform(list: List<Double>, into: Matrix4): Matrix4 {
        if (list.size < 16) return into.setToIdentity()
        // JSON column-major a0..a15 maps to Matrix4(m11=a0, m12=a4, m13=a8, m14=a12, ...).
        return into.set(
            list[0], list[4], list[8],  list[12],
            list[1], list[5], list[9],  list[13],
            list[2], list[6], list[10], list[14],
            list[3], list[7], list[11], list[15],
        )
    }

    private fun resolveAndRewrite(rawUri: String, base: ResolvedBase, authProvider: TilesetAuthProvider): String {
        val resolved = resolveUri(base, rawUri)
        return authProvider.rewriteChildUri(parentUri = base.baseUri, childUri = resolved)
    }

    /** Split [baseUri] once per parse into `origin` + `dirWithSlash` for child URI
     *  resolution. Empty origin signals an opaque base (file:// etc.) — [resolveUri]
     *  then takes the slower Uri-library path. */
    internal fun parseBase(baseUri: String): ResolvedBase {
        val suffixAt = firstOf(baseUri, '?', '#')
        val pathPart = if (suffixAt >= 0) baseUri.substring(0, suffixAt) else baseUri
        val schemeEnd = pathPart.indexOf("://")
        if (schemeEnd >= 0) {
            val originEnd = pathPart.indexOf('/', startIndex = schemeEnd + 3)
            if (originEnd > 0) {
                val origin = pathPart.substring(0, originEnd)
                val pathAfterOrigin = pathPart.substring(originEnd)
                val lastSlash = pathAfterOrigin.lastIndexOf('/')
                val dirWithSlash = if (lastSlash >= 0) pathAfterOrigin.substring(0, lastSlash + 1) else "/"
                return ResolvedBase(origin = origin, dirWithSlash = dirWithSlash, baseUri = baseUri)
            }
        }
        return ResolvedBase(origin = "", dirWithSlash = "", baseUri = baseUri)
    }

    /** Resolve a relative [child] URI against the precomputed [base]. Absolute URLs and
     *  root-relative children short-circuit; otherwise splices `origin + dir + child`.
     *  Any `?…`/`#…` suffix on [child] naturally lands at the tail. */
    internal fun resolveUri(base: ResolvedBase, child: String): String {
        if (child.startsWith("http://") || child.startsWith("https://") || child.startsWith("data:")) return child
        if (base.origin.isEmpty()) return resolveUriFallback(base.baseUri, child)
        val spliced = if (child.startsWith("/")) base.origin + child
                      else base.origin + base.dirWithSlash + child
        // Collapse dot segments — some servers 403 raw `..` and some HTTP stacks send paths verbatim.
        return if (child.startsWith(".") || child.contains("/.")) collapseDotSegments(spliced) else spliced
    }

    /** Remove `.` segments and fold `seg/..` pairs in the path part of [uri], leaving the
     *  origin and any `?…`/`#…` suffix untouched. Called only when a dot segment is present. */
    internal fun collapseDotSegments(uri: String): String {
        val suffixAt = firstOf(uri, '?', '#')
        val full = if (suffixAt >= 0) uri.substring(0, suffixAt) else uri
        val suffix = if (suffixAt >= 0) uri.substring(suffixAt) else ""
        val schemeEnd = full.indexOf("://")
        val pathStart = if (schemeEnd >= 0) full.indexOf('/', schemeEnd + 3) else 0
        if (pathStart < 0) return uri
        val prefix = full.substring(0, pathStart)
        val segments = ArrayList<String>()
        for (seg in full.substring(pathStart).split('/')) when (seg) {
            "", "." -> {}
            ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.size - 1)
            else -> segments.add(seg)
        }
        val trailingSlash = if (full.endsWith("/") && segments.isNotEmpty()) "/" else ""
        return prefix + "/" + segments.joinToString("/") + trailingSlash + suffix
    }

    /** Slow fallback when [baseUri] doesn't match `scheme://authority/...`. Used for
     *  custom-scheme tilesets (e.g. file:// or blob:); the URI library is heavyweight
     *  but correct for these shapes. Strips empty `?` / `#` delimiters that some servers
     *  reject — `path?` produces a request that hits an empty-query handler instead of
     *  the intended path. */
    private fun resolveUriFallback(baseUri: String, child: String): String {
        return try {
            val parentUri = Uri.parse(baseUri)
            val parentPath = parentUri.path ?: ""
            val dir = parentPath.substringBeforeLast('/', missingDelimiterValue = "")
            val childSuffixAt = firstOf(child, '?', '#')
            val childPath = if (childSuffixAt >= 0) child.substring(0, childSuffixAt) else child
            val rawSuffix = if (childSuffixAt >= 0) child.substring(childSuffixAt) else ""
            val childSuffix = if (rawSuffix == "?" || rawSuffix == "#") "" else rawSuffix
            val rawPath = if (childPath.startsWith("/")) childPath else "$dir/$childPath"
            val newPath = if (rawPath.contains("/.")) collapseDotSegments(rawPath) else rawPath
            parentUri.buildUpon().path(newPath).clearQuery().fragment(null).build().toString() + childSuffix
        } catch (_: Throwable) {
            val dir = baseUri.substringBeforeLast('/', missingDelimiterValue = "")
            if (child.startsWith("/")) child else "$dir/$child"
        }
    }

    /** Zero-alloc alternative to `indexOfAny(charArrayOf(a, b))`. Off the hot path. */
    private fun firstOf(s: String, a: Char, b: Char): Int {
        val ia = s.indexOf(a)
        val ib = s.indexOf(b)
        return when {
            ia < 0 -> ib
            ib < 0 -> ia
            else -> if (ia < ib) ia else ib
        }
    }
}
