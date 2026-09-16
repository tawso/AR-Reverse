package kz.arkotlinreserve.tracking.opencv

import kz.arkotlinreserve.core.ArucoDictionary
import kz.arkotlinreserve.core.CameraGeometry
import kz.arkotlinreserve.core.Intrinsics
import kz.arkotlinreserve.core.MarkerDefinition
import kz.arkotlinreserve.core.MarkerObservation
import kz.arkotlinreserve.core.Pose
import kz.arkotlinreserve.core.Quaternion
import kz.arkotlinreserve.core.Vector3
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point
import org.opencv.core.Point3
import org.opencv.geometry.Geometry
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.Objdetect
import kotlin.math.sqrt

data class DetectionResult(
    val observations: List<MarkerObservation>,
    val durationMillis: Double,
    val imageWidth: Int,
    val imageHeight: Int,
)

/**
 * Синхронный детектор. Вызывать только с одного фонового потока.
 * Входной массив копируется в native Mat и после вызова больше не удерживается.
 */
class OpenCvMarkerTracker(dictionary: ArucoDictionary) : AutoCloseable {
    private val detector = ArucoDetector(
        Objdetect.getPredefinedDictionary(
            when (dictionary) {
                ArucoDictionary.DICT_4X4_50 -> Objdetect.DICT_4X4_50
                ArucoDictionary.DICT_5X5_100 -> Objdetect.DICT_5X5_100
            },
        ),
    )
    private var input = Mat()
    private var rotated = Mat()

    fun detect(
        gray: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
        intrinsics: Intrinsics,
        definitions: Map<Int, MarkerDefinition>,
        timestampNanos: Long,
    ): DetectionResult {
        val started = System.nanoTime()
        require(gray.size == width * height) { "Некорректный размер серого кадра" }
        input.create(height, width, CvType.CV_8UC1)
        input.put(0, 0, gray)
        val image = rotate(input, rotationDegrees)
        val effectiveIntrinsics = CameraGeometry.rotateIntrinsics(intrinsics, rotationDegrees)
        val corners = mutableListOf<Mat>()
        val ids = Mat()
        return try {
            detector.detectMarkers(image, corners, ids)
            val observations = buildList {
                for (index in 0 until ids.rows()) {
                    val id = ids.get(index, 0)[0].toInt()
                    val definition = definitions[id] ?: continue
                    estimatePose(corners[index], definition, effectiveIntrinsics, timestampNanos)?.let(::add)
                }
            }
            DetectionResult(observations, (System.nanoTime() - started) / 1_000_000.0, image.cols(), image.rows())
        } finally {
            corners.forEach(Mat::release)
            ids.release()
        }
    }

    private fun rotate(source: Mat, degrees: Int): Mat = when ((degrees % 360 + 360) % 360) {
        0 -> source
        90 -> rotated.also { Core.rotate(source, it, Core.ROTATE_90_CLOCKWISE) }
        180 -> rotated.also { Core.rotate(source, it, Core.ROTATE_180) }
        270 -> rotated.also { Core.rotate(source, it, Core.ROTATE_90_COUNTERCLOCKWISE) }
        else -> error("Поддерживаются только повороты, кратные 90°")
    }

    private fun estimatePose(cornerMat: Mat, marker: MarkerDefinition, camera: Intrinsics, timestampNanos: Long): MarkerObservation? {
        val detectedCorners = MatOfPoint2f(cornerMat).toArray()
        val imagePoints = MatOfPoint2f(*detectedCorners.map { Point(it.x, it.y) }.toTypedArray())
        val half = marker.physicalSizeMeters / 2.0
        val objectPoints = MatOfPoint3f(
            Point3(-half, half, 0.0), Point3(half, half, 0.0),
            Point3(half, -half, 0.0), Point3(-half, -half, 0.0),
        )
        val cameraMatrix = Mat.eye(3, 3, CvType.CV_64F)
        cameraMatrix.put(0, 0, camera.fx)
        cameraMatrix.put(1, 1, camera.fy)
        cameraMatrix.put(0, 2, camera.cx)
        cameraMatrix.put(1, 2, camera.cy)
        val distortion = MatOfDouble(*camera.distortion.toDoubleArray())
        val rvec = Mat()
        val tvec = Mat()
        val projected = MatOfPoint2f()
        val rotation = Mat()
        return try {
            val solved = Geometry.solvePnP(objectPoints, imagePoints, cameraMatrix, distortion, rvec, tvec, false, Geometry.SOLVEPNP_IPPE_SQUARE)
            if (!solved) return null
            val t = doubleArrayOf(0.0, 0.0, 0.0).also { tvec.get(0, 0, it) }
            if (t.any { !it.isFinite() } || t[2] <= 0.01 || t[2] > 50.0) return null
            Geometry.projectPoints(objectPoints, rvec, tvec, cameraMatrix, distortion, projected)
            val observed = imagePoints.toArray()
            val reprojection = projected.toArray()
            val rms = sqrt(observed.indices.sumOf { i ->
                val dx = observed[i].x - reprojection[i].x
                val dy = observed[i].y - reprojection[i].y
                dx * dx + dy * dy
            } / observed.size)
            if (!rms.isFinite() || rms > 8.0) return null
            Geometry.Rodrigues(rvec, rotation)
            val r = DoubleArray(9).also { rotation.get(0, 0, it) }
            val glRotation = doubleArrayOf(r[0], r[1], r[2], -r[3], -r[4], -r[5], -r[6], -r[7], -r[8])
            MarkerObservation(
                marker.markerId,
                Pose(Vector3(t[0].toFloat(), (-t[1]).toFloat(), (-t[2]).toFloat()), quaternion(glRotation)),
                timestampNanos,
                rms,
                camera.approximate,
            )
        } finally {
            imagePoints.release(); objectPoints.release(); cameraMatrix.release(); distortion.release()
            rvec.release(); tvec.release(); projected.release(); rotation.release()
        }
    }

    private fun quaternion(m: DoubleArray): Quaternion {
        val trace = m[0] + m[4] + m[8]
        val q = if (trace > 0.0) {
            val s = sqrt(trace + 1.0) * 2.0
            Quaternion(((m[7] - m[5]) / s).toFloat(), ((m[2] - m[6]) / s).toFloat(), ((m[3] - m[1]) / s).toFloat(), (0.25 * s).toFloat())
        } else if (m[0] > m[4] && m[0] > m[8]) {
            val s = sqrt(1.0 + m[0] - m[4] - m[8]) * 2.0
            Quaternion((0.25 * s).toFloat(), ((m[1] + m[3]) / s).toFloat(), ((m[2] + m[6]) / s).toFloat(), ((m[7] - m[5]) / s).toFloat())
        } else if (m[4] > m[8]) {
            val s = sqrt(1.0 + m[4] - m[0] - m[8]) * 2.0
            Quaternion(((m[1] + m[3]) / s).toFloat(), (0.25 * s).toFloat(), ((m[5] + m[7]) / s).toFloat(), ((m[2] - m[6]) / s).toFloat())
        } else {
            val s = sqrt(1.0 + m[8] - m[0] - m[4]) * 2.0
            Quaternion(((m[2] + m[6]) / s).toFloat(), ((m[5] + m[7]) / s).toFloat(), (0.25 * s).toFloat(), ((m[3] - m[1]) / s).toFloat())
        }
        return q.normalized()
    }

    override fun close() {
        detector.clear()
        input.release()
        rotated.release()
    }
}
