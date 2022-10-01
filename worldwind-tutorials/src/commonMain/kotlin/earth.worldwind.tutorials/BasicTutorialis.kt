package earth.worldwind.tutorials

import earth.worldwind.WorldWind
import earth.worldwind.geom.*
import earth.worldwind.globe.elevation.coverage.ElevationCoverage
import earth.worldwind.layer.RenderableLayer
import earth.worldwind.ogc.Wcs100ElevationCoverage
import earth.worldwind.render.Color
import earth.worldwind.render.Font
import earth.worldwind.render.FontWeight
import earth.worldwind.render.image.ImageSource
import earth.worldwind.shape.*
import kotlin.math.atan
import kotlin.math.sqrt

/**
 * Position WorldWind camera at a given location and configure it to point in a given direction.
 */
fun setCameraView(engine: WorldWind) {
    // Create a view of Point Mugu airport as seen from an aircraft above Oxnard, CA.
    val aircraft = Position.fromDegrees(34.2, -119.2, 3000.0) // Above Oxnard CA, altitude in meters
    val airport = Position.fromDegrees(34.1192744, -119.1195850, 4.0) // KNTD airport, Point Mugu CA, altitude MSL

    // Compute heading and tilt angles from aircraft to airport
    val heading = aircraft.greatCircleAzimuth(airport)
    val distanceRadians = aircraft.greatCircleDistance(airport)
    val distance = distanceRadians * engine.globe.getRadiusAt(aircraft.latitude, aircraft.longitude)
    val tilt = Angle.fromRadians(atan(distance / aircraft.altitude))

    // Apply the camera view
    engine.camera.set(
        aircraft.latitude, aircraft.longitude, aircraft.altitude, AltitudeMode.ABSOLUTE, heading, tilt, roll = Angle.ZERO
    )
}

fun setLookAtView(engine: WorldWind) {
    // Create a view of LAX airport as seen from an aircraft above Santa Monica, CA.
    val aircraft = Position.fromDegrees(34.0158333, -118.4513056, 2500.0)
    // Aircraft above Santa Monica airport, altitude in meters
    val airport = Position.fromDegrees(33.9424368, -118.4081222, 38.7)

    // Compute heading and distance from aircraft to airport
    val heading = aircraft.greatCircleAzimuth(airport)
    val distanceRadians = aircraft.greatCircleDistance(airport)
    val distance = distanceRadians * engine.globe.getRadiusAt(aircraft.latitude, aircraft.longitude)

    // Compute camera settings
    val altitude = aircraft.altitude - airport.altitude
    val range = sqrt(altitude * altitude + distance * distance)
    val tilt = Angle.fromRadians(atan(distance / aircraft.altitude))

    // Apply new "look at" view
    val lookAt = LookAt().set(
        airport.latitude, airport.longitude, airport.altitude, AltitudeMode.ABSOLUTE, range, heading, tilt, roll = Angle.ZERO
    )
    engine.cameraFromLookAt(lookAt)
}

/**
 * Create a RenderableLayer for placemarks
 */
fun buildPlacemarksLayer(): RenderableLayer {
    val layer = RenderableLayer("Placemarks")

    // Create a simple placemark at downtown Ventura, CA. This placemark is a 20x20 cyan square centered on the
    // geographic position. This placemark demonstrates the creation with a convenient factory method.
    val ventura = Placemark.createWithColorAndSize(
        Position.fromDegrees(34.281, -119.293, 0.0),
        Color(0f, 1f, 1f, 1f), 20
    )
    layer.addRenderable(ventura)

    // Create an image-based placemark of an aircraft above the ground with a leader-line to the surface.
    // This placemark demonstrates creation via a constructor and a convenient PlacemarkAttributes factory method.
    // The image is scaled to 1.5 times its original size.
    var attributes = PlacemarkAttributes.createWithImageAndLeader(ImageSource.fromResource(MR.images.aircraft_fixwing)).apply {
        imageScale = 1.5
    }
    val airplane = Placemark(
        Position.fromDegrees(34.260, -119.2, 5000.0), attributes
    )
    layer.addRenderable(airplane)

    // Create an image-based placemark with a label at Oxnard Airport, CA. This placemark demonstrates creation
    // with a constructor and a convenient PlacemarkAttributes factory method. The image is scaled to 2x
    // its original size, with the bottom center of the image anchored at the geographic position.
    attributes = PlacemarkAttributes.createWithImage(ImageSource.fromResource(MR.images.airport_terminal)).apply {
        imageOffset = Offset.bottomCenter()
        imageScale = 2.0
    }
    val airport = Placemark(
        Position.fromDegrees(34.200, -119.208, 0.0), attributes, "Oxnard Airport"
    )
    layer.addRenderable(airport)

    // Create an image-based placemark from a bitmap. This placemark demonstrates creation with a
    // constructor and a convenient PlacemarkAttributes factory method. First, a 64x64 bitmap is loaded,
    // and then it is passed into the placemark attributes. The bottom center of the image anchored
    // at the geographic position.
    attributes = PlacemarkAttributes.createWithImage(ImageSource.fromResource(MR.images.ehipcc)).apply {
        imageOffset = Offset.bottomCenter()
    }
    val wildfire = Placemark(Position.fromDegrees(34.300, -119.25, 0.0), attributes)
    layer.addRenderable(wildfire)

    return layer
}

/**
 * Create a layer to display the tutorial paths.
 */
fun buildPathsLayer(): RenderableLayer {
    val layer = RenderableLayer("Paths")

    // Create a basic path with the default attributes, the default altitude mode (ABSOLUTE),
    // and the default path type (GREAT_CIRCLE).
    var positions = listOf(
        Position.fromDegrees(50.0, -180.0, 1e5),
        Position.fromDegrees(30.0, -100.0, 1e6),
        Position.fromDegrees(50.0, -40.0, 1e5)
    )
    var path = Path(positions)
    layer.addRenderable(path)

    // Create a terrain following path with the default attributes, and the default path type (GREAT_CIRCLE).
    positions = listOf(
        Position.fromDegrees(40.0, -180.0, 0.0),
        Position.fromDegrees(20.0, -100.0, 0.0),
        Position.fromDegrees(40.0, -40.0, 0.0)
    )
    path = Path(positions)
    path.altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the path vertices to the ground
    path.isFollowTerrain = true // follow the ground between path vertices
    layer.addRenderable(path)

    // Create an extruded path with the default attributes, the default altitude mode (ABSOLUTE),
    // and the default path type (GREAT_CIRCLE).
    positions = listOf(
        Position.fromDegrees(30.0, -180.0, 1e5),
        Position.fromDegrees(10.0, -100.0, 1e6),
        Position.fromDegrees(30.0, -40.0, 1e5)
    )
    path = Path(positions)
    path.isExtrude = true // extrude the path from the ground to each path position's altitude
    layer.addRenderable(path)

    // Create an extruded path with custom attributes that display the extruded vertical lines,
    // make the extruded interior 50% transparent, and increase the path line with.
    positions = listOf(
        Position.fromDegrees(20.0, -180.0, 1e5),
        Position.fromDegrees(0.0, -100.0, 1e6),
        Position.fromDegrees(20.0, -40.0, 1e5)
    )
    val attrs = ShapeAttributes()
    attrs.isDrawVerticals = true // display the extruded verticals
    attrs.interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
    attrs.outlineWidth = 3f
    path = Path(positions, attrs)
    path.isExtrude = true // extrude the path from the ground to each path position's altitude
    layer.addRenderable(path)

    return layer
}

/**
 * Create a layer to display the tutorial polygons.
 */
fun buildPolygonsLayer(): RenderableLayer {
    val layer = RenderableLayer("Polygons")

    // Create a basic polygon with the default attributes, the default altitude mode (ABSOLUTE),
    // and the default path type (GREAT_CIRCLE).
    var positions = listOf(
        Position.fromDegrees(40.0, -135.0, 5.0e5),
        Position.fromDegrees(45.0, -140.0, 7.0e5),
        Position.fromDegrees(50.0, -130.0, 9.0e5),
        Position.fromDegrees(45.0, -120.0, 7.0e5),
        Position.fromDegrees(40.0, -125.0, 5.0e5)
    )
    var poly = Polygon(positions)
    layer.addRenderable(poly)

    // Create a terrain following polygon with the default attributes, and the default path type (GREAT_CIRCLE).
    positions = listOf(
        Position.fromDegrees(40.0, -105.0, 0.0),
        Position.fromDegrees(45.0, -110.0, 0.0),
        Position.fromDegrees(50.0, -100.0, 0.0),
        Position.fromDegrees(45.0, -90.0, 0.0),
        Position.fromDegrees(40.0, -95.0, 0.0)
    )
    poly = Polygon(positions)
    poly.altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the polygon vertices to the ground
    poly.followTerrain = true // follow the ground between polygon vertices
    layer.addRenderable(poly)

    // Create an extruded polygon with the default attributes, the default altitude mode (ABSOLUTE),
    // and the default path type (GREAT_CIRCLE).
    positions = listOf(
        Position.fromDegrees(20.0, -135.0, 5.0e5),
        Position.fromDegrees(25.0, -140.0, 7.0e5),
        Position.fromDegrees(30.0, -130.0, 9.0e5),
        Position.fromDegrees(25.0, -120.0, 7.0e5),
        Position.fromDegrees(20.0, -125.0, 5.0e5)
    )
    poly = Polygon(positions)
    poly.isExtrude = true // extrude the polygon from the ground to each polygon position's altitude
    layer.addRenderable(poly)

    // Create an extruded polygon with custom attributes that display the extruded vertical lines,
    // make the extruded interior 50% transparent, and increase the polygon line with.
    positions = listOf(
        Position.fromDegrees(20.0, -105.0, 5.0e5),
        Position.fromDegrees(25.0, -110.0, 7.0e5),
        Position.fromDegrees(30.0, -100.0, 9.0e5),
        Position.fromDegrees(25.0, -90.0, 7.0e5),
        Position.fromDegrees(20.0, -95.0, 5.0e5)
    )
    val attrs = ShapeAttributes()
    attrs.isDrawVerticals = true // display the extruded verticals
    attrs.interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
    attrs.outlineWidth = 3f
    poly = Polygon(positions, attrs)
    poly.isExtrude = true // extrude the polygon from the ground to each polygon position's altitude
    layer.addRenderable(poly)

    // Create a polygon with an inner hole by specifying multiple polygon boundaries
    poly = Polygon()
    poly.addBoundary(
        listOf(
            Position.fromDegrees(0.0, -135.0, 5.0e5),
            Position.fromDegrees(5.0, -140.0, 7.0e5),
            Position.fromDegrees(10.0, -130.0, 9.0e5),
            Position.fromDegrees(5.0, -120.0, 7.0e5),
            Position.fromDegrees(0.0, -125.0, 5.0e5)
        )
    )
    poly.addBoundary(
        listOf(
            Position.fromDegrees(2.5, -130.0, 6.0e5),
            Position.fromDegrees(5.0, -135.0, 7.0e5),
            Position.fromDegrees(7.5, -130.0, 8.0e5),
            Position.fromDegrees(5.0, -125.0, 7.0e5)
        )
    )
    layer.addRenderable(poly)

    // Create an extruded polygon with an inner hole and custom attributes that display the extruded vertical lines,
    // make the extruded interior 50% transparent, and increase the polygon line with.
    poly = Polygon(emptyList(), attrs)
    poly.addBoundary(
        listOf(
            Position.fromDegrees(0.0, -105.0, 5.0e5),
            Position.fromDegrees(5.0, -110.0, 7.0e5),
            Position.fromDegrees(10.0, -100.0, 9.0e5),
            Position.fromDegrees(5.0, -90.0, 7.0e5),
            Position.fromDegrees(0.0, -95.0, 5.0e5)
        )
    )
    poly.addBoundary(
        listOf(
            Position.fromDegrees(2.5, -100.0, 6.0e5),
            Position.fromDegrees(5.0, -105.0, 7.0e5),
            Position.fromDegrees(7.5, -100.0, 8.0e5),
            Position.fromDegrees(5.0, -95.0, 7.0e5)
        )
    )
    poly.isExtrude = true // extrude the polygon from the ground to each polygon position's altitude
    layer.addRenderable(poly)

    return layer
}

/**
 * Create a layer in which to display the ellipse shapes.
 */
fun buildEllipsesLayer(): RenderableLayer {
    val layer = RenderableLayer("Ellipses")

    // Create a surface ellipse with the default attributes, a 500km major-radius and a 300km minor-radius. Surface
    // ellipses are configured with a CLAMP_TO_GROUND altitudeMode and followTerrain set to true.
    var ellipse = Ellipse(Position.fromDegrees(45.0, -120.0, 0.0), 500000.0, 300000.0)
    ellipse.altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
    ellipse.isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
    layer.addRenderable(ellipse)

    // Create a surface ellipse with with custom attributes that make the interior 50% transparent and increase the
    // outline width.
    var attrs = ShapeAttributes()
    attrs.interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
    attrs.outlineWidth = 3f
    ellipse = Ellipse(Position.fromDegrees(45.0, -100.0, 0.0), 500000.0, 300000.0, attrs)
    ellipse.altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
    ellipse.isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
    layer.addRenderable(ellipse)

    // Create a surface ellipse with a heading of 45 degrees, causing the semi-major axis to point Northeast and the
    // semi-minor axis to point Southeast.
    ellipse = Ellipse(Position.fromDegrees(35.0, -120.0, 0.0), 500000.0, 300000.0)
    ellipse.altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
    ellipse.isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
    ellipse.heading = Angle.fromDegrees(45.0)
    layer.addRenderable(ellipse)

    // Create a surface circle with the default attributes and 400km radius.
    ellipse = Ellipse(Position.fromDegrees(35.0, -100.0, 0.0), 400000.0, 400000.0)
    ellipse.altitudeMode = AltitudeMode.CLAMP_TO_GROUND // clamp the ellipse's center position to the terrain surface
    ellipse.isFollowTerrain = true // cause the ellipse geometry to follow the terrain surface
    layer.addRenderable(ellipse)

    // Create an ellipse with the default attributes, an altitude of 200 km, and a 500km major-radius and a 300km
    // minor-radius.
    ellipse = Ellipse(Position.fromDegrees(25.0, -120.0, 200e3), 500000.0, 300000.0)
    layer.addRenderable(ellipse)

    // Create an ellipse with custom attributes that make the interior 50% transparent and an extruded outline with
    // vertical lines
    attrs = ShapeAttributes()
    attrs.interiorColor = Color(1f, 1f, 1f, 0.5f) // 50% transparent white
    attrs.isDrawVerticals = true
    ellipse = Ellipse(Position.fromDegrees(25.0, -100.0, 200e3), 500000.0, 300000.0, attrs)
    ellipse.isExtrude = true
    layer.addRenderable(ellipse)

    return layer
}

/**
 * Create a dash and fill layer to display the tutorial shapes.
 */
fun buildDashAndFillLayer(): RenderableLayer {
    val layer = RenderableLayer("Dash and fill")

    // Thicken all lines used in the tutorial.
    val thickenLine = ShapeAttributes().apply { outlineWidth = 4f }

    // Create a path with a simple dashed pattern generated from the ImageSource factory. The
    // ImageSource.fromLineStipple function generates a texture based on the provided factor and pattern, similar to
    // stipple parameters of OpenGL 2. The binary representation of the pattern value will be the pattern displayed,
    // where positions with a 1 appearing as opaque and a 0 as transparent.
    var positions = listOf(
        Position.fromDegrees(60.0, -100.0, 1e5),
        Position.fromDegrees(30.0, -120.0, 1e5),
        Position.fromDegrees(0.0, -100.0, 1e5)
    )
    var path = Path(positions).apply {
        attributes = ShapeAttributes(thickenLine).apply {
            outlineImageSource = ImageSource.fromLineStipple(factor = 2, pattern = 0xF0F0.toShort())
        }
    }
    layer.addRenderable(path)

    // Modify the factor of the pattern for comparison to first path. Only the factor is modified, not the pattern.
    positions = listOf(
        Position.fromDegrees(60.0, -90.0, 5e4),
        Position.fromDegrees(30.0, -110.0, 5e4),
        Position.fromDegrees(0.0, -90.0, 5e4)
    )
    path = Path(positions).apply {
        attributes = ShapeAttributes(thickenLine).apply {
            outlineImageSource = ImageSource.fromLineStipple(factor = 4, pattern = 0xF0F0.toShort())
        }
    }
    layer.addRenderable(path)

    // Create a path conforming to the terrain with a different pattern from the first two Paths.
    positions = listOf(
        Position.fromDegrees(60.0, -80.0, 0.0),
        Position.fromDegrees(30.0, -100.0, 0.0),
        Position.fromDegrees(0.0, -80.0, 0.0)
    )
    path = Path(positions).apply {
        attributes = ShapeAttributes(thickenLine).apply {
            outlineImageSource = ImageSource.fromLineStipple(factor = 8, pattern = 0xDFF6.toShort())
        }
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        isFollowTerrain = true
    }
    layer.addRenderable(path)

    // Create an Ellipse using an image as a repeating fill pattern
    val ellipseCenter = Position.fromDegrees(40.0, -70.0, 1e5)
    val ellipse = Ellipse(ellipseCenter, 1.5e6, 800e3).apply {
        attributes = ShapeAttributes(thickenLine).apply {
            interiorImageSource = ImageSource.fromResource(MR.images.pattern_sample_houndstooth)
        }
    }
    layer.addRenderable(ellipse)

    // Create a surface polygon using an image as a repeating fill pattern and a dash pattern for the outline
    // of the polygon.
    positions = listOf(
        Position.fromDegrees(25.0, -85.0, 0.0),
        Position.fromDegrees(10.0, -80.0, 0.0),
        Position.fromDegrees(10.0, -60.0, 0.0),
        Position.fromDegrees(25.0, -55.0, 0.0)
    )
    val polygon = Polygon(positions).apply {
        attributes = ShapeAttributes(thickenLine).apply {
            interiorImageSource = ImageSource.fromResource(MR.images.pattern_sample_houndstooth)
            outlineImageSource = ImageSource.fromLineStipple(8, 0xDFF6.toShort())
        }
        altitudeMode = AltitudeMode.CLAMP_TO_GROUND
        followTerrain = true
    }
    layer.addRenderable(polygon)

    return layer
}

/**
 * Create a layer to display the tutorial labels.
 */
fun buildLabelsLayer(): RenderableLayer {
    val layer = RenderableLayer("Labels")

    // Create a basic label with the default attributes, including the default text color (white), the default text
    // size (24 pixels), the system default font, and the default alignment (bottom center).
    var label = Label(Position.fromDegrees(38.8977, -77.0365, 0.0), "The White House")
    layer.addRenderable(label)

    // Create a label with a black text color, the default text size, the system default font, the default
    // alignment, and a thick white text outline.
    var attrs = TextAttributes()
    attrs.textColor = Color(0f, 0f, 0f, 1f) // black text via r,g,b,a
    attrs.outlineColor = Color(1f, 1f, 1f, 1f) // white outline via r,g,b,a
    attrs.outlineWidth = 5f // thicken the white outline
    label = Label(Position.fromDegrees(38.881389, -77.036944, 0.0), "Thomas Jefferson Memorial", attrs)
    layer.addRenderable(label)

    // Create a right-aligned label using a bottom-right offset.
    attrs = TextAttributes()
    attrs.textOffset = Offset.bottomRight()
    label = Label(Position.fromDegrees(38.8893, -77.050111, 0.0), "Lincoln Memorial", attrs)
    layer.addRenderable(label)

    // Create a left-aligned label using a bottom-left offset.
    attrs = TextAttributes()
    attrs.textOffset = Offset.bottomLeft()
    label = Label(Position.fromDegrees(38.889803, -77.009114, 0.0), "United States Capitol", attrs)
    layer.addRenderable(label)

    // Create a label with a 48 pixel text size and a bold font.
    attrs = TextAttributes()
    attrs.font = Font("arial", FontWeight.BOLD, 28)
    label = Label(Position.fromDegrees(38.907192, -77.036871, 0.0), "Washington", attrs)
    layer.addRenderable(label)

    // Create a label with its orientation fixed relative to the globe.
    label = Label(Position.fromDegrees(38.89, -77.023611, 0.0), "National Mall")
    label.rotationMode = OrientationMode.RELATIVE_TO_GLOBE
    layer.addRenderable(label)

    return layer
}

/**
 * Create a layer with the example sightline
 */
fun buildSightLineLayer(): RenderableLayer {
    val layer = RenderableLayer("Sightline")

    // Specify the sightline position, which is the origin of the line of sight calculation
    val position = Position.fromDegrees(46.230, -122.190, 2500.0)
    // Specify the range of the sightline (meters)
    val range = 10000.0
    // Create attributes for the visible terrain
    val visibleAttributes = ShapeAttributes().apply { interiorColor = Color(0f, 1f, 0f, 0.5f) }
    // Create attributes for the occluded terrain
    val occludedAttributes = ShapeAttributes().apply { interiorColor = Color(0.1f, 0.1f, 0.1f, 0.8f) }
    // Create the sightline
    val sightline = OmnidirectionalSightline(position, range).apply {
        // Set the attributes
        attributes = visibleAttributes
        occludeAttributes = occludedAttributes
    }
    // Add sightline to a layer
    layer.addRenderable(sightline)
    // Create a Placemark to visualize the position of the sightline
    val placemark = Placemark(position)
    placemark.attributes.apply {
        imageSource = ImageSource.fromResource(MR.images.aircraft_fixwing)
        imageScale = 2.0
        isDrawLeader = true
    }
    layer.addRenderable(placemark)

    return layer
}

fun buildSurfaceImageLayer(): RenderableLayer {
    val layer = RenderableLayer("Surface Image")

    // Configure a Surface Image to display an Android resource showing the WorldWindEarth logo.
    var sector = Sector.fromDegrees(37.46, 15.5, 0.5, 0.6)
    val surfaceImageResource = SurfaceImage(sector, ImageSource.fromResource(MR.images.worldwind_logo))
    layer.addRenderable(surfaceImageResource)

    // Configure a Surface Image to display a remote image showing Mount Etna erupting on July 13th, 2001.
    sector = Sector.fromDegrees(37.46543388598137, 14.60128369746704, 0.45360804083528, 0.75704283995502)
    val urlString = "https://worldwind.arc.nasa.gov/android/tutorials/data/etna.jpg"
    val surfaceImageUrl = SurfaceImage(sector, ImageSource.fromUrlString(urlString))
    layer.addRenderable(surfaceImageUrl)

    return layer
}

fun buildWCSElevationCoverage(): ElevationCoverage {
    // Specify the bounding sector - provided by the WCS
    val coverageSector = Sector.fromDegrees(25.0, -125.0, 25.0, 60.0)
    // Specify the number of levels to match data resolution
    val numberOfLevels = 12
    // Specify the version 1.0.0 WCS address
    val serviceAddress = "https://elevation.nationalmap.gov/arcgis/services/3DEPElevation/ImageServer/WCSServer"
    // Specify the coverage name
    val coverage = "DEP3Elevation"
    // Specify the image format
    val imageFormat = "geotiff"
    // Create an elevation coverage from a version 1.0.0 WCS
    return Wcs100ElevationCoverage(coverageSector, numberOfLevels, serviceAddress, coverage, imageFormat)
}