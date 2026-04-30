package earth.worldwind.draw

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe

interface DrawableTerrain: Drawable {
    val offset: Globe.Offset
    val sector: Sector
    val vertexOrigin: Vec3
    /**
     * World-space bounding sphere radius around [vertexOrigin] that conservatively contains
     * every vertex of this terrain tile (including peak elevation). Used by per-cascade cullers
     * (see [earth.worldwind.layer.shadow.ShadowState.CascadeState.intersectsSphere]) to skip
     * tiles that fall entirely outside a cascade's footprint. `0` opts out of culling.
     */
    val boundingSphereRadius: Double get() = 0.0
    fun useVertexPointAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun useVertexHeightsAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun useVertexTexCoordAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun drawLines(dc: DrawContext): Boolean
    fun drawTriangles(dc: DrawContext): Boolean
}