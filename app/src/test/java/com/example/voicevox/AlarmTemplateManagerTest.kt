package com.example.voicevox

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AlarmTemplateManagerTest {

    private val testJson = """
    {
      "preview": {
        "template": "試聴: %s"
      },
      "mandatory_alarm": {
        "generation_template": "レガシー: %d時%d分 %s %d",
        "categories": {
          "standard": {
            "name": "標準",
            "templates": [
              "{time}です。{event}まであと{leadTime}分です。",
              "%d時%d分です。%sまであと%d分です。"
            ]
          },
          "urgent": {
            "name": "焦り・緊急",
            "templates": [
              "大変！もう{hour}時{minute}分！{event}に遅刻しちゃう！"
            ]
          },
          "gentle": {
            "name": "優しめ",
            "templates": [
              "おはよう。{time}だよ。{event}の準備しようね。"
            ]
          }
        }
      },
      "morning_reading": {
        "greeting": [
          "おはようございます。%sです。",
          "朝ですよ。%sです。"
        ],
        "event_list_header": "予定は以下の通りです。"
      }
    }
    """.trimIndent()

    @Before
    fun setUp() {
        AlarmTemplateManager.setTestJson(testJson)
    }

    @After
    fun tearDown() {
        AlarmTemplateManager.setTestJson(null)
    }

    @Test
    fun getMandatoryCategories_parsesAllCategoriesCorrectly() {
        val categories = AlarmTemplateManager.getMandatoryCategories()
        assertEquals(3, categories.size)

        val standard = categories.find { it.id == "standard" }
        assertNotNull(standard)
        assertEquals("標準", standard!!.name)
        assertEquals(2, standard.templates.size)

        val urgent = categories.find { it.id == "urgent" }
        assertNotNull(urgent)
        assertEquals("焦り・緊急", urgent!!.name)
        assertEquals(1, urgent.templates.size)

        val gentle = categories.find { it.id == "gentle" }
        assertNotNull(gentle)
        assertEquals("優しめ", gentle!!.name)
        assertEquals(1, gentle.templates.size)
    }

    @Test
    fun getRandomMandatoryTemplate_withCategoryFilter() {
        val urgentTemplate = AlarmTemplateManager.getRandomMandatoryTemplate(categoryId = "urgent")
        assertEquals("大変！もう{hour}時{minute}分！{event}に遅刻しちゃう！", urgentTemplate)

        val gentleTemplate = AlarmTemplateManager.getRandomMandatoryTemplate(categoryId = "gentle")
        assertEquals("おはよう。{time}だよ。{event}の準備しようね。", gentleTemplate)
    }

    @Test
    fun getRandomMandatoryTemplate_withAllOrNull_picksFromAnyCategory() {
        val allTemplates = listOf(
            "{time}です。{event}まであと{leadTime}分です。",
            "%d時%d分です。%sまであと%d分です。",
            "大変！もう{hour}時{minute}分！{event}に遅刻しちゃう！",
            "おはよう。{time}だよ。{event}の準備しようね。"
        )

        for (i in 0 until 20) {
            val template = AlarmTemplateManager.getRandomMandatoryTemplate(categoryId = null)
            assertTrue("Template should be in the available pool", allTemplates.contains(template))
        }
    }

    @Test
    fun formatMandatoryAlarmMessage_withNamedTokens() {
        val template = "起きて！{time}だよ！{event}まであと{leadTime}分しかない！"
        val message = AlarmTemplateManager.formatMandatoryAlarmMessage(
            template = template,
            hour = 8,
            minute = 15,
            eventSummary = "ゼミ発表",
            leadTimeMinutes = 45
        )

        assertEquals("起きて！8時15分だよ！ゼミ発表まであと45分しかない！", message)
    }

    @Test
    fun formatMandatoryAlarmMessage_withPositionalFormat() {
        val template = "%d時%d分です。%sまであと%d分です。"
        val message = AlarmTemplateManager.formatMandatoryAlarmMessage(
            template = template,
            hour = 7,
            minute = 30,
            eventSummary = "会議",
            leadTimeMinutes = 30
        )

        assertEquals("7時30分です。会議まであと30分です。", message)
    }

    @Test
    fun formatMandatoryAlarmMessage_withInvalidFormat_doesNotCrash() {
        val badTemplate = "壊れた書式 %d %s %q"
        val message = AlarmTemplateManager.formatMandatoryAlarmMessage(
            template = badTemplate,
            hour = 9,
            minute = 0,
            eventSummary = "授業",
            leadTimeMinutes = 20
        )

        assertNotNull(message)
        assertTrue(message.contains("9時0分です。授業まであと20分です。"))
    }

    @Test
    fun getMorningTemplate_supportsArrayAndString() {
        val greetingPool = listOf(
            "おはようございます。%sです。",
            "朝ですよ。%sです。"
        )

        val greeting = AlarmTemplateManager.getMorningTemplate(key = "greeting")
        assertTrue(greetingPool.contains(greeting))

        val header = AlarmTemplateManager.getMorningTemplate(key = "event_list_header")
        assertEquals("予定は以下の通りです。", header)
    }

    @Test
    fun legacyJson_withoutCategories_fallsBackToStandard() {
        val legacyJson = """
        {
          "mandatory_alarm": {
            "generation_template": "%d時%d分 %s %d"
          }
        }
        """.trimIndent()
        AlarmTemplateManager.setTestJson(legacyJson)

        val categories = AlarmTemplateManager.getMandatoryCategories()
        assertEquals(1, categories.size)
        assertEquals("standard", categories[0].id)
        assertEquals("%d時%d分 %s %d", categories[0].templates[0])

        val template = AlarmTemplateManager.getRandomMandatoryTemplate()
        assertEquals("%d時%d分 %s %d", template)
    }
}