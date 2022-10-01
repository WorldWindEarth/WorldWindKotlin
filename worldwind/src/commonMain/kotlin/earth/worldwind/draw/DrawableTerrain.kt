package earth.worldwind.draw

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3

interface DrawableTerrain: Drawable {
    val sector: Sector
    val vertexOrigin: Vec3
    fun useVertexPointAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun useVertexTexCoordAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun drawLines(dc: DrawContext): Boolean
    fun drawTriangles(dc: DrawContext): Boolean
}