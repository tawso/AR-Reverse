package kz.arkotlinreserve.core

import kotlin.math.sqrt

/** Правосторонняя поза: метры, кватернион в порядке x/y/z/w. */
data class Pose(
    val translation: Vector3,
    val rotation: Quaternion,
) {
    init {
        require(translation.isFinite()) { "Координаты позы должны быть конечными" }
        require(rotation.isFinite()) { "Кватернион должен быть конечным" }
    }
}

data class Vector3(val x: Float, val y: Float, val z: Float) {
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()
    operator fun plus(other: Vector3) = Vector3(x + other.x, y + other.y, z + other.z)
    operator fun times(scale: Float) = Vector3(x * scale, y * scale, z * scale)
}

data class Quaternion(val x: Float, val y: Float, val z: Float, val w: Float) {
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()

    fun normalized(): Quaternion {
        val length = sqrt(x * x + y * y + z * z + w * w)
        require(length > 1e-6f) { "Нулевой кватернион недопустим" }
        return Quaternion(x / length, y / length, z / length, w / length)
    }

    companion object {
        val Identity = Quaternion(0f, 0f, 0f, 1f)
    }
}

data class Intrinsics(
    val fx: Double,
    val fy: Double,
    val cx: Double,
    val cy: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val distortion: List<Double> = emptyList(),
    val approximate: Boolean = false,
) {
    init {
        require(fx.isFinite() && fy.isFinite() && fx > 0 && fy > 0) { "Фокусные расстояния должны быть положительными" }
        require(cx.isFinite() && cy.isFinite()) { "Главная точка должна быть конечной" }
        require(imageWidth > 0 && imageHeight > 0) { "Размер изображения должен быть положительным" }
        require(distortion.all(Double::isFinite)) { "Коэффициенты дисторсии должны быть конечными" }
    }

    fun scaledTo(width: Int, height: Int): Intrinsics {
        require(width > 0 && height > 0)
        val sx = width.toDouble() / imageWidth
        val sy = height.toDouble() / imageHeight
        return copy(fx = fx * sx, fy = fy * sy, cx = cx * sx, cy = cy * sy, imageWidth = width, imageHeight = height)
    }

    companion object {
        fun approximate(width: Int, height: Int): Intrinsics {
            val focal = maxOf(width, height).toDouble()
            return Intrinsics(focal, focal, width / 2.0, height / 2.0, width, height, approximate = true)
        }
    }
}
