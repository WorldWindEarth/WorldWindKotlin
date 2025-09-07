package earth.worldwind.formats.kml.models

import kotlinx.serialization.Serializable
import nl.adaptivity.xmlutil.serialization.XmlElement

/**
 * A region contains a bounding box ([LatLonAltBox]) that describes an area of interest defined by geographic
 * coordinates and altitudes. In addition, a Region contains an LOD (level of detail) extent ([Lod]) that defines
 * a validity range of the associated Region in terms of projected screen size. A Region is said to be "active" when
 * the bounding box is within the user's view and the LOD requirements are met. Objects associated with a Region are
 * drawn only when the Region is active. When the <viewRefreshMode> is onRegion, the Link or Icon is loaded only when
 * the [Region] is active. See the "Topics in KML" page on Regions for more details. In a Container or NetworkLink
 * hierarchy, this calculation uses the Region that is the closest ancestor in the hierarchy.
 */
@Serializable
internal data class Region(
    override val id: String? = null,

    /**
     * A bounding box that describes an area of interest defined by geographic coordinates and altitudes.
     * Default values and required fields are as follows:
     */
    @XmlElement
    val latLonAltBox: LatLonAltBox,

    /**
     * Lod is an abbreviation for Level of Detail. <Lod> describes the size of the projected region on the screen that
     * is required in order for the region to be considered "active." Also specifies the size of the pixel ramp used for
     * fading in (from transparent to opaque) and fading out (from opaque to transparent). See diagram below for
     * a visual representation of these parameters.
     */
    @XmlElement
    val lod: Lod? = null,
) : Object()