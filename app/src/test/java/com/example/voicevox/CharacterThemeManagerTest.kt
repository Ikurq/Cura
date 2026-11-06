package com.example.voicevox

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CharacterThemeManagerTest {

    private val hexColorRegex = Regex("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")

    @Test
    fun testAllPresetThemesHaveValidColorsAndCircleBulge() {
        val presets = CharacterThemeManager.PRESETS
        assertTrue("Presets should not be empty", presets.isNotEmpty())

        for (theme in presets) {
            val navStyle = theme.navStyle
            assertEquals("Nav bulge corner radius must be 35dp (circle) for ${theme.id}", 35, navStyle.bulgeCornerRadiusDp)

            // 有効なカラーHEXフォーマットか検証
            assertTrue("Nav background hex should be valid for ${theme.id}", hexColorRegex.matches(navStyle.navBackgroundColorHex))
            assertTrue("Active item hex should be valid for ${theme.id}", hexColorRegex.matches(navStyle.activeItemColorHex))
            assertTrue("Inactive item hex should be valid for ${theme.id}", hexColorRegex.matches(navStyle.inactiveItemColorHex))
            assertTrue("Gradient end hex should be valid for ${theme.id}", hexColorRegex.matches(theme.gradientEndHex))

            // ナビバーの背景色は画面背景下部と同一色ではなく、視認可能な差があること
            assertNotEquals("Nav bar background should not match background bottom color for ${theme.id}",
                theme.gradientEndHex.lowercase(),
                navStyle.navBackgroundColorHex.lowercase()
            )

            // ストローク色が指定されている場合は有効なカラーであること
            navStyle.strokeColorHex?.let {
                assertTrue("Stroke color hex should be valid for ${theme.id}", hexColorRegex.matches(it))
            }

            assertTrue("Stroke width must be positive for ${theme.id}", navStyle.strokeWidthDp > 0f)
        }
    }

    @Test
    fun testAllPresetThemesHaveValidDialogueAssets() {
        val presets = CharacterThemeManager.PRESETS
        val assetDir = File("src/main/assets")

        val expectedMilestones = listOf("5", "10", "15", "20", "30")

        for (theme in presets) {
            val assetFile = File(assetDir, theme.dialogueAsset)
            assertTrue("Asset file must exist: ${theme.dialogueAsset} for character ${theme.id}", assetFile.exists())

            val content = assetFile.readText(Charsets.UTF_8)
            val json = Json.parseToJsonElement(content).jsonObject

            // 必須セクションの存在確認
            assertTrue("system_logs must exist for ${theme.id}", json.containsKey("system_logs"))
            assertTrue("boot_logs must exist for ${theme.id}", json.containsKey("boot_logs"))
            assertTrue("rapid_tap_reactions must exist for ${theme.id}", json.containsKey("rapid_tap_reactions"))
            assertTrue("idle_lines must exist for ${theme.id}", json.containsKey("idle_lines"))
            assertTrue("level_stories must exist for ${theme.id}", json.containsKey("level_stories"))
            assertTrue("time_based_greetings must exist for ${theme.id}", json.containsKey("time_based_greetings"))
            assertTrue("situational_lines must exist for ${theme.id}", json.containsKey("situational_lines"))

            // level_stories の各マイルストーン (5, 10, 15, 20, 30) が定義されていること
            val stories = json["level_stories"]!!.jsonObject
            for (ms in expectedMilestones) {
                assertTrue("level_stories must contain Lv.$ms for ${theme.id}", stories.containsKey(ms))
            }
        }
    }
}
