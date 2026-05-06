package earth.worldwind.compose.samples

import earth.worldwind.WorldWind
import earth.worldwind.geom.AltitudeMode
import earth.worldwind.geom.Angle.Companion.ZERO
import earth.worldwind.geom.Angle.Companion.degrees
import earth.worldwind.globe.elevation.coverage.BasicElevationCoverage
import earth.worldwind.layer.BackgroundLayer
import earth.worldwind.layer.atmosphere.AtmosphereLayer
import earth.worldwind.layer.starfield.StarFieldLayer

fun WorldWind.setupBasicGlobe() {
    globe.elevationModel.addCoverage(BasicElevationCoverage())
    layers.addLayer(BackgroundLayer())
    layers.addLayer(StarFieldLayer())
    layers.addLayer(AtmosphereLayer())
    camera.set(
        latitude = 30.0.degrees,
        longitude = -90.0.degrees,
        altitude = 1.5e7,
        altitudeMode = AltitudeMode.ABSOLUTE,
        heading = ZERO,
        tilt = ZERO,
        roll = ZERO,
    )
}
