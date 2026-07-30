package com.example.voicevox

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.voicevox.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File as JavaFile
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private lateinit var launcherLayout: View
    private lateinit var toolbar: View
    private lateinit var fragmentContainer: View

    private lateinit var welcomeTitle: TextView
    private lateinit var playerLevelText: TextView
    private lateinit var expProgressBar: ProgressBar
    private lateinit var expValueText: TextView

    private lateinit var charLevelText: TextView
    private lateinit var charExpProgressBar: ProgressBar
    private lateinit var charExpText: TextView

    private lateinit var nextQuestText: TextView
    private lateinit var topMissionText: TextView
    private lateinit var nextSummonText: TextView

    private lateinit var systemLogText: TextView
    private lateinit var sysLogLabel: TextView

    private lateinit var launchAlarmButton: View
    private lateinit var launchTaskButton: View
    private lateinit var launchTimetableButton: View
    private lateinit var launchAttendanceButton: View
    private lateinit var layoutAttendanceButton: View

    private lateinit var dialogueBubble: View
    private lateinit var dialogueText: TextView

    private var logHandler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null

    private var systemUpdateHandler = Handler(Looper.getMainLooper())
    private var systemUpdateRunnable = object : Runnable {
        override fun run() {
            updateSystemHud()
            systemUpdateHandler.postDelayed(this, 1000)
        }
    }

    private var lastTapTime: Long = 0
    private var tapCount: Int = 0
    private var dialogueJob: Job? = null
    private var expGainJob: Job? = null
    private var idleDialogueJob: Job? = null
    private var isDialogueSkippable: Boolean = true

    private var devUnlockTapCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // UI初期化
        initUiElements()

        // パーミッション等の初期チェック
        checkOverlayPermission()
        checkBatteryOptimization()
        checkExactAlarmPermission()
        checkNotificationPermission()

        // 初期アニメーションとデータ読み込み
        runInitialLoadingAnimation()
        updatePlayerStatus()
        updateDashboardInfo()
        updateAttendanceButtonVisibility()
        updateCharacterCostume()

        // 継続的な更新処理
        systemUpdateHandler.post(systemUpdateRunnable)
        startSystemLogLoop()
        startExpGainTimer()
        startIdleTimer()
        
        scheduleMidnightRefresh()
        clearTempAudioFiles()

        // メインナビゲーション
        setupNavigation()
        setupCharacterDialogue()
    }

    private fun initUiElements() {
        launcherLayout = findViewById(R.id.launcherLayout)
        toolbar = findViewById(R.id.toolbar)
        fragmentContainer = findViewById(R.id.fragmentContainer)

        welcomeTitle = findViewById(R.id.welcomeTitle)
        playerLevelText = findViewById(R.id.playerLevelText)
        expProgressBar = findViewById(R.id.expProgressBar)
        expValueText = findViewById(R.id.expValueText)

        charLevelText = findViewById(R.id.charLevelText)
        charExpProgressBar = findViewById(R.id.charExpProgressBar)
        charExpText = findViewById(R.id.charExpText)

        nextQuestText = findViewById(R.id.nextQuestText)
        topMissionText = findViewById(R.id.topMissionText)
        nextSummonText = findViewById(R.id.nextSummonText)

        systemLogText = findViewById(R.id.systemLogText)
        sysLogLabel = findViewById(R.id.sysLogLabel)

        launchAlarmButton = findViewById(R.id.layoutAlarmButton)
        launchTaskButton = findViewById(R.id.layoutTaskButton)
        launchTimetableButton = findViewById(R.id.launchTimetableButton)
        launchAttendanceButton = findViewById(R.id.launchAttendanceButton)
        layoutAttendanceButton = findViewById(R.id.layoutAttendanceButton)

        dialogueBubble = findViewById(R.id.dialogueBubble)
        dialogueText = findViewById(R.id.dialogueText)

        // 背景などの装飾（存在すればパルスさせる）
        findViewById<View>(R.id.statusCard)?.let { pulseBorder(it) }
        findViewById<View>(R.id.characterSection)?.let { pulseBorder(it) }
    }

    private fun setupNavigation() {
        findViewById<View>(R.id.btnQuickSettings).setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment())
                .commit()
            toolbar.findViewById<TextView>(resources.getIdentifier("toolbarTitle", "id", packageName))?.text = "SETTINGS"
        }

        findViewById<View>(R.id.toolbar).findViewById<View>(android.R.id.home)?.setOnClickListener {
            showLauncherView()
        }
        
        // Toolbarの戻るボタン（独自実装があれば）
        findViewById<View>(resources.getIdentifier("btnToolbarBack", "id", packageName))?.setOnClickListener {
            showLauncherView()
        }

        launchAlarmButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, AlarmFragment()).commit()
            toolbar.findViewById<TextView>(resources.getIdentifier("toolbarTitle", "id", packageName))?.text = "ALARM_SYNC"
        }

        launchTaskButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, TaskFragment()).commit()
            toolbar.findViewById<TextView>(resources.getIdentifier("toolbarTitle", "id", packageName))?.text = "TASK_CORE"
        }

        launchTimetableButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, TimetableFragment()).commit()
            toolbar.findViewById<TextView>(resources.getIdentifier("toolbarTitle", "id", packageName))?.text = "SCHEDULE_MAP"
        }

        launchAttendanceButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction().replace(R.id.fragmentContainer, AttendanceManagerFragment()).commit()
            toolbar.findViewById<TextView>(resources.getIdentifier("toolbarTitle", "id", packageName))?.text = "ATTENDANCE_LINK"
        }

        sysLogLabel.setOnClickListener {
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            if (prefs.getBoolean("developer_mode_unlocked", false)) return@setOnClickListener

            devUnlockTapCount++
            if (devUnlockTapCount >= 7) {
                prefs.edit { putBoolean("developer_mode_unlocked", true) }
                Toast.makeText(this, "デバッガー権限を取得しました！", Toast.LENGTH_SHORT).show()
                devUnlockTapCount = 0
            } else if (devUnlockTapCount > 2) {
                val remaining = 7 - devUnlockTapCount
                Toast.makeText(this, "デバッガーになるまであと $remaining 回...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun startSystemLogLoop() {
        val logs = CuraMessageManager.getSystemLogs(this)
        var logIndex = 0
        val interval = CuraMessageManager.getIntConstant(this, "log_update_interval_ms", 3000).toLong()

        logRunnable?.let { logHandler.removeCallbacks(it) }
        logRunnable = object : Runnable {
            override fun run() {
                systemLogText.text = logs[logIndex]
                logIndex = (logIndex + 1) % logs.size
                logHandler.postDelayed(this, interval)
            }
        }
        logHandler.post(logRunnable!!)
    }

    private fun updatePlayerStatus() {
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val userName = appPrefs.getString("user_name", "PLAYER")
        welcomeTitle.text = userName

        val playerPrefs = getSharedPreferences("PlayerPrefs", MODE_PRIVATE)
        val playerTotalExp = playerPrefs.getLong("totalExp", 0L)
        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()

        fun calculateLevelInfo(exp: Long): Triple<Int, Long, Long> {
            val lv = (exp / expPerLevel).toInt() + 1
            val currentLevelExp = (lv - 1) * expPerLevel
            return Triple(lv, exp - currentLevelExp, expPerLevel)
        }

        val (lv, current, required) = calculateLevelInfo(playerTotalExp)
        playerLevelText.text = "Lv.$lv (RANK: MASTER)"
        expProgressBar.max = required.toInt()
        expProgressBar.progress = current.toInt()
        expValueText.text = "$current / $required EXP"

        val showPlayerLv = appPrefs.getBoolean("show_player_level", true)
        val pVisibility = if (showPlayerLv) View.VISIBLE else View.GONE
        playerLevelText.visibility = pVisibility
        expProgressBar.visibility = pVisibility
        expValueText.visibility = pVisibility

        // Character Level
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val charTotalExp = charPrefs.getLong("totalExp", 0L)
        val (cLv, cCurr, cReq) = calculateLevelInfo(charTotalExp)
        charLevelText.text = "CURA Lv.$cLv"
        charExpProgressBar.max = cReq.toInt()
        charExpProgressBar.progress = cCurr.toInt()
        charExpText.text = "$cCurr/$cReq"

        val showCharLv = appPrefs.getBoolean("show_char_level", true)
        findViewById<View>(R.id.charLevelCard).visibility = if (showCharLv) View.VISIBLE else View.GONE

        checkAndTriggerStory(cLv)
    }

    private fun checkAndTriggerStory(currentLv: Int) {
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        if (!charPrefs.getBoolean("memory_unlocked", false)) return

        val lastSeenLv = charPrefs.getInt("last_seen_story_lv", 0)
        
        // 未読の中で条件を満たすストーリーを探す
        val milestones = listOf(5, 10, 15, 20, 30)
        val nextMilestone = milestones.firstOrNull { it > lastSeenLv && currentLv >= it }
        
        if (nextMilestone != null) {
            CuraMessageManager.getLevelStory(this, nextMilestone)?.let { story ->
                showDialogueTextBubble(story, isSkippable = false)
                charPrefs.edit { putInt("last_seen_story_lv", nextMilestone) }
            }
        }
    }

    private fun setupCharacterDialogue() {
        val characterImage = findViewById<View>(R.id.characterImage)
        val touchTarget = findViewById<View>(R.id.characterTouchTarget)

        touchTarget.setOnClickListener {
            if (!isDialogueSkippable) return@setOnClickListener

            resetIdleTimer()
            val currentTime = System.currentTimeMillis()

            val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
            val currentCount = charPrefs.getLong("cumulativeInteractionCount", 0L) + 1
            charPrefs.edit { putLong("cumulativeInteractionCount", currentCount) }

            val unlockThreshold = CuraMessageManager.getIntConstant(this, "memory_unlock_tap_count", 300).toLong()

            // 特別な昔話の解放
            if (!charPrefs.getBoolean("memory_unlocked", false) && currentCount >= unlockThreshold) {
                charPrefs.edit { putBoolean("memory_unlocked", true) }
                CuraMessageManager.getMilestoneMessage(this, unlockThreshold)?.let { msg ->
                    showDialogueTextBubble(msg, isSkippable = false)
                    return@setOnClickListener
                }
            }

            // マイルストーンメッセージ
            CuraMessageManager.getMilestoneMessage(this, currentCount)?.let { msg ->
                showDialogueTextBubble(msg, isSkippable = false)
                return@setOnClickListener
            }

            // 連打判定
            if (currentTime - lastTapTime < 300) {
                tapCount++
            } else {
                if (dialogueBubble.alpha > 0f) tapCount++ else tapCount = 1
            }
            lastTapTime = currentTime

            characterImage.animate().scaleX(1.01f).scaleY(0.99f).setDuration(80).withEndAction {
                characterImage.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).start()
            }.start()

            if (tapCount >= 8) {
                showDialogueTextBubble(CuraMessageManager.getRandomRapidTapReaction(this))
            } else {
                showRandomDialogue()
            }
        }
    }

    private fun showRandomDialogue() {
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val charTotalExp = charPrefs.getLong("totalExp", 0L)
        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()
        val charLv = (charTotalExp / expPerLevel).toInt() + 1
        val isMemoryUnlocked = charPrefs.getBoolean("memory_unlocked", false)

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hasUrgentTasks = ScheduleLoader.hasPriority5Tasks(this)

        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        val randomRoll = (1..100).random()

            // --- 抽選ロジック ---
        val dialogue = when {
            // 1. 追憶 (解放済かつ低確率 5%)
            isMemoryUnlocked && randomRoll <= 5 -> {
                // 回想はここでも動的に分岐可能だが、現状はランダムな季節/曜日/挨拶を優先する設計に合わせる
                CuraMessageManager.getRandomGreeting(this) 
            }
            
            // 2. 実用的セリフ (40%)
            randomRoll <= 45 -> {
                when {
                    battery < 15 -> "マスター、電力が残りわずかです！キュラが消えちゃう前に、充電をお願いします…！"
                    hasUrgentTasks -> "マスター、期限が迫っている重要ミッションがあります。キュラと一緒に片付けちゃいましょう！"
                    nextEvent != null && (nextEvent.startTime - now) < 3600000 -> {
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEvent.startTime))
                        "マスター、まもなく ${timeStr} から『${nextEvent.summary}』の予定ですね。準備に不備はありませんか？"
                    }
                    else -> CuraMessageManager.getRandomGreeting(this)
                }
            }
            
            // 3. フレーバー (55%)
            else -> {
                val seasonal = CuraMessageManager.getRandomSeasonalLine(this)
                val dayOfWeek = CuraMessageManager.getRandomDayOfWeekLine(this)
                val flavor = CuraMessageManager.getRandomGreeting(this)
                seasonal ?: dayOfWeek ?: flavor
            }
        }

        showDialogueTextBubble(dialogue)
    }

    private fun showDialogueTextBubble(text: String, isSkippable: Boolean = true) {
        dialogueJob?.cancel()
        isDialogueSkippable = isSkippable 

        dialogueJob = lifecycleScope.launch {
            val parts = text.split("|")
            for (i in parts.indices) {
                val currentPart = parts[i].trim()
                dialogueText.text = currentPart
                dialogueBubble.animate().cancel()
                dialogueBubble.alpha = 1f

                val displayTime = (currentPart.length * 120L + 300L).coerceAtLeast(1500L)

                if (i < parts.size - 1) {
                    delay(displayTime)
                    dialogueBubble.animate().alpha(0f).setDuration(250).start()
                    delay(300.milliseconds)
                } else {
                    delay(displayTime + 600L)
                    dialogueBubble.animate().alpha(0f).setDuration(400).withEndAction {
                        tapCount = 0
                        isDialogueSkippable = true
                    }.start()
                }
            }
        }
    }

    private fun startIdleTimer() {
        val checkInterval = CuraMessageManager.getIntConstant(this, "idle_check_interval_ms", 30000).toLong()
        val threshold = CuraMessageManager.getIntConstant(this, "idle_threshold_ms", 60000).toLong()

        idleDialogueJob?.cancel()
        idleDialogueJob = lifecycleScope.launch {
            while (true) {
                delay(checkInterval)
                if (System.currentTimeMillis() - lastTapTime > threshold && launcherLayout.isVisible) {
                    showDialogueTextBubble(CuraMessageManager.getRandomIdleLine(this@MainActivity))
                }
            }
        }
    }

    private fun resetIdleTimer() {
        lastTapTime = System.currentTimeMillis()
        startIdleTimer()
    }

    private fun startExpGainTimer() {
        expGainJob?.cancel()
        expGainJob = lifecycleScope.launch {
            while (true) {
                delay(60000) // 1分
                addSessionExp(1)
            }
        }
    }

    private fun addSessionExp(amount: Int) {
        getSharedPreferences("PlayerPrefs", MODE_PRIVATE).edit {
            val current = getSharedPreferences("PlayerPrefs", MODE_PRIVATE).getLong("totalExp", 0L)
            putLong("totalExp", current + amount)
        }
        getSharedPreferences("CharacterPrefs", MODE_PRIVATE).edit {
            val current = getSharedPreferences("CharacterPrefs", MODE_PRIVATE).getLong("totalExp", 0L)
            putLong("totalExp", current + amount)
        }
        updatePlayerStatus()
    }

    private fun updateSystemHud() {
        val timeText = findViewById<TextView>(R.id.hudTimeText)
        val batteryText = findViewById<TextView>(R.id.hudBatteryText)
        val batteryBar = findViewById<ProgressBar>(R.id.hudBatteryBar)
        val now = Calendar.getInstance().time
        timeText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        batteryText.text = "$level%"
        batteryBar.progress = level
    }

    private fun updateDashboardInfo() {
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        findViewById<TextView>(R.id.nextQuestText).text = nextEvent?.let {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
            "$time ${it.summary}"
        } ?: "本日の予定は終了しました"

        val tasks = ScheduleLoader.loadTasksForToday(this)
        findViewById<TextView>(R.id.topMissionText).text = if (tasks.isNotEmpty()) tasks[0] else "すべてのタスクをクリア"

        val alarmPrefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString("alarmListJSON", null)
        var nextAlarmStr = "未設定"
        if (alarmJson != null) {
            val arr = org.json.JSONArray(alarmJson)
            val activeAlarms = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getBoolean("isEnabled")) activeAlarms.add(obj.getInt("hour") to obj.getInt("minute"))
            }
            if (activeAlarms.isNotEmpty()) {
                val cal = Calendar.getInstance()
                val ch = cal.get(Calendar.HOUR_OF_DAY)
                val cm = cal.get(Calendar.MINUTE)
                val next = activeAlarms.map { (h, m) ->
                    var diff = (h * 60 + m) - (ch * 60 + cm)
                    if (diff <= 0) diff += 24 * 60
                    diff to (h to m)
                }.minBy { it.first }.second
                nextAlarmStr = String.format(Locale.getDefault(), "%02d:%02d", next.first, next.second)
            }
        }
        findViewById<TextView>(R.id.nextSummonText).text = nextAlarmStr
    }

    private fun showFeatureView() {
        launcherLayout.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        fragmentContainer.visibility = View.VISIBLE
    }

    private fun showLauncherView() {
        if (launcherLayout.isVisible) return
        launcherLayout.visibility = View.VISIBLE
        toolbar.visibility = View.GONE
        fragmentContainer.visibility = View.GONE
        updatePlayerStatus()
        updateDashboardInfo()
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment != null) supportFragmentManager.beginTransaction().remove(fragment).commit()
    }

    override fun onResume() {
        super.onResume()
        updatePlayerStatus()
        updateDashboardInfo()
        updateAttendanceButtonVisibility()
        updateCharacterCostume()
    }

    private fun updateAttendanceButtonVisibility() {
        val schedulePrefs = getSharedPreferences("SchedulePrefs", MODE_PRIVATE)
        val customJson = schedulePrefs.getString("eventListJSON", "[]")
        var hasTracked = false
        try {
            val arr = org.json.JSONArray(customJson)
            for (i in 0 until arr.length()) if (arr.getJSONObject(i).optBoolean("isAttendanceTracked", false)) hasTracked = true
        } catch (e: Exception) {}

        val attendancePrefs = getSharedPreferences("AttendancePrefs", MODE_PRIVATE)
        for (key in attendancePrefs.all.keys) if (key.startsWith("track_") && attendancePrefs.all[key] == true) hasTracked = true
        layoutAttendanceButton.visibility = if (hasTracked) View.VISIBLE else View.GONE
    }

    private fun updateCharacterCostume() {
        val characterImage = findViewById<ImageView>(R.id.characterImage)
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
        val isWeekend = dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY
        val isSummer = month in 6..9

        val costumeRes = when {
            isSummer && isWeekend -> R.drawable.guardian_character_summer_casual
            isWeekend -> R.drawable.guardian_character_casual
            isSummer -> R.drawable.guardian_character_summer
            else -> R.drawable.guardian_character
        }
        characterImage.setImageResource(costumeRes)
    }

    private fun pulseBorder(view: View) {
        ObjectAnimator.ofFloat(view, View.ALPHA, 0.3f, 1.0f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun runInitialLoadingAnimation() {
        val overlay = findViewById<View>(R.id.initialLoadingOverlay)
        val logText = findViewById<TextView>(R.id.loadingLogText)
        val statusText = findViewById<TextView>(R.id.loadingStatusText)
        val progressBar = findViewById<ProgressBar>(R.id.loadingProgressBar)
        val logs = CuraMessageManager.getBootLogs(this)

        lifecycleScope.launch {
            for (i in 0..100) {
                delay(3) 
                progressBar.progress = i
                if (i % 10 == 0) {
                    val idx = (i / 10).coerceAtMost(logs.size - 1)
                    logText.append("> ${logs[idx]}\n")
                    statusText.text = logs[idx]
                }
            }
            delay(50)
            statusText.text = "ESTABLISHED"
            overlay.animate().alpha(0f).setDuration(150).withEndAction { overlay.visibility = View.GONE }.start()
        }
    }

    private fun clearTempAudioFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = JavaFile(filesDir, "voice_cache")
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles()
                val now = System.currentTimeMillis()
                files?.forEach { if (now - it.lastModified() > 3 * 24 * 60 * 60 * 1000) it.delete() }
            }
        }
    }

    private fun scheduleMidnightRefresh() {
        val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply { action = "REFRESH_CALENDARS" }
        val pi = android.app.PendingIntent.getBroadcast(this, 999, intent, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 5); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        am.setInexactRepeating(android.app.AlarmManager.RTC_WAKEUP, cal.timeInMillis, android.app.AlarmManager.INTERVAL_DAY, pi)
        sendBroadcast(intent)
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val am = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!am.canScheduleExactAlarms()) { /* Handle request */ }
        }
    }

    private fun checkOverlayPermission() { if (!Settings.canDrawOverlays(this)) { } }
    private fun checkBatteryOptimization() { if (!(getSystemService(POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(packageName)) { } }
    private fun checkNotificationPermission() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) { } }

    override fun onDestroy() {
        super.onDestroy()
        logRunnable?.let { logHandler.removeCallbacks(it) }
        systemUpdateHandler.removeCallbacks(systemUpdateRunnable)
        expGainJob?.cancel()
        idleDialogueJob?.cancel()
    }
}
