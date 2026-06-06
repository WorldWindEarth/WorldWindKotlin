@file:JsModule("canvg")

package earth.worldwind.shape.milstd2525

import org.w3c.dom.CanvasRenderingContext2D
import org.w3c.dom.Document
import kotlin.js.Promise

/**
 * SVG renderer on canvas.
 */
external class Canvg : JsAny {
    /**
     * Main constructor.
     * @param ctx - Rendering context.
     * @param svg - SVG Document.
     * @param options - Rendering options.
     */
    constructor(
        ctx: CanvasRenderingContext2D,
        svg: Document,
        options: JsAny? = definedExternally
    )

    /**
     * Create new Canvg instance with inherited options.
     * @param ctx - Rendering context.
     * @param svg - SVG source string or URL.
     * @param options - Rendering options.
     * @returns Canvg instance.
     */
    fun fork(
        ctx: CanvasRenderingContext2D,
        svg: String,
        options: JsAny? = definedExternally
    ): Promise<Canvg>

    /**
     * Create new Canvg instance with inherited options.
     * @param ctx - Rendering context.
     * @param svg - SVG source string.
     * @param options - Rendering options.
     * @returns Canvg instance.
     */
    fun forkString(
        ctx: CanvasRenderingContext2D,
        svg: String,
        options: JsAny? = definedExternally
    ): Canvg

    /**
     * Document is ready promise.
     * @returns Ready promise.
     */
    fun ready(): Promise<JsAny?>

    /**
     * Document is ready value.
     * @returns Is ready or not.
     */
    fun isReady(): Boolean

    /**
     * Render only first frame, ignoring animations and mouse.
     * @param options - Rendering options.
     */
    fun render(options: JsAny? = definedExternally): Promise<JsAny?>

    /**
     * Start rendering.
     * @param options - Render options.
     */
    fun start(options: JsAny? = definedExternally)

    /**
     * Stop rendering.
     */
    fun stop()

    /**
     * Resize SVG to fit in given size.
     * @param width
     * @param height
     * @param preserveAspectRatio
     */
    fun resize(
        width: Double,
        height: Double = definedExternally,
        preserveAspectRatio: Boolean = definedExternally
    )

    companion object {
        /**
         * Create Canvg instance from SVG source string or URL.
         * @param ctx - Rendering context.
         * @param svg - SVG source string or URL.
         * @param options - Rendering options.
         * @returns Canvg instance.
         */
        fun from(ctx: CanvasRenderingContext2D, svg: String, options: JsAny? = definedExternally): Promise<Canvg>

        /**
         * Create Canvg instance from SVG source string.
         * @param ctx - Rendering context.
         * @param svg - SVG source string.
         * @param options - Rendering options.
         * @returns Canvg instance.
         */
        fun fromString(ctx: CanvasRenderingContext2D, svg: String, options: JsAny? = definedExternally): Canvg
    }
}
