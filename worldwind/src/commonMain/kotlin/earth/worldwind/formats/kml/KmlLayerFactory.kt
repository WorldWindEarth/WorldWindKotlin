package earth.worldwind.formats.kml

import earth.worldwind.formats.DEFAULT_DENSITY
import earth.worldwind.formats.DEFAULT_LABEL_VISIBILITY_THRESHOLD
import earth.worldwind.formats.computeSector
import earth.worldwind.formats.kml.models.LookAt
import earth.worldwind.formats.kml.models.Style
import earth.worldwind.formats.kml.models.StyleMap
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Position
import earth.worldwind.geom.Sector
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.AbstractSurfaceRenderable
import earth.worldwind.render.Renderable
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.Ellipse
import earth.worldwind.shape.Label
import earth.worldwind.shape.Path
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.Polygon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.StringReader
import kotlin.math.cos

@OptIn(XmlUtilInternal::class)
object KmlLayerFactory {
    private const val KML_LAYER_NAME = "KML Layer"
    private val kml = KML()
    private val converter = KmlToRenderableConverter()

    const val KML_LAYER_ID_KEY = "KMLLayerId"
    const val KML_LAYER_SECTOR_KEY = "KMLLayerSector"
    const val KML_LAYER_LOOK_AT_KEY = "KMLLayerLookAt"

    private data class KmlLayerData(
        val id: String,
        val displayName: String?,
        val lookAt: earth.worldwind.geom.LookAt?,
        val renderables: List<Renderable>,
    )

    suspend fun createLayer(
        text: String,
        displayName: String? = KML_LAYER_NAME,
        labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
        density: Float = DEFAULT_DENSITY,
        resources: Map<String, ImageSource> = emptyMap(), // key is the resource href, value is the reader to read it from
    ) = createLayer(StringReader(text), displayName, labelVisibilityThreshold, density, resources)

    suspend fun createLayer(
        reader: Reader,
        displayName: String? = KML_LAYER_NAME,
        labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
        density: Float = DEFAULT_DENSITY,
        resources: Map<String, ImageSource> = emptyMap(), // key is the resource href, value is the reader to read it from
    ): RenderableLayer {
        converter.init(density, resources)

        return RenderableLayer(displayName).apply {
            decodeFromReader(reader).map { data ->
                setLayerData(labelVisibilityThreshold, data)
            }
        }.also {
            converter.clear()
        }
    }

    suspend fun createLayers(
        text: String,
        labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
        density: Float = DEFAULT_DENSITY,
        resources: Map<String, ImageSource> = emptyMap(), // key is the resource href, value is the reader to read it from
    ) = createLayers(StringReader(text), labelVisibilityThreshold, density, resources)

    suspend fun createLayers(
        reader: Reader,
        labelVisibilityThreshold: Double = DEFAULT_LABEL_VISIBILITY_THRESHOLD,
        density: Float = DEFAULT_DENSITY,
        resources: Map<String, ImageSource> = emptyMap(), // key is the resource href, value is the reader to read it from
    ): List<RenderableLayer> {
        converter.init(density, resources)

        return decodeFromReader(reader).map { data ->
            RenderableLayer(data.displayName).apply {
                setLayerData(labelVisibilityThreshold, data)
            }
        }.also {
            converter.clear()
        }
    }

    private fun RenderableLayer.setLayerData(
        labelVisibilityThreshold: Double,
        data: KmlLayerData
    ) {
        isPickEnabled = false // Layer is not pickable by default
        if (labelVisibilityThreshold != 0.0) data.renderables.filterIsInstance<Label>()
            .forEach { label ->
                label.visibilityThreshold = labelVisibilityThreshold
            }

        addAllRenderables(data.renderables)

        putUserProperty(KML_LAYER_ID_KEY, data.id)
        computeSector(data.renderables)?.let {
            putUserProperty(KML_LAYER_SECTOR_KEY, it)
        }
        data.lookAt?.let {
            putUserProperty(KML_LAYER_LOOK_AT_KEY, data.lookAt)
        }
    }

    private suspend fun decodeFromReader(
        reader: Reader
    ): List<KmlLayerData> = withContext(Dispatchers.Default) {
        val styles = mutableMapOf<String, Style>()
        val styleMaps: MutableMap<String, StyleMap> = mutableMapOf()
        val eventsAwaitingStyle = mutableMapOf<String, MutableList<KML.KmlEvent.KmlPlacemark>>()

        // parentId to renderables
        val renderables = mutableMapOf<String, MutableList<Renderable>>()
        // parentId to name
        val names = mutableMapOf<String, String>()
        val lookAts = mutableMapOf<String, earth.worldwind.geom.LookAt?>()

        kml.decodeFromReader(reader).collect { event ->
            when (event) {
                is KML.KmlEvent.KmlDocument -> {
                    names[event.id] = event.name
                    renderables[event.id] = mutableListOf()
                    lookAts[event.id] = event.lookAt?.toGeomLookAt()
                }

                is KML.KmlEvent.KmlFolder -> {
                    names[event.id] = event.name
                    renderables[event.id] = mutableListOf()
                    lookAts[event.id] = event.lookAt?.toGeomLookAt()
                }

                is KML.KmlEvent.KmlStyleMap -> {
                    val styleMap = event.styleMap
                    val styleMapId = styleMap.id

                    if (styleMapId != null) {
                        styleMaps[styleMapId] = styleMap

                        val styleId = styleMap.pairs?.firstOrNull()?.styleUrl?.removePrefix("#")
                        val style = styles[styleId]?.also { styles[styleMapId] = it }

                        if (style != null) {
                            eventsAwaitingStyle.remove(style.id)?.forEach {
                                converter.convertPlacemarkToRenderable(
                                    placemark = it.placemark,
                                    definedStyle = style,
                                ).forEach { renderable ->
                                    renderables[it.parentId]?.add(renderable)
                                }
                            }
                        }
                    }
                }

                is KML.KmlEvent.KmlStyle -> {
                    val style = event.style
                    val styleId = style.id

                    val finalId = styleMaps.entries.find { (_, value) ->
                        value.pairs?.any { it.styleUrl?.removePrefix("#") == styleId } == true
                    }?.key ?: styleId

                    if (finalId != null) {
                        styles[finalId] = style
                        eventsAwaitingStyle.remove(style.id)?.forEach {
                            converter.convertPlacemarkToRenderable(
                                placemark = it.placemark,
                                definedStyle = style,
                            ).forEach { renderable ->
                                renderables[it.parentId]?.add(renderable)
                            }
                        }
                    }
                }

                is KML.KmlEvent.KmlPlacemark -> {
                    // Check if the placemark has a styleUrl
                    val styleId = event.placemark.styleUrl?.removePrefix("#")
                    val style = styleId?.let { styles[it] }

                    if (styleId != null && style == null) {
                        eventsAwaitingStyle.getOrPut(styleId, ::mutableListOf).add(event)
                    } else {
                        converter.convertPlacemarkToRenderable(
                            placemark = event.placemark,
                            definedStyle = style,
                        ).forEach { renderable -> renderables[event.parentId]?.add(renderable) }
                    }
                }

                is KML.KmlEvent.KmlGroundOverlay -> {
                    converter.convertGroundOverlayToRenderable(event.groundOverlay)
                        .let { renderable -> renderables[event.parentId]?.addAll(renderable) }
                }
            }
        }

        // at this moment, the whole document is parsed, and all styles are loaded into the styles map
        // so we check if there are any missed styles and convert placemarks with them
        eventsAwaitingStyle.forEach { (styleId, events) ->
            val style = styles[styleId] ?: Style(styleId)
            events.forEach { event ->
                converter.convertPlacemarkToRenderable(
                    placemark = event.placemark,
                    definedStyle = style,
                ).forEach { renderable -> renderables[event.parentId]?.add(renderable) }
            }
        }

        renderables
            .filter { it.value.isNotEmpty() } // Filter out empty renderable lists
            .map { entry ->
                KmlLayerData(
                    id = entry.key,
                    displayName = names[entry.key],
                    lookAt = lookAts[entry.key],
                    renderables = entry.value,
                )
            }
    }

    private fun LookAt.toGeomLookAt(): earth.worldwind.geom.LookAt? {
        val latitude = latitude ?: return null
        val longitude = longitude ?: return null
        val range = range ?: return null

        return earth.worldwind.geom.LookAt(
            position = Position.fromDegrees(
                latitudeDegrees = latitude,
                longitudeDegrees = longitude,
                altitude = altitude ?: 0.0
            ),
            altitudeMode = AltitudeMode.ABSOLUTE,
            range = range,
            heading = Angle.fromDegrees(heading ?: 0.0),
            tilt = Angle.fromDegrees(tilt ?: 0.0),
            roll = ZERO
        )
    }
}