package kz.arkotlinreserve

import android.content.Context
import kz.arkotlinreserve.core.Intrinsics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class CalibrationFile(val profiles: List<CalibrationProfile>)

@Serializable
private data class CalibrationProfile(
    val cameraId: String,
    val imageWidth: Int,
    val imageHeight: Int,
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val distortion: List<Double> = emptyList(),
) {
    fun intrinsics() = Intrinsics(fx, fy, cx, cy, imageWidth, imageHeight, distortion, approximate = false)
}

internal object CalibrationLoader {
    fun load(context: Context, cameraId: String, width: Int, height: Int, path: String = "camera-calibration.json"): Intrinsics {
        val calibrated = runCatching {
            context.assets.open(path).bufferedReader().use { reader ->
                Json.decodeFromString<CalibrationFile>(reader.readText()).profiles.firstOrNull { it.cameraId == cameraId }?.intrinsics()
            }
        }.getOrNull()
        return calibrated?.scaledTo(width, height) ?: Intrinsics.approximate(width, height)
    }
}
