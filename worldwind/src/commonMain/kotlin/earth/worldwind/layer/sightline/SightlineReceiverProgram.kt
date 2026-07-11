package earth.worldwind.layer.sightline

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE5

/**
 * Contract for shader programs that splice in [SightlineReceiverGlsl]. Identity-keyed cache:
 * [earth.worldwind.draw.DrawableSightline] allocates a fresh [SightlineState] every frame, so receivers compare
 * the new state instance against the last one they uploaded and skip the work when they match
 * (covers the within-frame "second program-bind sees the same state" case).
 */
interface SightlineReceiverProgram {
    fun loadSightlineEnabled(state: SightlineState, localMatrix: Matrix4)
    fun loadSightlineDisabled()
    var lastSightlineState: SightlineState?
}

/**
 * Binds the sightline depth cube on unit 5 and uploads the sightline uniforms into [program].
 * Skips the work when there's no active sightline this frame (or in pick mode), and clears
 * `applySightline` so receivers fall through to "no tint".
 *
 * Receiver programs feed **camera-relative** positions into `emitSightlineVaryings` (the same
 * varying that drives the shadow receiver — see
 * [earth.worldwind.layer.shadow.ShadowReceiverGlsl] for why raw ECEF float32 positions are
 * unusable), so the world -> sightline-local matrix is re-based against the eye point here,
 * in double precision, before upload.
 */
fun DrawContext.applySightlineReceiverUniforms(program: SightlineReceiverProgram, applySightline: Boolean = true) {
    val state = sightlineState
    if (!applySightline || isPickMode || state == null) {
        program.loadSightlineDisabled()
        program.lastSightlineState = null
        // Bind a benign cube so the never-sampled unit-5 sampler doesn't trip macOS's
        // empty-unit validator - and, under hardware compare samplers, so WebGL2's draw-time
        // sampler-vs-format validation sees a compare-mode depth cube. Bound once per frame.
        if (!sightlineBenignBound) {
            activeTextureUnit(GL_TEXTURE5)
            (if (gl.hasShadowSamplers) nullShadowDepthCubeTexture else defaultCubeTexture).bindTexture(this)
            activeTextureUnit(GL_TEXTURE0)
            sightlineBenignBound = true
        }
        lastSightlineTextureBind = null
        return
    }
    if (lastSightlineTextureBind !== state) {
        sightlineBenignBound = false
        activeTextureUnit(GL_TEXTURE5)
        sightlineDepthCubeTexture.bindTexture(this)
        activeTextureUnit(GL_TEXTURE0)
        lastSightlineTextureBind = state
    }
    if (program.lastSightlineState !== state) {
        // Fold the eye translation into the world -> local matrix so camera-relative
        // receiver positions resolve to the same sightline-local coordinates as world ones.
        sightlineLocalScratch.copy(state.sightlineView).multiplyByTranslation(eyePoint.x, eyePoint.y, eyePoint.z)
        program.loadSightlineEnabled(state, sightlineLocalScratch)
        program.lastSightlineState = state
    }
}
