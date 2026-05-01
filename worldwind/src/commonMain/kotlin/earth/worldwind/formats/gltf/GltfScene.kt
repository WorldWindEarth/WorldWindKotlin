package earth.worldwind.formats.gltf

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableCollada
import earth.worldwind.geom.*
import earth.worldwind.layer.shadow.ShadowMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.Color
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.program.BasicTextureProgram
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.*
import kotlin.math.atan2
import kotlin.math.sqrt

internal class GltfEntity(
    val vertices: FloatArray,
    val normals: FloatArray?,
    val indicesShort: ShortArray?,
    val indicesInt: IntArray?,
    val color: Color,
    val worldMatrix: Matrix4,
    val normalMatrix: Matrix4
)

class GltfScene internal constructor(
    position: Position,
    private val entities: List<GltfEntity>
) : AbstractRenderable() {
    var position: Position = Position(position)
        set(value) { field.copy(value); invalidate() }
    var altitudeMode = AltitudeMode.ABSOLUTE
        set(value) { field = value; invalidate() }
    var heading = 0.0
        set(value) { field = value; invalidate() }
    var pitch = 0.0
        set(value) { field = value; invalidate() }
    var roll = 0.0
        set(value) { field = value; invalidate() }
    var scale = 1.0
        set(value) { field = value; invalidate() }
    /**
     * Cast/receive selector for cascaded sun shadows. Default [ShadowMode.ENABLED] = scene
     * both casts and receives. Common opt-out: [ShadowMode.RECEIVE_ONLY] for HUD-style or
     * self-lit glTFs that look wrong projected onto the ground.
     */
    var shadowMode: ShadowMode = ShadowMode.ENABLED

    private val placePoint = Vec3()
    private val transformationMatrix = Matrix4()
    private val normalTransformMatrix = Matrix4()
    private val vboKey = Any()
    private val iboKey = Any()
    private val bufferVersion = 0
    private var transformValid = false
    // Local-space radius covering every vertex after worldMatrix; world radius = this × scale.
    // Rotations preserve magnitude, so heading/pitch/roll changes don't invalidate the cache.
    private var localBoundingRadius = -1.0
    private val boundingSphere = BoundingSphere()
    private val scratchVec = Vec3()

    private fun invalidate() { transformValid = false }

    override fun doRender(rc: RenderContext) {
        rc.geographicToCartesian(position.latitude, position.longitude, position.altitude, altitudeMode, placePoint)
        // Bounding-sphere cull, not centerpoint cull: in pick mode the frustum is the pick rect
        // (typically 3x3 px), so a model's centerpoint usually falls outside even when its body
        // overlaps the pick.
        if (localBoundingRadius < 0) computeLocalBoundingRadius()
        boundingSphere.center.copy(placePoint)
        boundingSphere.radius = (localBoundingRadius * scale).coerceAtLeast(1.0)
        if (!boundingSphere.intersectsFrustum(rc.frustum)) return

        val distanceSq = rc.cameraPoint.distanceToSquared(placePoint)

        if (!transformValid) {
            buildTransformationMatrix(rc)
            transformValid = true
        }

        val program = rc.getShaderProgram(BasicTextureProgram.KEY) { BasicTextureProgram() }

        // VBO layout: [vertices0][vertices1]...[normals0][normals1]...
        var vertexFloatOffset = 0
        val entityVertexOffsets = IntArray(entities.size)
        for (i in entities.indices) {
            entityVertexOffsets[i] = vertexFloatOffset
            vertexFloatOffset += entities[i].vertices.size
        }
        val normalSectionStart = vertexFloatOffset
        var normalFloatOffset = 0
        val entityNormalOffsets = IntArray(entities.size) { -1 }
        for (i in entities.indices) {
            val n = entities[i].normals
            if (n != null && n.isNotEmpty()) {
                entityNormalOffsets[i] = normalSectionStart + normalFloatOffset
                normalFloatOffset += n.size
            }
        }
        val totalFloats = normalSectionStart + normalFloatOffset

        // IBO layout
        val is32Bit = entities.any { it.indicesInt != null }
        val indexSize = if (is32Bit) 4 else 2
        var indexByteOffset = 0
        val entityIndexOffsets = IntArray(entities.size) { -1 }
        for (i in entities.indices) {
            val e = entities[i]
            if (e.indicesShort != null || e.indicesInt != null) {
                entityIndexOffsets[i] = indexByteOffset
                indexByteOffset += (e.indicesInt?.size ?: e.indicesShort?.size ?: 0) * indexSize
            }
        }

        val vbo = rc.getBufferObject(vboKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(vboKey, bufferVersion) {
            val data = FloatArray(totalFloats)
            for (i in entities.indices) entities[i].vertices.copyInto(data, entityVertexOffsets[i])
            for (i in entities.indices) if (entityNormalOffsets[i] >= 0) entities[i].normals!!.copyInto(data, entityNormalOffsets[i])
            NumericArray.Floats(data)
        }

        var iboRef: BufferObject? = null
        if (indexByteOffset > 0) {
            iboRef = rc.getBufferObject(iboKey) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0) }
            rc.offerGLBufferUpload(iboKey, bufferVersion) {
                if (is32Bit) {
                    val idxData = IntArray(entities.sumOf { it.indicesInt?.size ?: it.indicesShort?.size ?: 0 })
                    var pos = 0
                    for (e in entities) when {
                        e.indicesInt != null -> { e.indicesInt.copyInto(idxData, pos); pos += e.indicesInt.size }
                        e.indicesShort != null -> for (v in e.indicesShort) { idxData[pos++] = v.toInt() and 0xFFFF }
                    }
                    NumericArray.Ints(idxData)
                } else {
                    val idxData = ShortArray(entities.sumOf { it.indicesShort?.size ?: 0 })
                    var pos = 0
                    for (e in entities) e.indicesShort?.let { it.copyInto(idxData, pos); pos += it.size }
                    NumericArray.Shorts(idxData)
                }
            }
        }

        val drawable = DrawableCollada()
        drawable.program = program
        drawable.vertexBuffer = vbo
        drawable.indexBuffer = iboRef
        drawable.doubleSided = false
        drawable.shadowMode = shadowMode
        drawable.layerOpacity = rc.currentLayer.opacity
        drawable.transformationMatrix.copy(transformationMatrix)
        drawable.normalTransformMatrix.copy(normalTransformMatrix)
        drawable.boundingCenter.copy(boundingSphere.center)
        drawable.boundingRadius = boundingSphere.radius

        var pickedObjectId = 0
        if (rc.isPickMode) {
            pickedObjectId = rc.nextPickedObjectId()
            PickedObject.identifierToUniqueColor(pickedObjectId, drawable.pickColor)
        }

        for (i in entities.indices) {
            val entity = entities[i]
            val es = DrawableCollada.EntityDrawState()
            es.vertexByteOffset = entityVertexOffsets[i] * 4

            if (entityNormalOffsets[i] >= 0) {
                es.hasNormals = true
                es.normalByteOffset = entityNormalOffsets[i] * 4
            }

            if (entityIndexOffsets[i] >= 0) {
                es.indexed = true
                es.indexByteOffset = entityIndexOffsets[i]
                es.indexCount = entity.indicesInt?.size ?: entity.indicesShort?.size ?: 0
                es.indexType = if (is32Bit) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT
            } else {
                es.indexed = false
                es.vertexCount = entity.vertices.size / 3
            }

            es.color.copy(entity.color)
            es.opacity = entity.color.alpha

            es.nodeWorldMatrix.copy(entity.worldMatrix)
            es.nodeNormalMatrix.copy(entity.normalMatrix)
            es.useLocalTransforms = true
            drawable.entities.add(es)
        }

        val drawableCount = rc.drawableCount
        rc.offerShapeDrawable(drawable, distanceSq)
        if (rc.isPickMode && rc.drawableCount != drawableCount) {
            rc.offerPickedObject(PickedObject.fromRenderable(pickedObjectId, this, rc.currentLayer))
        }
    }

    private fun computeLocalBoundingRadius() {
        var maxSquared = 0.0
        for (entity in entities) {
            val v = entity.vertices
            var i = 0
            while (i + 2 < v.size) {
                scratchVec.set(v[i].toDouble(), v[i + 1].toDouble(), v[i + 2].toDouble())
                scratchVec.multiplyByMatrix(entity.worldMatrix)
                val sq = scratchVec.x * scratchVec.x + scratchVec.y * scratchVec.y + scratchVec.z * scratchVec.z
                if (sq > maxSquared) maxSquared = sq
                i += 3
            }
        }
        localBoundingRadius = sqrt(maxSquared)
    }

    private fun buildTransformationMatrix(rc: RenderContext) {
        rc.globe.geographicToCartesianTransform(position.latitude, position.longitude, position.altitude, transformationMatrix)
        transformationMatrix.multiplyByRotation(0.0, 0.0, 1.0, heading.degrees)
        transformationMatrix.multiplyByRotation(1.0, 0.0, 0.0, pitch.degrees)
        transformationMatrix.multiplyByRotation(0.0, 1.0, 0.0, roll.degrees)
        transformationMatrix.multiplyByScale(scale, scale, scale)

        val rx = atan2(transformationMatrix.m[6], transformationMatrix.m[10]).radians
        val cosY = sqrt(transformationMatrix.m[6] * transformationMatrix.m[6] + transformationMatrix.m[10] * transformationMatrix.m[10])
        val ry = atan2(-transformationMatrix.m[2], cosY).radians
        val rz = atan2(transformationMatrix.m[1], transformationMatrix.m[0]).radians
        normalTransformMatrix.setToIdentity()
        normalTransformMatrix.multiplyByRotation(-1.0, 0.0, 0.0, rx)
        normalTransformMatrix.multiplyByRotation(0.0, -1.0, 0.0, ry)
        normalTransformMatrix.multiplyByRotation(0.0, 0.0, -1.0, rz)
    }
}
