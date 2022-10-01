package earth.worldwind.render

import earth.worldwind.draw.DrawContext

/**
 * Handle to a rendering resource, such as a GLSL program, GL texture or GL vertex buffer object.
 */
interface RenderResource {
    /**
     * Frees any resources associated with this instance. After this method returns the rendering resource is invalid,
     * and any associated GL object is deleted.
     *
     * @param dc the current draw context
     */
    fun release(dc: DrawContext)
}