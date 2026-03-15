package earth.worldwind.tutorials

import dev.icerock.moko.graphics.Color
import dev.icerock.moko.resources.ImageResource
import dev.icerock.moko.resources.ResourceContainer
import dev.icerock.moko.resources.ResourcePlatformDetails
import kotlin.String
import kotlin.collections.List

public actual object MR {
  private val contentHash: String = "7147a7a0e84d947a315841f4b3213a8f"

  public actual object images : ResourceContainer<ImageResource> {
    public actual override val __platformDetails: ResourcePlatformDetails =
        ResourcePlatformDetails()

    public actual val aircraft_fighter: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/aircraft_fighter.png\")") as String, fileName =
        "aircraft_fighter.png")

    public actual val aircraft_fixwing: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/aircraft_fixwing.png\")") as String, fileName =
        "aircraft_fixwing.png")

    public actual val airport_terminal: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/airport_terminal.png\")") as String, fileName =
        "airport_terminal.png")

    public actual val aladdin_carpet: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/aladdin_carpet.jpg\")") as String, fileName = "aladdin_carpet.jpg")

    public actual val ehipcc: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/ehipcc.png\")") as String, fileName = "ehipcc.png")

    public actual val manhole: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/manhole.png\")") as String, fileName = "manhole.png")

    public actual val pattern_sample_houndstooth: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/pattern_sample_houndstooth.png\")") as String, fileName =
        "pattern_sample_houndstooth.png")

    public actual val worldwind_logo: ImageResource = ImageResource(fileUrl =
        js("require(\"./images/worldwind_logo.png\")") as String, fileName = "worldwind_logo.png")

      public actual val korogode_image: ImageResource = ImageResource(fileUrl =
          js("require(\"./images/korogode_image.jpg\")") as String, fileName = "korogode_image.png")

    public actual override fun values(): List<ImageResource> = listOf(aircraft_fighter,
        aircraft_fixwing, airport_terminal, aladdin_carpet, ehipcc, manhole,
        pattern_sample_houndstooth, worldwind_logo, korogode_image)
  }
}
