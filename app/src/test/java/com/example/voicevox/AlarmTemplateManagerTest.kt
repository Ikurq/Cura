package com.example.voicevox

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

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
          "おはようございます。ただいま、%sです。",
          "朝の予定確認です。現在は%sになります。"
        ],
        "event_relative_before": "「%s」まで、あと%d分です。",
        "event_relative_after": "「%s」の開始時刻から、%d分過ぎています！急いでください！",
        "event_relative_just": "「%s」の開始時刻ちょうどです。",
        "event_list_header": "今日の予定は、",
        "event_item": "%sから%s、",
        "event_list_footer": "です。",
        "task_list_header": "今日のタスクは、",
        "task_item": "%s、",
        "task_list_footer": "があります。",
        "empty_schedule": "本日の予定やタスクはありません。",
        "closing": "今日も一日、元気に頑張りましょう！"
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
            "おはようございます。ただいま、%sです。",
            "朝の予定確認です。現在は%sになります。"
        )

        val greeting = AlarmTemplateManager.getMorningTemplate(key = "greeting")
        assertTrue(greetingPool.contains(greeting))

        val header = AlarmTemplateManager.getMorningTemplate(key = "event_list_header")
        assertEquals("今日の予定は、", header)
    }

    @Test
    fun buildMorningReadingMessage_whenEventsAndTasksEmpty_returnsGreetingEmptyAndClosing() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 0)
        }

        val message = AlarmTemplateManager.buildMorningReadingMessage(
            events = emptyList(),
            tasks = emptyList(),
            now = cal
        )

        assertTrue("Message must contain 7時0分", message.contains("7時0分"))
        assertTrue("Message must contain empty schedule message", message.contains("本日の予定やタスクはありません。"))
        assertTrue("Message must contain closing", message.contains("今日も一日、元気に頑張りましょう！"))
    }

    @Test
    fun buildMorningReadingMessage_withEventsAndTasks_formatsCorrectly() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
        }

        val eventTime = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 10)
            set(Calendar.MINUTE, 30)
        }.timeInMillis

        val events = listOf(
            IcsEvent(summary = "研究ミーティング", startTime = eventTime)
        )
        val tasks = listOf("レポート提出")

        val message = AlarmTemplateManager.buildMorningReadingMessage(
            events = events,
            tasks = tasks,
            now = cal
        )

        assertTrue(message.contains("今日の予定は、"))
        assertTrue(message.contains("10時30分から研究ミーティング、です。"))
        assertTrue(message.contains("今日のタスクは、"))
        assertTrue(message.contains("レポート提出、があります。"))
        assertTrue(message.contains("今日も一日、元気に頑張りましょう！"))
        assertFalse(message.contains("本日の予定やタスクはありません。"))
    }

    @Test
    fun buildMorningReadingMessage_withMandatoryEventNear_includesRelativeNotice() {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        // 30分後の予定
        val eventTime = cal.timeInMillis + (30 * 60 * 1000)
        val events = listOf(
            IcsEvent(summary = "朝礼", startTime = eventTime)
        )

        val message = AlarmTemplateManager.buildMorningReadingMessage(
            events = events,
            tasks = emptyList(),
            now = cal
        )

        assertTrue(message.contains("「朝礼」まで、あと30分です。"))
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
