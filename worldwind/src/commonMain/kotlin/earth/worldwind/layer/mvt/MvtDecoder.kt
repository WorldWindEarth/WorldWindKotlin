package earth.worldwind.layer.mvt

import earth.worldwind.util.IntList

/**
 * Decode the bytes of a Mapbox Vector Tile (MVT v2.1) into an [MvtTile]. Geometry stays in
 * tile-local extent coordinates; conversion to lat/lon is [MvtGeometry]'s job.
 *
 * Walks the protobuf wire format directly via [ProtobufReader] — no codegen, no runtime.
 *
 * Wire layout (only the fields we care about; unknown fields are skipped):
 * ```
 * Tile           { repeated Layer layers = 3 }
 * Layer          { string name = 1; repeated Feature features = 2; repeated string keys = 3;
 *                  repeated Value values = 4; uint32 extent = 5; uint32 version = 15 }
 * Feature        { uint64 id = 1; repeated uint32 tags = 2 [packed]; GeomType type = 3;
 *                  repeated uint32 geometry = 4 [packed] }
 * Value          { string=1; float=2; double=3; int64=4; uint64=5; sint64=6; bool=7 }
 * ```
 *
 * Some servers send packed repeated fields as non-packed, and some old encoders do the
 * opposite; both forms decode identically here because [appendPackedUInt32] accepts a
 * single value (non-packed) by treating it as a one-element packed run.
 */
object MvtDecoder {

    fun decode(bytes: ByteArray): MvtTile {
        val reader = ProtobufReader(bytes)
        val layers = mutableListOf<MvtLayer>()
        while (reader.pos < bytes.size) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                3 -> layers += readLayer(reader)
                else -> reader.skipValue(tag)
            }
        }
        return MvtTile(layers)
    }

    private fun readLayer(reader: ProtobufReader): MvtLayer {
        val limit = reader.readDelimitedLimit()
        var name = ""
        var version = 1
        var extent = 4096
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        val features = mutableListOf<MvtFeature>()
        // Reused across this layer's features (see readFeature) so per-feature tag/geometry
        // accumulation doesn't re-allocate the backing int[] each time.
        val tagsScratch = IntList()
        val geomScratch = IntList()
        while (reader.pos < limit) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> name = reader.readString()
                2 -> features += readFeature(reader, tagsScratch, geomScratch)
                3 -> keys += reader.readString()
                4 -> values += readValue(reader)
                5 -> extent = reader.readInt()
                15 -> version = reader.readInt()
                else -> reader.skipValue(tag)
            }
        }
        return MvtLayer(name, version, extent, keys, values, features)
    }

    private fun readFeature(reader: ProtobufReader, tagsScratch: IntList, geomScratch: IntList): MvtFeature {
        val limit = reader.readDelimitedLimit()
        var id = 0L
        var type = MvtGeometryType.UNKNOWN
        // Accumulate packed varints into primitive IntLists (not ArrayList<Int>) so nothing is
        // boxed — geometry alone is hundreds of ints per polygon. The scratch lists are reused
        // across the layer's features, so the backing int[] grows once to the layer's max and
        // stays instead of re-allocating per feature; only the retained toIntArray() copies remain.
        tagsScratch.clear()
        geomScratch.clear()
        while (reader.pos < limit) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> id = reader.readLong()
                2 -> appendPackedUInt32(reader, tag, tagsScratch)
                3 -> type = decodeGeomType(reader.readInt())
                4 -> appendPackedUInt32(reader, tag, geomScratch)
                else -> reader.skipValue(tag)
            }
        }
        return MvtFeature(
            id, type,
            if (tagsScratch.size > 0) tagsScratch.toIntArray() else EMPTY_INTS,
            if (geomScratch.size > 0) geomScratch.toIntArray() else EMPTY_INTS,
        )
    }

    private fun readValue(reader: ProtobufReader): Any? {
        val limit = reader.readDelimitedLimit()
        var out: Any? = null
        while (reader.pos < limit) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                1 -> out = reader.readString()
                2 -> out = reader.readFloat()
                3 -> out = reader.readDouble()
                4 -> out = reader.readLong()                 // int64
                5 -> out = reader.readLong()                 // uint64 — keep as Long, callers cast as needed
                6 -> out = reader.readSInt()                 // sint64 (zigzag)
                7 -> out = reader.readBool()
                else -> reader.skipValue(tag)
            }
        }
        return out
    }

    /**
     * Append one occurrence of a `repeated uint32` field into [out]. Handles both the packed
     * form (wire type 2 — one tag, length-delimited run of varints) and the non-packed form
     * (wire type 0 — one varint per tag, caller's outer loop will re-enter this for each).
     *
     * MVT spec mandates packed for `tags` and `geometry`, but real-world tile producers have
     * been caught emitting them non-packed; accepting both costs one wire-type check.
     */
    private fun appendPackedUInt32(reader: ProtobufReader, tag: Int, out: IntList) {
        if ((tag and 0x7) == 2) {
            val limit = reader.readDelimitedLimit()
            while (reader.pos < limit) out.add(reader.readInt())
        } else {
            out.add(reader.readInt())
        }
    }

    private fun decodeGeomType(raw: Int): MvtGeometryType = when (raw) {
        1 -> MvtGeometryType.POINT
        2 -> MvtGeometryType.LINESTRING
        3 -> MvtGeometryType.POLYGON
        else -> MvtGeometryType.UNKNOWN
    }

    private val EMPTY_INTS = IntArray(0)
}
