package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement
import nl.adaptivity.xmlutil.serialization.XmlSerialName

@Serializable
internal abstract class Feature(
    var id: String? = null,

    @XmlElement
    @XmlSerialName("name")
    var name: String? = null,

    /**
     * Boolean value. Specifies whether a Document or Folder appears closed or open when first loaded into the
     * Places panel. 0=collapsed (the default), 1=expanded. See also <ListStyle>.
     * This element applies only to Document, Folder, and NetworkLink.
     */
    @XmlElement
    @XmlSerialName("open")
    var open: Boolean? = null,

    /**
     * Boolean value. Specifies whether the feature is drawn in the 3D viewer when it is initially loaded.
     * In order for a feature to be visible, the <visibility> tag of all its ancestors must also be set to 1.
     * In the Google Earth List View, each Feature has a checkbox that allows the user to control
     * visibility of the Feature.
     */
    @XmlElement
    var visibility: Boolean? = null,

    /**
     * Defines a viewpoint (camera) associated with this Feature.
     */
    @XmlElement
    var lookAt: LookAt? = null,

    @XmlElement
    var groundOverlay: GroundOverlay? = null,
) : AbstractKml()