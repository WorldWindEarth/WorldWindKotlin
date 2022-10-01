package earth.worldwind

open class PickedObjectList {
    protected val objectsById = mutableMapOf<Int, PickedObject>()
    val objects get() = objectsById.values
    val count get() = objectsById.size
    val topPickedObject get() = objects.firstOrNull { po -> po.isOnTop }
    val terrainPickedObject get() = objects.firstOrNull { po -> po.isTerrain }
    val hasNonTerrainObjects get() = objects.firstOrNull { po -> !po.isTerrain } != null

    fun offerPickedObject(pickedObject: PickedObject) { objectsById[pickedObject.identifier] = pickedObject }

    fun pickedObjectWithId(identifier: Int) = objectsById[identifier]

    fun clearPickedObjects() = objectsById.clear()

    fun keepTopAndTerrainObjects() = objectsById.entries.removeAll { e -> !e.value.isOnTop && !e.value.isTerrain }

    override fun toString() = objects.joinToString(", ", "PickedObjectList{", "}") { po -> po.toString() }
}