package com.example.voicevox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
}
