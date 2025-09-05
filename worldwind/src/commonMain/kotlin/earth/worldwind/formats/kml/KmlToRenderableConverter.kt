package earth.worldwind.formats.kml

import earth.worldwind.MR
import earth.worldwind.formats.*
import earth.worldwind.formats.DEFAULT_DENSITY
import earth.worldwind.formats.DEFAULT_FILL_COLOR
import earth.worldwind.formats.DEFAULT_IMAGE_SCALE
import earth.worldwind.formats.DEFAULT_LINE_COLOR
import earth.worldwind.formats.HIGHLIGHT_INCREMENT
import earth.worldwind.formats.forceHttps
import earth.worldwind.formats.isValidHttpsUrl
import earth.worldwind.formats.kml.models.Geometry
import earth.worldwind.formats.kml.models.GroundOverlay
import earth.worldwind.formats.kml.models.Icon
import earth.worldwind.formats.kml.models.IconStyle
import earth.worldwind.formats.kml.models.LabelStyle
import earth.worldwind.formats.kml.models.LatLonBox
import earth.worldwind.formats.kml.models.LineString
import earth.worldwind.formats.kml.models.LineStyle
import earth.worldwind.formats.kml.models.LinearRing
import earth.worldwind.formats.kml.models.MultiGeometry
import earth.worldwind.formats.kml.models.Placemark
import earth.worldwind.formats.kml.models.Point
import earth.worldwind.formats.kml.models.PolyStyle
import earth.worldwind.formats.kml.models.Polygon
import earth.worldwind.formats.kml.models.Style
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Offset
import earth.worldwind.geom.OffsetMode.FRACTION
import earth.worldwind.geom.OffsetMode.PIXELS
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.PathType
import earth.worldwind.shape.ShapeAttributes
import earth.worldwind.shape.SurfaceImage
import earth.worldwind.shape.TextAttributes

internal class KmlToRenderableConverter {

    var defaultIconColor = DEFAULT_ICON_COLOR
    var defaultLineColor = DEFAULT_LINE_COLOR
    var defaultFillColor = DEFAULT_FILL_COLOR

    companion object {
        private val spaceCharsRegex by lazy { "[\\s\\n\\t\\r\\u00A0\\u200B\\u202F\\u2009]".toRegex() }

        /**
         * optimized method to get coordinates from a string
         */
        private fun extractPoints(input: String?): List<Position> {
            // Normalize input by trimming leading/trailing whitespaces
            // and replacing all forms of space with a single space
            val normalizedInput = input?.trim()?.replace(spaceCharsRegex, " ")

            if (normalizedInput.isNullOrBlank()) return emptyList()

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

                result.add(Position.fromDegrees(lat, lon, alt ?: 0.0))

                // Skip the space between coordinate sets
                while (i < length && normalizedInput[i] == ' ') i++
            }

            return result
        }
    }

    private var density = DEFAULT_DENSITY
    private var resources: Map<String, ImageSource> = emptyMap()

    fun init(density: Float = DEFAULT_DENSITY, resources: Map<String, ImageSource> = emptyMap()) {
        this.density = density
        this.resources = resources
    }

    fun clear() {
        density = DEFAULT_DENSITY
        resources = emptyMap()
    }

    fun convertPlacemarkToRenderable(
        placemark: Placemark,
        definedStyle: Style? = null,
    ): List<Renderable> {
        val style = placemark.stylesList?.firstOrNull() ?: definedStyle
        val geometry: Geometry = placemark.geometryList?.firstOrNull() ?: return emptyList()
        return getRenderableFrom(geometry, style, placemark.name)
    }

    fun convertGroundOverlayToRenderable(groundOverlay: GroundOverlay): List<Renderable> {
        val surfaceImage = SurfaceImage(
            sector = groundOverlay.latLonBox?.toSector() ?: return emptyList(),
            imageSource = groundOverlay.icon?.toImageSource() ?: return emptyList(),
        )
        return listOf(surfaceImage)
    }

    private fun getRenderableFrom(
        geometry: Geometry,
        style: Style?,
        name: String?,
    ): List<Renderable> {
        return when (geometry) {
            is MultiGeometry -> {
                geometry.geometryList?.map { getRenderableFrom(it, style, name) }?.flatten()
            }

            is LineString -> {
                listOf(createPathFromLineString(geometry, style, name))
            }

            is LinearRing -> {
                listOf(createPathFromLinearRing(geometry, style, name))
            }

            is Polygon -> {
                listOf(createPolygonFromPolygon(geometry, style, name))
            }

            is Point -> {
                createPlacemark(geometry, style, name)?.let { listOf(it) }
            }

            else -> {
                null
            }
        } ?: emptyList()
    }

    private fun createPathFromLineString(
        lineString: LineString,
        style: Style?,
        name: String?
    ) = Path(extractPoints(lineString.coordinates?.value)).apply {
        altitudeMode = getAltitudeModeFrom(lineString.altitudeMode)
        lineString.extrude?.let { isExtrude = it }
        lineString.tessellate?.let { isFollowTerrain = it }
        pathType = getPathTypeBy(altitudeMode, isFollowTerrain)

        name?.let { displayName = it }

        highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }

        applyStyleOnShapeAttributes(style)
    }

    private fun createPathFromLinearRing(
        linearRing: LinearRing,
        style: Style?,
        name: String?
    ) = Path(extractPoints(linearRing.coordinates?.value)).apply {
        altitudeMode = getAltitudeModeFrom(linearRing.altitudeMode)
        linearRing.extrude?.let { isExtrude = it }
        linearRing.tessellate?.let { isFollowTerrain = it }
        pathType = getPathTypeBy(altitudeMode, isFollowTerrain)

        name?.let { displayName = it }

        highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }

        applyStyleOnShapeAttributes(style)
    }

    private fun createPolygonFromPolygon(
        polygon: Polygon,
        style: Style?,
        name: String?
    ): earth.worldwind.shape.Polygon {
        return earth.worldwind.shape.Polygon().apply {
            altitudeMode = getAltitudeModeFrom(polygon.altitudeMode)
            polygon.extrude?.let { isExtrude = it }
            isFollowTerrain = altitudeMode == AltitudeMode.CLAMP_TO_GROUND
            pathType = getPathTypeBy(altitudeMode, isFollowTerrain)

            polygon.outerBoundaryIs?.let {
                it.value?.forEach { linearRing ->
                    linearRing.coordinates?.value?.let { value ->
                        addBoundary(extractPoints(value))
                    }
                }
            }

            polygon.innerBoundaryIs?.let {
                it.value?.forEach { linearRing ->
                    linearRing.coordinates?.value?.let { value ->
                        addBoundary(extractPoints(value))
                    }
                }
            }

            name?.let { displayName = it }

            highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }

            applyStyleOnShapeAttributes(style)
        }
    }

    private fun getPathTypeBy(altitudeMode: AltitudeMode, isFollowTerrain: Boolean?): PathType {
        // If the path is clamped to the ground and terrain conforming, draw as a great circle.
        // Otherwise draw as linear segments.
        return if (altitudeMode == AltitudeMode.CLAMP_TO_GROUND && isFollowTerrain == true) {
            PathType.GREAT_CIRCLE
        } else {
            PathType.LINEAR
        }
    }

    private fun createPlacemark(point: Point, style: Style?, name: String?): Renderable? {
        val position = extractPoints(point.coordinates?.value).firstOrNull() ?: return null

        val iconStyle = style?.stylesList?.filterIsInstance<IconStyle>()?.firstOrNull()
        val labelStyle = style?.stylesList?.filterIsInstance<LabelStyle>()?.firstOrNull()

        return if (iconStyle?.scale == 0.0) {
            Label(position, name).apply {
                displayName = name // Display name is used to search renderable in layer
                altitudeMode = getAltitudeModeFrom(point.altitudeMode)
                attributes.applyStyle(labelStyle)
            }
        } else {
            earth.worldwind.shape.Placemark(position, label = name).apply {
                displayName = name // Display name is used to search renderable in layer
                altitudeMode = getAltitudeModeFrom(point.altitudeMode)
                attributes.apply {
                    imageScale = (iconStyle?.scale ?: DEFAULT_IMAGE_SCALE) * density
                    imageSource = iconStyle?.icon?.toImageSource() ?: ImageSource.fromResource(MR.images.kml_placemark)
                    imageColor = iconStyle?.color?.let { fromHexABRG(it) } ?: defaultIconColor
                    imageOffset = if (altitudeMode == AltitudeMode.CLAMP_TO_GROUND) Offset.bottomCenter() else Offset.center()

                    attributes.isDrawLeader = point.extrude == true

                    labelAttributes.applyStyle(labelStyle)
                    // if icon is present move label, so it doesn't overlap the icon
                    if (imageSource != null) labelAttributes.textOffset = Offset(PIXELS, -32.0, FRACTION, 0.4)
                }
            }
        }
    }

    private fun Icon.toImageSource(): ImageSource? {
        return href
            ?.let(::forceHttps)
            ?.let {
                try {
                    resources[it.substringAfterLast('/')]
                        ?: if (isValidHttpsUrl(it)) ImageSource.fromUrlString(it) else null
                } catch (_: Exception) {
                    // Ignore malformed URL
                    null
                }
            }
    }

    private fun LatLonBox.toSector(): Sector? {
        val north = north ?: return null
        val south = south ?: return null
        val east = east ?: return null
        val west = west ?: return null

        return Sector(
            Angle.fromDegrees(south),
            Angle.fromDegrees(north),
            Angle.fromDegrees(west),
            Angle.fromDegrees(east)
        )
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

            outlineColor = lineStyle?.color?.let { fromHexABRG(it) } ?: defaultLineColor
            interiorColor = polyStyle?.color?.let { fromHexABRG(it) } ?: defaultFillColor
            lineStyle?.width?.let { outlineWidth = it }

            isPickInterior = false // Allow picking outline only

            // Disable depths write for translucent shapes to avoid conflict with always on top Placemarks
            if (interiorColor.alpha < 1.0f) isDepthWrite = false

            isDrawVerticals = true
        }
    }

    private fun getAltitudeModeFrom(value: String?) = when (value) {
        "absolute" -> AltitudeMode.ABOVE_SEA_LEVEL
        "clampToGround" -> AltitudeMode.CLAMP_TO_GROUND
        "relativeToGround" -> AltitudeMode.RELATIVE_TO_GROUND
        else -> AltitudeMode.CLAMP_TO_GROUND
    }
}