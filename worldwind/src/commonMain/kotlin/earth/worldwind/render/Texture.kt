package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.powerOfTwoCeiling

open class Texture(val width: Int, val height: Int, protected val format: Int, protected val type: Int, protected val isRT: Boolean = false) : RenderResource {
    companion object {
        protected fun estimateByteCount(width: Int, height: Int, format: Int, type: Int, hasMipMap: Boolean): Int {
            require(width >= 0 && height >= 0) {
                logMessage(ERROR, "Texture", "estimateByteCount", "invalidWidthOrHeight")
            }
            // Compute the number of bytes per row of texture image level 0. Use a default of 32 bits per pixel when either
            // of the bitmap's type or internal format are unrecognized. Adjust the width to the next highest power-of-two
            // to better estimate the memory consumed by non-power-of-two images.
            val widthPow2 = powerOfTwoCeiling(width)
            val bytesPerRow = when (type) {
                GL_UNSIGNED_BYTE -> when (format) {
                    GL_ALPHA, GL_LUMINANCE -> widthPow2 // 8 bits per pixel
                    GL_LUMINANCE_ALPHA -> widthPow2 * 2 // 16 bits per pixel
                    GL_RGB -> widthPow2 * 3 // 24 bits per pixel
                    GL_RGBA -> widthPow2 * 4 // 32 bits per pixel
                    else -> widthPow2 * 4 // 32 bits per pixel
                }
                GL_UNSIGNED_SHORT, GL_UNSIGNED_SHORT_5_6_5,
                GL_UNSIGNED_SHORT_4_4_4_4, GL_UNSIGNED_SHORT_5_5_5_1 -> widthPow2 * 2 // 16 bits per pixel
                GL_UNSIGNED_INT -> widthPow2 * 4 // 32 bits per pixel
                else -> widthPow2 * 4 // 32 bits per pixel
            }

            // Compute the number of bytes for the entire texture image level 0 (i.e. bytePerRow * numRows). Adjust the
            // height to the next highest power-of-two to better estimate the memory consumed by non power-of-two images.
            val heightPow2 = powerOfTwoCeiling(height)
            var byteCount = bytesPerRow * heightPow2

            // If the texture will have mipmaps, add 1/3 to account for the bytes used by texture image level 1 through
            // texture image level N.
            if (hasMipMap) byteCount += byteCount / 3
            return byteCount
        }
    }

    val coordTransform = Matrix3()
    val byteCount get() = estimateByteCount(width, height, format, type, hasMipMap)
    protected var name = KglTexture.NONE
    protected var parameters: MutableMap<Int, Int>? = null
    protected open val hasMipMap = false
    private var pickMode = false

    fun getTexParameter(name: Int) = parameters?.get(name)?:0

    fun setTexParameter(name: Int, param: Int) {
        val parameters = parameters ?: mutableMapOf<Int, Int>().also { parameters = it }
        parameters[name] = param
    }

    override fun release(dc: DrawContext) {
        if (name.isValid()) deleteTexture(dc)
    }

    fun getTextureName(dc: DrawContext): KglTexture {
        if (!name.isValid()) createTexture(dc)
        return name
    }

    fun bindTexture(dc: DrawContext): Boolean {
        if (!name.isValid()) createTexture(dc)
        if (name.isValid()) dc.bindTexture(name)
        if (name.isValid() && pickMode != dc.isPickMode) {
            setTexParameters(dc)
            pickMode = dc.isPickMode
        }
        return name.isValid()
    }

    protected open fun createTexture(dc: DrawContext) {
        val currentTexture = dc.currentTexture
        try {
            // Create the OpenGL texture 2D object.
            name = dc.gl.createTexture()
            dc.gl.bindTexture(GL_TEXTURE_2D, name)

            // Specify the texture object's image data
            allocTexImage(dc)

            // Configure the texture object's parameters.
            setTexParameters(dc)
        } finally {
            // Restore the current OpenGL texture object binding.
            dc.gl.bindTexture(GL_TEXTURE_2D, currentTexture)
        }
    }

    protected open fun deleteTexture(dc: DrawContext) {
        dc.gl.deleteTexture(name)
        name = KglTexture.NONE
    }

    protected open fun allocTexImage(dc: DrawContext) {
        // Following line of code is a dirty hack to disable AFBC compression on Mali GPU driver,
        // which cause huge memory leak during surface shapes drawing on terrain textures.
        if (isRT and dc.gl.hasMaliOOMBug) dc.gl.texImage2D(GL_TEXTURE_2D, 0, format, 1, 1, 0, format, type, null)

        // Allocate texture memory for the OpenGL texture 2D object. The texture memory is initialized with 0.
        dc.gl.texImage2D(
            GL_TEXTURE_2D, 0 /*level*/, format, width, height, 0 /*border*/, format, type, null /*pixels*/
        )
    }

    // TODO refactor setTexParameters to apply all configured tex parameters
    // TODO apply defaults only when no parameter is configured
    // TODO consider simplifying the defaults and requiring that layers/shapes specify what they want
    protected open fun setTexParameters(dc: DrawContext) {
        var param: Int

        // Configure the OpenGL texture minification function. Always use the nearest filtering function in picking mode.
        when {
            dc.isPickMode -> dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            getTexParameter(GL_TEXTURE_MIN_FILTER).also { param = it } != 0 ->
                dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, param)
            else -> dc.gl.texParameteri(
                GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, if (hasMipMap) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR
            )
        }

        // Configure the OpenGL texture magnification function. Always use the nearest filtering function in picking mode.
        when {
            dc.isPickMode -> dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            getTexParameter(GL_TEXTURE_MAG_FILTER).also { param = it } != 0 ->
                dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, param)
            else -> dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        }

        // Configure the OpenGL texture wrapping function for texture coordinate S. Default to the edge clamping
        // function to render image tiles without seams.
        if (getTexParameter(GL_TEXTURE_WRAP_S).also { param = it } != 0)
            dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, param)
        else dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)

        // Configure the OpenGL texture wrapping function for texture coordinate T. Default to the edge clamping
        // function to render image tiles without seams.
        if (getTexParameter(GL_TEXTURE_WRAP_T).also { param = it } != 0)
            dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, param)
        else dc.gl.texParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }
}