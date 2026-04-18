package earth.worldwind.formats.collada

internal object ColladaUtils {
    fun getRawValues(element: XmlElement): List<String>? {
        val text = element.textContent.replace("\n", " ").replace("\\s+".toRegex(), " ").trim()
        if (text.isEmpty()) return null
        return text.split(" ")
    }

    fun bufferDataFloat32(element: XmlElement): FloatArray? {
        val raw = getRawValues(element) ?: return null
        return FloatArray(raw.size) { raw[it].toFloat() }
    }

    fun getFilename(filePath: String): String {
        var path = filePath
        var pos = path.lastIndexOf('\\')
        if (pos != -1) path = path.substring(pos + 1)
        pos = path.lastIndexOf('/')
        if (pos != -1) path = path.substring(pos + 1)
        return path
    }

    fun querySelectorById(elements: List<XmlElement>, id: String): XmlElement? =
        elements.firstOrNull { it.getAttribute("id") == id }

    fun getTextureType(uvs: FloatArray): Boolean {
        for (uv in uvs) if (uv !in 0f..1f) return false
        return true
    }
}
