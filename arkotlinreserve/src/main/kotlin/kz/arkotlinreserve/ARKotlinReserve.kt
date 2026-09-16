package kz.arkotlinreserve

import android.content.Context
import androidx.lifecycle.LifecycleOwner
import kz.arkotlinreserve.core.ARConfig
import kz.arkotlinreserve.core.ContentRegistry
import kz.arkotlinreserve.core.ContentSource
import kz.arkotlinreserve.core.ARSession
import kz.arkotlinreserve.core.MarkerDefinition
import kotlinx.coroutines.flow.StateFlow
import org.opencv.android.OpenCVLoader

object ARKotlinReserve {
    suspend fun create(
        context: Context,
        config: ARConfig = ARConfig(),
        contentSource: ContentSource = BundledAssetContentSource(context),
    ): AndroidARSession {
        check(OpenCVLoader.initLocal()) { "Не удалось загрузить встроенную библиотеку OpenCV" }
        return AndroidARSession(context.applicationContext, config, contentSource.load())
    }
}

/** Android-привязка публичной сессии. Все методы, кроме потоков состояния, вызываются с главного потока. */
interface AndroidSession : ARSession {
    val activeContent: StateFlow<MarkerDefinition?>
    fun attach(lifecycleOwner: LifecycleOwner, view: ARView)
    fun detach()
    fun updateConfig(config: ARConfig)
    override fun replaceContent(registry: ContentRegistry)
}
