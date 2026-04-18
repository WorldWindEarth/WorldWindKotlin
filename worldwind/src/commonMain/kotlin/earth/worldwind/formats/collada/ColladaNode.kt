package earth.worldwind.formats.collada

import earth.worldwind.geom.Angle
import earth.worldwind.geom.Matrix4
import kotlin.math.atan2
import kotlin.math.sqrt

class ColladaNode private constructor() {
    val children = mutableListOf<ColladaNode>()
    val materials = mutableMapOf<String, String>()
    var meshKey: String = ""
    var id: String = ""
    var nodeName: String = ""
    var sid: String = ""
    val localMatrix = Matrix4()
    val worldMatrix = Matrix4()
    val normalMatrix = Matrix4()

    companion object {
        internal fun parse(element: XmlElement, iNodes: List<XmlElement>, parentWorldMatrix: Matrix4? = null): List<ColladaNode> {
            val template = ColladaNode()
            template.id = element.getAttribute("id") ?: ""
            template.sid = element.getAttribute("sid") ?: ""
            template.nodeName = element.getAttribute("name") ?: ""
            template.setNodeTransforms(element, parentWorldMatrix)

            val meshNodes = mutableListOf<ColladaNode>()
            var node: ColladaNode = template

            for (child in element.children) {
                when (child.name) {
                    "node" -> node.children.addAll(parse(child, iNodes, node.worldMatrix))
                    "instance_geometry" -> {
                        val newNode = ColladaNode()
                        newNode.id = template.id
                        newNode.nodeName = template.nodeName
                        newNode.sid = template.sid
                        newNode.localMatrix.copy(template.localMatrix)
                        newNode.worldMatrix.copy(template.worldMatrix)
                        newNode.normalMatrix.copy(template.normalMatrix)
                        if (node.meshKey.isNotEmpty()) meshNodes.add(node)
                        node = newNode
                        node.meshKey = child.getAttribute("url")?.removePrefix("#") ?: ""
                        for (mat in child.querySelectorAll("instance_material")) {
                            val target = mat.getAttribute("target")?.removePrefix("#") ?: continue
                            val symbol = mat.getAttribute("symbol") ?: continue
                            node.materials[target] = symbol
                        }
                        meshNodes.add(node)
                    }
                    "instance_node" -> {
                        val iNodeId = child.getAttribute("url")?.removePrefix("#") ?: continue
                        val iNode = iNodes.firstOrNull { it.getAttribute("id") == iNodeId }
                        if (iNode != null) node.children.addAll(parse(iNode, iNodes, node.worldMatrix))
                    }
                }
            }
            return meshNodes
        }
    }

    private fun setNodeTransforms(element: XmlElement, parentWorldMatrix: Matrix4?) {
        val parent = parentWorldMatrix ?: Matrix4()
        val tmp = Matrix4()

        for (child in element.children) {
            when (child.name) {
                "matrix" -> {
                    val values = ColladaUtils.bufferDataFloat32(child) ?: continue
                    if (values.size >= 16) {
                        for (i in 0 until 16) tmp.m[i] = values[i].toDouble()
                        localMatrix.multiplyByMatrix(tmp)
                    }
                }
                "rotate" -> {
                    val v = ColladaUtils.bufferDataFloat32(child) ?: continue
                    if (v.size >= 4) {
                        tmp.setToIdentity()
                        tmp.multiplyByRotation(v[0].toDouble(), v[1].toDouble(), v[2].toDouble(), Angle.fromDegrees(v[3].toDouble()))
                        localMatrix.multiplyByMatrix(tmp)
                    }
                }
                "translate" -> {
                    val v = ColladaUtils.bufferDataFloat32(child) ?: continue
                    if (v.size >= 3) {
                        tmp.setToIdentity()
                        tmp.multiplyByTranslation(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                        localMatrix.multiplyByMatrix(tmp)
                    }
                }
                "scale" -> {
                    val v = ColladaUtils.bufferDataFloat32(child) ?: continue
                    if (v.size >= 3) {
                        tmp.setToIdentity()
                        tmp.multiplyByScale(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())
                        localMatrix.multiplyByMatrix(tmp)
                    }
                }
            }
        }

        worldMatrix.setToMultiply(parent, localMatrix)
        buildNormalMatrix(worldMatrix)
    }

    private fun buildNormalMatrix(worldMat: Matrix4) {
        val rx = Angle.fromRadians(atan2(worldMat.m[6], worldMat.m[10]))
        val cosY = sqrt(worldMat.m[6] * worldMat.m[6] + worldMat.m[10] * worldMat.m[10])
        val ry = Angle.fromRadians(atan2(-worldMat.m[2], cosY))
        val rz = Angle.fromRadians(atan2(worldMat.m[1], worldMat.m[0]))
        normalMatrix.setToIdentity()
        normalMatrix.multiplyByRotation(-1.0, 0.0, 0.0, rx)
        normalMatrix.multiplyByRotation(0.0, -1.0, 0.0, ry)
        normalMatrix.multiplyByRotation(0.0, 0.0, -1.0, rz)
    }
}
