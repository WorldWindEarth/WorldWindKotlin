package earth.worldwind.formats.geojson

import earth.worldwind.formats.DEFAULT_DENSITY
import earth.worldwind.formats.DEFAULT_IMAGE_SCALE
import earth.worldwind.formats.DEFAULT_LABEL_VISIBILITY_THRESHOLD
import earth.worldwind.formats.DEFAULT_PLACEMARK_ICON_SIZE
import earth.worldwind.formats.computeSector
import earth.worldwind.formats.forceHttps
import earth.worldwind.formats.isValidHttpsUrl
import earth.worldwind.geom.OffsetMode
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Color
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.AbstractShape
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.TextAttributes
import io.data2viz.geojson.Feature
import io.data2viz.geojson.FeatureCollection
import io.data2viz.geojson.Geometry
import io.data2viz.geojson.GeometryCollection
import io.data2viz.geojson.LineString
import io.data2viz.geojson.MultiLineString
import io.data2viz.geojson.MultiPoint
import io.data2viz.geojson.MultiPolygon
import io.data2viz.geojson.Point
import io.data2viz.geojson.Polygon
import io.data2viz.geojson.Position
import io.data2viz.geojson.toGeoJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

object GeoJsonLayerFactory {

    private const val GEO_JSON_LAYER_NAME = "Geo Json Layer"
    const val GEO_JSON_LAYER_ID_KEY = "GeoJsonLayerId"
    const val GEO_JSON_LAYER_SECTOR_KEY = "GeoJsonLayerSector"

    suspend fun createLayer(
        text: String,
        displayName: String? = GEO_JSON_LAYER_NAME,
        density: Float = DEFAULT_DENSITY,
        labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
        customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit = {},
    ): RenderableLayer {

        val layer = RenderableLayer(displayName).apply {
            isPickEnabled = false // Layer is not pickable by default
        }

        val (renderables, id) = withContext(Dispatchers.Default) {
            val geoJsonObject = text.toGeoJsonObject()
            val (featureCollection, id) = when (geoJsonObject) {
                is FeatureCollection -> geoJsonObject to Random.nextLong().toString()
                else -> {
                    val (feature, id) = when (geoJsonObject) {
                        is Feature -> geoJsonObject to geoJsonObject.id
                        is Geometry -> Feature(geoJsonObject) to null
                        else -> null to null
                    }
                    val array = feature?.let { arrayOf(feature) } ?: emptyArray()
                    FeatureCollection(array) to (id ?: Random.nextLong()).toString()
                }
            }
            val features = featureCollection.features

            val geometriesWithProperties = features.associate { feature ->
                @Suppress("UNCHECKED_CAST")
                val properties =
                    feature.properties as? LinkedHashMap<String, Any?> ?: LinkedHashMap()
                Pair(feature.geometry, Properties(properties))
            }

            convertRenderablesFrom(
                geometriesWithProperties,
                customLogicToApplyProperties,
                density
            ) to id
        }

        renderables.forEach { renderable ->
            if (labelVisibilityThreshold != 0.0 && renderable is Label) {
                renderable.visibilityThreshold = labelVisibilityThreshold
            }
        }

        layer.addAllRenderables(renderables)

        return layer.apply {
            addAllRenderables(renderables)
            putUserProperty(GEO_JSON_LAYER_ID_KEY, id)
            computeSector(renderables)?.let {
                putUserProperty(GEO_JSON_LAYER_SECTOR_KEY, it)
            }
        }
    }

    private fun convertRenderablesFrom(
        geometriesWithProperties: Map<Geometry, Properties>,
        customLogicToApplyProperties: Renderable.(LinkedHashMap<String, Any?>) -> Unit,
        density: Float,
    ) = geometriesWithProperties
        .map { (geometry, properties) ->
            convertToRenderable(geometry, properties, density)
                .onEach { it.customLogicToApplyProperties(properties.properties) }
        }
        .flatten()

    private fun convertToRenderable(
        geometry: Geometry,
        properties: Properties,
        density: Float,
    ): List<Renderable> {
        return when (geometry) {

            is GeometryCollection -> {
                geometry.geometries
                    .map { convertToRenderable(it, properties, density) }
                    .flatten()
            }

            is Polygon -> {
                val positions = geometry.coordinates.map { ring ->
                    ring.mapNotNull { position ->
                        getPositionFrom(position)
                    }
                }.flatten()

                val renderable = earth.worldwind.shape.Polygon(positions).apply {
                    altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                    isFollowTerrain = true
                    applyStyleOnShapeAttributes(properties)
                }
                listOf(renderable)
            }

            is MultiPolygon -> {
                geometry.coordinates.mapNotNull { polygon ->
                    val positions = polygon.map { ring ->
                        ring.mapNotNull { position ->
                            getPositionFrom(position)
                        }
                    }.flatten()

                    if (positions.isNotEmpty()) {
                        earth.worldwind.shape.Polygon(positions).apply {
                            altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                            isFollowTerrain = true
                            applyStyleOnShapeAttributes(properties)
                        }
                    } else null
                }
            }

            is LineString -> {
                val positions = geometry.coordinates.mapNotNull { position ->
                    getPositionFrom(position)
                }

                val renderable = Path(positions).apply {
                    altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                    isFollowTerrain = true
                    applyStyleOnShapeAttributes(properties)
                }
                listOf(renderable)
            }

            is MultiPoint -> {
                val positions = geometry.coordinates.mapNotNull { position ->
                    getPositionFrom(position)
                }

                val renderable = Path(positions).apply {
                    altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                    isFollowTerrain = true
                    applyStyleOnShapeAttributes(properties)
                }
                listOf(renderable)
            }

            is MultiLineString -> {
                geometry.coordinates.mapNotNull { line ->
                    val positions = line.mapNotNull { position ->
                        getPositionFrom(position)
                    }
                    if (positions.isNotEmpty()) {
                        Path(positions).apply {
                            altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                            isFollowTerrain = true
                            applyStyleOnShapeAttributes(properties)
                        }
                    } else null
                }
            }

            is Point -> {
                getPositionFrom(geometry.coordinates)?.let { position ->
                    val renderable =
                        if (properties.icon != null || properties.name.isNullOrBlank()) {
                            Placemark(position, label = properties.name).apply {
                                // Display name is used to search renderable in layer
                                displayName = properties.name
                                altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                                attributes.apply {
                                    try {
                                        properties.icon
                                            ?.let(::forceHttps)
                                            ?.let {
                                                if (isValidHttpsUrl(it)) {
                                                    imageSource = ImageSource.fromUrlString(it)

                                                    properties.iconOffset?.let { (x, y) ->
                                                        attributes.imageOffset.set(
                                                            OffsetMode.PIXELS, x,
                                                            OffsetMode.INSET_PIXELS, y,
                                                        )
                                                        attributes.labelAttributes.textOffset.set(
                                                            OffsetMode.PIXELS, -x / 2.0,
                                                            OffsetMode.INSET_PIXELS, y / 2.0
                                                        )
                                                    }
                                                }
                                            }
                                    } catch (_: Exception) {
                                        // cant load image, ignore
                                    }

                                    imageScale = if (imageSource == null) {
                                        properties.iconScale ?: DEFAULT_PLACEMARK_ICON_SIZE
                                    } else {
                                        DEFAULT_IMAGE_SCALE
                                    } * density

                                    labelAttributes.applyStyle(properties)
                                }
                            }
                        } else {
                            Label(position, properties.name).apply {
                                altitudeMode = earth.worldwind.geom.AltitudeMode.CLAMP_TO_GROUND
                                attributes.applyStyle(properties)
                            }
                        }
                    listOf(renderable)
                }
            }

            else -> null
        } ?: emptyList()
    }

    private fun getPositionFrom(position: Position): earth.worldwind.geom.Position? {
        return earth.worldwind.geom.Position.fromDegrees(
            longitudeDegrees = position.getOrNull(0) ?: return null,
            latitudeDegrees = position.getOrNull(1) ?: return null,
            altitude = position.getOrNull(2) ?: 0.0,
        )
    }

    private fun AbstractShape.applyStyleOnShapeAttributes(properties: Properties) {
        attributes.apply {
            properties.strokeColor?.let { outlineColor = it }
            properties.fillColor?.let { interiorColor = it }
            properties.strokeWidth?.let { outlineWidth = it.toFloat() }
            maximumIntermediatePoints = 0 // Disable intermediate points for better performance

            isPickInterior = false // Allow picking outline only

            // Disable depths write for translucent shapes to avoid conflict with always on top Placemarks
            if (interiorColor.alpha < 1.0f) isDepthWrite = false
        }
    }

    private fun TextAttributes.applyStyle(properties: Properties) {
        apply {
            scale = properties.labelScale ?: 1.0
        }
    }

    data class Properties(val properties: LinkedHashMap<String, Any?> = LinkedHashMap()) {
        val name: String?
            get() = properties["name"] as? String
        private val strokeOpacity: Double?
            get() = properties["stroke-opacity"].let { it as? Double ?: it as? Int }?.toDouble()
        private val stroke: String?
            get() = properties["stroke"] as? String
        val strokeWidth: Double?
            get() = properties["stroke-width"].let { it as? Double ?: it as? Int }?.toDouble()
        private val fillOpacity: Double?
            get() = properties["fill-opacity"].let { it as? Double ?: it as? Int }?.toDouble()
        private val fill: String?
            get() = properties["fill"] as? String
        val icon: String?
            get() = properties["icon"] as? String
        val iconOffset: Pair<Double, Double>?
            get() = properties["icon-offset"]?.let { it as? List<*> }?.let { offsetList ->
                val x = (offsetList.getOrNull(0) as? Number)?.toDouble() ?: DEFAULT_ICON_OFFSET
                val y = (offsetList.getOrNull(1) as? Number)?.toDouble() ?: DEFAULT_ICON_OFFSET
                x to y
            }
        val iconScale: Double?
            get() = properties["icon-scale"].let { it as? Double ?: it as? Int }?.toDouble()
        val labelScale: Double?
            get() = properties["label-scale"].let { it as? Double ?: it as? Int }?.toDouble()

        val fillColor: Color?
            get() = getColorFromHex(fill, fillOpacity)
        val strokeColor: Color?
            get() = getColorFromHex(stroke, strokeOpacity)

        private fun getColorFromHex(hex: String?, opacity: Double?): Color? {
            return hex?.let {
                Color.fromHexString(hexString = it, argb = false).apply {
                    val alpha = when {
                        opacity == null -> 1.0f
                        opacity < 0.0 -> 0.0f
                        opacity > 1.0 -> 1.0f
                        else -> opacity.toFloat()
                    }
                    set(red = red, green = green, blue = blue, alpha = alpha)
                }
            }
        }

        companion object {
            private const val DEFAULT_ICON_OFFSET = 16.0
        }
    }
}