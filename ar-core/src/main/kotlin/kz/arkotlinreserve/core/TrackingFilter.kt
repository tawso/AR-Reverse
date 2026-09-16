package kz.arkotlinreserve.core

import kotlin.math.acos
import kotlin.math.sin

class PoseSmoother(private val responsePerSecond: Float = 12f) {
    private var markerId: Int? = null
    private var pose: Pose? = null
    private var timestampNanos: Long = 0

    fun update(id: Int, value: Pose, timeNanos: Long): Pose {
        val previous = pose
        if (previous == null || markerId != id || timeNanos <= timestampNanos) {
            markerId = id
            pose = value
            timestampNanos = timeNanos
            return value
        }
        val dt = ((timeNanos - timestampNanos) / 1_000_000_000.0).coerceIn(0.0, 0.25).toFloat()
        val alpha = (1f - kotlin.math.exp(-responsePerSecond * dt)).coerceIn(0f, 1f)
        val result = Pose(
            previous.translation * (1f - alpha) + value.translation * alpha,
            slerp(previous.rotation, value.rotation, alpha),
        )
        pose = result
        timestampNanos = timeNanos
        return result
    }

    fun reset() {
        markerId = null
        pose = null
        timestampNanos = 0
    }

    private fun slerp(from: Quaternion, toValue: Quaternion, t: Float): Quaternion {
        val a = from.normalized()
        var b = toValue.normalized()
        var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
        if (dot < 0f) {
            b = Quaternion(-b.x, -b.y, -b.z, -b.w)
            dot = -dot
        }
        if (dot > 0.9995f) {
            return Quaternion(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y), a.z + t * (b.z - a.z), a.w + t * (b.w - a.w)).normalized()
        }
        val theta = acos(dot.coerceIn(-1f, 1f))
        val denominator = sin(theta)
        val wa = sin((1f - t) * theta) / denominator
        val wb = sin(t * theta) / denominator
        return Quaternion(a.x * wa + b.x * wb, a.y * wa + b.y * wb, a.z * wa + b.z * wb, a.w * wa + b.w * wb)
    }
}
