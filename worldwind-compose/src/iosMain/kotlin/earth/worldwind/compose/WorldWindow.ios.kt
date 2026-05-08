@file:OptIn(ExperimentalForeignApi::class)

package earth.worldwind.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import earth.worldwind.WorldWindow as PlatformWorldWindow

/**
 * Hosts the WorldWind engine inside a Compose Multiplatform iOS composition.
 *
 * Compose for iOS renders into its own Skia-backed surface and cannot share an EAGL/GL
 * context with our engine, so the platform [PlatformWorldWindow] (a `CAEAGLLayer`-backed
 * `UIView`) is hosted via `UIKitView`. The factory constructs with `CGRectZero`;
 * `UIKitView` resizes through `layoutSubviews` once Compose measures it, which triggers
 * the framebuffer recreation in [PlatformWorldWindow].
 *
 * `state.attach(engine, eventListener)` passes the forwarder rather than `this` because
 * K/N forbids the iOS WorldWindow from directly implementing the Kotlin
 * `WorldWind.EventListener` interface (Obj-C supertype + Kotlin interface mixing rule).
 *
 * iOS Compose has no built-in lifecycle owner, so background/foreground transitions are
 * routed through `NSNotificationCenter` directly: pause the display link on suspend,
 * resume on return.
 */
@Composable
fun WorldWindow(
    state: WorldWindowState,
    modifier: Modifier = Modifier,
    onCreated: (PlatformWorldWindow) -> Unit = {},
) {
    val wwd = remember(state) {
        PlatformWorldWindow(CGRectMake(0.0, 0.0, 0.0, 0.0)).also { window ->
            state.factory(window.engine)
            state.attach(window.engine, window.eventListener)
            onCreated(window)
        }
    }

    DisposableEffect(wwd) {
        // Subscribe via NSNotificationCenter's block-based observer API. The returned
        // tokens are NSObjectProtocol references we'll remove on dispose. Posting on
        // mainQueue ensures the GL-touching pause/resume calls run on the same thread
        // as the CADisplayLink.
        val center = NSNotificationCenter.defaultCenter
        val mainQueue = NSOperationQueue.mainQueue
        val backgroundToken = center.addObserverForName(
            name = UIApplicationDidEnterBackgroundNotification,
            `object` = null,
            queue = mainQueue,
        ) { _ -> wwd.pauseRendering() }
        val foregroundToken = center.addObserverForName(
            name = UIApplicationWillEnterForegroundNotification,
            `object` = null,
            queue = mainQueue,
        ) { _ -> wwd.resumeRendering() }

        onDispose {
            center.removeObserver(backgroundToken)
            center.removeObserver(foregroundToken)
            state.detach()
        }
    }

    UIKitView(
        factory = { wwd },
        modifier = modifier,
    )
}
