package earth.worldwind.globe.elevation.coverage.dted

import java.io.File

/** Parsing of NIMA DTED file paths (no regex): the SW corner + level from a file's name + parent dir.
 *  Standard layouts: combined `n45e034.dtX`, directory `<lon>/<lat>.dtX`, or flipped `<lat>/<lon>`. */
internal object DtedLayout {

    /** DTED level (0/1/2) from a `.dtN` extension, case-insensitive, or -1 if not a DTED file. */
    fun dtedLevel(name: String): Int {
        val n = name.length
        if (n < 4 || name[n - 4] != '.') return -1
        if (name[n - 3] != 'd' && name[n - 3] != 'D') return -1
        if (name[n - 2] != 't' && name[n - 2] != 'T') return -1
        return when (name[n - 1]) { '0' -> 0; '1' -> 1; '2' -> 2; else -> -1 }
    }

    /** SW corner of a DTED file from its path (combined name, `<lon>/<lat>`, or `<lat>/<lon>`);
     *  null if the name isn't NIMA-standard. */
    fun inferCorner(file: File): Pair<Int, Int>? {
        val name = file.nameWithoutExtension
        parseLatLon(name)?.let { return it }
        val parent = file.parentFile?.name ?: return null
        parseLon(parent)?.let { lon -> parseLat(name)?.let { return it to lon } }
        parseLat(parent)?.let { lat -> parseLon(name)?.let { return lat to it } }
        return null
    }

    // SW-corner token parsing (no regex). LAT starts N/S + 1-2 digits, LON E/W + 1-3; never collide.
    fun parseLat(s: String): Int? {
        val sign = when (s.firstOrNull()) { 'n', 'N' -> 1; 's', 'S' -> -1; else -> return null }
        if (s.length !in 2..3) return null
        var v = 0
        for (i in 1 until s.length) { val c = s[i]; if (c < '0' || c > '9') return null; v = v * 10 + (c - '0') }
        return sign * v
    }

    fun parseLon(s: String): Int? {
        val sign = when (s.firstOrNull()) { 'e', 'E' -> 1; 'w', 'W' -> -1; else -> return null }
        if (s.length !in 2..4) return null
        var v = 0
        for (i in 1 until s.length) { val c = s[i]; if (c < '0' || c > '9') return null; v = v * 10 + (c - '0') }
        return sign * v
    }

    fun parseLatLon(s: String): Pair<Int, Int>? {
        val latSign = when (s.firstOrNull()) { 'n', 'N' -> 1; 's', 'S' -> -1; else -> return null }
        var i = 1; var lat = 0; var latDigits = 0
        while (i < s.length && s[i] in '0'..'9') { lat = lat * 10 + (s[i] - '0'); i++; latDigits++ }
        if (latDigits !in 1..2) return null
        val lonSign = when (s.getOrNull(i)) { 'e', 'E' -> 1; 'w', 'W' -> -1; else -> return null }
        i++; var lon = 0; var lonDigits = 0
        while (i < s.length && s[i] in '0'..'9') { lon = lon * 10 + (s[i] - '0'); i++; lonDigits++ }
        if (lonDigits !in 1..3 || i != s.length) return null
        return (latSign * lat) to (lonSign * lon)
    }
}
