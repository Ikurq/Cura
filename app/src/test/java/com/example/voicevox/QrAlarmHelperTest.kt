package com.example.voicevox

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QrAlarmHelperTest {

    @Test
    fun testIsQrMatch_ExactMatch() {
        val expected = "CURA_DISMISS_ALARM"
        assertTrue(QrAlarmHelper.isQrMatch("CURA_DISMISS_ALARM", expected))
    }

    @Test
    fun testIsQrMatch_WithWhitespaceOrNewline() {
        val expected = "CURA_DISMISS_ALARM"
        assertTrue(QrAlarmHelper.isQrMatch("  CURA_DISMISS_ALARM \n", expected))
    }

    @Test
    fun testIsQrMatch_Mismatch() {
        val expected = "CURA_DISMISS_ALARM"
        assertFalse(QrAlarmHelper.isQrMatch("WRONG_CODE", expected))
        assertFalse(QrAlarmHelper.isQrMatch(null, expected))
        assertFalse(QrAlarmHelper.isQrMatch("", expected))
        assertFalse(QrAlarmHelper.isQrMatch("CURA_DISMISS_ALARM", ""))
    }

    @Test
    fun testAlarmItem_BackwardsCompatibility() {
        // requireQrCodeを含まない従来のJSON
        val legacyJson = """
            {
                "id": "alarm_123",
                "hour": 7,
                "minute": 0,
                "message": "起きてください",
                "speakerId": 3,
                "speakerName": "ずんだもん",
                "isEnabled": true,
                "readTasks": false,
                "vibrate": true,
                "repeatDays": [],
                "isOneShot": false
            }
        """.trimIndent()

        val item = Json.decodeFromString<AlarmItem>(legacyJson)
        assertEquals("alarm_123", item.id)
        assertFalse("requireQrCodeのデフォルト値はfalseであるべき", item.requireQrCode)
    }

    @Test
    fun testAlarmItem_SerializationWithQrFlag() {
        val item = AlarmItem(
            id = "alarm_qr",
            hour = 8,
            minute = 30,
            message = "朝です",
            speakerId = 3,
            speakerName = "ずんだもん",
            isEnabled = true,
            readTasks = true,
            requireQrCode = true
        )

        val json = Json.encodeToString(item)
        val deserialized = Json.decodeFromString<AlarmItem>(json)
        assertTrue(deserialized.requireQrCode)
    }
}
