package earth.worldwind.formats.kml

import earth.worldwind.formats.kml.models.AbstractStyle
import earth.worldwind.formats.kml.models.Document
import earth.worldwind.formats.kml.models.Feature
import earth.worldwind.formats.kml.models.Folder
import earth.worldwind.formats.kml.models.Geometry
import earth.worldwind.formats.kml.models.IconStyle
import earth.worldwind.formats.kml.models.LabelStyle
import earth.worldwind.formats.kml.models.LineString
import earth.worldwind.formats.kml.models.LineStyle
import earth.worldwind.formats.kml.models.LinearRing
import earth.worldwind.formats.kml.models.MultiGeometry
import earth.worldwind.formats.kml.models.Placemark
import earth.worldwind.formats.kml.models.Point
import earth.worldwind.formats.kml.models.PolyStyle
import earth.worldwind.formats.kml.models.Polygon
import earth.worldwind.formats.kml.models.Style
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.channelFlow
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlDeclMode
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.attributes
import nl.adaptivity.xmlutil.core.XmlVersion
import nl.adaptivity.xmlutil.core.impl.multiplatform.Reader
import nl.adaptivity.xmlutil.serialization.XML
import nl.adaptivity.xmlutil.xmlStreaming

@OptIn(XmlUtilInternal::class)
internal class KML {

    private val module = SerializersModule {
        polymorphic(Feature::class) {
            subclass(Document::class)
            subclass(Folder::class)
            subclass(Placemark::class)
        }
        polymorphic(Geometry::class) {
            subclass(LinearRing::class)
            subclass(LineString::class)
            subclass(MultiGeometry::class)
            subclass(Point::class)
            subclass(Polygon::class)
        }
        polymorphic(AbstractStyle::class) {
            subclass(IconStyle::class)
            subclass(LabelStyle::class)
            subclass(LineStyle::class)
            subclass(PolyStyle::class)
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
        }

        data class KmlDocument(
            override val parentId: String?,
            override val id: String? = null,
            override val name: String? = null,
        ) : Group()

        data class KmlFolder(
            override val parentId: String?,
            override val id: String? = null,
            override val name: String? = null,
        ) : Group()

        data class KmlStyle(
            val parentId: String?,
            val style: Style,
        ) : KmlEvent

        data class KmlPlacemark(
            val parentId: String?,
            val placemark: Placemark,
        ) : KmlEvent
    }

    fun decodeFromReader(reader: Reader) = channelFlow { decodeWith(xmlStreaming.newGenericReader(reader)) }

    private suspend fun ProducerScope<KmlEvent>.decodeWith(reader: XmlReader, parentId: String? = null) {
        if (reader.hasNext()) reader.next() else return

        do {
            when (reader.eventType) {
                EventType.START_ELEMENT -> when (reader.localName) {
                    KML_TAG -> decodeWith(reader, parentId)
                    DOCUMENT_TAG -> decodeFeature(reader, parentId, isDocument = true)
                    FOLDER_TAG -> decodeFeature(reader, parentId, isDocument = false)
                    PLACEMARK_TAG -> decodePlacemark(reader, parentId)
                    // TODO implement cascading style for reader, same as for text
                    CASCADING_STYLE_TAG -> reader.readIdleTag()
                    STYLE_TAG -> decodeStyle(reader, parentId)
                    else -> while (reader.next() != EventType.END_ELEMENT) { } // Skip the content of the element
                }

                EventType.END_ELEMENT -> return // end of the element
                else -> Unit // Ignore other events

            }
        } while (reader.hasNext() && reader.next() != EventType.END_DOCUMENT)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeStyle(reader: XmlReader, parentId: String?) {
        val style = xml.decodeFromReader<Style>(reader)
        val event = KmlEvent.KmlStyle(parentId, style)
        send(event)
    }

    private suspend fun ProducerScope<KmlEvent>.decodeFeature(
        reader: XmlReader,
        parentId: String?,
        isDocument: Boolean
    ) {
        var name: String? = null
        var isEventSend = false
        val id = reader.attributes.find { it.localName == ID_ATTRIBUTE }?.value?.ifEmpty { null }

        suspend fun trySendFeatureEvent() {
            if (isEventSend) return // Avoid sending the event multiple times

            val event = if (isDocument) KmlEvent.KmlDocument(parentId, id, name)
            else KmlEvent.KmlFolder(parentId, id, name)
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

                    FOLDER_TAG -> {
                        trySendFeatureEvent()
                        decodeFeature(reader, parentId = id, isDocument = false)
                    }

                    PLACEMARK_TAG -> {
                        trySendFeatureEvent()
                        decodePlacemark(reader, parentId = id)
                    }

                    // TODO implement cascading style for reader, same as for text
                    CASCADING_STYLE_TAG -> reader.readIdleTag()
                    STYLE_TAG -> decodeStyle(reader, parentId)
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

    private suspend fun ProducerScope<KmlEvent>.decodePlacemark(reader: XmlReader, parentId: String?) {
        val placemark = xml.decodeFromReader<Placemark>(reader)
        val event = KmlEvent.KmlPlacemark(parentId, placemark)
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
        private const val CASCADING_STYLE_TAG = ""
        private const val NAME_TAG = "name"
        private const val ID_ATTRIBUTE = "id"
    }
}