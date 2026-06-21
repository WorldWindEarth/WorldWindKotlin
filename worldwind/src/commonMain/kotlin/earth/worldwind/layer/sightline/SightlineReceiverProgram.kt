package earth.worldwind.layer.sightline

import earth.worldwind.draw.DrawContext
import earth.worldwind.geom.Matrix4
import earth.worldwind.render.Color
import earth.worldwind.util.kgl.GL_COLOR_ATTACHMENT0
import earth.worldwind.util.kgl.GL_TEXTURE0
import earth.worldwind.util.kgl.GL_TEXTURE4
import earth.worldwind.util.kgl.GL_TEXTURE5
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

/**
 * Binds the moments texture(s) on units 4-5 and uploads sightline matrices into [program].
 * Skips the work when there's no active sightline this frame (or in pick mode), and clears
 * `applySightline` so receivers fall through to "no tint".
 */
fun DrawContext.applySightlineReceiverUniforms(program: SightlineReceiverProgram, applySightline: Boolean = true) {
    val state = sightlineState
    if (!applySightline || isPickMode || state == null) {
        program.loadSightlineDisabled()
        program.lastSightlineState = null
        lastSightlineTextureBind = null
        return
    }
    if (lastSightlineTextureBind !== state) {
        if (state.omnidirectional) {
            // Cube path: bind cube moments at unit 5, ensure unit 4 has a benign 2D bind so
            // the unused sampler2D doesn't read undefined.
            activeTextureUnit(GL_TEXTURE5)
            momentsCubeMapTexture.bindTexture(this)
            activeTextureUnit(GL_TEXTURE4)
            defaultTexture.bindTexture(this)
        } else {
            // Directional path: bind 2D moments at unit 4, clear cube binding at unit 5 so
            // the unused samplerCube doesn't form a feedback loop with the next depth pass.
            activeTextureUnit(GL_TEXTURE4)
            momentsFramebuffer.getAttachedTexture(GL_COLOR_ATTACHMENT0).bindTexture(this)
            activeTextureUnit(GL_TEXTURE5)
            gl.bindTexture(GL_TEXTURE_CUBE_MAP, KglTexture.NONE)
        }
        activeTextureUnit(GL_TEXTURE0)
        lastSightlineTextureBind = state
    }
    if (program.lastSightlineState !== state) {
        program.loadSightlineEnabled(
            state.omnidirectional,
            state.sightlineView,
            state.cubeMapProjection,
            state.sightlineView,
            state.range,
            state.visibleColor,
            state.occludedColor,
        )
        program.lastSightlineState = state
    }
}
