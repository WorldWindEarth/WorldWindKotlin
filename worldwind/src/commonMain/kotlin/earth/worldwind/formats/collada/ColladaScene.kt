package earth.worldwind.formats.collada

import earth.worldwind.PickedObject
import earth.worldwind.draw.DrawableCollada
import earth.worldwind.geom.*
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Angle.Companion.radians
import earth.worldwind.render.AbstractRenderable
import earth.worldwind.render.RenderContext
import earth.worldwind.render.buffer.BufferObject
import earth.worldwind.render.image.ImageOptions
import earth.worldwind.render.image.ImageSource
import earth.worldwind.render.image.WrapMode
import earth.worldwind.render.program.BasicTextureProgram
import earth.worldwind.shape.IRayIntersectable
import earth.worldwind.shape.Intersection
import earth.worldwind.shape.RayIntersector
import earth.worldwind.util.NumericArray
import earth.worldwind.util.kgl.*
import kotlin.math.atan2
import kotlin.math.sqrt

class ColladaScene(
    position: Position,
    var dirPath: String,
    sceneCatalog: ColladaSceneCatalog,
    val unitScale: Double = 1.0
) : AbstractRenderable(), IRayIntersectable {
    var position: Position = Position(position.latitude, position.longitude, position.altitude)
        set(value) { field.copy(value); invalidate() }
    var altitudeMode = AltitudeMode.ABSOLUTE
        set(value) { field = value; invalidate() }
    var xRotation = 0.0
        set(value) { field = value; invalidate() }
    var yRotation = 0.0
        set(value) { field = value; invalidate() }
    var zRotation = 0.0
        set(value) { field = value; invalidate() }
    var xTranslation = 0.0
        set(value) { field = value; invalidate() }
    var yTranslation = 0.0
        set(value) { field = value; invalidate() }
    var zTranslation = 0.0
        set(value) { field = value; invalidate() }
    var scale = 1.0
        set(value) { field = value; invalidate() }
    var doubleSided = false
    var computedNormals = false
        set(value) { field = value; normalsRewritten = false; invalidate() }
    var localTransforms = true
        set(value) {
            if (field == value) return
            field = value
            localBoundingRadius = -1.0 // depends on whether nodeWorldMatrix is applied
        }
    var useTexturePaths = true
    var nodesToHide = setOf<String>()
    var hideNodes = false
    var imageSourceFactory: ((String) -> ImageSource?)? = null

    private val imageSourceCache = mutableMapOf<String, ImageSource?>()
    private val nodes: List<ColladaNode> = sceneCatalog.children
    private val meshes: Map<String, ColladaMesh> = sceneCatalog.meshes
    private val materials: Map<String, ColladaMaterial> = sceneCatalog.materials
    private val images: Map<String, ColladaImage> = sceneCatalog.images

    private val entities = mutableListOf<Entity>()
    private val placePoint = Vec3()
    private val transformationMatrix = Matrix4()
    private val normalTransformMatrix = Matrix4()
    private val vboKey = Any()
    private val iboKey = Any()
    private var bufferVersion = 0
    private var transformValid = false
    private var normalsRewritten = false
    private var cachedTransformedPoints: List<List<Vec3>>? = null
    // Local-space radius covering every vertex after node.worldMatrix. World radius adds
    // the user xyz-translation and scales by scale × unitScale. Invalidated when normals
    // are recomputed (rewriteBufferNormals expands indexed meshes) or localTransforms toggles.
    private var localBoundingRadius = -1.0
    private val boundingSphere = BoundingSphere()
    private val scratchVec = Vec3()

    init { flattenModel() }

    private class Entity(
        val mesh: ColladaParsedMesh,
        val material: ColladaMaterial?,
        val node: ColladaNode,
        val imageKey: String?
    )

    private fun flattenModel() {
        for (node in nodes) flattenNode(node)
        entities.sortBy { it.imageKey ?: "" }
    }

    private fun flattenNode(node: ColladaNode) {
        if (node.meshKey.isNotEmpty()) {
            val mesh = meshes[node.meshKey] ?: return
            for (parsedMesh in mesh.parsedMeshes) {
                val materialKey = node.materials.entries.firstOrNull { it.value == parsedMesh.material }?.key
                val material = materials[materialKey]
                val imageKey = if (material != null && parsedMesh.uvs != null) {
                    material.propertyMap["diffuse"] ?: material.propertyMap["reflective"]
                } else null
                entities.add(Entity(parsedMesh, material, node, imageKey))
            }
        }
        for (child in node.children) flattenNode(child)
    }

    private fun invalidate() {
        transformValid = false
        cachedTransformedPoints = null
    }

    override fun doRender(rc: RenderContext) {
        rc.geographicToCartesian(position.latitude, position.longitude, position.altitude, altitudeMode, placePoint)

        if (computedNormals && !normalsRewritten) {
            for (entity in entities) rewriteBufferNormals(entity.mesh)
            normalsRewritten = true
            bufferVersion++
            localBoundingRadius = -1.0 // vertex buffers were rewritten
        }

        // Bounding-sphere cull, not centerpoint cull: in pick mode the frustum is the pick rect
        // (typically 3x3 px), so a model's centerpoint usually falls outside even when its body
        // overlaps the pick.
        if (localBoundingRadius < 0) computeLocalBoundingRadius()
        val totalScale = scale * unitScale
        val translationOffset = sqrt(xTranslation * xTranslation + yTranslation * yTranslation + zTranslation * zTranslation)
        boundingSphere.center.copy(placePoint)
        boundingSphere.radius = ((localBoundingRadius + translationOffset) * totalScale).coerceAtLeast(1.0)
        if (!boundingSphere.intersectsFrustum(rc.frustum)) return

        val distanceSq = rc.cameraPoint.distanceToSquared(placePoint)

        if (!transformValid) {
            buildTransformationMatrix(rc)
            transformValid = true
        }

        val program = rc.getShaderProgram(BasicTextureProgram.KEY) { BasicTextureProgram() }

        // --- Compute VBO layout ---
        var vertexFloatOffset = 0
        val entityVertexOffsets = IntArray(entities.size)
        for (i in entities.indices) {
            entityVertexOffsets[i] = vertexFloatOffset
            vertexFloatOffset += entities[i].mesh.vertices.size
        }

        val uvSectionStart = vertexFloatOffset
        var uvFloatOffset = 0
        val entityUvOffsets = IntArray(entities.size) { -1 }
        for (i in entities.indices) {
            val e = entities[i]
            if (e.imageKey != null && e.mesh.uvs != null) {
                entityUvOffsets[i] = uvSectionStart + uvFloatOffset
                uvFloatOffset += e.mesh.uvs!!.size
            }
        }

        val normalSectionStart = uvSectionStart + uvFloatOffset
        var normalFloatOffset = 0
        val entityNormalOffsets = IntArray(entities.size) { -1 }
        for (i in entities.indices) {
            val n = entities[i].mesh.normals
            if (n != null && n.isNotEmpty()) {
                entityNormalOffsets[i] = normalSectionStart + normalFloatOffset
                normalFloatOffset += n.size
            }
        }
        val totalFloats = normalSectionStart + normalFloatOffset

        // --- Compute IBO layout ---
        var is32Bit = false
        for (e in entities) if (e.mesh.is32BitIndices) { is32Bit = true; break }
        val indexSize = if (is32Bit) 4 else 2
        var indexByteOffset = 0
        val entityIndexOffsets = IntArray(entities.size) { -1 }
        for (i in entities.indices) {
            val e = entities[i]
            if (e.mesh.indexedRendering) {
                entityIndexOffsets[i] = indexByteOffset
                val cnt = e.mesh.indices?.size ?: (e.mesh.indicesShort?.size ?: 0)
                indexByteOffset += cnt * indexSize
            }
        }

        // --- Upload VBO ---
        val vbo = rc.getBufferObject(vboKey) { BufferObject(GL_ARRAY_BUFFER, 0) }
        rc.offerGLBufferUpload(vboKey, bufferVersion) {
            val data = FloatArray(totalFloats)
            for (i in entities.indices) entities[i].mesh.vertices.copyInto(data, entityVertexOffsets[i])
            for (i in entities.indices) if (entityUvOffsets[i] >= 0) entities[i].mesh.uvs!!.copyInto(data, entityUvOffsets[i])
            for (i in entities.indices) if (entityNormalOffsets[i] >= 0) entities[i].mesh.normals!!.copyInto(data, entityNormalOffsets[i])
            NumericArray.Floats(data)
        }

        // --- Upload IBO ---
        var iboRef: BufferObject? = null
        if (indexByteOffset > 0) {
            iboRef = rc.getBufferObject(iboKey) { BufferObject(GL_ELEMENT_ARRAY_BUFFER, 0) }
            rc.offerGLBufferUpload(iboKey, bufferVersion) {
                if (is32Bit) {
                    val idxData = IntArray(entities.sumOf { it.mesh.indices?.size ?: it.mesh.indicesShort?.size ?: 0 })
                    var pos = 0
                    for (e in entities) when {
                        e.mesh.indices != null -> { e.mesh.indices!!.copyInto(idxData, pos); pos += e.mesh.indices!!.size }
                        e.mesh.indicesShort != null -> for (v in e.mesh.indicesShort!!) { idxData[pos++] = v.toInt() and 0xFFFF }
                    }
                    NumericArray.Ints(idxData)
                } else {
                    val idxData = ShortArray(entities.sumOf { it.mesh.indicesShort?.size ?: 0 })
                    var pos = 0
                    for (e in entities) e.mesh.indicesShort?.let { it.copyInto(idxData, pos); pos += it.size }
                    NumericArray.Shorts(idxData)
                }
            }
        }

        // --- Build drawable ---
        val drawable = DrawableCollada()
        drawable.program = program
        drawable.vertexBuffer = vbo
        drawable.indexBuffer = iboRef
        drawable.doubleSided = doubleSided
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
            if (hideNodes && entity.node.id in nodesToHide) continue

            val es = DrawableCollada.EntityDrawState()
            es.vertexByteOffset = entityVertexOffsets[i] * 4
            es.indexed = entity.mesh.indexedRendering

            if (entityUvOffsets[i] >= 0) {
                es.hasUvs = true
                es.uvByteOffset = entityUvOffsets[i] * 4
            }
            if (entityNormalOffsets[i] >= 0) {
                es.hasNormals = true
                es.normalByteOffset = entityNormalOffsets[i] * 4
            }

            if (entity.mesh.indexedRendering && entityIndexOffsets[i] >= 0) {
                es.indexByteOffset = entityIndexOffsets[i]
                es.indexCount = entity.mesh.indices?.size ?: (entity.mesh.indicesShort?.size ?: 0)
                es.indexType = if (is32Bit) GL_UNSIGNED_INT else GL_UNSIGNED_SHORT
            } else {
                es.vertexCount = entity.mesh.vertices.size / 3
            }

            // Material color
            val material = entity.material
            if (material != null) {
                val colorData = if (material.techniqueType == "constant") material.dataMap["reflective"]
                    else material.dataMap["diffuse"]
                if (colorData != null && colorData.size >= 3) {
                    val alpha = if (colorData.size >= 4) colorData[3] else 1f
                    es.color.set(colorData[0], colorData[1], colorData[2], alpha)
                    es.opacity = alpha
                }
            }

            // Texture
            if (es.hasUvs && entity.imageKey != null) {
                val image = images[entity.imageKey]
                if (image != null) {
                    val imagePath = if (useTexturePaths) image.path else image.fileName
                    val fullPath = dirPath + imagePath
                    val wrapMode = if (entity.mesh.clamp) WrapMode.CLAMP else WrapMode.REPEAT
                    val imageSource = imageSourceFactory?.let { factory ->
                        imageSourceCache.getOrPut(fullPath) { factory.invoke(fullPath) }
                    } ?: ImageSource.fromUrlString(fullPath)
                    es.texture = rc.getTexture(imageSource, ImageOptions().apply { this.wrapMode = wrapMode })
                }
            }

            es.nodeWorldMatrix.copy(entity.node.worldMatrix)
            es.nodeNormalMatrix.copy(entity.node.normalMatrix)
            es.useLocalTransforms = localTransforms
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
            val vtx = entity.mesh.vertices
            val mat = entity.node.worldMatrix
            var i = 0
            while (i + 2 < vtx.size) {
                scratchVec.set(vtx[i].toDouble(), vtx[i + 1].toDouble(), vtx[i + 2].toDouble())
                if (localTransforms) scratchVec.multiplyByMatrix(mat)
                val sq = scratchVec.x * scratchVec.x + scratchVec.y * scratchVec.y + scratchVec.z * scratchVec.z
                if (sq > maxSquared) maxSquared = sq
                i += 3
            }
        }
        localBoundingRadius = sqrt(maxSquared)
    }

    private fun buildTransformationMatrix(rc: RenderContext) {
        rc.globe.geographicToCartesianTransform(position.latitude, position.longitude, position.altitude, transformationMatrix)
        transformationMatrix.multiplyByRotation(1.0, 0.0, 0.0, xRotation.degrees)
        transformationMatrix.multiplyByRotation(0.0, 1.0, 0.0, yRotation.degrees)
        transformationMatrix.multiplyByRotation(0.0, 0.0, 1.0, zRotation.degrees)
        val totalScale = scale * unitScale
        transformationMatrix.multiplyByScale(totalScale, totalScale, totalScale)
        transformationMatrix.multiplyByTranslation(xTranslation, yTranslation, zTranslation)

        val rx = atan2(transformationMatrix.m[6], transformationMatrix.m[10]).radians
        val cosY = sqrt(transformationMatrix.m[6] * transformationMatrix.m[6] + transformationMatrix.m[10] * transformationMatrix.m[10])
        val ry = atan2(-transformationMatrix.m[2], cosY).radians
        val rz = atan2(transformationMatrix.m[1], transformationMatrix.m[0]).radians
        normalTransformMatrix.setToIdentity()
        normalTransformMatrix.multiplyByRotation(-1.0, 0.0, 0.0, rx)
        normalTransformMatrix.multiplyByRotation(0.0, -1.0, 0.0, ry)
        normalTransformMatrix.multiplyByRotation(0.0, 0.0, -1.0, rz)
    }

    override fun rayIntersections(ray: Line, globe: earth.worldwind.globe.Globe): Array<Intersection> {
        val transformedPts = cachedTransformedPoints ?: buildTransformedPoints().also { cachedTransformedPoints = it }
        val positions = RayIntersector.computeIntersections(globe, ray, transformedPts, ray.origin)
        return positions.map { pos ->
            val pt = Vec3()
            globe.geographicToCartesian(pos.latitude, pos.longitude, pos.altitude, pt)
            Intersection(pos, ray.origin.distanceTo(pt))
        }.toTypedArray()
    }

    private fun buildTransformedPoints(): List<List<Vec3>> = entities.map { entity ->
        val vtxs = entity.mesh.vertices
        val pts = mutableListOf<Vec3>()
        if (entity.mesh.indexedRendering) {
            val idxs = entity.mesh.indices
                ?: entity.mesh.indicesShort?.let { s -> IntArray(s.size) { s[it].toInt() and 0xFFFF } }
                ?: IntArray(0)
            var i = 0
            while (i + 2 < idxs.size) {
                for (j in 0..2) pts.add(transformPoint(vtxs, idxs.getOrElse(i + j) { 0 } * 3, entity.node))
                i += 3
            }
        } else {
            var i = 0
            while (i + 2 < vtxs.size) { pts.add(transformPoint(vtxs, i, entity.node)); i += 3 }
        }
        pts
    }

    private fun transformPoint(vtxs: FloatArray, off: Int, node: ColladaNode): Vec3 {
        val v = Vec3(vtxs.getOrElse(off) { 0f }.toDouble(), vtxs.getOrElse(off + 1) { 0f }.toDouble(), vtxs.getOrElse(off + 2) { 0f }.toDouble())
        if (localTransforms) v.multiplyByMatrix(node.worldMatrix)
        v.multiplyByMatrix(transformationMatrix)
        return v
    }

    private fun rewriteBufferNormals(mesh: ColladaParsedMesh) {
        if (mesh.normalsComputed || !mesh.indexedRendering) { mesh.normalsComputed = true; return }
        val vtxs = mesh.vertices
        val idxs = mesh.indices
            ?: mesh.indicesShort?.let { s -> IntArray(s.size) { s[it].toInt() and 0xFFFF } }
            ?: return
        val hasUvs = mesh.uvs?.isNotEmpty() == true
        val newVtxs = FloatArray(idxs.size * 3)
        val newNormals = FloatArray(idxs.size * 3)
        val newUvs = if (hasUvs) FloatArray(idxs.size * 2) else null

        var i = 0
        while (i + 2 < idxs.size) {
            val v0 = idxs[i] * 3; val v1 = idxs[i + 1] * 3; val v2 = idxs[i + 2] * 3
            val e1x = vtxs.getOrElse(v1) { 0f } - vtxs.getOrElse(v0) { 0f }
            val e1y = vtxs.getOrElse(v1 + 1) { 0f } - vtxs.getOrElse(v0 + 1) { 0f }
            val e1z = vtxs.getOrElse(v1 + 2) { 0f } - vtxs.getOrElse(v0 + 2) { 0f }
            val e2x = vtxs.getOrElse(v2) { 0f } - vtxs.getOrElse(v0) { 0f }
            val e2y = vtxs.getOrElse(v2 + 1) { 0f } - vtxs.getOrElse(v0 + 1) { 0f }
            val e2z = vtxs.getOrElse(v2 + 2) { 0f } - vtxs.getOrElse(v0 + 2) { 0f }
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val len = sqrt((nx * nx + ny * ny + nz * nz).toDouble()).toFloat().coerceAtLeast(1e-10f)
            nx /= len; ny /= len; nz /= len
            for (j in 0..2) {
                val ni = (i + j) * 3; val si = idxs[i + j] * 3
                newVtxs[ni] = vtxs.getOrElse(si) { 0f }
                newVtxs[ni + 1] = vtxs.getOrElse(si + 1) { 0f }
                newVtxs[ni + 2] = vtxs.getOrElse(si + 2) { 0f }
                newNormals[ni] = nx; newNormals[ni + 1] = ny; newNormals[ni + 2] = nz
                if (hasUvs) {
                    val ui = (i + j) * 2; val su = idxs[i + j] * 2
                    newUvs!![ui] = mesh.uvs!!.getOrElse(su) { 0f }
                    newUvs[ui + 1] = mesh.uvs!!.getOrElse(su + 1) { 0f }
                }
            }
            i += 3
        }
        mesh.vertices = newVtxs
        mesh.normals = newNormals
        if (hasUvs) mesh.uvs = newUvs
        mesh.indices = null; mesh.indicesShort = null
        mesh.normalsComputed = true
    }
}

