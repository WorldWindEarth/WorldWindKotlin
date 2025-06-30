package earth.worldwind.formats.kml

import earth.worldwind.formats.kml.models.Style
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.render.Renderable
import earth.worldwind.shape.Label
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.core.impl.multiplatform.StringReader

@OptIn(XmlUtilInternal::class)
object KmlLayerFactory {
    private const val KML_LAYER_NAME = "KML Layer"
    private const val CHUNKS_SIZE = 1000

    private val kml = KML()
    private val converter = KmlToRenderableConverter()

    fun createLayer(
        text: String, scope: CoroutineScope,
        displayName: String? = KML_LAYER_NAME,
        chunkSize: Int = CHUNKS_SIZE,
        labelVisibilityThreshold: Double = 0.0
    ) = createLayer(StringReader(text), scope, displayName, chunkSize, labelVisibilityThreshold)

    fun createLayer(
        reader: Reader, scope: CoroutineScope,
        displayName: String? = KML_LAYER_NAME,
        chunkSize: Int = CHUNKS_SIZE,
        labelVisibilityThreshold: Double = 0.0
    ) = RenderableLayer(displayName).apply {
        scope.launch {
            import(reader, chunkSize)
                .flowOn(Dispatchers.Default)
                .collect { list ->
                    if (labelVisibilityThreshold != 0.0) list.filterIsInstance<Label>().forEach { label ->
                        label.visibilityThreshold = labelVisibilityThreshold
                    }
                    addAllRenderables(list)
                }
        }
    }

    fun import(reader: Reader, chunkSize: Int): Flow<List<Renderable>> = channelFlow {
        require(chunkSize > 0) { "chunkSize should be greater than 0" }

        val buffer = ArrayList<Renderable>(chunkSize)

        suspend fun emitBuffer() {
            send(buffer.toList())
            buffer.clear()
        }

        decodeFromReader(reader).collect { model ->
            if (buffer.size >= chunkSize) emitBuffer()
            buffer.add(model)
        }

        // emit remaining models
        if (buffer.isNotEmpty()) emitBuffer()
    }

    private suspend fun decodeFromReader(reader: Reader): Flow<Renderable> {
        val styles = mutableMapOf<String, Style>()
        val eventsAwaitingStyle = mutableMapOf<String, MutableList<KML.KmlEvent.KmlPlacemark>>()

        return kml.decodeFromReader(reader).transform { event ->
            when (event) {
                is KML.KmlEvent.KmlDocument -> Unit  // ignore document events, as they are not renderable
                is KML.KmlEvent.KmlFolder -> Unit // ignore folder events, as they are not renderable

                is KML.KmlEvent.KmlStyle -> {
                    val style = event.style
                    val styleId = style.id
                    if (styleId != null) {
                        styles[styleId] = style
                        eventsAwaitingStyle.remove(style.id)?.forEach {
                            converter.convertPlacemarkToRenderable(
                                placemark = it.placemark,
                                definedStyle = style,
                            )?.let { renderable -> emit(renderable) }
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
                        )?.let { emit(it) }
                    }
                }
            }
        }
    }
}