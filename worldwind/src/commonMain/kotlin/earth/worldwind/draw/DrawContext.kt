package earth.worldwind.draw

import earth.worldwind.PickedObjectList
import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec2
import earth.worldwind.geom.Vec3
import earth.worldwind.geom.Viewport
import earth.worldwind.render.Color
import earth.worldwind.render.Framebuffer
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.util.kgl.*

open class DrawContext(val gl: Kgl) {
    val eyePoint = Vec3()
    val viewport = Viewport()
    val projection = Matrix4()
    val modelview = Matrix4()
    val modelviewProjection = Matrix4()
//    val infiniteProjection = Matrix4()
    val screenProjection = Matrix4()
    var drawableQueue: DrawableQueue? = null
    var drawableTerrain: DrawableQueue? = null
    var pickedObjects: PickedObjectList? = null
    var pickViewport: Viewport? = null
    var pickPoint: Vec2? = null
    var isPickMode = false
    private var framebuffer = KglFramebuffer.NONE
    private var program = KglProgram.NONE
    private var textureUnit = GL_TEXTURE0
    private val textures = Array(32) {KglTexture.NONE}
    private var arrayBuffer = KglBuffer.NONE
    private var elementArrayBuffer = KglBuffer.NONE
    private var scratchFramebufferCache: Framebuffer? = null
    private var unitSquareBufferCache: FloatBufferObject? = null
    private var scratchBuffer = ByteArray(4)
    private val pixelArray = ByteArray(4)
    /**
     * Returns count of terrain drawables in queue
     */
    val drawableTerrainCount get() = drawableTerrain?.count?:0
    /**
     * Returns the name of the OpenGL framebuffer object that is currently active.
     */
    val currentFramebuffer get() = framebuffer
    /**
     * Returns the name of the OpenGL program object that is currently active.
     */
    val currentProgram get() = program
    /**
     * Returns the OpenGL multitexture unit that is currently active. Returns a value from the GL_TEXTUREi enumeration,
     * where i ranges from 0 to 32.
     */
    val currentTextureUnit get() = textureUnit
    /**
     * Returns the name of the OpenGL texture 2D object currently bound to the active multitexture unit. The active
     * multitexture unit may be determined by calling currentTextureUnit.
     */
    val currentTexture get() = currentTexture(textureUnit)
    /**
     * Returns an OpenGL framebuffer object suitable for offscreen drawing. The framebuffer has a 32-bit color buffer
     * and a 32-bit depth buffer, both attached as OpenGL texture 2D objects.
     * <br>
     * The framebuffer may be used by any drawable and for any purpose. However, the draw context makes no guarantees
     * about the framebuffer's contents. Drawables must clear the framebuffer before use, and must assume its contents
     * may be modified by another drawable, either during the current frame or in a subsequent frame.
     * <br>
     * The OpenGL framebuffer object is created on first use and cached. Subsequent calls to this method return the
     * cached buffer object.
     */
    val scratchFramebuffer get() = scratchFramebufferCache ?: Framebuffer().apply {
        val colorAttachment = Texture(1024, 1024, GL_RGBA, GL_UNSIGNED_BYTE, true)
        val depthAttachment = Texture(1024, 1024, GL_DEPTH_COMPONENT, GL_UNSIGNED_SHORT, true)
        // TODO consider modifying Texture's tex parameter behavior in order to make this unnecessary
        depthAttachment.setTexParameter(GL_TEXTURE_MIN_FILTER, GL_NEAREST)
        depthAttachment.setTexParameter(GL_TEXTURE_MAG_FILTER, GL_NEAREST)
        attachTexture(this@DrawContext, colorAttachment, GL_COLOR_ATTACHMENT0)
        attachTexture(this@DrawContext, depthAttachment, GL_DEPTH_ATTACHMENT)
    }.also { scratchFramebufferCache = it }
    /**
     * Returns an OpenGL buffer object containing a unit square expressed as four vertices at (0, 1), (0, 0), (1, 1) and
     * (1, 0). Each vertex is stored as two 32-bit floating point coordinates. The four vertices are in the order
     * required by a triangle strip.
     * <br>
     * The OpenGL buffer object is created on first use and cached. Subsequent calls to this method return the cached
     * buffer object.
     */
    val unitSquareBuffer get() = unitSquareBufferCache ?: FloatBufferObject(
        GL_ARRAY_BUFFER, floatArrayOf(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
    ).also { unitSquareBufferCache = it }
    /**
     * Returns a scratch list suitable for accumulating entries during drawing. The list is cleared before each frame,
     * otherwise its contents are undefined.
     */
    val scratchList = mutableListOf<Drawable>()

    fun reset() {
        eyePoint.set(0.0, 0.0, 0.0)
        viewport.setEmpty()
        projection.setToIdentity()
        modelview.setToIdentity()
        modelviewProjection.setToIdentity()
        screenProjection.setToIdentity()
//        infiniteProjection.setToIdentity()
        drawableQueue = null
        drawableTerrain = null
        pickedObjects = null
        pickViewport = null
        pickPoint = null
        isPickMode = false
        scratchBuffer.fill(0)
        scratchList.clear()
    }

    fun contextLost() {
        // Clear objects and values associated with the current OpenGL context.
        framebuffer = KglFramebuffer.NONE
        program = KglProgram.NONE
        textureUnit = GL_TEXTURE0
        arrayBuffer = KglBuffer.NONE
        elementArrayBuffer = KglBuffer.NONE
        scratchFramebufferCache = null
        unitSquareBufferCache = null
        textures.fill(KglTexture.NONE)
    }

    fun peekDrawable() = drawableQueue?.peekDrawable()

    fun pollDrawable() = drawableQueue?.pollDrawable()

    fun rewindDrawables() { drawableQueue?.rewindDrawables() }

    fun getDrawableTerrain(index: Int) = drawableTerrain?.getDrawable(index) as DrawableTerrain? ?: error("Invalid index")

    /**
     * Makes an OpenGL framebuffer object active. The active framebuffer becomes the target of all OpenGL commands that
     * render to the framebuffer or read from the framebuffer. This has no effect if the specified framebuffer object is
     * already active. The default is framebuffer 0, indicating that the default framebuffer provided by the windowing
     * system is active.
     *
     * @param framebuffer the name of the OpenGL framebuffer object to make active, or 0 to make the default
     * framebuffer provided by the windowing system active
     */
    fun bindFramebuffer(framebuffer: KglFramebuffer) {
        if (this.framebuffer != framebuffer) {
            this.framebuffer = framebuffer
            gl.bindFramebuffer(GL_FRAMEBUFFER, framebuffer)
        }
    }

    /**
     * Makes an OpenGL program object active as part of current rendering state. This has no effect if the specified
     * program object is already active. The default is program 0, indicating that no program is active.
     *
     * @param program the name of the OpenGL program object to make active, or 0 to make no program active
     */
    fun useProgram(program: KglProgram) {
        if (this.program != program) {
            this.program = program
            gl.useProgram(program)
        }
    }

    /**
     * Specifies the OpenGL multitexture unit to make active. This has no effect if the specified multitexture unit is
     * already active. The default is GL_TEXTURE0.
     *
     * @param textureUnit the multitexture unit, one of GL_TEXTUREi, where i ranges from 0 to 32.
     */
    fun activeTextureUnit(textureUnit: Int) {
        if (this.textureUnit != textureUnit) {
            this.textureUnit = textureUnit
            gl.activeTexture(textureUnit)
        }
    }

    /**
     * Returns the name of the OpenGL texture 2D object currently bound to the specified multitexture unit.
     *
     * @param textureUnit the multitexture unit, one of GL_TEXTUREi, where i ranges from 0 to 32.
     *
     * @return the currently bound texture 2D object, or 0 if no texture object is bound
     */
    fun currentTexture(textureUnit: Int) = textures[textureUnit - GL_TEXTURE0]

    /**
     * Makes an OpenGL texture 2D object bound to the current multitexture unit. This has no effect if the specified
     * texture object is already bound. The default is texture 0, indicating that no texture is bound.
     *
     * @param texture the name of the OpenGL texture 2D object to make active, or 0 to make no texture active
     */
    fun bindTexture(texture: KglTexture) {
        val textureUnitIndex = textureUnit - GL_TEXTURE0
        if (textures[textureUnitIndex] != texture) {
            textures[textureUnitIndex] = texture
            gl.bindTexture(GL_TEXTURE_2D, texture)
        }
    }

    /**
     * Returns the name of the OpenGL buffer object bound to the specified target buffer.
     *
     * @param target the target buffer, either GL_ARRAY_BUFFER or GL_ELEMENT_ARRAY_BUFFER
     *
     * @return the currently bound buffer object, or 0 if no buffer object is bound
     */
    fun currentBuffer(target: Int): KglBuffer {
        return when (target) {
            GL_ARRAY_BUFFER -> arrayBuffer
            GL_ELEMENT_ARRAY_BUFFER -> elementArrayBuffer
            else -> KglBuffer.NONE
        }
    }

    /**
     * Makes an OpenGL buffer object bound to a specified target buffer. This has no effect if the specified buffer
     * object is already bound. The default is buffer 0, indicating that no buffer object is bound.
     *
     * @param target   the target buffer, either GL_ARRAY_BUFFER or GL_ELEMENT_ARRAY_BUFFER
     * @param buffer the name of the OpenGL buffer object to make active
     */
    fun bindBuffer(target: Int, buffer: KglBuffer) {
        if (target == GL_ARRAY_BUFFER && arrayBuffer != buffer) {
            arrayBuffer = buffer
            gl.bindBuffer(target, buffer)
        } else if (target == GL_ELEMENT_ARRAY_BUFFER && elementArrayBuffer != buffer) {
            elementArrayBuffer = buffer
            gl.bindBuffer(target, buffer)
        } else {
            gl.bindBuffer(target, buffer)
        }
    }

    /**
     * Reads the fragment color at a screen point in the currently active OpenGL frame buffer. The X and Y components
     * indicate OpenGL screen coordinates, which originate in the frame buffer's lower left corner.
     *
     * @param x      the screen point's X component
     * @param y      the screen point's Y component
     * @param result an optional pre-allocated Color in which to return the fragment color, or null to return a new
     * color
     *
     * @return the result argument set to the fragment color, or a new color if the result is null
     */
    fun readPixelColor(x: Int, y: Int, result: Color): Color {
        // Read the fragment pixel as an RGBA 8888 color.
        gl.readPixels(x, y, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, pixelArray)

        // Convert the RGBA 8888 color to a WorldWind color.
        result.red = (pixelArray[0].toInt() and 0xFF) / 0xFF.toFloat()
        result.green = (pixelArray[1].toInt() and 0xFF) / 0xFF.toFloat()
        result.blue = (pixelArray[2].toInt() and 0xFF) / 0xFF.toFloat()
        result.alpha = (pixelArray[3].toInt() and 0xFF) / 0xFF.toFloat()
        return result
    }

    /**
     * Reads the unique fragment colors within a screen rectangle in the currently active OpenGL frame buffer. The
     * components indicate OpenGL screen coordinates, which originate in the frame buffer's lower left corner.
     *
     * @param x      the screen rectangle's X component
     * @param y      the screen rectangle's Y component
     * @param width  the screen rectangle's width
     * @param height the screen rectangle's height
     *
     * @return a set containing the unique fragment colors
     */
    fun readPixelColors(x: Int, y: Int, width: Int, height: Int): Set<Color> {
        // Read the fragment pixels as a tightly packed array of RGBA 8888 colors.
        val pixelCount = width * height
        val pixelBuffer = scratchBuffer(pixelCount * 4)
        gl.readPixels(x, y, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer)
        val resultSet = mutableSetOf<Color>()
        var result = Color()
        for (idx in 0 until pixelCount step 4) {
            // Convert the RGBA 8888 color to a WorldWind color.
            result.red = (pixelBuffer[idx + 0].toInt() and 0xFF) / 0xFF.toFloat()
            result.green = (pixelBuffer[idx + 1].toInt() and 0xFF) / 0xFF.toFloat()
            result.blue = (pixelBuffer[idx + 2].toInt() and 0xFF) / 0xFF.toFloat()
            result.alpha = (pixelBuffer[idx + 3].toInt() and 0xFF) / 0xFF.toFloat()

            // Accumulate the unique colors in a set.
            if (resultSet.add(result)) result = Color()
        }
        return resultSet
    }

    /**
     * Returns a scratch NIO buffer suitable for use during drawing. The returned buffer has capacity at least equal to
     * the specified capacity. The buffer is cleared before each frame, otherwise its contents, position, limit and mark
     * are undefined.
     *
     * @param capacity the buffer's minimum capacity in bytes
     *
     * @return the draw context's scratch buffer
     */
    fun scratchBuffer(capacity: Int): ByteArray {
        if (scratchBuffer.size < capacity) scratchBuffer = ByteArray(capacity)
        return scratchBuffer
    }
}