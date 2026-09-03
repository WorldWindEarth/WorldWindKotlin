package earth.worldwind.globe.geoid

import dev.icerock.moko.resources.AssetResource
import earth.worldwind.assets.MR as AssetsMR
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope

@OptIn(DelicateCoroutinesApi::class)
expect open class EGM96Geoid(
    offsetsFile: AssetResource = AssetsMR.assets.EGM96_dat,
    scope: CoroutineScope = GlobalScope
) : AbstractEGM96Geoid {
    override val isInitialized: Boolean
    override fun release()
    override suspend fun loadData(offsetsFile: AssetResource)
    override fun getValue(k: Int): Short
}