package earth.worldwind.layer.buildings

import earth.worldwind.render.Color

/**
 * Maps OSM colour/material tags to a wall [Color]. Tries, in order:
 * 1. `building:colour` or `colour` parsed as `#RRGGBB`/`#RGB`/CSS-name.
 * 2. `building:material` mapped via a small lookup table.
 *
 * Returns `null` when nothing parses, leaving the caller free to use a default. The lookup
 * tables stay small on purpose - they cover what's frequently tagged in OSM. Unknown values
 * are treated as "no opinion" rather than guessed at.
 */
object OsmColors {
    fun resolve(tags: Map<String, String>): Color? {
        parseColor(tags["building:colour"] ?: tags["colour"])?.let { return it }
        return materialColor(tags["building:material"])
    }

    /** Accepts `#RRGGBB`, `#RGB`, `#RRGGBBAA`, or a CSS-style name from [NAMED]. */
    internal fun parseColor(raw: String?): Color? {
        if (raw.isNullOrBlank()) return null
        val s = raw.trim()
        if (s.startsWith("#")) {
            // Color.fromHexString only accepts 6- or 8-digit forms. Expand 3-digit `#RGB`.
            val hex = if (s.length == 4) "#${s[1]}${s[1]}${s[2]}${s[2]}${s[3]}${s[3]}" else s
            return try { Color.fromHexString(hex) } catch (_: Throwable) { null }
        }
        return NAMED[s.lowercase()]
    }

    private fun materialColor(raw: String?): Color? {
        if (raw.isNullOrBlank()) return null
        return MATERIALS[raw.trim().lowercase()]
    }

    /** CSS-ish names commonly seen in OSM `building:colour` / `colour` values. Tones are tuned
     *  on the lower-saturation side so they read as architectural surfaces rather than primary
     *  colours - a "red" building should look like brick, not a stop sign. */
    private val NAMED: Map<String, Color> = mapOf(
        "white"      to Color(red = 240, green = 240, blue = 235),
        "black"      to Color(red =  50, green =  50, blue =  50),
        "gray"       to Color(red = 140, green = 140, blue = 140),
        "grey"       to Color(red = 140, green = 140, blue = 140),
        "lightgray"  to Color(red = 200, green = 200, blue = 200),
        "lightgrey"  to Color(red = 200, green = 200, blue = 200),
        "darkgray"   to Color(red =  90, green =  90, blue =  90),
        "darkgrey"   to Color(red =  90, green =  90, blue =  90),
        "red"        to Color(red = 180, green =  80, blue =  65),
        "maroon"     to Color(red = 130, green =  55, blue =  50),
        "brown"      to Color(red = 130, green =  90, blue =  60),
        "orange"     to Color(red = 215, green = 140, blue =  70),
        "yellow"     to Color(red = 220, green = 195, blue = 105),
        "beige"      to Color(red = 220, green = 205, blue = 175),
        "cream"      to Color(red = 235, green = 220, blue = 190),
        "green"      to Color(red = 115, green = 140, blue = 100),
        "olive"      to Color(red = 130, green = 130, blue =  85),
        "blue"       to Color(red = 100, green = 130, blue = 170),
        "lightblue"  to Color(red = 160, green = 190, blue = 215),
        "purple"     to Color(red = 130, green = 100, blue = 145),
        "pink"       to Color(red = 220, green = 170, blue = 175),
    )

    /** `building:material` heuristic. Single tone per material - facade variation isn't modelled. */
    private val MATERIALS: Map<String, Color> = mapOf(
        "brick"          to Color(red = 165, green = 100, blue =  80),
        "concrete"       to Color(red = 180, green = 180, blue = 175),
        "reinforced_concrete" to Color(red = 180, green = 180, blue = 175),
        "stone"          to Color(red = 160, green = 155, blue = 140),
        "sandstone"      to Color(red = 200, green = 175, blue = 135),
        "marble"         to Color(red = 225, green = 220, blue = 215),
        "wood"           to Color(red = 155, green = 115, blue =  75),
        "timber_framing" to Color(red = 200, green = 175, blue = 140),
        "glass"          to Color(red = 140, green = 165, blue = 190),
        "metal"          to Color(red = 140, green = 140, blue = 150),
        "steel"          to Color(red = 140, green = 140, blue = 150),
        "plaster"        to Color(red = 230, green = 220, blue = 200),
        "cement_block"   to Color(red = 175, green = 175, blue = 165),
    )
}
