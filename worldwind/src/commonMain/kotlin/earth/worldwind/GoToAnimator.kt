package earth.worldwind

import earth.worldwind.geom.Location
import earth.worldwind.geom.LookAt
import earth.worldwind.geom.Position
import earth.worldwind.geom.Vec3
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Incrementally and smoothly moves the Camera to a specified position.
 */
open class GoToAnimator(
    /**
     * The [WorldWind] engine associated with this animator.
     */
    protected val engine: WorldWind
) {
    /**
     * The frequency in milliseconds at which to animate the position change.
     */
    var animationFrequency = 20L
    /**
     * The animation's duration, in milliseconds. When the distance is short, less than twice the viewport
     * size, the travel time is reduced proportionally to the distance to travel. It therefore takes less
     * time to move shorter distances.
     */
    var travelTime = 3000
    /**
     * Main scope to launch animation
     */
    protected val mainScope get() = engine.renderResourceCache.mainScope
    /**
     * A temp variable used to hold the current view as a look at during calculations. Using an object level temp
     * property negates the need for ad-hoc allocations and reduces load on the garbage collector.
     */
    protected val lookAt = LookAt()
    protected var animationJob: Job? = null
    protected var completionCallback: ((GoToAnimator) -> Unit)? = null
    protected var targetPosition: Position? = null
    protected var startPosition: Position? = null
    protected var startTime = Instant.DISTANT_PAST
    protected var maxAltitude = 0.0
    protected var maxAltitudeReachedTime = Instant.DISTANT_PAST
    protected var panVelocity = 0.0
    protected var rangeVelocity = 0.0

    /**
     * Stop the current animation.
     */
    fun cancel() { animationJob?.cancel() }

    /**
     * Moves the camera to a specified look at location or position.
     *
     * @param position The [Location] or [Position] to move the camera to. If this
     * argument contains an "altitude" property, as [Position] does, the end point of the navigation is
     * at the specified altitude. Otherwise, the end point is at the current altitude of the camera.
     * @param completionCallback If not null, specifies a function to call when the animation completes.
     * The completion callback is called with a single argument, this animator.
     */
    open fun goTo(position: Location, completionCallback: ((GoToAnimator) -> Unit)? = null) {
        if (engine.viewport.isEmpty) return

        this.completionCallback = completionCallback

        engine.cameraAsLookAt(lookAt)
        // Capture the target position and determine its altitude.
        val targetPosition = Position(
            position.latitude, position.longitude, if (position is Position) position.altitude else lookAt.range
        ).also { targetPosition = it }

        // Capture the start position and start time.
        val startPosition = Position(lookAt.position.latitude, lookAt.position.longitude, lookAt.range).also { startPosition = it }
        startTime = Clock.System.now()

        // Determination of the pan and range velocities requires the distance to be travelled.
        var animationDuration = travelTime
        val panDistance = startPosition.greatCircleDistance(targetPosition)

        // Determine how high we need to go to give the user context. The max altitude computed is approximately
        // that needed to fit the start and end positions in the same viewport assuming a 45 degree field of view.
        val pA = engine.globe.geographicToCartesian(startPosition.latitude, startPosition.longitude, 0.0, Vec3())
        val pB = engine.globe.geographicToCartesian(targetPosition.latitude, targetPosition.longitude, 0.0, Vec3())
        maxAltitude = pA.distanceTo(pB)

        // Determine an approximate viewport size in radians in order to determine whether we actually change
        // the range as we pan to the new location. We don't want to change the range if the distance between
        // the start and target positions is small relative to the current viewport.
        val viewportSize = engine.pixelSizeAtDistance(startPosition.altitude) *
                engine.viewport.width / engine.globe.equatorialRadius

        // Start and target positions are close, so don't back out.
        if (panDistance <= 2 * viewportSize) maxAltitude = startPosition.altitude

        // We need to capture the time the max altitude is reached in order to begin decreasing the range
        // midway through the animation. If we're already above the max altitude, then that time is now since
        // we don't back out if the current altitude is above the computed max altitude.
        maxAltitudeReachedTime = if (maxAltitude <= lookAt.range) Clock.System.now() else Instant.DISTANT_PAST

        // Compute the total range to travel since we need that to compute the range velocity.
        // Note that the range velocity and pan velocity are computed so that the respective animations, which
        // operate independently, finish at the same time.
        val rangeDistance = if (maxAltitude > startPosition.altitude) {
            max(0.0, maxAltitude - startPosition.altitude) + abs(targetPosition.altitude - maxAltitude)
        } else {
            abs(targetPosition.altitude - startPosition.altitude)
        }

        // Determine which distance governs the animation duration.
        val animationDistance = max(panDistance, rangeDistance / engine.globe.equatorialRadius)
        if (animationDistance == 0.0) return // current and target positions are the same

        if (animationDistance < 2 * viewportSize) {
            // Start and target positions are close, so reduce the travel time based on the
            // distance to travel relative to the viewport size.
            animationDuration = min(((animationDistance / viewportSize) * travelTime).roundToInt(), travelTime)
        }

        // Don't let the animation duration go to 0.
        animationDuration = max(1, animationDuration)

        // Determine the pan velocity, in radians per millisecond.
        panVelocity = panDistance / animationDuration

        // Determine the range velocity, in meters per millisecond.
        rangeVelocity = rangeDistance / animationDuration // meters per millisecond

        // Set up the animation timer.
        setUpAnimationTimer()
    }

    protected open fun setUpAnimationTimer() {
        animationJob?.cancel()
        animationJob = mainScope.launch {
            delay(animationFrequency)
            if (!isActive || !update()) completionCallback?.invoke(this@GoToAnimator) else setUpAnimationTimer()
        }
    }

    /**
     * This is the timer callback function. It invokes the range animator and the pan animator.
     */
    protected open fun update(): Boolean {
        val currentPosition = Position(lookAt.position.latitude, lookAt.position.longitude, lookAt.range)
        val continueUpdateRange = updateRange(currentPosition)
        val continueUpdateLocation = updateLocation(currentPosition)
        WorldWind.requestRedraw()
        return continueUpdateRange || continueUpdateLocation
    }

    /**
     * This function animates the range.
     */
    protected open fun updateRange(currentPosition: Position): Boolean {
        val startPosition = startPosition ?: return false
        val targetPosition = targetPosition ?: return false

        // If we haven't reached the maximum altitude, then step-wise increase it. Otherwise, step-wise change
        // the range towards the target altitude.
        val continueAnimation = if (maxAltitudeReachedTime == Instant.DISTANT_PAST) {
            val elapsedTime = Clock.System.now() - startTime
            val nextRange = min(startPosition.altitude + rangeVelocity * elapsedTime.inWholeMilliseconds, maxAltitude)
            // We're done if we get withing 1 meter of the desired range.
            if (abs(lookAt.range - nextRange) < 1) maxAltitudeReachedTime = Clock.System.now()
            lookAt.range = nextRange
            true
        } else {
            val elapsedTime = Clock.System.now() - maxAltitudeReachedTime
            val nextRange = if (maxAltitude > targetPosition.altitude) {
                max(maxAltitude - (rangeVelocity * elapsedTime.inWholeMilliseconds), targetPosition.altitude)
            } else {
                min(maxAltitude + (rangeVelocity * elapsedTime.inWholeMilliseconds), targetPosition.altitude)
            }
            lookAt.range = nextRange
            // We're done if we get withing 1 meter of the desired range.
            abs(lookAt.range - targetPosition.altitude) > 1
        }

        engine.cameraFromLookAt(lookAt)

        return continueAnimation
    }

    /**
     * This function animates the pan to the desired location.
     */
    protected open fun updateLocation(currentPosition: Position): Boolean {
        val startPosition = startPosition ?: return false
        val targetPosition = targetPosition ?: return false
        val elapsedTime = Clock.System.now() - startTime
        val distanceTravelled = startPosition.greatCircleDistance(currentPosition)
        val distanceRemaining = currentPosition.greatCircleDistance(targetPosition)
        val azimuthToTarget = currentPosition.greatCircleAzimuth(targetPosition)
        val distanceForNow = panVelocity * elapsedTime.inWholeMilliseconds
        val nextDistance = min(distanceForNow - distanceTravelled, distanceRemaining)
        val nextLocation = currentPosition.greatCircleLocation(azimuthToTarget, nextDistance, Location())
        var locationReached = false

        lookAt.position.latitude = nextLocation.latitude
        lookAt.position.longitude = nextLocation.longitude
        engine.cameraFromLookAt(lookAt)

        // We're done if we're within a meter of the desired location.
        if (nextDistance < 1.0 / engine.globe.equatorialRadius) locationReached = true

        return !locationReached
    }
}