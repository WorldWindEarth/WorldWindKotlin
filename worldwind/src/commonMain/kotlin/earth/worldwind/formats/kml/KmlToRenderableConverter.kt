package earth.worldwind.formats.kml

import earth.worldwind.formats.kml.models.Geometry
import earth.worldwind.formats.kml.models.IconStyle
import earth.worldwind.formats.kml.models.LabelStyle
import earth.worldwind.formats.kml.models.LineString
import earth.worldwind.formats.kml.models.LineStyle
import earth.worldwind.formats.kml.models.Placemark
import earth.worldwind.formats.kml.models.Point
import earth.worldwind.formats.kml.models.PolyStyle
import earth.worldwind.formats.kml.models.Polygon
import earth.worldwind.formats.kml.models.Style
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Offset
import earth.worldwind.geom.Position
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.TextAttributes

internal class KmlToRenderableConverter {
    companion object {
        private const val HIGHLIGHT_INCREMENT = 4f

        private val spaceCharsRegex by lazy { "[\\s\\n\\t\\r\\u00A0\\u200B\\u202F\\u2009]".toRegex() }

        /**
         * optimized method to get coordinates from a string
         */
        private fun extractPoints(input: String?): Pair<List<Position>, Boolean> {
            var absolute = false

            // Normalize input by trimming leading/trailing whitespaces
            // and replacing all forms of space with a single space
            val normalizedInput = input?.trim()?.replace(spaceCharsRegex, " ")

            if (normalizedInput.isNullOrBlank()) return Pair(emptyList(), false)

            val result = mutableListOf<Position>()
            val length = normalizedInput.length
            var i = 0

            fun parseDouble(start: Int, end: Int): Double {
                var number = 0.0
                var decimal = 0.0
                var decimalPlace = 1.0
                var negative = false
                var inDecimal = false
                for (j in start until end) {
                    val c = normalizedInput[j]
                    when {
                        c == '-' -> negative = true
                        c == '.' -> inDecimal = true
                        inDecimal -> {
                            decimal = decimal * 10 + (c - '0')
                            decimalPlace *= 10
                        }

                        else -> number = number * 10 + (c - '0')
                    }
                }
                val value = number + (decimal / decimalPlace)
                return if (negative) -value else value
            }

            while (i < length) {
                var start = i

                // Find longitude
                while (i < length && normalizedInput[i] != ',') i++
                val lon = parseDouble(start, i)
                i++ // Skip the comma

                // Find latitude
                start = i
                while (i < length && normalizedInput[i] != ',' && normalizedInput[i] != ' ') i++
                val lat = parseDouble(start, i)

                // Check for altitude
                var alt: Double? = null
                if (i < length && normalizedInput[i] == ',') {
                    i++ // Skip the comma
                    start = i
                    while (i < length && normalizedInput[i] != ' ') i++
                    alt = parseDouble(start, i)
                }

                absolute = absolute || alt != null

                result.add(Position.fromDegrees(lat, lon, alt ?: 0.0))

                // Skip the space between coordinate sets
                while (i < length && normalizedInput[i] == ' ') i++
            }

            return result to absolute
        }
    }

    fun convertPlacemarkToRenderable(placemark: Placemark, definedStyle: Style? = null): Renderable? {
        val style = placemark.stylesList?.firstOrNull() ?: definedStyle
        val geometry: Geometry = placemark.geometryList?.firstOrNull() ?: return null

        return when (geometry) {
            is LineString -> createPathFromLineString(geometry, style, placemark.name)
            is Polygon -> createPathFromPolygon(geometry, style, placemark.name)
            is Point -> createPlacemark(geometry, style, placemark.name)
            else -> null
        }
    }

    private fun createPathFromLineString(lineString: LineString, style: Style?, name: String?): Renderable {
        val (positions, isAbsolute) = extractPoints(lineString.coordinates?.value)

        return Path(positions).apply {
            altitudeMode = getAltitudeModeFrom(lineString.altitudeMode)
                ?: if (isAbsolute) AltitudeMode.ABSOLUTE else AltitudeMode.CLAMP_TO_GROUND

            lineString.extrude.let { isExtrude = it ?: (altitudeMode == AltitudeMode.ABSOLUTE) }
            lineString.tessellate.let { isFollowTerrain = it ?: (altitudeMode != AltitudeMode.ABSOLUTE) }

            // If the path is clamped to the ground and terrain conforming, draw as a great circle.
            // Otherwise draw as linear segments.
            pathType = if (altitudeMode == AltitudeMode.CLAMP_TO_GROUND && isFollowTerrain) {
                PathType.GREAT_CIRCLE
            } else {
                PathType.LINEAR
            }

            name?.let { displayName = it }

            highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }
            maximumIntermediatePoints = 0 // Disable intermediate points for performance reasons

            applyStyleOnShapeAttributes(style)
        }
    }

    private fun createPathFromPolygon(polygon: Polygon, style: Style?, name: String?): Renderable {
        return earth.worldwind.shape.Polygon().apply {
            polygon.extrude?.let { isExtrude = it }
            polygon.tessellate?.let { isFollowTerrain = it }

            altitudeMode = getAltitudeModeFrom(polygon.altitudeMode) ?: AltitudeMode.CLAMP_TO_GROUND

            polygon.outerBoundaryIs?.let {
                it.value?.forEach { linearRing ->
                    linearRing.coordinates?.value?.let { value ->
                        val (positions, _) = extractPoints(value)
                        addBoundary(positions)
                    }
                }
            }

            polygon.innerBoundaryIs?.let {
                it.value?.forEach { linearRing ->
                    linearRing.coordinates?.value?.let { value ->
                        val (positions, _) = extractPoints(value)
                        addBoundary(positions)
                    }
                }
            }

            // If the path is clamped to the ground and terrain conforming, draw as a great circle.
            // Otherwise draw as linear segments.
            pathType = if (altitudeMode == AltitudeMode.CLAMP_TO_GROUND && isFollowTerrain) {
                PathType.GREAT_CIRCLE
            } else {
                PathType.LINEAR
            }

            name?.let { displayName = it }

            highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }
            maximumIntermediatePoints = 0 // Disable intermediate points for performance reasons

            applyStyleOnShapeAttributes(style)
        }
    }

    private fun createPlacemark(point: Point, style: Style?, name: String?): Renderable? {
        val (positions, _) = extractPoints(point.coordinates?.value)
        val position = positions.firstOrNull() ?: return null

        val iconStyle = style?.stylesList?.filterIsInstance<IconStyle>()?.firstOrNull()
        val labelStyle = style?.stylesList?.filterIsInstance<LabelStyle>()?.firstOrNull()

        return if (iconStyle?.scale == 0.0) {
            Label(position, name).apply {
                attributes.applyStyle(labelStyle)
            }
        } else {
            earth.worldwind.shape.Placemark(position, label = name).apply {
                attributes.apply {
                    iconStyle?.scale?.let { imageScale = it }
                    iconStyle?.color?.let { imageColor = fromHexABRG(it) }
                    labelAttributes.applyStyle(labelStyle)
                }
            }
        }.apply {
            altitudeMode = getAltitudeModeFrom(point.altitudeMode) ?: AltitudeMode.CLAMP_TO_GROUND
        }
    }

    private fun TextAttributes.applyStyle(labelStyle: LabelStyle?) {
        apply {
            labelStyle?.color?.let {
                textColor = fromHexABRG(it)
                outlineColor = textColor.toContrastColor()
            }
            labelStyle?.width?.let { outlineWidth = it }
            textOffset = Offset.center()
        }
    }

    private fun fromHexABRG(it: String) = Color.fromHexString(hexString = it, argb = true).apply {
        // replace red and blue channels, because KML uses ABGR format
        set(red = blue, green = green, blue = red, alpha = alpha)
    }

    private fun AbstractShape.applyStyleOnShapeAttributes(style: Style?) {
        attributes.apply {
            val lineStyle = style?.stylesList?.filterIsInstance<LineStyle>()?.firstOrNull()
            val polyStyle = style?.stylesList?.filterIsInstance<PolyStyle>()?.firstOrNull()

            lineStyle?.color?.let { outlineColor = fromHexABRG(it) }
            polyStyle?.color?.let { interiorColor = fromHexABRG(it) }
            lineStyle?.width?.let { outlineWidth = it }

            isPickInterior = false // Allow picking outline only

            // Disable depths write for translucent shapes to avoid conflict with always on top Placemarks
            if (interiorColor.alpha < 1.0f) isDepthWrite = false
        }
    }

    private fun getAltitudeModeFrom(value: String?) = when (value) {
        "absolute" -> AltitudeMode.ABSOLUTE
        "clampToGround" -> AltitudeMode.CLAMP_TO_GROUND
        "relativeToGround" -> AltitudeMode.RELATIVE_TO_GROUND
        else -> null
    }
}