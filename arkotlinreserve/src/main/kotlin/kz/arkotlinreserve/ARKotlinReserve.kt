package kz.arkotlinreserve

import android.content.Context
import androidx.camera.view.PreviewView
import androidx.lifecycle.LifecycleOwner
import kz.arkotlinreserve.core.ARConfig
import kz.arkotlinreserve.core.ContentRegistry
import kz.arkotlinreserve.core.ContentSource
import kz.arkotlinreserve.core.ARSession
import kz.arkotlinreserve.renderer.ARRenderView

object ARKotlinReserve {
    suspend fun create(
        context: Context,
        config: ARConfig = ARConfig(),
        contentSource: ContentSource = BundledAssetContentSource(context),
    ): AndroidARSession = AndroidARSession(context.applicationContext, config, contentSource.load())
}

/** Android-привязка публичной сессии. Все методы, кроме потоков состояния, вызываются с главного потока. */
interface AndroidSession : ARSession {
    fun attach(lifecycleOwner: LifecycleOwner, previewView: PreviewView, renderView: ARRenderView)
    fun detach()
    fun updateConfig(config: ARConfig)
    override fun replaceContent(registry: ContentRegistry)
}
