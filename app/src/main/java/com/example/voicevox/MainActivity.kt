package com.example.voicevox

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.isVisible
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

        // Toolbarの初期設定
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { showLauncherView() }

        // Androidの戻るボタンの挙動をカスタマイズ
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.fragmentContainer.isVisible) {
                    showLauncherView()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

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

        // アニメーション適用
        setupCyberAnimations()

        // 継続的な更新処理
        systemUpdateHandler.post(systemUpdateRunnable)
        startSystemLogLoop()
        startExpGainTimer()
        startIdleTimer()
        
        scheduleMidnightRefresh()
        clearTempAudioFiles()

        // ナビゲーション設定
        setupNavigation()
        setupCharacterDialogue()
    }

    private fun setupCyberAnimations() {
        // メインボタンの「枠（border）」部分のみを点滅させる
        pulseBorder(binding.borderAlarm)
        pulseBorder(binding.borderTask)
        pulseBorder(binding.borderTimetable)
        pulseBorder(binding.borderAttendance)
        
        // レベルカード全体ではなく、もし専用の枠Viewがあればそれを点滅させるべきですが
        // 今は文字まで点滅するのを防ぐため、一旦カード全体のパルスは停止します。
    }

    private fun setupNavigation() {
        // メインメニューボタン
        binding.launchAlarmButton.setOnClickListener { switchFragment(AlarmFragment(), R.string.title_alarm_sync) }
        binding.launchTaskButton.setOnClickListener { switchFragment(TaskFragment(), R.string.title_task_core) }
        binding.launchTimetableButton.setOnClickListener { switchFragment(TimetableFragment(), R.string.title_schedule_map) }
        binding.launchAttendanceButton.setOnClickListener { switchFragment(AttendanceManagerFragment(), R.string.title_attendance_link) }
        
        // クイック設定
        binding.btnQuickSettings.setOnClickListener { switchFragment(SettingsFragment(), R.string.title_settings) }

        // ボトムナビゲーションの動作復旧
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_alarm -> { switchFragment(AlarmFragment(), R.string.title_alarm_sync); true }
                R.id.nav_tasks -> { switchFragment(TaskFragment(), R.string.title_task_core); true }
                R.id.nav_timetable -> { switchFragment(TimetableFragment(), R.string.title_schedule_map); true }
                else -> false
            }
        }

        binding.sysLogLabel.setOnClickListener {
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            if (prefs.getBoolean("developer_mode_unlocked", false)) return@setOnClickListener

            devUnlockTapCount++
            if (devUnlockTapCount >= 7) {
                prefs.edit { putBoolean("developer_mode_unlocked", true) }
                Toast.makeText(this, getString(R.string.toast_dev_mode_unlocked), Toast.LENGTH_SHORT).show()
                devUnlockTapCount = 0
            } else if (devUnlockTapCount > 2) {
                val remaining = 7 - devUnlockTapCount
                Toast.makeText(this, getString(R.string.toast_dev_mode_remaining, remaining), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun switchFragment(fragment: androidx.fragment.app.Fragment, titleRes: Int) {
        showFeatureView()
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        binding.toolbar.title = getString(titleRes)
    }

    private fun startSystemLogLoop() {
        val logs = CuraMessageManager.getSystemLogs(this)
        var logIndex = 0
        val interval = CuraMessageManager.getIntConstant(this, "log_update_interval_ms", 3000).toLong()

        logRunnable?.let { logHandler.removeCallbacks(it) }
        logRunnable = object : Runnable {
            override fun run() {
                binding.systemLogText.text = logs[logIndex]
                logIndex = (logIndex + 1) % logs.size
                logHandler.postDelayed(this, interval)
            }
        }
        logHandler.post(logRunnable!!)
    }

    private fun updatePlayerStatus() {
        val appPrefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        val userName = appPrefs.getString("user_name", "PLAYER")
        binding.welcomeTitle.text = userName

        val playerPrefs = getSharedPreferences("PlayerPrefs", MODE_PRIVATE)
        val playerTotalExp = playerPrefs.getLong("totalExp", 0L)
        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()

        fun calculateLevelInfo(exp: Long): Triple<Int, Long, Long> {
            val lv = (exp / expPerLevel).toInt() + 1
            val currentLevelExp = (lv - 1) * expPerLevel
            return Triple(lv, exp - currentLevelExp, expPerLevel)
        }

        val (lv, current, required) = calculateLevelInfo(playerTotalExp)
        binding.playerLevelText.text = "Lv.$lv (RANK: MASTER)"
        binding.expProgressBar.max = required.toInt()
        binding.expProgressBar.progress = current.toInt()
        binding.expValueText.text = "$current / $required EXP"

        val showPlayerLv = appPrefs.getBoolean("show_player_level", true)
        val pVisibility = if (showPlayerLv) View.VISIBLE else View.GONE
        binding.playerLevelText.visibility = pVisibility
        binding.expProgressBar.visibility = pVisibility
        binding.expValueText.visibility = pVisibility

        // Character Level
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val charTotalExp = charPrefs.getLong("totalExp", 0L)
        val (cLv, cCurr, cReq) = calculateLevelInfo(charTotalExp)
        binding.charLevelText.text = "CURA Lv.$cLv"
        binding.charExpProgressBar.max = cReq.toInt()
        binding.charExpProgressBar.progress = cCurr.toInt()
        binding.charExpText.text = "$cCurr/$cReq"

        val showCharLv = appPrefs.getBoolean("show_char_level", true)
        binding.charLevelCard.visibility = if (showCharLv) View.VISIBLE else View.GONE

        checkAndTriggerStory(cLv)
    }

    private fun checkAndTriggerStory(currentLv: Int) {
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        if (!charPrefs.getBoolean("memory_unlocked", false)) return

        val lastSeenLv = charPrefs.getInt("last_seen_story_lv", 0)
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
        binding.characterTouchTarget.setOnClickListener {
            if (!isDialogueSkippable) return@setOnClickListener

            resetIdleTimer()
            val currentTime = System.currentTimeMillis()

            val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
            val currentCount = charPrefs.getLong("cumulativeInteractionCount", 0L) + 1
            charPrefs.edit { putLong("cumulativeInteractionCount", currentCount) }

            val unlockThreshold = CuraMessageManager.getIntConstant(this, "memory_unlock_tap_count", 300).toLong()

            if (!charPrefs.getBoolean("memory_unlocked", false) && currentCount >= unlockThreshold) {
                charPrefs.edit { putBoolean("memory_unlocked", true) }
                CuraMessageManager.getMilestoneMessage(this, unlockThreshold)?.let { msg ->
                    showDialogueTextBubble(msg, isSkippable = false)
                    return@setOnClickListener
                }
            }

            CuraMessageManager.getMilestoneMessage(this, currentCount)?.let { msg ->
                showDialogueTextBubble(msg, isSkippable = false)
                return@setOnClickListener
            }

            if (currentTime - lastTapTime < 300) {
                tapCount++
            } else {
                if (binding.dialogueBubble.alpha > 0f) tapCount++ else tapCount = 1
            }
            lastTapTime = currentTime

            binding.characterImage.animate().scaleX(1.01f).scaleY(0.99f).setDuration(80).withEndAction {
                binding.characterImage.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).start()
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
        val isMemoryUnlocked = charPrefs.getBoolean("memory_unlocked", false)

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hasUrgentTasks = ScheduleLoader.hasPriority5Tasks(this)

        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        val randomRoll = (1..100).random()
        val dialogue = when {
            isMemoryUnlocked && randomRoll <= 5 -> CuraMessageManager.getRandomGreeting(this) 
            randomRoll <= 45 -> {
                when {
                    battery < 15 -> CuraMessageManager.getSituationalLine(this, "battery_low")
                    hasUrgentTasks -> CuraMessageManager.getSituationalLine(this, "urgent_tasks")
                    nextEvent != null && (nextEvent.startTime - now) < 3600000 -> {
                        val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEvent.startTime))
                        String.format(CuraMessageManager.getSituationalLine(this, "next_event_reminder"), timeStr, nextEvent.summary)
                    }
                    else -> CuraMessageManager.getRandomGreeting(this)
                }
            }
            else -> CuraMessageManager.getRandomSeasonalLine(this) ?: CuraMessageManager.getRandomDayOfWeekLine(this) ?: CuraMessageManager.getRandomGreeting(this)
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
                binding.dialogueText.text = currentPart
                binding.dialogueBubble.animate().cancel()
                binding.dialogueBubble.alpha = 1f

                val displayTime = (currentPart.length * 120L + 300L).coerceAtLeast(1500L)

                if (i < parts.size - 1) {
                    delay(displayTime)
                    binding.dialogueBubble.animate().alpha(0f).setDuration(250).start()
                    delay(300.milliseconds)
                } else {
                    delay(displayTime + 600L)
                    binding.dialogueBubble.animate().alpha(0f).setDuration(400).withEndAction {
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
                if (System.currentTimeMillis() - lastTapTime > threshold && binding.launcherLayout.isVisible) {
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
                delay(60000)
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
        val now = Calendar.getInstance().time
        binding.hudTimeText.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val level = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        binding.hudBatteryText.text = "$level%"
        binding.hudBatteryBar.progress = level
    }

    private fun updateDashboardInfo() {
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        binding.nextQuestText.text = nextEvent?.let {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
            "$time ${it.summary}"
        } ?: getString(R.string.dashboard_no_schedule)

        val tasks = ScheduleLoader.loadTasksForToday(this)
        binding.topMissionText.text = if (tasks.isNotEmpty()) tasks[0] else getString(R.string.dashboard_all_tasks_done)

        val alarmPrefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString("alarmListJSON", null)
        var nextAlarmStr = getString(R.string.dashboard_not_set)
        if (alarmJson != null) {
            try {
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
            } catch (e: Exception) {}
        }
        binding.nextSummonText.text = nextAlarmStr
    }

    private fun showFeatureView() {
        binding.launcherLayout.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        binding.fragmentContainer.visibility = View.VISIBLE
    }

    private fun showLauncherView() {
        if (binding.launcherLayout.isVisible) return
        binding.launcherLayout.visibility = View.VISIBLE
        binding.toolbar.visibility = View.GONE
        binding.fragmentContainer.visibility = View.GONE
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
        binding.layoutAttendanceButton.visibility = if (hasTracked) View.VISIBLE else View.GONE
    }

    private fun updateCharacterCostume() {
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
        binding.characterImage.setImageResource(costumeRes)
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
        val logs = CuraMessageManager.getBootLogs(this)
        lifecycleScope.launch {
            for (i in 0..100) {
                delay(3) 
                binding.loadingProgressBar.progress = i
                if (i % 10 == 0) {
                    val idx = (i / 10).coerceAtMost(logs.size - 1)
                    binding.loadingLogText.append("> ${logs[idx]}\n")
                    binding.loadingStatusText.text = logs[idx]
                }
            }
            delay(50)
            binding.loadingStatusText.text = "ESTABLISHED"
            binding.initialLoadingOverlay.animate().alpha(0f).setDuration(150).withEndAction { 
                binding.initialLoadingOverlay.visibility = View.GONE 
            }.start()
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
