package kz.arkotlinreserve

import android.content.Context
import kz.arkotlinreserve.core.ArucoDictionary
import kz.arkotlinreserve.core.ContentRegistry
import kz.arkotlinreserve.core.ContentSource
import kz.arkotlinreserve.core.LocalTransform
import kz.arkotlinreserve.core.MarkerDefinition
import kz.arkotlinreserve.core.PrimitiveType
import kz.arkotlinreserve.core.Quaternion
import kz.arkotlinreserve.core.Vector3
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BundledAssetContentSource(
    private val context: Context,
    private val assetPath: String = "ar-content.json",
) : ContentSource {
    override suspend fun load(): ContentRegistry {
        val text = try {
            context.assets.open(assetPath).bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            throw IllegalArgumentException("Не удалось прочитать assets/$assetPath", error)
        }
        val dto = try {
            Json { ignoreUnknownKeys = false }.decodeFromString<RegistryDto>(text)
        } catch (error: Exception) {
            throw IllegalArgumentException("Некорректный JSON в assets/$assetPath: ${error.message}", error)
        }
        return try { dto.toRegistry() } catch (error: Exception) {
            throw IllegalArgumentException("Некорректная конфигурация assets/$assetPath: ${error.message}", error)
        }
    }
}

@Serializable
private data class RegistryDto(val schemaVersion: Int, val dictionary: String, val markers: List<MarkerDto>) {
    fun toRegistry() = ContentRegistry(schemaVersion, enumValueOf(dictionary), markers.map(MarkerDto::toDefinition))
}

@Serializable
private data class MarkerDto(
    val markerId: Int,
    val physicalSizeMeters: Float,
    val contentKey: String,
    val title: String,
    val description: String,
    val primitive: String,
    val color: String,
    val localTransform: TransformDto = TransformDto(),
) {
    fun toDefinition() = MarkerDefinition(
        markerId, physicalSizeMeters, contentKey, title, description,
        enumValueOf<PrimitiveType>(primitive.uppercase()), parseColor(color), localTransform.toTransform(),
    )

    private fun parseColor(value: String): Long {
        val normalized = value.removePrefix("#")
        require(normalized.length == 6 || normalized.length == 8) { "Цвет должен иметь формат #RRGGBB или #AARRGGBB" }
        return (if (normalized.length == 6) "FF$normalized" else normalized).toLong(16)
    }
}

@Serializable
private data class TransformDto(
    val translation: List<Float> = listOf(0f, 0f, 0f),
    val rotationQuaternion: List<Float> = listOf(0f, 0f, 0f, 1f),
    val scale: List<Float> = listOf(1f, 1f, 1f),
) {
    fun toTransform(): LocalTransform {
        require(translation.size == 3 && rotationQuaternion.size == 4 && scale.size == 3) { "Неверная длина массива трансформации" }
        return LocalTransform(
            Vector3(translation[0], translation[1], translation[2]),
            Quaternion(rotationQuaternion[0], rotationQuaternion[1], rotationQuaternion[2], rotationQuaternion[3]),
            Vector3(scale[0], scale[1], scale[2]),
        )
    }
}
