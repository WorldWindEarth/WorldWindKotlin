package earth.worldwind.layer.starfield

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.program.AbstractShaderProgram
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.KglUniformLocation

/**
 * StarFieldProgram is a GLSL program that draws points representing stars.
 */
class StarFieldProgram : AbstractShaderProgram() {
    override var programSources = arrayOf(
        """
            //.x = declination
            //.y = right ascension
            //.z = point size
            //.w = magnitude
            attribute vec4 vertexPoint;

            uniform mat4 mvpMatrix;
            /* number of days (positive or negative) since Greenwich noon, Terrestrial Time, on 1 January 2000 (J2000.0) */
            uniform float numDays;
            uniform vec2 magnitudeRange;

            varying float magnitudeWeight;

            /* normalizes an angle between 0.0 and 359.0 */
            float normalizeAngle(float angle) {
               float angleDivisions = angle / 360.0;
               return 360.0 * (angleDivisions - floor(angleDivisions));
            }

            /* transforms declination and right ascension in cartesian coordinates */
            vec3 computePosition(float dec, float ra) {
               float GMST = normalizeAngle(280.46061837 + 360.98564736629 * numDays);
               float GHA = normalizeAngle(GMST - ra);
               float lon = -GHA + 360.0 * step(180.0, GHA);
               float latRad = radians(dec);
               float lonRad = radians(lon);
               float radCosLat = cos(latRad);
               return vec3(radCosLat * sin(lonRad), sin(latRad), radCosLat * cos(lonRad));
            }

            /* normalizes a value between 0.0 and 1.0 */
            float normalizeScalar(float value, float minValue, float maxValue){
               return (value - minValue) / (maxValue - minValue);
            }

            void main() {
               vec3 vertexPosition = computePosition(vertexPoint.x, vertexPoint.y);
               gl_Position = mvpMatrix * vec4(vertexPosition.xyz, 1.0);
               gl_Position.z = gl_Position.w - 0.00001;
               gl_PointSize = vertexPoint.z;
               magnitudeWeight = normalizeScalar(vertexPoint.w, magnitudeRange.x, magnitudeRange.y);
            }
        """.trimIndent(),
        """
            precision mediump float;

            uniform sampler2D textureSampler;
            uniform int textureEnabled;

            varying float magnitudeWeight;

            const vec4 white = vec4(1.0, 1.0, 1.0, 1.0);
            const vec4 grey = vec4(0.5, 0.5, 0.5, 1.0);

            void main() {
               if (textureEnabled == 1) {
                   gl_FragColor = texture2D(textureSampler, gl_PointCoord);
               }
               else {
            /* paint the starts in shades of grey, where the brightest star is white and the dimmest star is grey */
                   gl_FragColor = mix(white, grey, magnitudeWeight);
               }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")
    private var mvpMatrixId = KglUniformLocation.NONE
    private var numDaysId = KglUniformLocation.NONE
    private var magnitudeRangeId = KglUniformLocation.NONE
    private var textureUnitId = KglUniformLocation.NONE
    private var textureEnabledId = KglUniformLocation.NONE
    private val array = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        mvpMatrixId = gl.getUniformLocation(program, "mvpMatrix")
        numDaysId = gl.getUniformLocation(program, "numDays")
        magnitudeRangeId = gl.getUniformLocation(program, "magnitudeRange")
        textureUnitId = gl.getUniformLocation(program, "textureSampler")
        textureEnabledId = gl.getUniformLocation(program, "textureEnabled")
    }

    /**
     * Loads the specified matrix as the value of this program's 'mvpMatrix' uniform variable.
     *
     * @param matrix The matrix to load.
     */
    fun loadModelviewProjection(matrix: Matrix4) {
        // Don't bother testing whether mvpMatrix has changed, the common case is to load a different matrix.
        matrix.transposeToArray(array, 0)
        gl.uniformMatrix4fv(mvpMatrixId, 1, false, array, 0)
    }

    /**
     * Loads the specified number as the value of this program's 'numDays' uniform variable.
     *
     * @param numDays The number of days (positive or negative) since Greenwich noon, Terrestrial Time,
     * on 1 January 2000 (J2000.0)
     */
    fun loadNumDays(numDays: Float) = gl.uniform1f(numDaysId, numDays)

    /**
     * Loads the specified numbers as the value of this program's 'magnitudeRange' uniform variable.
     *
     * @param minMag Minimal magnitude
     * @param maxMag Maximal magnitude
     */
    fun loadMagnitudeRange(minMag: Float, maxMag: Float) = gl.uniform2f(magnitudeRangeId, minMag, maxMag)

    /**
     * Loads the specified number as the value of this program's 'textureSampler' uniform variable.
     *
     * @param unit The texture unit.
     */
    fun loadTextureUnit(unit: Int) = gl.uniform1i(textureUnitId, unit - GL_TEXTURE0)

    /**
     * Loads the specified boolean as the value of this program's 'textureEnabledLocation' uniform variable.
     *
     * @param value Texture enabled
     */
    fun loadTextureEnabled(value: Boolean) = gl.uniform1i(textureEnabledId, if (value) 1 else 0)

    companion object {
        val KEY = StarFieldProgram::class
    }
}