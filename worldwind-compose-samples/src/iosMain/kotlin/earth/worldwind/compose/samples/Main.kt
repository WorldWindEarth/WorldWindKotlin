package earth.worldwind.compose.samples

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.ComposeUIViewController
import earth.worldwind.compose.WorldWindow
import earth.worldwind.compose.rememberWorldWindowState
import platform.UIKit.UIViewController

/**
 * iOS entry point for the Compose Multiplatform sample app.
 *
 * Apps embed this from a SwiftUI host via [`UIViewControllerRepresentable`]:
 *
 * ```swift
 * import ComposeApp
 *
 * struct ComposeView: UIViewControllerRepresentable {
 *     func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
 *     func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
 * }
 * ```
 *
 * Or from a UIKit `AppDelegate` by setting it as the window's `rootViewController`.
 *
 * The framework name is `ComposeApp` (configured in `build.gradle.kts`) so the Swift
 * import line above resolves the symbol at link time.
 */
@Suppress("FunctionName", "unused")
fun MainViewController(): UIViewController = ComposeUIViewController { SampleApp() }

@Composable
private fun SampleApp() {
    val state = rememberWorldWindowState { engine -> engine.setupBasicGlobe() }
    WorldWindow(state = state, modifier = Modifier.fillMaxSize())
}
