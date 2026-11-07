package com.example.voicevox

import android.media.AudioManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AlarmVolumeManagerTest {

    private class FakeAudioStreamAdapter(
        private val maxVolume: Int = 15,
        var currentVolume: Int = 3
    ) : IAudioStreamAdapter {
        var lastSetVolume: Int = -1
        var setVolumeCallCount: Int = 0

        override fun getMaxVolume(streamType: Int): Int = maxVolume
        override fun getStreamVolume(streamType: Int): Int = currentVolume
        override fun setStreamVolume(streamType: Int, index: Int, flags: Int) {
            currentVolume = index
            lastSetVolume = index
            setVolumeCallCount++
        }
    }

    @Before
    fun setUp() {
        AlarmVolumeManager.resetForTest()
    }

    @After
    fun tearDown() {
        AlarmVolumeManager.resetForTest()
    }

    @Test
    fun testBoostWhenCurrentVolumeIsLow() {
        val fakeAdapter = FakeAudioStreamAdapter(maxVolume = 15, currentVolume = 3)
        AlarmVolumeManager.audioAdapterProvider = { fakeAdapter }

        AlarmVolumeManager.acquireAlarmVolume(null, ratio = 1.0f)

        assertTrue(AlarmVolumeManager.isBoosted)
        assertEquals(3, AlarmVolumeManager.originalVolume)
        assertEquals(15, fakeAdapter.currentVolume)
        assertEquals(1, fakeAdapter.setVolumeCallCount)

        AlarmVolumeManager.releaseAlarmVolume(null)

        assertFalse(AlarmVolumeManager.isBoosted)
        assertEquals(3, fakeAdapter.currentVolume)
        assertEquals(2, fakeAdapter.setVolumeCallCount)
    }

    @Test
    fun testNoChangeWhenAlreadyAtMaxVolume() {
        val fakeAdapter = FakeAudioStreamAdapter(maxVolume = 15, currentVolume = 15)
        AlarmVolumeManager.audioAdapterProvider = { fakeAdapter }

        AlarmVolumeManager.acquireAlarmVolume(null, ratio = 1.0f)

        assertFalse(AlarmVolumeManager.isBoosted)
        assertEquals(15, fakeAdapter.currentVolume)
        assertEquals(0, fakeAdapter.setVolumeCallCount)

        AlarmVolumeManager.releaseAlarmVolume(null)
        assertEquals(15, fakeAdapter.currentVolume)
        assertEquals(0, fakeAdapter.setVolumeCallCount)
    }

    @Test
    fun testMultipleHoldersLifecycleMaintainsVolumeUntilLastRelease() {
        val fakeAdapter = FakeAudioStreamAdapter(maxVolume = 15, currentVolume = 4)
        AlarmVolumeManager.audioAdapterProvider = { fakeAdapter }

        // 1. AlarmService が音量を確保 (holders: 1)
        AlarmVolumeManager.acquireAlarmVolume(null, ratio = 1.0f)
        assertEquals(1, AlarmVolumeManager.activeHolders)
        assertEquals(15, fakeAdapter.currentVolume)
        assertTrue(AlarmVolumeManager.isBoosted)

        // 2. AlarmAlertActivity が起動して音量を確保 (holders: 2)
        AlarmVolumeManager.acquireAlarmVolume(null, ratio = 1.0f)
        assertEquals(2, AlarmVolumeManager.activeHolders)
        assertEquals(15, fakeAdapter.currentVolume)

        // 3. アラーム停止により AlarmService が破棄 (holders: 1 に減少、まだ音量は最大維持)
        AlarmVolumeManager.releaseAlarmVolume(null)
        assertEquals(1, AlarmVolumeManager.activeHolders)
        assertEquals(15, fakeAdapter.currentVolume)
        assertTrue(AlarmVolumeManager.isBoosted)

        // 4. モーニング読み上げ完了により AlarmAlertActivity が破棄 (holders: 0、元の音量 4 に復元)
        AlarmVolumeManager.releaseAlarmVolume(null)
        assertEquals(0, AlarmVolumeManager.activeHolders)
        assertEquals(4, fakeAdapter.currentVolume)
        assertFalse(AlarmVolumeManager.isBoosted)
    }

    @Test
    fun testForceReleaseRestoresImmediatelyRegardlessOfHolders() {
        val fakeAdapter = FakeAudioStreamAdapter(maxVolume = 15, currentVolume = 2)
        AlarmVolumeManager.audioAdapterProvider = { fakeAdapter }

        AlarmVolumeManager.acquireAlarmVolume(null)
        AlarmVolumeManager.acquireAlarmVolume(null)
        assertEquals(2, AlarmVolumeManager.activeHolders)
        assertEquals(15, fakeAdapter.currentVolume)

        AlarmVolumeManager.releaseAlarmVolume(null, force = true)
        assertEquals(0, AlarmVolumeManager.activeHolders)
        assertEquals(2, fakeAdapter.currentVolume)
        assertFalse(AlarmVolumeManager.isBoosted)
    }

    @Test
    fun testTargetVolumeFloorEnsuresHighAudibility() {
        val fakeAdapter = FakeAudioStreamAdapter(maxVolume = 15, currentVolume = 1)
        AlarmVolumeManager.audioAdapterProvider = { fakeAdapter }

        // 低すぎる比率 (0.1f) を指定しても安全フロア (80% = 12) が適用されること
        AlarmVolumeManager.acquireAlarmVolume(null, ratio = 0.1f)
        assertEquals(12, fakeAdapter.currentVolume)
    }
}
