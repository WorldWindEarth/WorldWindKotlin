package earth.worldwind.layer.ogc3d.stream

/** Magic-bytes-based content-type dispatcher for 3D Tiles payloads. */
object ContentDispatcher {
    enum class Kind { B3DM, I3DM, PNTS, CMPT, GLTF, GLTF_JSON, TILESET_JSON, UNKNOWN }

    /** Detect from the first ~8 bytes. JSON detection probes the first 256. */
    fun detect(bytes: ByteArray): Kind {
        if (bytes.size < 4) return Kind.UNKNOWN
        val m0 = bytes[0].toInt() and 0xFF
        val m1 = bytes[1].toInt() and 0xFF
        val m2 = bytes[2].toInt() and 0xFF
        val m3 = bytes[3].toInt() and 0xFF
        return when {
            m0 == 'b'.code && m1 == '3'.code && m2 == 'd'.code && m3 == 'm'.code -> Kind.B3DM
            m0 == 'i'.code && m1 == '3'.code && m2 == 'd'.code && m3 == 'm'.code -> Kind.I3DM
            m0 == 'p'.code && m1 == 'n'.code && m2 == 't'.code && m3 == 's'.code -> Kind.PNTS
            m0 == 'c'.code && m1 == 'm'.code && m2 == 'p'.code && m3 == 't'.code -> Kind.CMPT
            m0 == 'g'.code && m1 == 'l'.code && m2 == 'T'.code && m3 == 'F'.code -> Kind.GLTF
            looksLikeJson(bytes) -> detectJsonKind(bytes)
            else -> Kind.UNKNOWN
        }
    }

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        // Strip UTF-8 BOM + leading whitespace, then look for '{'.
        var i = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) i = 3
        while (i < bytes.size && bytes[i].toInt().toChar().isWhitespace()) i++
        return i < bytes.size && bytes[i].toInt().toChar() == '{'
    }

    private fun detectJsonKind(bytes: ByteArray): Kind {
        val sample = bytes.decodeToString(0, minOf(bytes.size, 256))
        return when {
            "\"asset\"" in sample && ("\"root\"" in sample || "\"geometricError\"" in sample) -> Kind.TILESET_JSON
            "\"asset\"" in sample && "\"scenes\"" in sample -> Kind.GLTF_JSON
            else -> Kind.UNKNOWN
        }
    }
}
