package earth.worldwind.formats.kml

import earth.worldwind.MR
import earth.worldwind.formats.*
import earth.worldwind.formats.kml.KmlLayerFactory.KML_DEFAULT_IMAGE_SOURCE_KEY
import earth.worldwind.formats.kml.models.*
import earth.worldwind.formats.kml.models.AltitudeMode
import earth.worldwind.formats.kml.models.Placemark
import earth.worldwind.formats.kml.models.Polygon
import earth.worldwind.geom.*
import earth.worldwind.geom.AltitudeMode.*
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.OffsetMode.*
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.*

internal class KmlToRenderableConverter {

    var defaultIconColor = DEFAULT_ICON_COLOR
    var defaultLineColor = DEFAULT_LINE_COLOR
    var defaultFillColor = DEFAULT_FILL_COLOR

    companion object {
        private val spaceCharsRegex by lazy { "[\\s\\n\\t\\r\\u00A0\\u200B\\u202F\\u2009]".toRegex() }

        /**
         * optimized method to get coordinates from a string
         */
        private fun extractPoints(input: String, altitudeOffset: Double = 0.0): List<Position> {
            // Normalize input by trimming leading/trailing whitespaces
            // and replacing all forms of space with a single space
            val normalizedInput = input.trim().replace(spaceCharsRegex, " ")

            if (normalizedInput.isBlank()) return emptyList()

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
                    alt = parseDouble(start, i) + altitudeOffset
                }

                result.add(Position.fromDegrees(lat, lon, alt ?: 0.0))

                // Skip the space between coordinate sets
                while (i < length && normalizedInput[i] == ' ') i++
            }

            return result
        }
    }

    private var options = KmlLayerFactory.Options()

    fun init(options: KmlLayerFactory.Options) {
        this.options = options
    }

    fun clear() {
        options = KmlLayerFactory.Options()
    }

    fun convertPlacemarkToRenderable(
        placemark: Placemark,
        definedStyle: Style? = null
    ): List<Renderable> {
        val style =
            placemark.styleSelector.firstOrNull() as? Style ?: definedStyle // TODO Add support of StyleMap
        val geometry: Geometry = placemark.geometries.firstOrNull() ?: return emptyList()
        return getRenderableFrom(geometry, style, placemark.name)
    }

    fun convertGroundOverlayToRenderable(groundOverlay: GroundOverlay): List<Renderable> {
        val surfaceImage = SurfaceImage(
            sector = groundOverlay.latLonBox?.toSector() ?: return emptyList(),
            imageSource = groundOverlay.icon?.toImageSource() ?: return emptyList(),
        ).apply {
            zOrder = groundOverlay.drawOrder.toDouble()
        }
        return listOf(surfaceImage)
    }

    private fun getRenderableFrom(
        geometry: Geometry,
        style: Style?,
        name: String?
    ): List<Renderable> = when (geometry) {
        is MultiGeometry -> {
            geometry.geometries.map { getRenderableFrom(it, style, name) }.flatten()
        }

        is LineString -> buildList {
            createPathFromLineString(geometry, style, name).let { path ->
                add(path)
                if (options.renderLabelsForShapes) add(createLabelFor(path, name))
            }
        }

        is LinearRing -> buildList {
            createPathFromLinearRing(geometry, style, name).let { path ->
                add(path)
                if (options.renderLabelsForShapes) add(createLabelFor(path, name))
            }
        }

        is Polygon -> buildList {
            createPolygonFromPolygon(geometry, style, name).let { path ->
                add(path)
                if (options.renderLabelsForShapes) add(createLabelFor(path, name))
            }
        }

        is Point -> {
            createPlacemark(geometry, style, name)?.let { listOf(it) }
        }

        else -> {
            null
        }
    } ?: emptyList()

    private fun createLabelFor(
        shape: AbstractShape,
        name: String?,
    ): Label {
        val position = shape.referencePosition.let { position ->
            Position( position.latitude, position.longitude, 0.0)
        }
        return Label(position, name).apply {
            altitudeMode = CLAMP_TO_GROUND
            attributes.apply {
                textColor = shape.attributes.outlineColor
                outlineColor = textColor.toContrastColor()
                textOffset = Offset.center()
            }
        }
    }

    private fun createPathFromLineString(
        lineString: LineString, style: Style?, name: String?
    ) = Path(extractPoints(lineString.coordinates.value, lineString.altitudeOffset)).apply {
        altitudeMode = getAltitudeModeFrom(lineString.altitudeMode)
        isExtrude = lineString.extrude
        isFollowTerrain = lineString.tessellate
        pathType = getPathTypeBy(altitudeMode, isFollowTerrain)
        maximumIntermediatePoints = 0 // Disable intermediate point for performance reasons
        zOrder = lineString.drawOrder.toDouble()

        name?.let { displayName = it }

        highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }

        applyStyleOnShapeAttributes(style)
    }

    private fun createPathFromLinearRing(
        linearRing: LinearRing, style: Style?, name: String?
    ) = Path(extractPoints(linearRing.coordinates.value, linearRing.altitudeOffset)).apply {
        altitudeMode = getAltitudeModeFrom(linearRing.altitudeMode)
        isExtrude = linearRing.extrude
        isFollowTerrain = linearRing.tessellate
        pathType = getPathTypeBy(altitudeMode, isFollowTerrain)
        maximumIntermediatePoints = 0 // Disable intermediate point for performance reasons

        name?.let { displayName = it }

        highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }

        applyStyleOnShapeAttributes(style)
    }

    private fun createPolygonFromPolygon(
        polygon: Polygon, style: Style?, name: String?
    ) = earth.worldwind.shape.Polygon().apply {
        altitudeMode = getAltitudeModeFrom(polygon.altitudeMode)
        isExtrude = polygon.extrude
        // Clamp to ground polygon is always on texture, even if tessellate is not true
        isFollowTerrain = altitudeMode == CLAMP_TO_GROUND
        pathType = getPathTypeBy(altitudeMode, isFollowTerrain)
        maximumIntermediatePoints = 0 // Disable intermediate point for performance reasons

        polygon.outerBoundaryIs?.let {
            it.value.let { linearRing ->
                addBoundary(extractPoints(linearRing.coordinates.value, linearRing.altitudeOffset))
            }
        }

        polygon.innerBoundaryIs?.let {
            it.value.forEach { linearRing ->
                addBoundary(extractPoints(linearRing.coordinates.value, linearRing.altitudeOffset))
            }
        }

        name?.let { displayName = it }

        highlightAttributes = ShapeAttributes(attributes).apply { outlineWidth += HIGHLIGHT_INCREMENT }

        applyStyleOnShapeAttributes(style)
    }

    private fun getPathTypeBy(altitudeMode: earth.worldwind.geom.AltitudeMode, isFollowTerrain: Boolean): PathType {
        // If the path is clamped to the ground and terrain conforming, draw as a great circle.
        // Otherwise draw as linear segments.
        return if (altitudeMode == CLAMP_TO_GROUND && isFollowTerrain) {
            PathType.GREAT_CIRCLE
        } else {
            PathType.LINEAR
        }
    }

    private fun createPlacemark(point: Point, style: Style?, name: String?): Renderable? {
        val position = extractPoints(point.coordinates.value).firstOrNull() ?: return null

        val iconStyle = style?.styles?.filterIsInstance<IconStyle>()?.firstOrNull()
        val labelStyle = style?.styles?.filterIsInstance<LabelStyle>()?.firstOrNull()

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
                isBillboardingEnabled = true // Prevent icons from going underground
                attributes.apply {
                    imageScale = iconStyle?.scale ?: DEFAULT_IMAGE_SCALE
                    imageColor = iconStyle?.color?.let { fromHexABRG(it) } ?: defaultIconColor
                    imageOffset = iconStyle?.hotSpot?.let {
                        Offset(getOffsetModeFrom(it.xunits), it.x, getOffsetModeFrom(it.yunits), it.y)
                    } ?: Offset.center()
                    imageSource = iconStyle?.icon?.toImageSource()?.also {
                        // Apply density only to external KML icons
                        imageScale *= options.density
                    } ?: options.resources[KML_DEFAULT_IMAGE_SOURCE_KEY]
                            ?: ImageSource.fromResource(MR.images.kml_placemark)
                                .also {
                                    // Special offset for default push pin
                                    imageOffset.set(PIXELS, 10.0, PIXELS, 3.0)
                                }

                    isDrawLeader = point.extrude

                    labelAttributes.applyStyle(labelStyle)
                    // if icon is present move label, so it doesn't overlap the icon
                    if (imageSource != null) labelAttributes.textOffset = Offset(PIXELS, -34.0, FRACTION, 0.1)
                }
            }
        }
    }

    private fun Icon.toImageSource() = href.let(::forceHttps).let {
        try {
            options.resources[it.substringAfterLast('/')]
                ?: if (isValidHttpsUrl(it)) ImageSource.fromUrlString(it) else null
        } catch (_: Exception) {
            // Ignore malformed URL
            null
        }
    }

    private fun LatLonBox.toSector() = Sector(south.degrees, north.degrees, west.degrees, east.degrees)

    private fun TextAttributes.applyStyle(labelStyle: LabelStyle?) {
        apply {
            labelStyle?.color?.let {
                textColor = fromHexABRG(it)
                outlineColor = textColor.toContrastColor()
            }
            textOffset = Offset.center()
        }
    }

    private fun fromHexABRG(it: String) = Color.fromHexString(hexString = it, argb = true).apply {
        // replace red and blue channels, because KML uses ABGR format
        set(red = blue, green = green, blue = red, alpha = alpha)
    }

    private fun AbstractShape.applyStyleOnShapeAttributes(style: Style?) {
        attributes.apply {
            val lineStyle = style?.styles?.filterIsInstance<LineStyle>()?.firstOrNull()
            val polyStyle = style?.styles?.filterIsInstance<PolyStyle>()?.firstOrNull()

            outlineColor = lineStyle?.color?.let { fromHexABRG(it) } ?: defaultLineColor
            interiorColor = polyStyle?.color?.let { fromHexABRG(it) } ?: defaultFillColor
            outlineWidth = lineStyle?.width ?: options.density

            isPickInterior = false // Allow picking outline only

            // Disable depths write for translucent shapes to avoid conflict with always on top Placemarks
            if (interiorColor.alpha < 1.0f) isDepthWrite = false

            isDrawVerticals = true
        }
    }

    private fun getAltitudeModeFrom(value: AltitudeMode?) = when (value) {
        AltitudeMode.absolute -> ABOVE_SEA_LEVEL
        AltitudeMode.clampToGround, AltitudeMode.clampToSeaFloor -> CLAMP_TO_GROUND
        AltitudeMode.relativeToGround, AltitudeMode.relativeToSeaFloor -> RELATIVE_TO_GROUND
        else -> CLAMP_TO_GROUND
    }

    private fun getOffsetModeFrom(value: HotSpotUnits) = when (value) {
        HotSpotUnits.pixels -> PIXELS
        HotSpotUnits.fraction -> FRACTION
        HotSpotUnits.insetPixels -> INSET_PIXELS
    }
}