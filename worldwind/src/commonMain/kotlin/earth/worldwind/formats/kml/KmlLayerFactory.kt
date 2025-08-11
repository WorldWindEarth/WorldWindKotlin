package earth.worldwind.formats.kml

import earth.worldwind.formats.kml.models.Style
import earth.worldwind.formats.kml.models.StyleMap
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.StringReader

@OptIn(XmlUtilInternal::class)
object KmlLayerFactory {
    private const val KML_LAYER_NAME = "KML Layer"
    private val kml = KML()
    private val converter = KmlToRenderableConverter()

    suspend fun createLayer(
        text: String,
        displayName: String? = KML_LAYER_NAME,
        labelVisibilityThreshold: Double = 0.0
    ) = createLayer(StringReader(text), displayName, labelVisibilityThreshold)

    suspend fun createLayer(
        reader: Reader,
        displayName: String? = KML_LAYER_NAME,
        labelVisibilityThreshold: Double = 0.0
    ) = RenderableLayer(displayName).apply {
        isPickEnabled = false // Layer is not pickable by default
        decodeFromReader(reader).map { (_, renderables) ->
            if (labelVisibilityThreshold != 0.0) renderables.filterIsInstance<Label>()
                .forEach { label ->
                    label.visibilityThreshold = labelVisibilityThreshold
                }
            addAllRenderables(renderables)
        }
    }

    suspend fun createLayers(
        text: String,
        labelVisibilityThreshold: Double = 0.0
    ) = createLayers(StringReader(text), labelVisibilityThreshold)

    suspend fun createLayers(
        reader: Reader,
        labelVisibilityThreshold: Double = 0.0
    ) = decodeFromReader(reader).map { (name, renderables) ->
        if (labelVisibilityThreshold != 0.0) renderables.filterIsInstance<Label>()
            .forEach { label ->
                label.visibilityThreshold = labelVisibilityThreshold
            }
        RenderableLayer(name).apply {
            isPickEnabled = false // Layer is not pickable by default
            addAllRenderables(renderables)
        }
    }

    private suspend fun decodeFromReader(
        reader: Reader
    ): Map<String, List<Renderable>> = withContext(Dispatchers.Default) {
        val styles = mutableMapOf<String, Style>()
        val styleMaps: MutableMap<String, StyleMap> = mutableMapOf()
        val eventsAwaitingStyle = mutableMapOf<String, MutableList<KML.KmlEvent.KmlPlacemark>>()

        // parentId to renderables
        val renderables = mutableMapOf<String, MutableList<Renderable>>()
        // parentId to name
        val names = mutableMapOf<String, String>()

        kml.decodeFromReader(reader).collect { event ->
            when (event) {
                is KML.KmlEvent.KmlDocument -> {
                    names[event.id] = event.name
                    renderables[event.id] = mutableListOf()
                }

                is KML.KmlEvent.KmlFolder -> {
                    names[event.id] = event.name
                    renderables[event.id] = mutableListOf()
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
            .mapValues { entry -> entry.value }
            .filter { it.value.isNotEmpty() } // Filter out empty renderable lists
            .mapKeys { names[it.key] ?: it.key }
    }
}