package earth.worldwind.navigator

import earth.worldwind.WorldWindow

interface NavigatorListener {
    fun onNavigatorEvent(wwd: WorldWindow, event: NavigatorEvent)
}