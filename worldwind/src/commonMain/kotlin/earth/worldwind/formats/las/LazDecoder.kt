package earth.worldwind.formats.las

/**
 * Pluggable LASzip decompressor. Decompression is a large, self-contained algorithm, so it
 * lives behind this interface exactly like the 3D-Tiles Draco decoder
 * ([earth.worldwind.layer.ogc3d.Ogc3dDecoderRegistry.dracoPointCloudDecoder]): plain-LAS
 * parsing is always available; opening a `.laz` requires an installed decoder.
 *
 * A decoder's job is purely decompression: it reconstructs the standard ASPRS point records
 * (the concatenation of the LASzip items, in item order, which byte-for-byte matches an
 * uncompressed record for the same point format) so [LasReader] can share one field-extraction
 * path across compressed and uncompressed files.
 */
interface LazChunkDecoder {
    /**
     * Decompress every point in [fileBytes]. The chunk table and compressed data start at
     * [LasHeader.offsetToPointData].
     *
     * @return a byte array of `header.pointCount * decodedRecordLength` bytes, where
     *   `decodedRecordLength` is the sum of the [LaszipVlr] item sizes; laid out as standard
     *   ASPRS point records.
     */
    suspend fun decode(fileBytes: ByteArray, header: LasHeader, vlr: LaszipVlr): ByteArray
}

/** Process-wide registry for the optional [LazChunkDecoder]. Wire once at app startup. */
object LasDecoderRegistry {
    var lazDecoder: LazChunkDecoder? = null
}
