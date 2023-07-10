package earth.worldwind.layer.starfield

import dev.icerock.moko.resources.FileResource
import earth.worldwind.MR
import earth.worldwind.WorldWind
import earth.worldwind.draw.DrawContext
import earth.worldwind.draw.DrawableGroup
import earth.worldwind.draw.DrawableLambda
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.AbstractLayer
import earth.worldwind.render.RenderContext
import earth.worldwind.render.Texture
import earth.worldwind.render.buffer.FloatBufferObject
import earth.worldwind.render.image.ImageSource
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.WARN
import earth.worldwind.util.Logger.log
import earth.worldwind.util.Logger.logMessage
import earth.worldwind.util.SunPosition
import earth.worldwind.util.kgl.GL_ALIASED_POINT_SIZE_RANGE
import earth.worldwind.util.kgl.GL_ARRAY_BUFFER
import earth.worldwind.util.kgl.GL_FLOAT
import earth.worldwind.util.kgl.GL_POINTS
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

/**
 * Constructs a layer showing stars and the Sun around the Earth.
 * If used together with the AtmosphereLayer, the StarFieldLayer must be inserted before the AtmosphereLayer.
 *
 * If you want to use your own star data, the file provided must be .json
 * and the fields "ra", "dec" and "vmag" must be present in the metadata.
 * ra and dec must be expressed in degrees.
 *
 * This layer uses J2000.0 as the ref epoch.
 *
 * If the star data .json file is too big, consider enabling gzip compression on your web server.
 * For more info about enabling gzip compression consult the configuration for your web server.
 */
open class StarFieldLayer(starDataSource: FileResource = MR.files.stars): AbstractLayer("StarField") {
    override var isPickEnabled = false // The StarField Layer is not pickable.
    /**
     * Resource for the stars data
     */
    var starDataSource = starDataSource
        set(value) {
            field = value
            invalidateStarData()
        }
    /**
     * Resource for the sun texture image.
     */
    var sunImageSource = ImageSource.fromResource(MR.images.sun_texture)
    /**
     * The size of the Sun in pixels.
     * This can not exceed the maximum allowed pointSize of the GPU.
     * A warning will be given if the size is too big and the allowed max size will be used.
     */
    var sunSize = 128f
    /**
     * Indicates weather to show or hide the Sun
     */
    var isShowSun = true
    /**
     * Display star field on a specified time point. If null, then current time will be used each frame.
     */
    var time : Instant? = null
    protected val matrix = Matrix4() //The MVP matrix of this layer.
    protected var starsPositionsVboCacheKey = nextCacheKey() //gpu cache key for the stars vbo.
    protected var numStars = 0
    protected var starData: StarData? = null
    protected var minMagnitude = Float.MAX_VALUE
    protected var maxMagnitude = Float.MIN_VALUE
    protected var sunBufferViewHashCode = 0
    /**
     * A flag to indicate the star data is currently being retrieved.
     */
    protected var loadStarted = false
    protected val minScale = 10e6
    protected var sunPositionsCacheKey = nextCacheKey()
    protected val sunBufferView = FloatArray(4)
    protected var MAX_GL_POINT_SIZE = 0f

    protected fun nextCacheKey() = Any()

    protected open fun invalidateStarData() {
        starData = null
        starsPositionsVboCacheKey = nextCacheKey()
    }

    override fun doRender(rc: RenderContext) {
        if (rc.globe!!.is2D) return // Star Field layer is not applicable for 2D globe

        loadStarData(rc)

        val sunTexture = rc.getTexture(sunImageSource, null) ?: return // Sun texture is not loaded yet
        val starData = starData ?: return // Star data is not loaded yet
        val starsPositionsBuffer = rc.getBufferObject(starsPositionsVboCacheKey) {
            FloatBufferObject(GL_ARRAY_BUFFER, createStarsGeometry(starData, rc))
        }
        val time = time ?: Clock.System.now()
        // Number of days since Greenwich noon, Terrestrial Time, on 1 January 2000 (J2000.0)
        val julianDate = SunPosition.computeJulianDate(time)
        val sunCelestialLocation = SunPosition.getAsCelestialLocation(time)

        //.x = declination
        //.y = right ascension
        //.z = point size
        //.w = magnitude
        sunBufferView[0] = sunCelestialLocation.declination.inDegrees.toFloat()
        sunBufferView[1] = sunCelestialLocation.rightAscension.inDegrees.toFloat()
        sunBufferView[2] = sunSize.coerceAtMost(MAX_GL_POINT_SIZE)
        sunBufferView[3] = 1f

        val hashCode = sunBufferView.contentHashCode()
        if (sunBufferViewHashCode != hashCode) {
            sunBufferViewHashCode = hashCode
            sunPositionsCacheKey = nextCacheKey()
        }
        val sunPositionsBuffer = rc.getBufferObject(sunPositionsCacheKey) {
            FloatBufferObject(GL_ARRAY_BUFFER, sunBufferView)
        }

        val scale = (rc.camera!!.position.altitude * 1.5).coerceAtLeast(minScale)
        matrix.copy(rc.modelviewProjection)
        matrix.multiplyByScale(scale, scale, scale)

        val program = rc.getShaderProgram { StarFieldProgram() }
        rc.offerDrawable(DrawableLambda { dc ->
            program.useProgram(dc)
            dc.gl.depthMask(false)
            try {
                program.loadModelviewProjection(matrix)
                // This subtraction does not work properly on the GPU due to precision loss. It must be done on the CPU.
                program.loadNumDays((julianDate - 2451545.0).toFloat())
                renderStars(dc, program, starsPositionsBuffer)
                if (isShowSun) renderSun(dc, program, sunPositionsBuffer, sunTexture)
            } finally {
                dc.gl.depthMask(true)
            }
        }, DrawableGroup.BACKGROUND, 0.0)
    }

    protected open fun loadStarData(rc: RenderContext) {
        if (starData == null && !loadStarted) {
            loadStarted = true
            rc.renderResourceCache?.retrieveTextFile(starDataSource) {
                starData = Json.decodeFromString(it)
                loadStarted = false
                WorldWind.requestRedraw()
            }
        }
    }

    protected open fun renderStars(dc: DrawContext, program: StarFieldProgram, buffer: FloatBufferObject) {
        buffer.bindBuffer(dc)
        dc.gl.vertexAttribPointer(0, 4, GL_FLOAT, false, 0, 0)
        program.loadMagnitudeRange(minMagnitude, maxMagnitude)
        program.loadTextureEnabled(false)
        dc.gl.drawArrays(GL_POINTS, 0, numStars)
    }

    protected open fun renderSun(
        dc: DrawContext, program: StarFieldProgram, sunBuffer: FloatBufferObject, sunTexture: Texture
    ) {
        if (MAX_GL_POINT_SIZE == 0f) MAX_GL_POINT_SIZE = dc.gl.getParameterfv(GL_ALIASED_POINT_SIZE_RANGE)[1]

        if (sunSize > MAX_GL_POINT_SIZE)
            log(WARN, "StarFieldLayer - sunSize is to big, max size allowed is: $MAX_GL_POINT_SIZE")

        sunBuffer.bindBuffer(dc)
        dc.gl.vertexAttribPointer(0, 4, GL_FLOAT, false, 0, 0)
        program.loadTextureEnabled(true)
        sunTexture.bindTexture(dc)
        dc.gl.drawArrays(GL_POINTS, 0, 1)
    }

    protected open fun createStarsGeometry(starData: StarData, rc: RenderContext): FloatArray {
        val indexes = parseStarsMetadata(starData.metadata)
        require(indexes[0] != -1) {
            logMessage(ERROR, "StarFieldLayer", "createStarsGeometry", "Missing ra field in star data.")
        }
        require(indexes[1] != -1) {
            logMessage(ERROR, "StarFieldLayer", "createStarsGeometry", "Missing dec field in star data.")
        }
        require(indexes[2] != -1) {
            logMessage(ERROR, "StarFieldLayer", "createStarsGeometry", "Missing vmag field in star data.")
        }

        numStars = starData.data.size
        minMagnitude = Float.MAX_VALUE
        maxMagnitude = Float.MIN_VALUE

        val positions = FloatArray(numStars * 4)
        var positionIndex = 0
        for (i in starData.data.indices) {
            val starInfo = starData.data[i]
            val rightAscension = starInfo[indexes[0]] //for longitude
            val declination = starInfo[indexes[1]] //for latitude
            val magnitude = starInfo[indexes[2]]
            val pointSize = if (magnitude < 2) 2f else 1f

            positions[positionIndex++] = declination
            positions[positionIndex++] = rightAscension
            positions[positionIndex++] = pointSize * rc.densityFactor
            positions[positionIndex++] = magnitude

            minMagnitude = minMagnitude.coerceAtMost(magnitude)
            maxMagnitude = maxMagnitude.coerceAtLeast(magnitude)
        }

        return positions
    }

    protected open fun parseStarsMetadata(metadata: List<StarMetadata>): Array<Int> {
        var raIndex = -1
        var decIndex = -1
        var magIndex = -1
        for (i in metadata.indices) {
            val starMetaInfo = metadata[i]
            if (starMetaInfo.name == "ra") raIndex = i
            if (starMetaInfo.name == "dec") decIndex = i
            if (starMetaInfo.name == "vmag") magIndex = i
        }
        return arrayOf(raIndex, decIndex, magIndex)
    }

    @Serializable
    data class  StarMetadata(
        val name: String, val description: String, val datatype: String,
        val arraysize: Int, val unit: String, val ucd: String
    )

    @Serializable
    data class StarData(val metadata: List<StarMetadata>, val data: List<List<Float>>)
}
