package earth.worldwind.formats.dted

/**
 * Synthesises a minimal MIL-PRF-89020B-compliant DTED file in memory for tests —
 * no real `.dt0/.dt1/.dt2` fixture required.
 *
 * Layout:
 *   UHL (80 bytes, "UHL" ID + DDDMMSSH lon/lat + width/height ASCII)
 *   DSI (648 bytes, "DSI" ID + classification + "DTEDn" series designator)
 *   ACC (2700 bytes, "ACC" ID only — accuracy fields are unused by the reader)
 *   width × per-column record (8-byte header + height int16 BE elev + 4-byte
 *   BE unsigned-byte-sum checksum)
 */
internal fun synthDted(
    latSW: Int = 0,
    lonSW: Int = 0,
    width: Int = 3,
    height: Int = 3,
    level: Int = 1,
    elevation: (column: Int, latIndex: Int) -> Short = { _, _ -> 100 },
): ByteArray {
    val recSize = DTED.REC_HEADER_SIZE + height * 2 + DTED.REC_CHKSUM_SIZE
    val bytes = ByteArray(DTED.DATA_OFFSET + width * recSize)

    // --- UHL ---
    writeAscii(bytes, 0, "UHL")
    // longitude: DDDMMSSH (3 deg + 2 min + 2 sec + 1 hemi = 8 chars)
    val lonDeg = if (lonSW >= 0) lonSW else -lonSW
    val lonHemi = if (lonSW >= 0) 'E' else 'W'
    writeAscii(bytes, 4, lonDeg.toString().padStart(3, '0') + "00" + "00" + lonHemi)
    // latitude: DDDMMSSH
    val latDeg = if (latSW >= 0) latSW else -latSW
    val latHemi = if (latSW >= 0) 'N' else 'S'
    writeAscii(bytes, 12, latDeg.toString().padStart(3, '0') + "00" + "00" + latHemi)
    writeAscii(bytes, 32, "U  ")
    writeAscii(bytes, 47, width.toString().padStart(4, '0'))
    writeAscii(bytes, 51, height.toString().padStart(4, '0'))

    // --- DSI ---
    writeAscii(bytes, DTED.DSI_OFFSET, "DSI")
    writeAscii(bytes, DTED.DSI_OFFSET + 3, "U")
    writeAscii(bytes, DTED.DSI_OFFSET + 59, "DTED$level")

    // --- ACC ---
    writeAscii(bytes, DTED.ACC_OFFSET, "ACC")

    // --- Data records ---
    var off = DTED.DATA_OFFSET
    for (x in 0 until width) {
        bytes[off] = 0xAA.toByte() // sentinel byte
        // header bytes 1..7 left as zero
        for (i in 0 until height) {
            val v = elevation(x, i).toInt()
            bytes[off + DTED.REC_HEADER_SIZE + i * 2] = ((v shr 8) and 0xFF).toByte()
            bytes[off + DTED.REC_HEADER_SIZE + i * 2 + 1] = (v and 0xFF).toByte()
        }
        // Checksum: sum of unsigned bytes across header + elevation payload.
        var sum = 0
        val checksumOff = off + DTED.REC_HEADER_SIZE + height * 2
        for (i in off until checksumOff) sum += bytes[i].toInt() and 0xFF
        bytes[checksumOff]     = ((sum ushr 24) and 0xFF).toByte()
        bytes[checksumOff + 1] = ((sum ushr 16) and 0xFF).toByte()
        bytes[checksumOff + 2] = ((sum ushr 8) and 0xFF).toByte()
        bytes[checksumOff + 3] = (sum and 0xFF).toByte()
        off += recSize
    }
    return bytes
}

private fun writeAscii(bytes: ByteArray, offset: Int, s: String) {
    for (i in s.indices) bytes[offset + i] = s[i].code.toByte()
}
