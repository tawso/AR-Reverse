package kz.arkotlinreserve.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test fun `масштабирование параметров камеры учитывает обе оси`() {
        val scaled = Intrinsics(1000.0, 900.0, 500.0, 250.0, 1000, 500).scaledTo(500, 1000)
        assertEquals(500.0, scaled.fx, 1e-9)
        assertEquals(1800.0, scaled.fy, 1e-9)
        assertEquals(250.0, scaled.cx, 1e-9)
        assertEquals(500.0, scaled.cy, 1e-9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `реестр отклоняет повторяющиеся маркеры`() {
        val marker = MarkerDefinition(1, 0.05f, "a", "A", "", PrimitiveType.CUBE, 0xff00ff00)
        ContentRegistry(1, ArucoDictionary.DICT_4X4_50, listOf(marker, marker.copy(contentKey = "b")))
    }

    @Test fun `смена маркера сбрасывает сглаживание`() {
        val smoother = PoseSmoother(1f)
        smoother.update(1, Pose(Vector3(0f, 0f, 1f), Quaternion.Identity), 1_000_000_000)
        val switched = smoother.update(2, Pose(Vector3(5f, 0f, 1f), Quaternion.Identity), 1_010_000_000)
        assertEquals(5f, switched.translation.x, 0f)
        assertTrue(switched.rotation.w.isFinite())
    }

    @Test fun `поворот на 90 градусов переставляет фокус и главную точку`() {
        val rotated = CameraGeometry.rotateIntrinsics(Intrinsics(800.0, 900.0, 300.0, 200.0, 640, 480), 90)
        assertEquals(900.0, rotated.fx, 0.0)
        assertEquals(800.0, rotated.fy, 0.0)
        assertEquals(279.0, rotated.cx, 0.0)
        assertEquals(300.0, rotated.cy, 0.0)
        assertEquals(480, rotated.imageWidth)
        assertEquals(640, rotated.imageHeight)
    }

    @Test fun `fit center создаёт симметричные боковые поля`() {
        val viewport = CameraGeometry.fitCenterViewport(1000, 1000, 2000, 1000)
        assertEquals(Viewport(500, 0, 1000, 1000), viewport)
    }

    @Test fun `центрированные параметры дают нулевой сдвиг проекции`() {
        val matrix = CameraGeometry.projectionMatrix(Intrinsics(500.0, 500.0, 320.0, 240.0, 640, 480))
        assertEquals(0f, matrix[8], 1e-6f)
        assertEquals(0f, matrix[9], 1e-6f)
        assertEquals(-1f, matrix[11], 0f)
    }

    @Test fun `состояние проходит retained и lost после grace period`() {
        val machine = TrackingStateMachine(300)
        assertEquals(TrackingState.Searching, machine.missed(1000))
        assertEquals(TrackingState.Tracking(7), machine.observed(7, 1000))
        assertEquals(TrackingState.TemporarilyLost(7, 250), machine.missed(1250))
        assertEquals(TrackingState.Lost, machine.missed(1301))
        assertEquals(TrackingState.Lost, machine.missed(1400))
    }
}
