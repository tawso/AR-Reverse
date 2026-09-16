package kz.arkotlinreserve

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.camera.view.PreviewView
import kz.arkotlinreserve.renderer.ARRenderView

/** Готовая поверхность камеры и прозрачного 3D-слоя. Владельцем остаётся View-иерархия приложения. */
class ARView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    internal val preview = PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    internal val renderer = ARRenderView(context)

    init {
        addView(preview, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(renderer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
}
