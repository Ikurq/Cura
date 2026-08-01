package com.example.voicevox.core.character

import com.example.voicevox.core.core.CuraTime
import com.example.voicevox.core.repository.AlarmRepository
import com.example.voicevox.core.repository.PlayerRepository
import com.example.voicevox.core.repository.TaskRepository
import com.example.voicevox.core.schedule.ScheduleService
import kotlin.random.Random

/** キュラの衣装。季節と曜日で決まる。 */
enum class Costume {
    /** 冬服(既定)。 */
    WINTER,

    /** 夏服(6〜9月の平日)。 */
    SUMMER,

    /** 私服(土日)。 */
    CASUAL,

    /** 夏の私服(6〜9月の土日)。 */
    SUMMER_CASUAL,
}

/** 吹き出しに出す1回分のセリフ。 */
data class Dialogue(
    val text: String,
    /** false ならタップで飛ばせない。ストーリーや解放イベントで使う。 */
    val isSkippable: Boolean = true,
) {
    /** `|` で区切られたページ。表示側はこれを順に出す。 */
    val pages: List<String> get() = text.split("|").map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * キャラクター(キュラ)の振る舞い。
 *
 * Android 版 MainActivity に散らばっていた抽選ロジックをまとめたもの。
 * 電池残量だけはプラットフォーム依存なので、呼び出し側から渡してもらう。
 */
class CharacterEngine(
    private val playerRepository: PlayerRepository,
    private val taskRepository: TaskRepository,
    private val alarmRepository: AlarmRepository,
    private val scheduleService: ScheduleService,
    private val random: Random = Random.Default,
) {

    /** いまの衣装。 */
    fun costume(nowMillis: Long = CuraTime.nowMillis()): Costume {
        val date = CuraTime.toLocalDate(nowMillis)
        val isSummer = date.monthNumber in 6..9
        val isWeekend = CuraTime.calendarDayOfWeek(date).let { it == 1 || it == 7 }
        return when {
            isSummer && isWeekend -> Costume.SUMMER_CASUAL
            isWeekend -> Costume.CASUAL
            isSummer -> Costume.SUMMER
            else -> Costume.WINTER
        }
    }

    /** SYS_LOG に流す行。順番に出す前提。 */
    fun systemLogLines(): List<String> = CharacterLines.systemLog

    /**
     * キャラクターをタップしたときの反応。
     *
     * 累計回数を1つ進めたうえで、解放イベント → 節目 → 連打 → 通常、の順に判定する。
     * 解放イベントと節目は通常セリフを抑止する(Android 版と同じ)。
     *
     * @param consecutiveTaps 直近の連打回数。8以上で連打用の反応になる。
     * @param batteryPercent 電池残量(0-100)。取れないなら 100 を渡す。
     */
    fun onTap(consecutiveTaps: Int, batteryPercent: Int, nowMillis: Long = CuraTime.nowMillis()): Dialogue {
        val count = playerRepository.interactionCount + 1
        playerRepository.interactionCount = count

        if (!playerRepository.memoryUnlocked && count >= MEMORY_UNLOCK_TAPS) {
            playerRepository.memoryUnlocked = true
            return Dialogue(CharacterLines.memoryUnlock, isSkippable = false)
        }

        if (count % MILESTONE_INTERVAL == 0L) {
            return Dialogue(CharacterLines.milestone(count), isSkippable = false)
        }

        if (consecutiveTaps >= RAPID_TAP_THRESHOLD) {
            return Dialogue(CharacterLines.rapidTap.random(random))
        }

        return randomDialogue(batteryPercent, nowMillis)
    }

    /** 放置されたときのセリフ。 */
    fun idleDialogue(): Dialogue = Dialogue(CharacterLines.idle.random(random))

    /**
     * まだ再生していないストーリーがあれば返す。無ければ null。
     * 返した時点で「見た」ものとして記録する。
     */
    fun pendingStory(): Dialogue? {
        if (!playerRepository.memoryUnlocked) return null
        val chapter = CharacterStory.nextChapter(
            currentLevel = playerRepository.characterLevel.level,
            lastSeenLevel = playerRepository.lastSeenStoryLevel,
        ) ?: return null

        playerRepository.lastSeenStoryLevel = chapter.level
        return Dialogue(chapter.text, isSkippable = false)
    }

    /**
     * 通常のセリフ抽選。
     *
     * 追憶(解放済みのみ、5%) / 実用(40%) / フレーバー(55%) の3系統。
     * 割合は Android 版のまま。
     */
    fun randomDialogue(batteryPercent: Int, nowMillis: Long = CuraTime.nowMillis()): Dialogue {
        val characterLevel = playerRepository.characterLevel.level
        val roll = random.nextInt(1, 101)

        if (playerRepository.memoryUnlocked && roll <= MEMORY_ROLL) {
            return Dialogue(CharacterLines.memoryFragments(characterLevel).random(random), isSkippable = false)
        }
        if (roll <= MEMORY_ROLL + PRACTICAL_ROLL) {
            return Dialogue(practicalLine(batteryPercent, nowMillis))
        }
        return Dialogue(flavorLine(characterLevel, nowMillis))
    }

    /** 状況を踏まえた実用的なセリフ。 */
    private fun practicalLine(batteryPercent: Int, nowMillis: Long): String {
        val hour = CuraTime.toLocalDateTime(nowMillis).hour

        if (batteryPercent < LOW_BATTERY_PERCENT) {
            return "マスター、電力が残りわずかです！キュラが消えちゃう前に、充電をお願いします…！"
        }
        if (taskRepository.hasUrgentTasks(nowMillis)) {
            return CharacterLines.urgentTask.random(random)
        }

        val nextEvent = scheduleService.nextEvent(nowMillis)
        if (nextEvent != null && (nextEvent.startTime - nowMillis) < ONE_HOUR_MILLIS) {
            val time = CuraTime.formatHourMinute(nextEvent.startTime)
            return "マスター、まもなく $time から『${nextEvent.summary}』の予定ですね。準備に不備はありませんか？"
        }

        val nextAlarm = alarmRepository.nextEnabled(nowMillis)
        if (hour >= NIGHT_HOUR) {
            return if (nextAlarm != null) {
                "本日のタスクはお疲れ様でした。明日は ${nextAlarm.timeLabel} にアラームがセットされていますね。ゆっくり休んでください。"
            } else {
                "マスター、明日のアラームが設定されていないようです。設定、忘れていませんか？"
            }
        }

        if (nextEvent != null) {
            val time = CuraTime.formatHourMinute(nextEvent.startTime)
            return "次回の同期予定は $time 、内容は『${nextEvent.summary}』ですね。"
        }

        return when (hour) {
            in 5..10 -> CharacterLines.morningGreeting
            in 11..14 -> CharacterLines.noonGreeting
            in 15..18 -> CharacterLines.eveningGreeting
            else -> CharacterLines.nightGreeting
        }.random(random)
    }

    /** 雑談。時間帯・曜日・月・レベルで候補が積み上がる。 */
    private fun flavorLine(characterLevel: Int, nowMillis: Long): String {
        val dateTime = CuraTime.toLocalDateTime(nowMillis)
        val hour = dateTime.hour

        if (hour in 0..4) return CharacterLines.lateNight.random(random)

        val candidates = mutableListOf<String>()
        candidates += CharacterLines.baseFlavor

        if (characterLevel >= 30) candidates += CharacterLines.flavorLv30

        candidates += when (hour) {
            in 5..10 -> CharacterLines.flavorMorning
            in 11..14 -> CharacterLines.flavorNoon
            in 15..18 -> CharacterLines.flavorEvening
            else -> CharacterLines.flavorNight
        }

        candidates += CharacterLines.byDayOfWeek[CuraTime.calendarDayOfWeek(dateTime.date)].orEmpty()

        if (characterLevel < 4) candidates += CharacterLines.flavorNovice
        if (characterLevel >= 4) candidates += CharacterLines.flavorLv4
        if (characterLevel >= 7) candidates += CharacterLines.flavorLv7

        candidates += CharacterLines.forSpecialDate(dateTime.monthNumber, dateTime.dayOfMonth)
        candidates += CharacterLines.byMonth[dateTime.monthNumber].orEmpty()

        return candidates.random(random)
    }

    private companion object {
        const val MEMORY_UNLOCK_TAPS = 300L
        const val MILESTONE_INTERVAL = 30L
        const val RAPID_TAP_THRESHOLD = 8
        const val MEMORY_ROLL = 5
        const val PRACTICAL_ROLL = 40
        const val LOW_BATTERY_PERCENT = 15
        const val NIGHT_HOUR = 20
        const val ONE_HOUR_MILLIS = 60L * 60L * 1000L
    }
}
