package earth.worldwind.draw

import earth.worldwind.geom.Sector
import earth.worldwind.geom.Vec3
import earth.worldwind.globe.Globe

interface DrawableTerrain: Drawable {
    val offset: Globe.Offset
    val sector: Sector
    val vertexOrigin: Vec3
    fun useVertexPointAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun useVertexHeightsAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun useVertexTexCoordAttrib(dc: DrawContext, attribLocation: Int): Boolean
    fun drawLines(dc: DrawContext): Boolean
    fun drawTriangles(dc: DrawContext): Boolean
}