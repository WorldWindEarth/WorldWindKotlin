@file:Suppress("ControlFlowWithEmptyBody")

package earth.worldwind.formats.kml

import earth.worldwind.formats.kml.models.*
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import nl.adaptivity.xmlutil.*
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.random.Random

@OptIn(XmlUtilInternal::class)
internal class KML {

    private val module = SerializersModule {
        polymorphic(Feature::class) {
            subclass(Document::class)
            subclass(Folder::class)
            subclass(Placemark::class)
            subclass(GroundOverlay::class)
        }
        polymorphic(Geometry::class) {
            subclass(LinearRing::class)
            subclass(LineString::class)
            subclass(MultiGeometry::class)
            subclass(Point::class)
            subclass(Polygon::class)
        }
        polymorphic(ColorStyle::class) {
            subclass(IconStyle::class)
            subclass(LabelStyle::class)
            subclass(LineStyle::class)
            subclass(PolyStyle::class)
        }
        polymorphic(AbstractView::class) {
            subclass(Camera::class)
            subclass(LookAt::class)
        }
        polymorphic(TimePrimitive::class) {
            subclass(TimeSpan::class)
            subclass(TimeStamp::class)
        }
        polymorphic(StyleSelector::class) {
            subclass(Style::class)
            subclass(StyleMap::class)
        }
    }

    private val xml = XML(module) {
        defaultPolicy {
            pedantic = true
            autoPolymorphic = false
            ignoreUnknownChildren()
        }
        xmlVersion = XmlVersion.XML10
        xmlDeclMode = XmlDeclMode.Charset
        indentString = "    "
    }

    sealed interface KmlEvent {
        sealed class Group : KmlEvent {
            abstract val parentId: String?
            abstract val id: String?
            abstract val name: String?
            abstract val lookAt: LookAt?
        }

        data class KmlDocument(
            override val parentId: String?,
            override val id: String,
            override val name: String,
            override val lookAt: LookAt?
        ) : Group()

        data class KmlFolder(
            override val parentId: String?,
            override val id: String,
            override val name: String,
            override val lookAt: LookAt?
        ) : Group()

        data class KmlStyle(
            val parentId: String,
            val style: Style,
        ) : KmlEvent

        data class KmlStyleMap(
            val parentId: String,
            val styleMap: StyleMap,
        ) : KmlEvent

        data class KmlPlacemark(
            val parentId: String,
            val placemark: Placemark,
        ) : KmlEvent

        data class KmlGroundOverlay(
            val parentId: String,
            val groundOverlay: GroundOverlay,
        ) : KmlEvent
    }

    fun decodeFromReader(reader: Reader) =
        channelFlow { decodeWith(xmlStreaming.newGenericReader(reader)) }

    private suspend fun ProducerScope<KmlEvent>.decodeWith(
        reader: XmlReader,
        parentId: String = "Root",
    ) {
        if (reader.hasNext()) reader.next() else return

        do {
            when (reader.eventType) {
                EventType.START_ELEMENT -> when (reader.localName) {
                    KML_TAG -> decodeWith(reader, parentId)
                    DOCUMENT_TAG -> decodeFeature(reader, parentId, isDocument = true)
                    FOLDER_TAG -> decodeFeature(reader, parentId, isDocument = false)
                    PLACEMARK_TAG -> decodePlacemark(reader, parentId)
                    GROUND_OVERLAY_TAG -> decodeGroundOverlay(reader, parentId)
                    STYLE_MAP_TAG -> decodeStyleMap(reader, parentId)
                    CASCADING_STYLE_TAG -> decodeCascadingStyle(reader, parentId)
                    STYLE_TAG -> decodeStyle(reader, parentId)
                    else -> while (reader.next() != EventType.END_ELEMENT) { } // Skip the content of the element
                }

                EventType.END_ELEMENT -> return // end of the element
                else -> Unit // Ignore other events

            }
        } while (reader.hasNext() && reader.next() != EventType.END_DOCUMENT)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeStyleMap(
        reader: XmlReader,
        parentId: String
    ) {
        val styleMap = xml.decodeFromReader<StyleMap>(reader)
        val event = KmlEvent.KmlStyleMap(parentId, styleMap)
        send(event)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeCascadingStyle(
        reader: XmlReader,
        parentId: String
    ) {
        val id = reader.attributes.find { it.localName == ID_ATTRIBUTE }?.value?.ifEmpty { null }
            ?: return

        do {
            if (reader.eventType == EventType.START_ELEMENT && reader.localName == STYLE_TAG) {
                val style = xml.decodeFromReader<Style>(reader)
                val event = KmlEvent.KmlStyle(parentId, style.copy(id = id))
                send(event)
            }
        } while (reader.hasNext() && reader.next() != EventType.END_ELEMENT)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeStyle(reader: XmlReader, parentId: String) {
        val style = xml.decodeFromReader<Style>(reader)
        val event = KmlEvent.KmlStyle(parentId, style)
        send(event)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeFeature(
        reader: XmlReader,
        parentId: String,
        isDocument: Boolean
    ) {
        var name: String? = null
        var lookAt: LookAt? = null
        var isEventSend = false
        val id = reader.attributes
            .find { it.localName == ID_ATTRIBUTE }?.value
            ?.ifEmpty { null } ?: Random.nextLong().toString()

        suspend fun trySendFeatureEvent() {
            if (isEventSend) return // Avoid sending the event multiple times

            val prefix = if (isDocument) "Document" else "Folder"
            val finalName = name ?: "$prefix $id"
            val event = if (isDocument) KmlEvent.KmlDocument(parentId, id, finalName, lookAt)
            else KmlEvent.KmlFolder(parentId, id, finalName, lookAt)
            send(event)
            isEventSend = true
        }

        // Skip the start element
        if (reader.hasNext()) reader.next() else return

        do {
            when (reader.eventType) {
                EventType.START_ELEMENT -> when (reader.localName) {
                    NAME_TAG -> reader.readName()?.let {
                        @Suppress("AssignedValueIsNeverRead")
                        name = it
                    }

                    DOCUMENT_TAG -> {
                        trySendFeatureEvent()
                        decodeFeature(reader, parentId = id, isDocument = true)
                    }

                    FOLDER_TAG -> {
                        trySendFeatureEvent()
                        decodeFeature(reader, parentId = id, isDocument = false)
                    }

                    PLACEMARK_TAG -> {
                        trySendFeatureEvent()
                        decodePlacemark(reader, parentId = id)
                    }

                    GROUND_OVERLAY_TAG -> {
                        trySendFeatureEvent()
                        decodeGroundOverlay(reader, parentId = id)
                    }

                    STYLE_MAP_TAG -> decodeStyleMap(reader, parentId)
                    CASCADING_STYLE_TAG -> decodeCascadingStyle(reader, parentId)
                    STYLE_TAG -> decodeStyle(reader, parentId)
                    LOOK_AT_TAG -> {
                        lookAt = xml.decodeFromReader(reader)
                    }

                    else -> reader.readIdleTag()
                }

                EventType.END_ELEMENT -> {
                    // group is empty, send the event
                    trySendFeatureEvent()
                    return
                }

                else -> Unit // Ignore other events
            }
        } while (reader.hasNext() && reader.next() != EventType.END_DOCUMENT)
    }

    private suspend fun ProducerScope<KmlEvent>.decodePlacemark(
        reader: XmlReader,
        parentId: String,
    ) {
        val placemark = xml.decodeFromReader<Placemark>(reader)
        val event = KmlEvent.KmlPlacemark(parentId, placemark)
        send(event)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeGroundOverlay(
        reader: XmlReader,
        parentId: String,
    ) {
        val groundOverlay = xml.decodeFromReader<GroundOverlay>(reader)
        val event = KmlEvent.KmlGroundOverlay(parentId, groundOverlay)
        send(event)
    }

    /**
     * Read name from the reader, it could contain multiple text elements
     * @return name or null if name is empty
     */
    private fun XmlReader.readName() = buildString {
        while (next() != EventType.END_ELEMENT) {
            when (eventType) {
                // This event occurs when the parser encounters an entity reference (like &amp; for &).
                // This is used for things like special characters in the document.
                EventType.ENTITY_REF -> text

                // This event occurs when the parser encounters text in an element.
                EventType.TEXT -> text

                else -> null // Skip the content of the element
            }?.ifEmpty { null }?.let { append(it) }
        }
    }.ifEmpty { null }

    private fun XmlReader.readIdleTag() {
        while (next() != EventType.END_ELEMENT) if (eventType == EventType.START_ELEMENT) readIdleTag()
    }

    companion object {
        private const val KML_TAG = "kml"
        private const val DOCUMENT_TAG = "Document"
        private const val FOLDER_TAG = "Folder"
        private const val PLACEMARK_TAG = "Placemark"
        private const val STYLE_TAG = "Style"
        private const val STYLE_MAP_TAG = "StyleMap"
        private const val CASCADING_STYLE_TAG = ""
        private const val NAME_TAG = "name"
        private const val LOOK_AT_TAG = "LookAt"
        private const val GROUND_OVERLAY_TAG = "GroundOverlay"
        private const val ID_ATTRIBUTE = "id"
    }
}