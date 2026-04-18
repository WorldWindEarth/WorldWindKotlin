package earth.worldwind.formats.collada

class ColladaImage(val id: String, val name: String) {
    var fileName: String = ""
        private set
    var path: String = ""
        private set

    companion object {
        internal fun parse(imageId: String, imageName: String, element: XmlElement): ColladaImage {
            val image = ColladaImage(imageId, imageName)
            for (child in element.children) {
                if (child.name == "init_from") {
                    image.fileName = ColladaUtils.getFilename(child.textContent)
                    image.path = child.textContent
                }
            }
            return image
        }
    }
}
