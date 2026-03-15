package earth.worldwind.tutorials

import dev.icerock.moko.resources.ImageResource
import dev.icerock.moko.resources.ResourceContainer
import dev.icerock.moko.resources.ResourcePlatformDetails
import kotlin.collections.List

public expect object MR {
  public object images : ResourceContainer<ImageResource> {
    public override val __platformDetails: ResourcePlatformDetails

    public val aircraft_fighter: ImageResource

    public val aircraft_fixwing: ImageResource

    public val airport_terminal: ImageResource

    public val aladdin_carpet: ImageResource

    public val ehipcc: ImageResource

    public val korogode_image: ImageResource

    public val manhole: ImageResource

    public val pattern_sample_houndstooth: ImageResource

    public val worldwind_logo: ImageResource

    public override fun values(): List<ImageResource>
  }
}
