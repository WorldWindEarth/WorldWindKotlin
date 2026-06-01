package earth.worldwind.layer.ogc3d.tileset

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Wire-format model for tileset.json per OGC 3D Tiles 1.0. Decoded with kotlinx.serialization
 * into [DocTileset] then traversed into the runtime [Tile3d] tree by [TilesetParser].
 *
 * Only the fields the engine actually consumes are modeled. Unknown JSON keys are ignored
 * (configured at the decoder).
 *
 * @see TilesetParser
 */
@Serializable
internal data class DocTileset(
    val asset: DocAsset = DocAsset(),
    val geometricError: Double = 0.0,
    val root: DocTile = DocTile(),
    @SerialName("extensionsUsed") val extensionsUsed: List<String> = emptyList(),
)

@Serializable
internal data class DocAsset(
    val version: String = "1.0",
    /** Null when omitted from the JSON; the layer resolves the effective axis (per-tile
     *  b3dm override > this field > 3D Tiles 1.0 default of Y). */
    val gltfUpAxis: String? = null,
)

@Serializable
internal data class DocTile(
    val boundingVolume: DocBoundingVolume = DocBoundingVolume(),
    val geometricError: Double = 0.0,
    val refine: String? = null,
    val transform: List<Double> = emptyList(),
    val content: DocContent? = null,
    val children: List<DocTile> = emptyList(),
    val viewerRequestVolume: DocBoundingVolume? = null,
    /** 3D Tiles 1.1 implicit-tiling root marker. Present only on the tile whose subtree
     *  describes the implicit subdivision; descendants are computed, not declared. */
    val implicitTiling: ImplicitTilingDescription? = null,
)

@Serializable
internal data class DocBoundingVolume(
    val region: List<Double> = emptyList(),
    val box: List<Double> = emptyList(),
    val sphere: List<Double> = emptyList(),
)

@Serializable
internal data class DocContent(
    val uri: String? = null,
    @SerialName("url") val urlLegacy: String? = null,
    val boundingVolume: DocBoundingVolume? = null,
)

@Suppress("unused")
@Serializable
internal data class DocExtras(val extras: JsonElement? = null)
