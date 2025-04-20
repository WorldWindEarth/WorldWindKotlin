package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.geom.Location.Companion.fromDegrees
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position.Companion.fromDegrees
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.milstd2525.MilStd2525Placemark
import earth.worldwind.shape.milstd2525.MilStd2525TacticalGraphic

class MilStd2525Tutorial(private val engine: WorldWind) : AbstractTutorial() {

    private val layer = RenderableLayer("Tactical Graphics").apply {
        // Add a "MIL-STD-2525 Friendly SOF Drone Aircraft"
        addRenderable(
            Placemark(
                fromDegrees(32.4520, 63.44553, 3000.0),
                MilStd2525Placemark.getPlacemarkAttributes("10030101001103002700", mapOf("Q" to "235", "AS" to "US"))
            ).apply {
                attributes.isDrawLeader = true
                isBillboardingEnabled = true
            }
        )

        // Add a "MIL-STD-2525 Hostile Self-Propelled Rocket Launchers"
        addRenderable(
            Placemark(
                fromDegrees(32.4014, 63.3894, 0.0),
                MilStd2525Placemark.getPlacemarkAttributes("10061040001303014100", mapOf("Q" to "90", "AJ" to "0.1"))
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                isBillboardingEnabled = true
            }
        )

        // Add a "MIL-STD-2525 Friendly Heavy Machine Gun"
        addRenderable(
            Placemark(
                fromDegrees(32.3902, 63.4161, 0.0),
                MilStd2525Placemark.getPlacemarkAttributes("10031500361102030000", mapOf(
                    "C" to "200",
                    "G" to "FOR REINFORCEMENTS",
                    "H" to "ADDED SUPPORT FOR JJ",
                    "V" to "MACHINE GUN",
                    "W" to "30140000ZSEP97", // Date/Time Group
                    "AS" to "US"
                ))
            ).apply {
                altitudeMode = AltitudeMode.CLAMP_TO_GROUND
                isBillboardingEnabled = true
            }
        )

        // Add "MIL-STD-2525 Counterattack by fire"
        addRenderable(
            MilStd2525TacticalGraphic("10062500003407000000", listOf(
                fromDegrees(32.379, 63.457),
                fromDegrees(32.348, 63.412),
                fromDegrees(32.364, 63.375),
                fromDegrees(32.396, 63.451),
            ))
        )
    }

    override fun start() {
        super.start()
        engine.layers.addLayer(layer)
        engine.cameraFromLookAt(
            LookAt(
                position = fromDegrees(32.420, 63.414, 0.0),
                altitudeMode = AltitudeMode.ABSOLUTE, range = 2e4,
                heading = ZERO, tilt = 60.0.degrees, roll = ZERO
            )
        )
    }

    override fun stop() {
        super.stop()
        engine.layers.removeLayer(layer)
    }

}