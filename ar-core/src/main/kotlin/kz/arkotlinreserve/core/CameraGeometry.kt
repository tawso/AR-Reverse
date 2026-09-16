package kz.arkotlinreserve.core

data class Viewport(val x: Int, val y: Int, val width: Int, val height: Int)

object CameraGeometry {
    fun rotateIntrinsics(source: Intrinsics, rotationDegrees: Int): Intrinsics = when (normalize(rotationDegrees)) {
        0 -> source
        90 -> Intrinsics(source.fy, source.fx, source.imageHeight - 1.0 - source.cy, source.cx, source.imageHeight, source.imageWidth, source.distortion, source.approximate)
        180 -> Intrinsics(source.fx, source.fy, source.imageWidth - 1.0 - source.cx, source.imageHeight - 1.0 - source.cy, source.imageWidth, source.imageHeight, source.distortion, source.approximate)
        270 -> Intrinsics(source.fy, source.fx, source.cy, source.imageWidth - 1.0 - source.cx, source.imageHeight, source.imageWidth, source.distortion, source.approximate)
        else -> error("Недостижимое состояние")
    }

    fun fitCenterViewport(imageWidth: Int, imageHeight: Int, surfaceWidth: Int, surfaceHeight: Int): Viewport {
        require(imageWidth > 0 && imageHeight > 0 && surfaceWidth > 0 && surfaceHeight > 0)
        val imageAspect = imageWidth.toDouble() / imageHeight
        val surfaceAspect = surfaceWidth.toDouble() / surfaceHeight
        return if (surfaceAspect > imageAspect) {
            val width = (surfaceHeight * imageAspect).toInt()
            Viewport((surfaceWidth - width) / 2, 0, width, surfaceHeight)
        } else {
            val height = (surfaceWidth / imageAspect).toInt()
            Viewport(0, (surfaceHeight - height) / 2, surfaceWidth, height)
        }
    }

    /** OpenGL column-major projection; камера смотрит вдоль -Z. */
    fun projectionMatrix(intrinsics: Intrinsics, near: Float = 0.01f, far: Float = 50f): FloatArray {
        require(near > 0 && far > near)
        val w = intrinsics.imageWidth.toFloat(); val h = intrinsics.imageHeight.toFloat()
        return floatArrayOf(
            (2f * intrinsics.fx / w).toFloat(), 0f, 0f, 0f,
            0f, (2f * intrinsics.fy / h).toFloat(), 0f, 0f,
            (1f - 2f * intrinsics.cx / w).toFloat(), (2f * intrinsics.cy / h - 1f).toFloat(), -(far + near) / (far - near), -1f,
            0f, 0f, -2f * far * near / (far - near), 0f,
        )
    }

    private fun normalize(degrees: Int): Int {
        val value = (degrees % 360 + 360) % 360
        require(value % 90 == 0) { "Поворот должен быть кратен 90°" }
        return value
    }
}
