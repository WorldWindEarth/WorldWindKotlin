package earth.worldwind.util

import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Provides a collection to keep track of resources whose retrieval failed and when retrieval may be tried again.
 */
open class AbsentResourceList<T>(
    /**
     * The number of attempts to make before the resource is marked as absent.
     */
    private val maxTrys: Int,
    /**
     * The amount of time to wait between attempts, in milliseconds.
     */
    private val minCheckInterval: Duration
) {
    /**
     * The amount of time, in milliseconds, beyond which retrieval attempts should again be allowed.
     * When this time has elapsed from the most recent failed attempt the number of try's attempted is
     * reset to 0. This prevents the resource from being permanently blocked.
     */
    var tryAgainInterval = 60.seconds

    private val possiblyAbsent = mutableMapOf<T, AbsentResourceEntry>()

    fun clear() { possiblyAbsent.clear() }

    /**
     * Indicates whether a specified resource is marked as absent.
     * @param resourceId The resource identifier.
     * @returns true if the resource is marked as absent, otherwise false.
     */
    fun isResourceAbsent(resourceId: T): Boolean {
        val entry = possiblyAbsent[resourceId] ?: return false
        if (entry.permanent) return true

        val timeSinceLastMark = Clock.System.now() - entry.timeOfLastMark

        if (timeSinceLastMark > tryAgainInterval) {
            possiblyAbsent.remove(resourceId)
            return false
        }

        return timeSinceLastMark < minCheckInterval || entry.numTrys > maxTrys
    }

    /**
     * Marks a resource attempt as having failed. This increments the number-of-tries counter and sets the time
     * of the last attempt. When this method has been called [AbsentResourceList.maxTrys] times the resource is marked
     * as absent until this absent resource list's [AbsentResourceList.tryAgainInterval] is reached.
     *
     * @param resourceId The resource identifier.
     * @param permanent Marks a resource attempt as having failed permanently. No attempt will ever again be made to retrieve the resource.
     */
    fun markResourceAbsent(resourceId: T, permanent: Boolean = false) {
        (possiblyAbsent[resourceId] ?: AbsentResourceEntry().also { possiblyAbsent[resourceId] = it }).makeTry(permanent)
    }

    /**
     * Removes the specified resource from this absent resource list. Call this method when retrieval attempt succeeded.
     * @param resourceId The resource identifier.
     */
    fun unmarkResourceAbsent(resourceId: T) { possiblyAbsent.remove(resourceId) }

    private inner class AbsentResourceEntry {
        var timeOfLastMark = Clock.System.now()
            private set
        var numTrys = 0
            private set
        var permanent = false
            private set

        fun makeTry(permanent: Boolean = false) {
            numTrys++
            timeOfLastMark = Clock.System.now()
            this.permanent = permanent
        }
    }
}