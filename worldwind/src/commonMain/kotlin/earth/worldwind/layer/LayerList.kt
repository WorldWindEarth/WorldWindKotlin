package earth.worldwind.layer

import earth.worldwind.render.RenderContext
import earth.worldwind.util.Logger.ERROR
import earth.worldwind.util.Logger.logMessage

open class LayerList() : Iterable<Layer> {
    protected val layers = mutableListOf<Layer>()

    constructor(layerList: LayerList): this() { addAllLayers(layerList) }

    constructor(layers: Iterable<Layer>): this() { addAllLayers(layers) }

    fun getLayer(index: Int): Layer {
        require(index in layers.indices) {
            logMessage(ERROR, "LayerList", "getLayer", "invalidIndex")
        }
        return layers[index]
    }

    fun setLayer(index: Int, layer: Layer): Layer {
        require(index in layers.indices) {
            logMessage(ERROR, "LayerList", "setLayer", "invalidIndex")
        }
        return layers.set(index, layer)
    }

    fun indexOfLayer(layer: Layer) = layers.indexOf(layer)

    fun indexOfLayerNamed(name: String): Int {
        for (idx in layers.indices) if ( name == layers[idx].displayName) return idx
        return -1
    }

    fun indexOfLayerWithProperty(key: Any, value: Any): Int {
        for (idx in layers.indices) {
            val layer = layers[idx]
            if (layer.hasUserProperty(key) && value == layer.getUserProperty(key)) return idx
        }
        return -1
    }

    fun addLayer(layer: Layer) { layers.add(layer) }

    fun addLayer(index: Int, layer: Layer) {
        require(index in layers.indices) {
            logMessage(ERROR, "LayerList", "addLayer", "invalidIndex")
        }
        layers.add(index, layer)
    }

    fun addAllLayers(list: LayerList) {
        //layers.ensureCapacity(list.layers.size)
        for (layer in list.layers) layers.add(layer) // we know the contents of layerList.layers is valid
    }

    fun addAllLayers(iterable: Iterable<Layer>) { for (layer in iterable) layers.add(layer) }

    fun removeLayer(layer: Layer): Boolean { return layers.remove(layer) }

    fun removeLayer(index: Int): Layer {
        require(index in layers.indices) {
            logMessage(ERROR, "LayerList", "removeLayer", "invalidIndex")
        }
        return layers.removeAt(index)
    }

    fun removeAllLayers(layers: Iterable<Layer>): Boolean {
        var removed = false
        for (layer in layers) removed = removed or this.layers.remove(layer)
        return removed
    }

    fun clearLayers() { layers.clear() }

    override fun iterator() = layers.iterator()

    fun render(rc: RenderContext) {
        for (i in layers.indices) {
            val layer = layers[i]
            rc.currentLayer = layer
            try {
                layer.render(rc)
            } catch (e: Exception) {
                logMessage(
                    ERROR, "LayerList", "render",
                    "Exception while rendering layer '${layer.displayName}'", e
                )
                // Keep going. Draw the remaining layers.
            }
        }
    }
}