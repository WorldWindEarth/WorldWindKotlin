package earth.worldwind.ogc.wms

import earth.worldwind.geom.Sector
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import nl.adaptivity.xmlutil.serialization.XmlChildrenName
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
@XmlSerialName("Layer", WMS_NAMESPACE, WMS_PREFIX)
data class WmsLayer(
    // Properties of the Layer element
    val layers: List<WmsLayer> = emptyList(),
    @XmlElement
    @XmlSerialName("Name", WMS_NAMESPACE, WMS_PREFIX)
    val name: String? = null,
    @XmlElement
    @XmlSerialName("Title", WMS_NAMESPACE, WMS_PREFIX)
    val title: String,
    @XmlElement
    @XmlSerialName("Abstract", WMS_NAMESPACE, WMS_PREFIX)
    val abstract: String? = null,
    @XmlSerialName("KeywordList", WMS_NAMESPACE, WMS_PREFIX)
    @XmlChildrenName("Keyword", WMS_NAMESPACE, WMS_PREFIX)
    val keywordList: List<String> = emptyList(),
    private val _styles: List<WmsStyle> = emptyList(),
    @XmlSerialName("CRS", WMS_NAMESPACE, WMS_PREFIX)
    private val _referenceSystems: List<String> = emptyList(),
    private val _geographicBoundingBox: WmsGeographicBoundingBox? = null,
    private val _boundingBoxes: List<WmsBoundingBox> = emptyList(),
    private val _dimensions: List<WmsDimension> = emptyList(),
    private val _attribution: WmsAttribution? = null,
    private val _authorityUrls: List<WmsAuthorityUrl> = emptyList(),
    val identifiers: List<WmsIdentifier> = emptyList(),
    val metadataUrls: List<WmsMetadataUrl> = emptyList(),
    @XmlSerialName("DataURL", WMS_NAMESPACE, WMS_PREFIX)
    val dataUrls: List<WmsInfoUrl> = emptyList(),
    @XmlSerialName("FeatureListURL", WMS_NAMESPACE, WMS_PREFIX)
    val featureListUrls: List<WmsInfoUrl> = emptyList(),
    @XmlElement
    @XmlSerialName("MaxScaleDenominator", WMS_NAMESPACE, WMS_PREFIX)
    private val _maxScaleDenominator: Double? = null,
    @XmlElement
    @XmlSerialName("MinScaleDenominator", WMS_NAMESPACE, WMS_PREFIX)
    private val _minScaleDenominator: Double? = null,
    // Properties of the Layer attributes
    @XmlSerialName("queryable", WMS_NAMESPACE, WMS_PREFIX)
    val isQueryable: Boolean = false,
    @XmlSerialName("cascaded", WMS_NAMESPACE, WMS_PREFIX)
    private val _cascaded: Int? = null,
    @XmlSerialName("opaque", WMS_NAMESPACE, WMS_PREFIX)
    val isOpaque: Boolean = false,
    @XmlSerialName("noSubsets", WMS_NAMESPACE, WMS_PREFIX)
    val isNoSubsets: Boolean = false,
    @XmlSerialName("fixedWidth", WMS_NAMESPACE, WMS_PREFIX)
    private val _fixedWidth: Int? = null,
    @XmlSerialName("fixedHeight", WMS_NAMESPACE, WMS_PREFIX)
    private val _fixedHeight: Int? = null,
) {
    val namedLayers: List<WmsLayer> get() = (name?.let { listOf(this) } ?: emptyList()) + layers.flatMap { layer -> layer.namedLayers }
    val styles: List<WmsStyle> get() = _styles + (parent?.styles ?: emptyList())
    val referenceSystems: List<String> get() = _referenceSystems + (parent?.referenceSystems ?: emptyList())
    val geographicBoundingBox: Sector? get() = _geographicBoundingBox?.geographicBoundingBox ?: parent?.geographicBoundingBox
    val boundingBoxes get(): List<WmsBoundingBox> {
        val result = mutableMapOf<String, WmsBoundingBox>()
        var parent = parent
        while (parent != null) {
            parent._boundingBoxes.forEach { bBox -> if (!result.containsKey(bBox.CRS)) result[bBox.CRS] = bBox }
            parent = parent.parent
        }
        return result.values.toList()
    }
    val dimensions get(): List<WmsDimension> {
        val result = mutableMapOf<String?, WmsDimension>()
        var parent = parent
        while (parent != null) {
            parent._dimensions.forEach { dimen -> if (!result.containsKey(dimen.name)) result[dimen.name] = dimen }
            parent = parent.parent
        }
        return result.values.toList()
    }
    val attribution: WmsAttribution? get() = _attribution ?: parent?.attribution
    val authorityUrls: List<WmsAuthorityUrl> get() = _authorityUrls + (parent?.authorityUrls ?: emptyList())
    val maxScaleDenominator: Double? get() = _maxScaleDenominator ?: parent?.maxScaleDenominator
    val minScaleDenominator: Double? get() = _minScaleDenominator ?: parent?.minScaleDenominator
    val cascaded: Int? get() = _cascaded ?: parent?.cascaded
    val fixedWidth: Int? get() = _fixedWidth ?: parent?.fixedWidth
    val fixedHeight: Int? get() = _fixedHeight ?: parent?.fixedHeight
    @Transient
    var capability: WmsCapability? = null
        get() = field ?: parent?.capability
    @Transient
    var parent: WmsLayer? = null

    init {
        layers.forEach { layer -> layer.parent = this }
    }

    fun getStyle(name: String) = styles.firstOrNull { style -> style.name == name }
}