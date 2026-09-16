package kz.arkotlinreserve

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentJsonTest {
    private val marker = """{"markerId":7,"physicalSizeMeters":0.08,"contentKey":"demo","title":"Үлгі","description":"Толтырғыш","primitive":"cube","color":"#00FF00"}"""

    @Test fun `валидный JSON создаёт реестр`() {
        val registry = parseContentJson("""{"schemaVersion":1,"dictionary":"DICT_4X4_50","markers":[$marker]}""")
        assertEquals(7, registry.markers.single().markerId)
        assertEquals(0xff00ff00, registry.markers.single().colorArgb)
    }

    @Test fun `неизвестный primitive даёт понятную ошибку`() {
        val invalid = marker.replace("cube", "sphere")
        val error = runCatching { parseContentJson("""{"schemaVersion":1,"dictionary":"DICT_4X4_50","markers":[$invalid]}""") }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message?.contains("конфигурация") == true)
    }

    @Test fun `повторяющийся ID отклоняется`() {
        val error = runCatching { parseContentJson("""{"schemaVersion":1,"dictionary":"DICT_4X4_50","markers":[$marker,$marker]}""") }.exceptionOrNull()
        assertTrue(error?.message?.contains("повторяющиеся ID") == true)
    }

    @Test fun `неизвестное поле отклоняется`() {
        val error = runCatching { parseContentJson("""{"schemaVersion":1,"dictionary":"DICT_4X4_50","unexpected":true,"markers":[$marker]}""") }.exceptionOrNull()
        assertTrue(error?.message?.contains("Некорректный JSON") == true)
    }
}
