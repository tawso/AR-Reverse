package kz.arkotlinreserve.core

import kotlinx.coroutines.flow.Flow

enum class ArucoDictionary(val maxMarkerId: Int) {
    DICT_4X4_50(49),
    DICT_5X5_100(99),
}

enum class PrimitiveType { CUBE, PLANE, AXES }

enum class AnalysisQuality(val width: Int, val height: Int) {
    PERFORMANCE(640, 480), BALANCED(960, 720), QUALITY(1280, 960),
}

data class ARConfig(
    val analysisQuality: AnalysisQuality = AnalysisQuality.BALANCED,
    val smoothingEnabled: Boolean = true,
    val showCoordinateAxes: Boolean = false,
    val markerLossGraceMillis: Long = 350,
    val maxReprojectionErrorPixels: Double = 8.0,
) {
    init {
        require(markerLossGraceMillis in 0..5_000) { "Допуск потери маркера должен быть от 0 до 5000 мс" }
        require(maxReprojectionErrorPixels.isFinite() && maxReprojectionErrorPixels > 0) { "Порог репроекции должен быть положительным" }
    }
}

data class LocalTransform(
    val translation: Vector3 = Vector3(0f, 0f, 0f),
    val rotation: Quaternion = Quaternion.Identity,
    val scale: Vector3 = Vector3(1f, 1f, 1f),
)

data class MarkerDefinition(
    val markerId: Int,
    val physicalSizeMeters: Float,
    val contentKey: String,
    val title: String,
    val description: String,
    val primitive: PrimitiveType,
    val colorArgb: Long,
    val localTransform: LocalTransform = LocalTransform(),
)

data class ContentRegistry(
    val schemaVersion: Int,
    val dictionary: ArucoDictionary,
    val markers: List<MarkerDefinition>,
) {
    init {
        require(schemaVersion == CURRENT_SCHEMA_VERSION) { "Неподдерживаемая версия схемы: $schemaVersion" }
        require(markers.isNotEmpty()) { "Нужен хотя бы один маркер" }
        require(markers.map { it.markerId }.distinct().size == markers.size) { "Обнаружены повторяющиеся ID маркеров" }
        require(markers.map { it.contentKey }.distinct().size == markers.size) { "Обнаружены повторяющиеся ключи контента" }
        markers.forEach {
            require(it.markerId in 0..dictionary.maxMarkerId) { "ID ${it.markerId} не входит в ${dictionary.name}" }
            require(it.physicalSizeMeters.isFinite() && it.physicalSizeMeters > 0f) { "Размер маркера ${it.markerId} должен быть положительным" }
            require(it.contentKey.isNotBlank()) { "Ключ контента маркера ${it.markerId} пуст" }
            require(it.title.isNotBlank()) { "Заголовок маркера ${it.markerId} пуст" }
            require(it.localTransform.translation.isFinite() && it.localTransform.scale.isFinite()) { "Трансформация маркера ${it.markerId} некорректна" }
            require(it.localTransform.scale.x > 0f && it.localTransform.scale.y > 0f && it.localTransform.scale.z > 0f) { "Масштаб должен быть положительным" }
            it.localTransform.rotation.normalized()
        }
    }

    fun marker(id: Int): MarkerDefinition? = markers.firstOrNull { it.markerId == id }

    companion object { const val CURRENT_SCHEMA_VERSION = 1 }
}

sealed interface TrackingState {
    data object Idle : TrackingState
    data object CameraStarting : TrackingState
    data object Searching : TrackingState
    data class Tracking(val markerId: Int) : TrackingState
    data class TemporarilyLost(val markerId: Int, val retainedForMillis: Long) : TrackingState
    data object Lost : TrackingState
    data class Error(val message: String) : TrackingState
}

data class MarkerObservation(
    val markerId: Int,
    val pose: Pose,
    val timestampNanos: Long,
    val reprojectionErrorPixels: Double,
    val approximateCalibration: Boolean,
)

data class SessionDiagnostics(
    val detectionMillis: Double = 0.0,
    val processedFramesPerSecond: Double = 0.0,
    val rendererFramesPerSecond: Double = 0.0,
    val analysisWidth: Int = 0,
    val analysisHeight: Int = 0,
    val activeMarkerId: Int? = null,
    val approximateCalibration: Boolean = true,
    val reprojectionErrorPixels: Double? = null,
)

interface ContentSource {
    suspend fun load(): ContentRegistry
}

interface ARSession : AutoCloseable {
    val state: kotlinx.coroutines.flow.StateFlow<TrackingState>
    val diagnostics: kotlinx.coroutines.flow.StateFlow<SessionDiagnostics>
    val observations: Flow<MarkerObservation>
    fun replaceContent(registry: ContentRegistry)
    override fun close()
}
