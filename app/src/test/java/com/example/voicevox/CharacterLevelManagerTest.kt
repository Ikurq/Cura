package com.example.voicevox

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterLevelManagerTest {

    @Test
    fun testGetTotalExpKey_formatsCorrectly() {
        assertEquals("totalExp_cura", CharacterLevelManager.getTotalExpKey("cura"))
        assertEquals("totalExp_charlotte", CharacterLevelManager.getTotalExpKey("charlotte"))
        assertEquals("totalExp_neko", CharacterLevelManager.getTotalExpKey("neko"))
        assertEquals("totalExp_shion", CharacterLevelManager.getTotalExpKey("shion"))
        // 大文字や空白、空文字の正規化
        assertEquals("totalExp_cura", CharacterLevelManager.getTotalExpKey(" CURA "))
        assertEquals("totalExp_cura", CharacterLevelManager.getTotalExpKey(""))
    }

    @Test
    fun testGetStorySeenLevelKey_formatsCorrectly() {
        assertEquals("last_seen_story_lv_cura", CharacterLevelManager.getStorySeenLevelKey("cura"))
        assertEquals("last_seen_story_lv_charlotte", CharacterLevelManager.getStorySeenLevelKey("charlotte"))
        assertEquals("last_seen_story_lv_neko", CharacterLevelManager.getStorySeenLevelKey("neko"))
        assertEquals("last_seen_story_lv_shion", CharacterLevelManager.getStorySeenLevelKey("shion"))
    }

    @Test
    fun testKeysForDifferentCharactersAreDistinct() {
        val charIds = listOf("cura", "charlotte", "neko", "shion")
        val expKeys = charIds.map { CharacterLevelManager.getTotalExpKey(it) }
        val storyKeys = charIds.map { CharacterLevelManager.getStorySeenLevelKey(it) }

        assertEquals(charIds.size, expKeys.distinct().size)
        assertEquals(charIds.size, storyKeys.distinct().size)

        for (i in charIds.indices) {
            assertNotEquals(expKeys[i], storyKeys[i])
        }
    }

    @Test
    fun testCalculateLevelInfo_basicAndEdgeCases() {
        val expPerLevel = 100L

        // 初期状態 (0 EXP)
        val info0 = CharacterLevelManager.calculateLevelInfo(0L, expPerLevel)
        assertEquals(1, info0.level)
        assertEquals(0L, info0.currentExpInLevel)
        assertEquals(100L, info0.requiredExpForNextLevel)

        // Lv.1 途中 (45 EXP)
        val info45 = CharacterLevelManager.calculateLevelInfo(45L, expPerLevel)
        assertEquals(1, info45.level)
        assertEquals(45L, info45.currentExpInLevel)
        assertEquals(100L, info45.requiredExpForNextLevel)

        // Lv.1 境界 (99 EXP)
        val info99 = CharacterLevelManager.calculateLevelInfo(99L, expPerLevel)
        assertEquals(1, info99.level)
        assertEquals(99L, info99.currentExpInLevel)
        assertEquals(100L, info99.requiredExpForNextLevel)

        // Lv.2 到達 (100 EXP)
        val info100 = CharacterLevelManager.calculateLevelInfo(100L, expPerLevel)
        assertEquals(2, info100.level)
        assertEquals(0L, info100.currentExpInLevel)
        assertEquals(100L, info100.requiredExpForNextLevel)

        // Lv.3 途中 (250 EXP)
        val info250 = CharacterLevelManager.calculateLevelInfo(250L, expPerLevel)
        assertEquals(3, info250.level)
        assertEquals(50L, info250.currentExpInLevel)
        assertEquals(100L, info250.requiredExpForNextLevel)

        // 負の経験値保護
        val infoNegative = CharacterLevelManager.calculateLevelInfo(-50L, expPerLevel)
        assertEquals(1, infoNegative.level)
        assertEquals(0L, infoNegative.currentExpInLevel)
        assertEquals(100L, infoNegative.requiredExpForNextLevel)
    }

    @Test
    fun testCalculateLevel_matchesInfo() {
        assertEquals(1, CharacterLevelManager.calculateLevel(0L))
        assertEquals(1, CharacterLevelManager.calculateLevel(99L))
        assertEquals(2, CharacterLevelManager.calculateLevel(100L))
        assertEquals(3, CharacterLevelManager.calculateLevel(250L))
        assertEquals(11, CharacterLevelManager.calculateLevel(1000L))
    }

    @Test
    fun testMigrationAndCharacterIsolation() {
        val fakePrefs = FakeSharedPreferences()

        // 1. 旧環境のデータ (全体共通 totalExp = 550L)
        fakePrefs.putLong(CuraConstants.KEY_TOTAL_EXP, 550L)

        // 2. cura のEXP取得 ➔ 旧キーから 550L を自動マイグレーション
        val curaExp = CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "cura")
        assertEquals(550L, curaExp)
        assertTrue(fakePrefs.contains("totalExp_cura"))
        assertEquals(550L, fakePrefs.getLong("totalExp_cura", 0L))

        // 3. 別キャラクター charlotte のEXP取得 ➔ 0L（独立している）
        val charlotteExp = CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "charlotte")
        assertEquals(0L, charlotteExp)

        // 4. charlotte に 200L を保存
        CharacterLevelManager.setTotalExpInPrefs(fakePrefs, "charlotte", 200L)
        assertEquals(200L, CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "charlotte"))

        // cura の値は 550L のまま維持されていること
        assertEquals(550L, CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "cura"))

        // 5. neko に 350L を保存
        CharacterLevelManager.setTotalExpInPrefs(fakePrefs, "neko", 350L)
        assertEquals(350L, CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "neko"))
        assertEquals(200L, CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "charlotte"))
        assertEquals(550L, CharacterLevelManager.getTotalExpFromPrefs(fakePrefs, "cura"))
    }

    // テスト用のシンプルなインメモリ FakeSharedPreferences 実装
    private class FakeSharedPreferences : SharedPreferences {
        private val data = mutableMapOf<String, Any>()

        fun putLong(key: String, value: Long) {
            data[key] = value
        }

        override fun getAll(): MutableMap<String, *> = data
        override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = data[key] as? MutableSet<String> ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = data.containsKey(key)

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val changes = mutableMapOf<String, Any?>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor { changes[key!!] = value; return this }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor { changes[key!!] = values; return this }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor { changes[key!!] = value; return this }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor { changes[key!!] = value; return this }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor { changes[key!!] = value; return this }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor { changes[key!!] = value; return this }
            override fun remove(key: String?): SharedPreferences.Editor { changes[key!!] = null; return this }
            override fun clear(): SharedPreferences.Editor { clearAll = true; return this }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) data.clear()
                changes.forEach { (k, v) ->
                    if (v == null) data.remove(k) else data[k] = v
                }
            }
        }

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}
