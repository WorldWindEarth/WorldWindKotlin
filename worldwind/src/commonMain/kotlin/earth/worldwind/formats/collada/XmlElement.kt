package earth.worldwind.formats.collada

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlUtilInternal
import nl.adaptivity.xmlutil.core.impl.multiplatform.StringReader
import nl.adaptivity.xmlutil.xmlStreaming

internal class XmlElement(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<XmlElement> = mutableListOf(),
    var textContent: String = ""
) {
    fun getAttribute(name: String): String? = attributes[name]

    fun querySelector(tagName: String): XmlElement? {
        for (child in children) {
            if (child.name == tagName) return child
            val found = child.querySelector(tagName)
            if (found != null) return found
        }
        return null
    }

    fun querySelectorAll(tagName: String): List<XmlElement> {
        val result = mutableListOf<XmlElement>()
        for (child in children) {
            if (child.name == tagName) result.add(child)
            result.addAll(child.querySelectorAll(tagName))
        }
        return result
    }

    fun getElementsByTagName(tagName: String): List<XmlElement> {
        val result = mutableListOf<XmlElement>()
        for (child in children) {
            if (child.name == tagName) result.add(child)
            result.addAll(child.getElementsByTagName(tagName))
        }
        return result
    }

    companion object {
        @OptIn(XmlUtilInternal::class)
        fun parse(xmlString: String): XmlElement {
            val root = XmlElement("__root__", emptyMap())
            val stack = ArrayDeque<XmlElement>()
            stack.addLast(root)
            val reader = xmlStreaming.newGenericReader(StringReader(xmlString))
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        val name = reader.localName
                        val attrs = mutableMapOf<String, String>()
                        for (i in 0 until reader.attributeCount) {
                            attrs[reader.getAttributeLocalName(i)] = reader.getAttributeValue(i)
                        }
                        val element = XmlElement(name, attrs)
                        stack.last().children.add(element)
                        stack.addLast(element)
                    }
                    EventType.END_ELEMENT -> stack.removeLast()
                    EventType.TEXT, EventType.CDSECT -> {
                        val text = reader.text
                        if (text.isNotBlank()) stack.last().textContent += text
                    }
                    else -> Unit
                }
            }
            return root.children.firstOrNull() ?: root
        }
    }
}
