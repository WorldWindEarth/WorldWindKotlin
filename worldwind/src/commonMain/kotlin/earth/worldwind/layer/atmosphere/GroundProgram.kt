package earth.worldwind.layer.atmosphere

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.layer.shadow.ShadowReceiverGlsl
import earth.worldwind.layer.shadow.ShadowReceiverProgram
import earth.worldwind.util.kgl.KglUniformLocation

class GroundProgram: AbstractAtmosphereProgram(), ShadowReceiverProgram {
    override var programSources = arrayOf(
        """
            #ifdef GL_ES
            precision mediump int; /* fragMode is used in both shaders, so we must use a common precision */
            #endif

            const int FRAGMODE_PRIMARY = 1;
            const int FRAGMODE_SECONDARY = 2;
            const int FRAGMODE_PRIMARY_TEX_BLEND = 3;

            const int SAMPLE_COUNT = 2;
            const float SAMPLES = 2.0;

            uniform int fragMode;
            uniform mat4 mvpMatrix;
            uniform mat3 texCoordMatrix;
            uniform vec3 vertexOrigin;
            uniform vec3 eyePoint;
            uniform float eyeMagnitude;	        /* The eye point's magnitude */
            uniform float eyeMagnitude2;	    /* eyeMagnitude^2 */
            uniform vec3 lightDirection;	    /* The direction vector to the light source */
            uniform vec3 invWavelength;	        /* 1 / pow(wavelength, 4) for the red, green, and blue channels */
            uniform float atmosphereRadius;     /* The outer (atmosphere) radius */
            uniform float atmosphereRadius2;    /* atmosphereRadius^2 */
            uniform float globeRadius;		    /* The inner (planetary) radius */
            uniform float KrESun;			    /* Kr * ESun */
            uniform float KmESun;			    /* Km * ESun */
            uniform float Kr4PI;			    /* Kr * 4 * PI */
            uniform float Km4PI;			    /* Km * 4 * PI */
            uniform float scale;			    /* 1 / (atmosphereRadius - globeRadius) */
            uniform float scaleDepth;		    /* The scale depth (i.e. the altitude at which the atmosphere's average density is found) */
            uniform float scaleOverScaleDepth;	/* fScale / fScaleDepth */

            attribute vec4 vertexPoint;
            attribute vec2 vertexTexCoord;

            varying vec3 primaryColor;
            varying vec3 secondaryColor;
            varying vec3 direction;
            varying vec2 texCoord;
            varying vec3 worldPos;
            varying float viewDepth;

            float scaleFunc(float cos) {
                float x = 1.0 - cos;
                return scaleDepth * exp(-0.00287 + x*(0.459 + x*(3.83 + x*(-6.80 + x*5.25))));
            }

            void main() {
                /* Get the ray from the camera to the vertex and its length (which is the far point of the ray passing through the
                atmosphere) */
                vec3 point = vertexPoint.xyz + vertexOrigin;
                vec3 ray = point - eyePoint;
                float far = length(ray);
                ray /= far;

                vec3 start;
                if (eyeMagnitude < atmosphereRadius) {
                    start = eyePoint;
                } else {
                    /* Calculate the closest intersection of the ray with the outer atmosphere (which is the near point of the ray
                    passing through the atmosphere) */
                    float B = 2.0 * dot(eyePoint, ray);
                    float C = eyeMagnitude2 - atmosphereRadius2;
                    float det = max(0.0, B*B - 4.0 * C);
                    float near = 0.5 * (-B - sqrt(det));

                    /* Calculate the ray's starting point, then calculate its scattering offset */
                    start = eyePoint + ray * near;
                    far -= near;
                }

                float depth = exp((globeRadius - atmosphereRadius) / scaleDepth);
                float eyeAngle = dot(-ray, point) / length(point);
                float lightAngle = dot(lightDirection, point) / length(point);

                /* Cap eyeScale to limit excessive atmospheric thickness for near-horizontal rays.
                   (when the camera is close to the ground and look at the horizon)
                   Only eyeScale is capped: lightScale is untouched so the day/night terminator
                   and night-side darkening remain physically correct. */
                float eyeScale = min(scaleFunc(max(eyeAngle, 0.0)), 2.0 * scaleDepth);

                float lightScale = scaleFunc(lightAngle);
                float eyeOffset = depth*eyeScale;
                float temp = (lightScale + eyeScale);

                /* Initialize the scattering loop variables */
                float sampleLength = far / SAMPLES;
                float scaledLength = sampleLength * scale;
                vec3 sampleRay = ray * sampleLength;
                vec3 samplePoint = start + sampleRay * 0.5;

                /* Now loop through the sample rays */
                vec3 frontColor = vec3(0.0, 0.0, 0.0);
                vec3 attenuate = vec3(0.0, 0.0, 0.0);
                for(int i=0; i<SAMPLE_COUNT; i++)
                {
                    float height = length(samplePoint);
                    float depth = exp(scaleOverScaleDepth * (globeRadius - height));

                    // Clamp scatter to 0 to prevent negative values (non-physical brightening) which cause absurd values (negative lightning)
                    // No upper clamp: the night side needs large scatter values to go fully dark.
                    float scatter = max(0.0, depth*temp - eyeOffset);

                    attenuate = exp(-scatter * (invWavelength * Kr4PI + Km4PI));
                    frontColor += attenuate * (depth * scaledLength);
                    samplePoint += sampleRay;
                }

                primaryColor = frontColor * (invWavelength * KrESun + KmESun);
                secondaryColor = attenuate; /* Calculate the attenuation factor for the ground */

                /* Transform the vertex point by the modelview-projection matrix */
                gl_Position = mvpMatrix * vertexPoint;

                worldPos = point;
                viewDepth = gl_Position.w;

                if (fragMode == FRAGMODE_PRIMARY_TEX_BLEND) {
                    /* Transform the vertex texture coordinate by the tex coord matrix */
                    texCoord = (texCoordMatrix * vec3(vertexTexCoord, 1.0)).st;
                }
            }
        """.trimIndent(),
        """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            precision mediump int; /* fragMode is used in both shaders, so we must use a common precision */
            #elif defined(GL_ES)
            precision mediump float;
            precision mediump int;
            #endif

            const int FRAGMODE_PRIMARY = 1;
            const int FRAGMODE_SECONDARY = 2;
            const int FRAGMODE_PRIMARY_TEX_BLEND = 3;

            uniform int fragMode;
            uniform sampler2D texSampler;

            varying vec3 primaryColor;
            varying vec3 secondaryColor;
            varying vec2 texCoord;
            varying vec3 worldPos;
            varying float viewDepth;

            ${ShadowReceiverGlsl.FRAGMENT_DECLARATIONS}

            void main () {
                if (fragMode == FRAGMODE_PRIMARY) {
                    gl_FragColor = vec4(primaryColor, 1.0);
                } else if (fragMode == FRAGMODE_SECONDARY) {
                    /* SECONDARY uses [GL_DST_COLOR, GL_ZERO] blend - folding shadow visibility
                       into [secondaryColor] darkens shadowed terrain via the same multiply. */
                    gl_FragColor = vec4(secondaryColor * computeShadowVisibility(worldPos, viewDepth), 1.0);
                } else if (fragMode == FRAGMODE_PRIMARY_TEX_BLEND) {
                    vec4 texColor = texture2D(texSampler, texCoord);
                    gl_FragColor = vec4(primaryColor + texColor.rgb * (1.0 - secondaryColor), 1.0);
                } else {
                    gl_FragColor = vec4(1.0); /* return opaque white fragments if fragMode is unrecognized */
                }
            }
        """.trimIndent()
    )
    override val attribBindings = arrayOf("vertexPoint")

    private var applyShadowId = KglUniformLocation.NONE
    private var useMSMId = KglUniformLocation.NONE
    private var ambientShadowId = KglUniformLocation.NONE
    private val shadowMapIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val cascadeFarDepthIds = arrayOf(KglUniformLocation.NONE, KglUniformLocation.NONE, KglUniformLocation.NONE)
    private val lightProjectionViewArray = FloatArray(16)

    override fun initProgram(dc: DrawContext) {
        super.initProgram(dc)
        applyShadowId = gl.getUniformLocation(program, "applyShadow")
        gl.uniform1i(applyShadowId, 0)
        useMSMId = gl.getUniformLocation(program, "useMSM")
        gl.uniform1i(useMSMId, 0)
        ambientShadowId = gl.getUniformLocation(program, "ambientShadow")
        gl.uniform1f(ambientShadowId, 0.4f)
        for (i in shadowMapIds.indices) {
            shadowMapIds[i] = gl.getUniformLocation(program, "shadowMap$i")
            gl.uniform1i(shadowMapIds[i], 1 + i)
            lightProjectionViewIds[i] = gl.getUniformLocation(program, "lightProjectionView$i")
            cascadeFarDepthIds[i] = gl.getUniformLocation(program, "cascadeFarDepth$i")
            gl.uniform1f(cascadeFarDepthIds[i], 0f)
        }
    }

    override var shadowUploadStamp: Long = -1L

    override fun loadShadowDisabled() {
        gl.uniform1i(applyShadowId, 0)
    }

    override fun loadShadowEnabled(
        ambientShadow: Float,
        lightProjectionView0: Matrix4,
        lightProjectionView1: Matrix4,
        lightProjectionView2: Matrix4,
        cascadeFarDepth0: Float,
        cascadeFarDepth1: Float,
        cascadeFarDepth2: Float,
        useMSM: Boolean,
    ) {
        gl.uniform1i(applyShadowId, 1)
        gl.uniform1i(useMSMId, if (useMSM) 1 else 0)
        gl.uniform1f(ambientShadowId, ambientShadow)
        lightProjectionView0.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[0], 1, false, lightProjectionViewArray, 0)
        lightProjectionView1.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[1], 1, false, lightProjectionViewArray, 0)
        lightProjectionView2.transposeToArray(lightProjectionViewArray, 0)
        gl.uniformMatrix4fv(lightProjectionViewIds[2], 1, false, lightProjectionViewArray, 0)
        gl.uniform1f(cascadeFarDepthIds[0], cascadeFarDepth0)
        gl.uniform1f(cascadeFarDepthIds[1], cascadeFarDepth1)
        gl.uniform1f(cascadeFarDepthIds[2], cascadeFarDepth2)
    }

    companion object {
        val KEY = GroundProgram::class
    }
}
