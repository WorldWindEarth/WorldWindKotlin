package earth.worldwind.layer.sightline

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.GL_COLOR_ATTACHMENT0
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE5
import earth.worldwind.util.kgl.GL_TEXTURE6
import earth.worldwind.util.kgl.GL_TEXTURE_CUBE_MAP
import earth.worldwind.util.kgl.KglTexture

/**
 * Contract for shader programs that splice in [SightlineReceiverGlsl]. Identity-keyed cache:
 * [earth.worldwind.draw.DrawableSightline] allocates a fresh [SightlineState] every frame, so receivers compare
 * the new state instance against the last one they uploaded and skip the work when they match
 * (covers the within-frame "second program-bind sees the same state" case).
 */
interface SightlineReceiverProgram {
    fun loadSightlineEnabled(
        omnidirectional: Boolean,
        sightlineMv: Matrix4,
        cubeMapProjection: Matrix4,
        sightlineLocal: Matrix4,
        range: Float,
        visibleColor: Color,
        occludedColor: Color,
    )
    fun loadSightlineDisabled()
    var lastSightlineState: SightlineState?
}

// Scratch for the camera-relative sightline matrix composition. GL thread only.
private val sightlineMvScratch = Matrix4()

/**
 * Binds the moments texture(s) on units 5-6 and uploads sightline matrices into [program].
 * Skips the work when there's no active sightline this frame (or in pick mode), and clears
 * `applySightline` so receivers fall through to "no tint".
 *
 * Receiver programs feed **camera-relative** positions into `emitSightlineVaryings` (the same
 * varying that drives the shadow receiver — see
 * [earth.worldwind.layer.shadow.ShadowReceiverGlsl] for why raw ECEF float32 positions are
 * unusable), so the world → sightline matrices are re-based against the eye point here, in
 * double precision, before upload.
 */
fun DrawContext.applySightlineReceiverUniforms(program: SightlineReceiverProgram, applySightline: Boolean = true) {
    val state = sightlineState
    if (!applySightline || isPickMode || state == null) {
        program.loadSightlineDisabled()
        program.lastSightlineState = null
        // Bind benign 2D + cube textures so the never-sampled unit-5/6 samplers don't trip macOS's
        // empty-unit validator. Deduped — bound once per frame.
        if (!sightlineBenignBound) {
            activeTextureUnit(GL_TEXTURE5)
            defaultTexture.bindTexture(this)
            activeTextureUnit(GL_TEXTURE6)
            defaultCubeTexture.bindTexture(this)
            activeTextureUnit(GL_TEXTURE0)
            sightlineBenignBound = true
        }
        lastSightlineTextureBind = null
        return
    }
    if (lastSightlineTextureBind !== state) {
        sightlineBenignBound = false
        if (state.omnidirectional) {
            // Cube path: bind cube moments at unit 6, ensure unit 5 has a benign 2D bind so
            // the unused sampler2D doesn't read undefined.
            activeTextureUnit(GL_TEXTURE6)
            momentsCubeMapTexture.bindTexture(this)
            activeTextureUnit(GL_TEXTURE5)
            defaultTexture.bindTexture(this)
        } else {
            // Directional path: bind 2D moments at unit 5, clear cube binding at unit 6 so
            // the unused samplerCube doesn't form a feedback loop with the next depth pass.
            activeTextureUnit(GL_TEXTURE5)
            momentsFramebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0).bindTexture(this)
            activeTextureUnit(GL_TEXTURE6)
            gl.bindTexture(GL_TEXTURE_CUBE_MAP, KglTexture.NONE)
        }
        activeTextureUnit(GL_TEXTURE0)
        lastSightlineTextureBind = state
    }
    if (program.lastSightlineState !== state) {
        // Fold the eye translation into the world -> sightline matrix so camera-relative
        // receiver positions resolve to the same sightline-eye coordinates as world ones.
        sightlineMvScratch.copy(state.sightlineView).multiplyByTranslation(eyePoint.x, eyePoint.y, eyePoint.z)
        program.loadSightlineEnabled(
            state.omnidirectional,
            sightlineMvScratch,
            state.cubeMapProjection,
            sightlineMvScratch,
            state.range,
            state.visibleColor,
            state.occludedColor,
        )
        program.lastSightlineState = state
    }
}
