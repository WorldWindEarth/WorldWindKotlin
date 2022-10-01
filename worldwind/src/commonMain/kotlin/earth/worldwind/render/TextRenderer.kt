package earth.worldwind.render

import earth.worldwind.shape.TextAttributes

expect class TextRenderer(rc: RenderContext) {
    fun renderText(text: String?, attributes: TextAttributes): Texture?
}