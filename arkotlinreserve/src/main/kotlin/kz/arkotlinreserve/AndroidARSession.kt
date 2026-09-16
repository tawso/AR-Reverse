package kz.arkotlinreserve

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kz.arkotlinreserve.core.ARConfig
import kz.arkotlinreserve.core.CameraGeometry
import kz.arkotlinreserve.core.ContentRegistry
import kz.arkotlinreserve.core.Intrinsics
import kz.arkotlinreserve.core.MarkerObservation
import kz.arkotlinreserve.core.PoseSmoother
import kz.arkotlinreserve.core.SessionDiagnostics
import kz.arkotlinreserve.core.TrackingState
import kz.arkotlinreserve.core.TrackingStateMachine
import kz.arkotlinreserve.renderer.ARRenderView
import kz.arkotlinreserve.tracking.opencv.OpenCvMarkerTracker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class AndroidARSession internal constructor(
    private val context: Context,
    initialConfig: ARConfig,
    initialContent: ContentRegistry,
) : AndroidSession, DefaultLifecycleObserver {
    private val _state = MutableStateFlow<TrackingState>(TrackingState.Idle)
    override val state = _state.asStateFlow()
    private val _diagnostics = MutableStateFlow(SessionDiagnostics())
    override val diagnostics = _diagnostics.asStateFlow()
    private val _observations = MutableSharedFlow<MarkerObservation>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val observations = _observations.asSharedFlow()
    private val _activeContent = MutableStateFlow<kz.arkotlinreserve.core.MarkerDefinition?>(null)
    override val activeContent = _activeContent.asStateFlow()
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ARKotlinReserve-анализ") }
    private val closed = AtomicBoolean(false)
    private var config = initialConfig
    @Volatile private var content = initialContent
    private var tracker = OpenCvMarkerTracker(initialContent.dictionary)
    private val smoother = PoseSmoother()
    private val stateMachine = TrackingStateMachine(initialConfig.markerLossGraceMillis)
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var renderView: ARRenderView? = null
    private var intrinsics: Intrinsics? = null
    private var active: MarkerObservation? = null
    private var frames = 0
    private var fpsWindowStarted = SystemClock.elapsedRealtime()

    override fun attach(lifecycleOwner: LifecycleOwner, view: ARView) {
        check(!closed.get()) { "Сессия уже закрыта" }
        detach()
        owner = lifecycleOwner
        this.previewView = view.preview
        this.renderView = view.renderer
        lifecycleOwner.lifecycle.addObserver(this)
        startCamera()
    }

    private fun startCamera() {
        val lifecycleOwner = owner ?: return
        _state.value = TrackingState.CameraStarting
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed.get() || owner !== lifecycleOwner) return@addListener
            runCatching {
                val cameraProvider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView?.surfaceProvider }
                val quality = config.analysisQuality
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(quality.width, quality.height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                        ).build(),
                    )
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()
                analysis.setAnalyzer(executor, ::analyze)
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                provider = cameraProvider
                val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
                intrinsics = CalibrationLoader.load(context, cameraId, quality.width, quality.height)
                _state.value = TrackingState.Searching
            }.onFailure { _state.value = TrackingState.Error("Не удалось открыть камеру: ${it.message}") }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun analyze(image: ImageProxy) {
        try {
            if (closed.get()) return
            val gray = copyLuma(image)
            val camera = intrinsics?.scaledTo(image.width, image.height) ?: Intrinsics.approximate(image.width, image.height)
            val registry = content
            val result = tracker.detect(gray, image.width, image.height, image.imageInfo.rotationDegrees, camera, registry.markers.associateBy { it.markerId }, image.imageInfo.timestamp)
            frames++
            val now = SystemClock.elapsedRealtime()
            val elapsed = now - fpsWindowStarted
            val analysisFps = if (elapsed >= 1_000) {
                val value = frames * 1_000.0 / elapsed
                frames = 0; fpsWindowStarted = now; value
            } else _diagnostics.value.processedFramesPerSecond
            val chosen = result.observations.firstOrNull { it.markerId == active?.markerId }
                ?: result.observations.minWithOrNull(compareBy<MarkerObservation> { it.reprojectionErrorPixels }.thenBy { it.markerId })
            if (chosen != null) {
                val filtered = if (config.smoothingEnabled) chosen.copy(pose = smoother.update(chosen.markerId, chosen.pose, chosen.timestampNanos)) else chosen
                active = filtered
                _activeContent.value = registry.marker(filtered.markerId)
                _state.value = stateMachine.observed(filtered.markerId, now)
                _observations.tryEmit(filtered)
                renderView?.update(filtered, registry.marker(filtered.markerId), CameraGeometry.rotateIntrinsics(camera, image.imageInfo.rotationDegrees), config.showCoordinateAxes)
            } else {
                val nextState = stateMachine.missed(now)
                _state.value = nextState
                if (nextState is TrackingState.Lost) {
                    active = null; smoother.reset(); renderView?.update(null, null, null, config.showCoordinateAxes)
                    _activeContent.value = null
                }
            }
            _diagnostics.value = SessionDiagnostics(result.durationMillis, analysisFps, renderView?.rendererFps() ?: 0.0, result.imageWidth, result.imageHeight, active?.markerId, camera.approximate, active?.reprojectionErrorPixels)
        } catch (error: Throwable) {
            Log.e("ARKotlinReserve", "Ошибка анализа кадра", error)
            _state.value = TrackingState.Error("Ошибка анализа кадра: ${error.message}")
        } finally {
            image.close()
        }
    }

    private fun copyLuma(image: ImageProxy): ByteArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val output = ByteArray(image.width * image.height)
        val row = ByteArray(plane.rowStride)
        for (y in 0 until image.height) {
            buffer.position(y * plane.rowStride)
            val count = minOf(plane.rowStride, buffer.remaining())
            buffer.get(row, 0, count)
            for (x in 0 until image.width) output[y * image.width + x] = row[x * plane.pixelStride]
        }
        return output
    }

    override fun updateConfig(config: ARConfig) {
        val resolutionChanged = this.config.analysisQuality != config.analysisQuality
        this.config = config
        stateMachine.updateGracePeriod(config.markerLossGraceMillis)
        if (resolutionChanged) startCamera()
    }

    override fun replaceContent(registry: ContentRegistry) {
        if (registry.dictionary != content.dictionary) {
            executor.execute {
                val replacement = OpenCvMarkerTracker(registry.dictionary)
                val previous = tracker
                tracker = replacement
                previous.close()
            }
        }
        content = registry
        active = null; _activeContent.value = null; smoother.reset(); stateMachine.reset()
    }

    override fun onResume(owner: LifecycleOwner) { renderView?.onResume() }
    override fun onPause(owner: LifecycleOwner) { renderView?.onPause() }

    override fun detach() {
        owner?.lifecycle?.removeObserver(this)
        provider?.unbindAll()
        provider = null; owner = null; previewView = null
        renderView?.update(null, null, null, false)
        renderView = null; active = null; _activeContent.value = null; smoother.reset(); stateMachine.reset()
        if (!closed.get()) _state.value = TrackingState.Idle
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        detach()
        executor.execute { tracker.close() }
        executor.shutdown()
        _state.value = TrackingState.Idle
    }
}
