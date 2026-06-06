package earth.worldwind.formats.collada

import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Position

/**
 * Geo-placement read from a KMZ's `doc.kml` `<Model>` (or a `<Placemark><Point>` fallback). KMZ-wrapped
 * COLLADA carries its reference position, orientation and scale here rather than in the `.dae`.
 */
internal class KmzPlacement(
    val daeHref: String?,
    val position: Position?,
    val scale: Double,
    val heading: Double,
    val tilt: Double,
    val roll: Double,
    val altitudeMode: AltitudeMode
)

/** Parses a KMZ `doc.kml` for the streaming COLLADA loader (JVM/Android). */
internal object KmzArchive {

    /** Parses a `doc.kml` string for the model placement using the generic [XmlElement] parser. */
    fun parsePlacement(kml: String): KmzPlacement {
        val root = XmlElement.parse(kml)
        // Scope to the <Model> so a sibling <Region>/<Placemark> can't hijack altitudeMode/Scale/Orientation/Link.
        val model = root.querySelector("Model") ?: root
        fun num(parent: XmlElement?, tag: String) = parent?.querySelector(tag)?.textContent?.trim()?.toDoubleOrNull()

        val location = model.querySelector("Location")
        val position = if (location != null) {
            val lon = num(location, "longitude"); val lat = num(location, "latitude")
            if (lon != null && lat != null) Position.fromDegrees(lat, lon, num(location, "altitude") ?: 0.0) else null
        } else {
            // Fallback: <Point><coordinates>lon,lat[,alt]</coordinates>
            model.querySelector("Point")?.querySelector("coordinates")?.textContent?.trim()
                ?.split(",")?.map { it.trim().toDoubleOrNull() }?.let { c ->
                    if (c.size >= 2 && c[0] != null && c[1] != null)
                        Position.fromDegrees(c[1]!!, c[0]!!, c.getOrNull(2) ?: 0.0) else null
                }
        }

        val orientation = model.querySelector("Orientation")
        val scale = num(model.querySelector("Scale"), "x") ?: 1.0
        // localName matching means a <gx:altitudeMode> extension also lands here; map its sea-floor
        // values to the nearest WorldWind equivalent (the enum has no sea-floor concept).
        val altitudeMode = when (model.querySelector("altitudeMode")?.textContent?.trim()) {
            "clampToGround", "clampToSeaFloor" -> AltitudeMode.CLAMP_TO_GROUND
            "relativeToGround", "relativeToSeaFloor" -> AltitudeMode.RELATIVE_TO_GROUND
            else -> AltitudeMode.ABSOLUTE
        }

        // KML <Link><href> is an element; some writers use an href attribute.
        val link = model.querySelector("Link")
        val daeHref = link?.querySelector("href")?.textContent?.trim()?.ifEmpty { null }
            ?: link?.getAttribute("href")

        return KmzPlacement(
            daeHref = daeHref,
            position = position,
            scale = scale,
            heading = num(orientation, "heading") ?: 0.0,
            tilt = num(orientation, "tilt") ?: 0.0,
            roll = num(orientation, "roll") ?: 0.0,
            altitudeMode = altitudeMode
        )
    }
}
