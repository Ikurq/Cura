package com.example.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CuraVoicevoxTest {

    @Test
    fun testCacheKeyIncludesVolumeScale() {
        val keyStandardVolume = CuraVoicevox.getCacheKey("起きてください", "3", 1.15f, 1.0f)
        val keyBoostedVolume = CuraVoicevox.getCacheKey("起きてください", "3", 1.15f, 1.4f)

        // 音量倍率が異なればキャッシュキーが異なること（過去の低音量キャッシュと混ざらない）
        assertNotEquals(keyStandardVolume, keyBoostedVolume)
        assertEquals("起きてください|3|1.15|1.0", keyStandardVolume)
        assertEquals("起きてください|3|1.15|1.4", keyBoostedVolume)
    }

    @Test
    fun testCacheKeyConsistency() {
        val key1 = CuraVoicevox.getCacheKey("おはよう", "1", 1.0f, 1.4f)
        val key2 = CuraVoicevox.getCacheKey("おはよう", "1", 1.0f, 1.4f)

        assertEquals(key1, key2)
    }
}
