package earth.worldwind.formats.collada

class ColladaMaterial(val id: String) {
    private val newParams = mutableMapOf<String, Param>()
    var techniqueType: String = ""
        private set
    val dataMap = mutableMapOf<String, FloatArray>()
    val propertyMap = mutableMapOf<String, String>()

    private class Param(val type: String, val initFrom: String?, val source: String?)

    companion object {
        internal fun parse(materialId: String, element: XmlElement): ColladaMaterial {
            val material = ColladaMaterial(materialId)
            for (child in element.children) {
                if (child.name == "profile_COMMON") material.parseProfileCommon(child)
            }
            return material
        }
    }

    private fun parseProfileCommon(element: XmlElement) {
        for (child in element.children) {
            when (child.name) {
                "newparam" -> parseNewparam(child)
                "technique" -> parseTechnique(child)
            }
        }
    }

    private fun parseNewparam(element: XmlElement) {
        val sid = element.getAttribute("sid") ?: return
        for (child in element.children) {
            when (child.name) {
                "surface" -> {
                    val initFrom = child.querySelector("init_from")
                    if (initFrom != null) newParams[sid] = Param("surface", initFrom.textContent, null)
                }
                "sampler2D" -> {
                    val source = child.querySelector("source")
                    if (source != null) newParams[sid] = Param("sampler2D", null, source.textContent)
                }
            }
        }
    }

    private fun parseTechnique(element: XmlElement) {
        for (child in element.children) {
            when (child.name) {
                "constant", "lambert", "blinn", "phong" -> {
                    techniqueType = child.name
                    parseTechniqueType(child)
                }
            }
        }
    }

    private fun parseTechniqueType(element: XmlElement) {
        for (child in element.children) {
            val nodeValue = child.children.firstOrNull() ?: continue
            when (nodeValue.name) {
                "color" -> {
                    val values = ColladaUtils.bufferDataFloat32(nodeValue) ?: continue
                    dataMap[child.name] = values.copyOfRange(0, minOf(4, values.size))
                }
                "float" -> {
                    val values = ColladaUtils.bufferDataFloat32(nodeValue) ?: continue
                    dataMap[child.name] = values
                }
                "texture" -> {
                    val texture = nodeValue.getAttribute("texture") ?: continue
                    val param1 = newParams[texture] ?: continue
                    val source = param1.source ?: continue
                    val param2 = newParams[source] ?: continue
                    val initFrom = param2.initFrom ?: continue
                    propertyMap[child.name] = initFrom
                }
            }
        }
    }
}
