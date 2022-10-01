package earth.worldwind.render

import earth.worldwind.shape.TextAttributes

actual class TextRenderer actual constructor(protected val rc: RenderContext) {
    /**
     * Creates a texture for a specified text string and specified text attributes.
     *
     * @param text The text string.
     * @returns A texture for the specified text string.
     */
    actual fun renderText(text: String?, attributes: TextAttributes): Texture? {
        TODO("Not yet implemented")
    }
}