package earth.worldwind.draw

import earth.worldwind.geom.Matrix4
import earth.worldwind.geom.Vec3
import earth.worldwind.render.Texture
import earth.worldwind.render.program.SurfaceOverlayProgram
import earth.worldwind.util.kgl.GL_ALWAYS
import earth.worldwind.util.kgl.GL_KEEP
import earth.worldwind.util.kgl.GL_STENCIL_TEST
import earth.worldwind.util.kgl.GL_TEXTURE0

/**
 * Ground surface that can receive draped surface-shape textures (e.g. a 3D-Tile mesh). After a
 * surface-shape drawable composites its per-terrain-tile texture onto the terrain, it re-draws
 * registered overlay surfaces through [SurfaceOverlayProgram], which projects the same texture
 * with per-fragment sector-local UVs — the raster-overlay draping pattern.
 */
interface GroundOverlaySurface {
    /** World bounding sphere center of this surface; `null` opts out of overlay drawing. */
    val overlayCenter: Vec3?
    /** World bounding sphere radius around [overlayCenter]. */
    val overlayRadius: Double
    /** Re-rasterize this surface's geometry with [program] active and the shape texture bound. */
    fun drawOverlay(dc: DrawContext, program: SurfaceOverlayProgram, uvMatrix: Matrix4)
}

/**
 * Drapes [texture] (a terrain tile's composited surface-shape texture) onto every registered
 * [GroundOverlaySurface] intersecting [terrain]'s bounding sphere. Callers invoke this right
 * after compositing the texture onto the terrain; the previously bound program is restored
 * afterwards for callers that rely on their program staying current between terrain tiles.
 */
internal fun drawSurfaceOverlayOnMeshes(dc: DrawContext, terrain: DrawableTerrain, texture: Texture) {
    val overlays = dc.groundOverlaySurfaces
    if (overlays.isEmpty()) return
    val uvMatrix = terrain.overlayUvMatrix ?: return
    val program = dc.surfaceOverlayProgram ?: return
    val terrainCenter = terrain.vertexOrigin
    val terrainRadius = terrain.boundingSphereRadius
    val previousProgram = dc.currentProgram
    var active = false
    try {
        for (idx in overlays.indices) {
            val overlay = overlays[idx]
            val center = overlay.overlayCenter ?: continue
            if (terrainRadius > 0.0) {
                val reach = terrainRadius + overlay.overlayRadius
                if (center.distanceToSquared(terrainCenter) > reach * reach) continue
            }
            if (!active) {
                if (!program.useProgram(dc)) return
                active = true
                // The overlay consumes only attrib 0; callers keep 1-3 enabled with stale pointers.
                dc.gl.disableVertexAttribArray(1)
                dc.gl.disableVertexAttribArray(2)
                dc.gl.disableVertexAttribArray(3)
                dc.activeTextureUnit(GL_TEXTURE0)
                if (!texture.bindTexture(dc)) return
                // Mesh depth is already in place from the color pass; the decal must not rewrite it.
                dc.gl.depthMask(false)
                // Shared read-only stencil state; each mesh's drawOverlay sets only its stencilFunc.
                dc.gl.enable(GL_STENCIL_TEST)
                dc.gl.stencilOp(GL_KEEP, GL_KEEP, GL_KEEP)
                dc.gl.stencilMask(0x00)
            }
            overlay.drawOverlay(dc, program, uvMatrix)
        }
    } finally {
        if (active) {
            dc.gl.disable(GL_STENCIL_TEST)
            dc.gl.stencilFunc(GL_ALWAYS, 0, 0xFF)
            dc.gl.stencilMask(0xFF)
            dc.gl.depthMask(true)
            dc.gl.enableVertexAttribArray(1)
            dc.gl.enableVertexAttribArray(2)
            dc.gl.enableVertexAttribArray(3)
            // Unbind the texture to avoid a feedback loop on the next render-to-texture pass.
            dc.defaultTexture.bindTexture(dc)
            dc.useProgram(previousProgram)
        }
    }
}
