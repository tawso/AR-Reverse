package kz.arkotlinreserve

import android.content.Context
import android.os.SystemClock
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
import kz.arkotlinreserve.renderer.ARRenderView
import kz.arkotlinreserve.tracking.opencv.OpenCvMarkerTracker
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.opencv.android.OpenCVLoader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

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
    private val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ARKotlinReserve-анализ") }
    private val closed = AtomicBoolean(false)
    private var config = initialConfig
    @Volatile private var content = initialContent
    private var tracker = OpenCvMarkerTracker(initialContent.dictionary)
    private val smoother = PoseSmoother()
    private var provider: ProcessCameraProvider? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var renderView: ARRenderView? = null
    private var intrinsics: Intrinsics? = null
    private var active: MarkerObservation? = null
    private var lastSeenElapsed = 0L
    private var frames = 0
    private var fpsWindowStarted = SystemClock.elapsedRealtime()

    override fun attach(lifecycleOwner: LifecycleOwner, previewView: PreviewView, renderView: ARRenderView) {
        check(!closed.get()) { "Сессия уже закрыта" }
        detach()
        owner = lifecycleOwner
        this.previewView = previewView.apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
        this.renderView = renderView
        lifecycleOwner.lifecycle.addObserver(this)
        startCamera()
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val lifecycleOwner = owner ?: return
        _state.value = TrackingState.CameraStarting
        if (!OpenCVLoader.initLocal()) {
            _state.value = TrackingState.Error("Не удалось загрузить OpenCV")
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (closed.get() || owner !== lifecycleOwner) return@addListener
            runCatching {
                val cameraProvider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = previewView?.surfaceProvider }
                val quality = config.analysisQuality
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(quality.width, quality.height))
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
                active = filtered; lastSeenElapsed = now
                _state.value = TrackingState.Tracking(filtered.markerId)
                _observations.tryEmit(filtered)
                renderView?.update(filtered, registry.marker(filtered.markerId), CameraGeometry.rotateIntrinsics(camera, image.imageInfo.rotationDegrees), config.showCoordinateAxes)
            } else {
                val retained = active
                val lostFor = now - lastSeenElapsed
                if (retained != null && lostFor <= config.markerLossGraceMillis) {
                    _state.value = TrackingState.TemporarilyLost(retained.markerId, lostFor)
                } else {
                    active = null; smoother.reset(); renderView?.update(null, null, null, config.showCoordinateAxes)
                    _state.value = if (lastSeenElapsed == 0L) TrackingState.Searching else TrackingState.Lost
                }
            }
            _diagnostics.value = SessionDiagnostics(result.durationMillis, analysisFps, renderView?.rendererFps() ?: 0.0, result.imageWidth, result.imageHeight, active?.markerId, camera.approximate, active?.reprojectionErrorPixels)
        } catch (error: Throwable) {
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
        active = null; smoother.reset()
    }

    override fun onResume(owner: LifecycleOwner) { renderView?.onResume() }
    override fun onPause(owner: LifecycleOwner) { renderView?.onPause() }

    override fun detach() {
        owner?.lifecycle?.removeObserver(this)
        provider?.unbindAll()
        provider = null; owner = null; previewView = null
        renderView?.update(null, null, null, false)
        renderView = null; active = null; smoother.reset(); lastSeenElapsed = 0
        if (!closed.get()) _state.value = TrackingState.Idle
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        detach()
        executor.shutdown()
        tracker.close()
        _state.value = TrackingState.Idle
    }
}
