package earth.worldwind.compose.samples

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import earth.worldwind.compose.WorldWindow
import earth.worldwind.compose.rememberWorldWindowState

class SampleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SampleApp() }
    }
}

@Composable
private fun SampleApp() {
    val state = rememberWorldWindowState { engine -> engine.setupBasicGlobe() }
    WorldWindow(state = state, modifier = Modifier.fillMaxSize())
}
