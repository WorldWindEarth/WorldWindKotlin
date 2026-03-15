package earth.worldwind.tutorials

import dev.icerock.moko.resources.ImageResource
import dev.icerock.moko.resources.ResourceContainer
import dev.icerock.moko.resources.ResourcePlatformDetails
import earth.worldwind.tutorials.R
import kotlin.String
import kotlin.collections.List

public actual object MR {
  private val contentHash: String = "2bb561c9822e7f4b4cdac81a28004d21"

  public actual object images : ResourceContainer<ImageResource> {
    public actual override val __platformDetails: ResourcePlatformDetails =
        ResourcePlatformDetails()

    public actual val aircraft_fighter: ImageResource = ImageResource(R.drawable.aircraft_fighter)

    public actual val aircraft_fixwing: ImageResource = ImageResource(R.drawable.aircraft_fixwing)

    public actual val airport_terminal: ImageResource = ImageResource(R.drawable.airport_terminal)

    public actual val aladdin_carpet: ImageResource = ImageResource(R.drawable.aladdin_carpet)

    public actual val ehipcc: ImageResource = ImageResource(R.drawable.ehipcc)

    public actual val korogode_image: ImageResource = ImageResource(R.drawable.korogode_image)

    public actual val manhole: ImageResource = ImageResource(R.drawable.manhole)

    public actual val pattern_sample_houndstooth: ImageResource =
        ImageResource(R.drawable.pattern_sample_houndstooth)

    public actual val worldwind_logo: ImageResource = ImageResource(R.drawable.worldwind_logo)

    public actual override fun values(): List<ImageResource> = listOf(aircraft_fighter,
        aircraft_fixwing, airport_terminal, aladdin_carpet, ehipcc, korogode_image, manhole,
        pattern_sample_houndstooth, worldwind_logo)
  }
}
