package earth.worldwind.examples.milstd2525

import android.util.SparseArray
import earth.worldwind.geom.Position
import earth.worldwind.shape.Placemark
import earth.worldwind.shape.PlacemarkAttributes

/**
 * Constructs a MIL-STD-2525 Placemark with an appropriate level of detail for the current distance from the camera.
 * Shared low-fidelity images are used when far away from the camera, whereas unique high-fidelity images are used
 * when near the camera. The high-fidelity images that are no longer in view are automatically freed, if necessary,
 * to release memory resources. The Placemark's symbol is lazily created (and recreated if necessary) via an
 * ImageSource.BitmapFactory that is established in the MilStd2525 utility class. See the [MilStd2525.getPlacemarkAttributes] for more information about resource
 * caching/sharing and the bitmap factory.
 *
 * @param position   The placemark's geographic position
 * @param symbolCode A 15-character alphanumeric identifier that provides the information necessary to display or
 * transmit a tactical symbol between MIL-STD-2525 compliant systems.
 * @param symbolModifiers  A optional collection of unit or tactical graphic modifiers. See:
 * https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersUnits.java
 * and https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/ModifiersTG.java
 * @param symbolAttributes A optional collection of rendering attributes. See https://github.com/missioncommand/mil-sym-android/blob/master/Renderer/src/main/java/armyc2/c2sd/renderer/utilities/MilStdAttributes.java
 */
class MilStd2525Placemark(
    position: Position,
    val symbolCode: String,
    val symbolModifiers: SparseArray<String>?,
    val symbolAttributes: SparseArray<String>?
) : Placemark(
    position, PlacemarkAttributes(), name = symbolCode
) {
    init {
        // Set the properties used to create the bitmap in the level of detail selector
        levelOfDetailSelector = MilStd2525LevelOfDetailSelector()
    }
}