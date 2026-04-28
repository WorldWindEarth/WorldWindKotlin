package earth.worldwind.render

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix3
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.kgl.*
import earth.worldwind.util.math.powerOfTwoCeiling

/**
 * @param format pixel format passed to `glTexImage2D` (e.g. `GL_RGBA`, `GL_DEPTH_COMPONENT`).
 * @param type pixel type passed to `glTexImage2D` (e.g. `GL_UNSIGNED_BYTE`, `GL_UNSIGNED_SHORT`).
 * @param internalFormat `internalformat` passed to `glTexImage2D`. Defaults to [format]
 *  (unsized), which is the only legal value on WebGL1 / GLES2. Pass a sized internal format
 *  (e.g. `GL_RGBA8`, `GL_DEPTH_COMPONENT16`) only when [Kgl.supportsSizedTextureFormats] is
 *  `true`. Required for MSAA blit-resolve compatibility — the resolve target's internal
 *  format must literally match the multisample renderbuffer's (`GL_RGBA8`).
 * @param target texture target. Defaults to `GL_TEXTURE_2D`. Pass `GL_TEXTURE_CUBE_MAP` for
 *  cube-map storage; the constructor will allocate all six face images.
 */
open class Texture(
    val width: Int, val height: Int,
    protected val format: Int, protected val type: Int,
    protected val isRT: Boolean = false,
    protected val internalFormat: Int = format,
    val target: Int = GL_TEXTURE_2D,
) : RenderResource {
    companion object {
        private const val TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE

        /**
         * Global anisotropic filtering level for all GL_LINEAR textures. Defaults to 16x:
         * the cost is paid only on textures whose minification/magnification ratio is high
         * (oblique drone footage projected on terrain, sharply-tilted SurfaceImage tiles),
         * exactly the cases where the sharpening is visible. Front-facing textures see no
         * runtime difference. Drivers that don't support GL_TEXTURE_MAX_ANISOTROPY_EXT
         * silently ignore the parameter.
         */
        var anisotropicFiltering = AFLevel.AF16X

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

    fun getTexParameter(name: Int) = parameters?.get(name) ?: 0

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

    open fun bindTexture(dc: DrawContext): Boolean {
        if (!name.isValid()) createTexture(dc)
        if (name.isValid()) {
            // [DrawContext.bindTexture] caches the active GL_TEXTURE_2D binding per texture
            // unit. For non-2D targets (e.g. GL_TEXTURE_CUBE_MAP) we bypass that cache and
            // bind directly - the cube-map binding doesn't share state with the 2D one and
            // is touched only a few times per frame from the sightline path, so the missed
            // cache is irrelevant.
            if (target == GL_TEXTURE_2D) dc.bindTexture(name) else dc.gl.bindTexture(target, name)
        }
        if (name.isValid() && pickMode != dc.isPickMode) {
            setTexParameters(dc)
            pickMode = dc.isPickMode
        }
        return name.isValid()
    }

    protected open fun createTexture(dc: DrawContext) {
        val currentTexture = dc.currentTexture
        try {
            // Create the OpenGL texture object.
            name = dc.gl.createTexture()
            dc.gl.bindTexture(target, name)

            // Specify the texture object's image data
            allocTexImage(dc)

            // Configure the texture object's parameters.
            setTexParameters(dc)
        } finally {
            // Restore the current binding. [DrawContext.currentTexture] only tracks GL_TEXTURE_2D,
            // so for non-2D targets we'd be binding a 2D texture to e.g. CUBE_MAP - WebGL hard-
            // errors on reusing a texture object across targets. Unbind via NONE in that case.
            dc.gl.bindTexture(target, if (target == GL_TEXTURE_2D) currentTexture else KglTexture.NONE)
        }
    }

    protected open fun deleteTexture(dc: DrawContext) {
        dc.gl.deleteTexture(name)
        name = KglTexture.NONE
    }

    protected open fun allocTexImage(dc: DrawContext) {
        // Following line of code is a dirty hack to disable AFBC compression on Mali GPU driver,
        // which cause huge memory leak during surface shapes drawing on terrain textures.
        // `null as ByteArray?` disambiguates from the FloatArray-buffer overload of texImage2D.
        if (isRT and dc.gl.hasMaliOOMBug) dc.gl.texImage2D(target, 0, internalFormat, 1, 1, 0, format, type, null as ByteArray?)

        // Allocate texture memory. For GL_TEXTURE_CUBE_MAP we need to call texImage2D once per
        // face with the corresponding face target (GL_TEXTURE_CUBE_MAP_POSITIVE_X..NEGATIVE_Z).
        // For GL_TEXTURE_2D the target is the binding target and a single allocation suffices.
        if (target == GL_TEXTURE_CUBE_MAP) {
            for (face in 0 until 6) {
                dc.gl.texImage2D(
                    GL_TEXTURE_CUBE_MAP_POSITIVE_X + face, 0, internalFormat,
                    width, height, 0, format, type, null as ByteArray?
                )
            }
        } else {
            dc.gl.texImage2D(
                target, 0 /*level*/, internalFormat, width, height, 0 /*border*/, format, type, null as ByteArray?
            )
        }
    }

    // TODO refactor setTexParameters to apply all configured tex parameters
    // TODO apply defaults only when no parameter is configured
    // TODO consider simplifying the defaults and requiring that layers/shapes specify what they want
    protected open fun setTexParameters(dc: DrawContext) {
        var param: Int

        // Configure the OpenGL texture minification function. Always use the nearest filtering function in picking mode.
        when {
            dc.isPickMode -> dc.gl.texParameteri(target, GL_TEXTURE_MIN_FILTER, GL_NEAREST)
            getTexParameter(GL_TEXTURE_MIN_FILTER).also { param = it } != 0 ->
                dc.gl.texParameteri(target, GL_TEXTURE_MIN_FILTER, param)
            else -> dc.gl.texParameteri(
                target, GL_TEXTURE_MIN_FILTER, if (hasMipMap) GL_LINEAR_MIPMAP_LINEAR else GL_LINEAR
            )
        }

        // Configure the OpenGL texture magnification function. Always use the nearest filtering function in picking mode.
        when {
            dc.isPickMode -> dc.gl.texParameteri(target, GL_TEXTURE_MAG_FILTER, GL_NEAREST)
            getTexParameter(GL_TEXTURE_MAG_FILTER).also { param = it } != 0 -> {
                dc.gl.texParameteri(target, GL_TEXTURE_MAG_FILTER, param)

                // Try to enable the anisotropic texture filtering only if we have a linear magnification filter.
                // This can't be enabled all the time because Windows seems to ignore the TEXTURE_MAG_FILTER parameter when
                // this extension is enabled.
                if (param == GL_LINEAR && anisotropicFiltering != AFLevel.OFF) {
                    dc.gl.texParameteri(target, TEXTURE_MAX_ANISOTROPY_EXT, anisotropicFiltering.level)
                }
            }
            else -> {
                dc.gl.texParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
                if (anisotropicFiltering != AFLevel.OFF) {
                    dc.gl.texParameteri(target, TEXTURE_MAX_ANISOTROPY_EXT, anisotropicFiltering.level)
                }
            }
        }

        // Configure the OpenGL texture wrapping function for texture coordinate S. Default to the edge clamping
        // function to render image tiles without seams.
        if (getTexParameter(GL_TEXTURE_WRAP_S).also { param = it } != 0)
            dc.gl.texParameteri(target, GL_TEXTURE_WRAP_S, param)
        else dc.gl.texParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)

        // Configure the OpenGL texture wrapping function for texture coordinate T. Default to the edge clamping
        // function to render image tiles without seams.
        if (getTexParameter(GL_TEXTURE_WRAP_T).also { param = it } != 0)
            dc.gl.texParameteri(target, GL_TEXTURE_WRAP_T, param)
        else dc.gl.texParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }
}