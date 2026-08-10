package com.example.voicevox

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
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
import kotlinx.serialization.json.Json
import java.io.File as JavaFile
import java.text.SimpleDateFormat
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var logHandler = Handler(Looper.getMainLooper())

    private var systemUpdateHandler = Handler(Looper.getMainLooper())
    private var systemUpdateRunnable = object : Runnable {
        override fun run() {
            updateFlavorHud()
            systemUpdateHandler.postDelayed(this, 30000) // 30秒ごとに更新
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

        // Androidの戻るボタンの挙動
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

        // マーキー（流れる文字）を有効化するためにフォーカスを強制する
        binding.homeContent.topMissionText.isSelected = true
        binding.homeContent.nextQuestText.isSelected = true
        binding.homeContent.nextSummonText.isSelected = true
        binding.hudFlavorText.isSelected = true

        // 継続的な更新処理
        systemUpdateHandler.post(systemUpdateRunnable)
        startExpGainTimer()
        startIdleTimer()
        
        scheduleMidnightRefresh()
        clearTempAudioFiles()

        // ナビゲーション設定
        setupNavigation()
        setupCharacterDialogue()

        // 緊急停止ボタンの設定
        binding.btnEmergencyStop.setOnClickListener {
            stopAllAlarms()
            Toast.makeText(this, "すべてのアラームを強制停止しました", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopAllAlarms() {
        val intent = Intent(this, AlarmService::class.java)
        stopService(intent)
    }

    private fun setupCyberAnimations() {
        // 枠の点滅（現在はすべて停止）
    }

    private fun setupNavigation() {
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            // アニメーション：背後の白い円を移動させる
            animateNavBulge(item.itemId)

            when (item.itemId) {
                R.id.nav_home -> { 
                    showLauncherView(fromNavigation = true)
                    true 
                }
                R.id.nav_alarm -> { 
                    switchFragment(AlarmFragment(), R.string.title_alarm_sync)
                    true 
                }
                R.id.nav_tasks -> { 
                    switchFragment(TaskFragment(), R.string.title_task_core)
                    true 
                }
                R.id.nav_timetable -> { 
                    switchFragment(TimetableFragment(), R.string.title_schedule_map)
                    true 
                }
                R.id.nav_attendance -> {
                    switchFragment(AttendanceManagerFragment(), R.string.title_attendance_link)
                    true
                }
                else -> false
            }
        }

        // デフォルト選択
        binding.bottomNavigation.selectedItemId = R.id.nav_home
        // animateNavBulgeは上の行でリスナーがトリガーされるため、ここでは不要


        // クイック設定
        binding.btnQuickSettings.setOnClickListener { 
            switchFragment(SettingsFragment(), R.string.title_settings)
        }
    }

    private fun animateNavBulge(itemId: Int) {
        val nav = binding.bottomNavigation
        val bulge = binding.navBulgeCircle
        
        // 実際に表示されているメニューアイテムを取得
        val visibleItems = mutableListOf<Int>()
        for (i in 0 until nav.menu.size()) {
            val item = nav.menu.getItem(i)
            if (item.isVisible) {
                visibleItems.add(item.itemId)
            }
        }
        
        val visibleCount = visibleItems.size
        if (visibleCount == 0) return

        // 選択されたアイテムが可視アイテムの中で何番目かを取得
        val index = visibleItems.indexOf(itemId).coerceAtLeast(0)

        nav.post {
            val totalWidth = nav.width
            val itemWidth = totalWidth / visibleCount
            val targetX = (itemWidth * index) + (itemWidth / 2) - (bulge.width / 2)
            
            // 円のスライド移動
            bulge.animate()
                .translationX(targetX.toFloat())
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
            
            // アイコン自体の持ち上げ（BottomNavigationViewの各子ビューに対して）
            val menuView = nav.getChildAt(0) as? android.view.ViewGroup ?: return@post
            menuView.clipChildren = false
            menuView.clipToPadding = false
            
            // BottomNavigationViewの内部構造では、非表示のアイテムは除外されているかGONEになっている
            // 子ビューのインデックスと可視アイテムのインデックスを対応させる
            var visibleViewIndex = 0
            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i) as? android.view.ViewGroup ?: continue
                itemView.clipChildren = false
                itemView.clipToPadding = false

                if (itemView.visibility != View.VISIBLE) continue
                
                // アイコンの持ち上げアニメーション
                // itemViewの最初の子要素（通常はアイコン）を取得し、安全にアニメーションを適用
                if (itemView.childCount > 0) {
                    val icon = itemView.getChildAt(0)
                    if (icon is android.view.ViewGroup) {
                        icon.clipChildren = false
                        icon.clipToPadding = false
                    }

                    if (visibleViewIndex == index) {
                        icon.animate().translationY(-64f).setDuration(300).start()
                        // 文字（通常は2番目の子要素）を取得して上に移動
                        if (itemView.childCount > 1) {
                            val label = itemView.getChildAt(1)
                            label.animate().translationY(-12f).setDuration(300).start()
                        }
                    } else {
                        icon.animate().translationY(0f).setDuration(250).start()
                        // 文字を元の位置に戻す
                        if (itemView.childCount > 1) {
                            val label = itemView.getChildAt(1)
                            label.animate().translationY(0f).setDuration(250).start()
                        }
                    }
                }
                visibleViewIndex++
            }
        }
    }

    private fun switchFragment(fragment: androidx.fragment.app.Fragment, titleRes: Int) {
        binding.launcherLayout.visibility = View.GONE
        binding.toolbar.visibility = View.VISIBLE
        binding.fragmentContainer.visibility = View.VISIBLE
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .commit()
        binding.toolbar.title = getString(titleRes)
    }

    private fun updatePlayerStatus() {
        val appPrefs = getSharedPreferences(CuraConstants.PREFS_APP, MODE_PRIVATE)

        val playerPrefs = getSharedPreferences(CuraConstants.PREFS_PLAYER, MODE_PRIVATE)
        val taskCount = playerPrefs.getInt(CuraConstants.KEY_COMPLETED_TASK_COUNT, 0)
        val alarmCount = playerPrefs.getInt(CuraConstants.KEY_ALARM_WAKEUP_COUNT, 0)

        val charPrefs = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE)
        val charTotalExp = charPrefs.getLong(CuraConstants.KEY_TOTAL_EXP, 0L)
        val expPerLevel = CuraMessageManager.getIntConstant(this, "exp_per_level", 100).toLong()

        fun calculateLevelInfo(exp: Long): Triple<Int, Long, Long> {
            val lv = (exp / expPerLevel).toInt() + 1
            val currentLevelExp = (lv - 1) * expPerLevel
            return Triple(lv, exp - currentLevelExp, expPerLevel)
        }

        val (lv, current, required) = calculateLevelInfo(charTotalExp)
        binding.homeContent.charLevelText.text = "CURA Lv.$lv"
        binding.homeContent.charExpProgressBar.max = required.toInt()
        binding.homeContent.charExpProgressBar.progress = current.toInt()
        binding.homeContent.charExpText.text = "$current/$required"

        val showCharLv = appPrefs.getBoolean("show_char_level", true)
        binding.homeContent.charGrowthLayout.visibility = if (showCharLv) View.VISIBLE else View.GONE

        checkAndTriggerStory(lv)
    }

    private fun checkAndTriggerStory(currentLv: Int) {
        val charPrefs = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE)
        if (!charPrefs.getBoolean(CuraConstants.KEY_MEMORY_UNLOCKED, false)) return

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
        binding.homeContent.characterTouchTarget.setOnClickListener {
            if (!isDialogueSkippable) return@setOnClickListener

            resetIdleTimer()
            val currentTime = System.currentTimeMillis()

            val charPrefs = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE)
            val currentCount = charPrefs.getLong(CuraConstants.KEY_CUMULATIVE_INTERACTION, 0L) + 1
            charPrefs.edit { putLong(CuraConstants.KEY_CUMULATIVE_INTERACTION, currentCount) }

            val unlockThreshold = CuraMessageManager.getIntConstant(this, "memory_unlock_tap_count", 300).toLong()

            if (!charPrefs.getBoolean(CuraConstants.KEY_MEMORY_UNLOCKED, false) && currentCount >= unlockThreshold) {
                charPrefs.edit { putBoolean(CuraConstants.KEY_MEMORY_UNLOCKED, true) }
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
                if (binding.homeContent.dialogueBubble.alpha > 0f) tapCount++ else tapCount = 1
            }
            lastTapTime = currentTime

            binding.homeContent.characterImage.animate().scaleX(1.01f).scaleY(0.99f).setDuration(80).withEndAction {
                binding.homeContent.characterImage.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).start()
            }.start()

            if (tapCount >= 8) {
                showDialogueTextBubble(CuraMessageManager.getRandomRapidTapReaction(this))
            } else {
                showRandomDialogue()
            }
        }
    }

    private fun showRandomDialogue() {
        val playerPrefs = getSharedPreferences(CuraConstants.PREFS_PLAYER, MODE_PRIVATE)
        val charPrefs = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE)
        
        // Priority 1: Pending Praise
        if (playerPrefs.getBoolean(CuraConstants.KEY_PENDING_TASK_PRAISE, false)) {
            playerPrefs.edit(commit = true) { putBoolean(CuraConstants.KEY_PENDING_TASK_PRAISE, false) }
            // パネルを一瞬光らせる（更新を通知）
            binding.homeContent.panelTasks.animate().alpha(0.3f).setDuration(150).withEndAction {
                binding.homeContent.panelTasks.animate().alpha(1.0f).setDuration(400).start()
            }.start()
            showDialogueTextBubble(CuraMessageManager.getSituationalLine(this, "task_completed_praise"))
            return
        }
        if (playerPrefs.getBoolean(CuraConstants.KEY_PENDING_ALARM_PRAISE, false)) {
            playerPrefs.edit(commit = true) { putBoolean(CuraConstants.KEY_PENDING_ALARM_PRAISE, false) }
            // パネルを一瞬光らせる（更新を通知）
            binding.homeContent.panelAlarm.animate().alpha(0.3f).setDuration(150).withEndAction {
                binding.homeContent.panelAlarm.animate().alpha(1.0f).setDuration(400).start()
            }.start()
            showDialogueTextBubble(CuraMessageManager.getSituationalLine(this, "alarm_wakeup_praise"))
            return
        }

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val tasks = ScheduleLoader.loadTasksForToday(this)
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        // Priority 2: Urgency/Status
        val dialogue = when {
            battery < 20 -> CuraMessageManager.getSituationalLine(this, "battery_low")
            tasks.size > 5 -> CuraMessageManager.getSituationalLine(this, "urgent_tasks")
            nextEvent != null && (nextEvent.startTime - now) < 1800000 -> {
                val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEvent.startTime))
                String.format(CuraMessageManager.getSituationalLine(this, "next_event_reminder"), timeStr, nextEvent.summary)
            }
            else -> {
                // Priority 3: Seasonal/Time-based / Normal
                val isMemoryUnlocked = charPrefs.getBoolean(CuraConstants.KEY_MEMORY_UNLOCKED, false)
                val randomRoll = (1..100).random()
                when {
                    isMemoryUnlocked && randomRoll <= 10 -> CuraMessageManager.getRandomGreeting(this)
                    tasks.isEmpty() && randomRoll <= 25 -> CuraMessageManager.getSituationalLine(this, "no_tasks_left")
                    randomRoll <= 50 -> CuraMessageManager.getRandomSeasonalLine(this) ?: CuraMessageManager.getRandomDayOfWeekLine(this) ?: CuraMessageManager.getRandomGreeting(this)
                    else -> CuraMessageManager.getRandomGreeting(this)
                }
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
                
                // 吹き出しを一旦リセット
                binding.homeContent.dialogueBubble.animate().cancel()
                binding.homeContent.dialogueBubble.alpha = 0f
                binding.homeContent.dialogueBubble.scaleX = 0.95f
                binding.homeContent.dialogueBubble.scaleY = 0.95f
                binding.homeContent.dialogueBubble.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .start()

                // タイプライター効果
                val fullText = currentPart
                for (j in 0..fullText.length) {
                    binding.homeContent.dialogueText.text = fullText.substring(0, j)
                    delay(40) // 1文字ごとの待機時間
                }

                val displayTime = (currentPart.length * 100L + 1000L).coerceAtLeast(2000L)

                if (i < parts.size - 1) {
                    delay(displayTime)
                    binding.homeContent.dialogueBubble.animate().alpha(0f).setDuration(200).start()
                    delay(250.milliseconds)
                } else {
                    delay(displayTime + 500L)
                    binding.homeContent.dialogueBubble.animate()
                        .alpha(0f)
                        .scaleX(0.98f)
                        .scaleY(0.98f)
                        .setDuration(300)
                        .withEndAction {
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
        getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE).edit {
            val current = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE).getLong(CuraConstants.KEY_TOTAL_EXP, 0L)
            putLong(CuraConstants.KEY_TOTAL_EXP, current + amount)
        }
        showExpFeedback(amount)
        updatePlayerStatus()
    }

    private fun showExpFeedback(amount: Int) {
        binding.homeContent.expFeedbackText.text = "+$amount EXP"
        binding.homeContent.expFeedbackText.visibility = View.VISIBLE
        binding.homeContent.expFeedbackText.alpha = 1f
        binding.homeContent.expFeedbackText.translationY = 0f
        binding.homeContent.expFeedbackText.animate()
            .alpha(0f)
            .translationY(-50f)
            .setDuration(1500)
            .withEndAction { binding.homeContent.expFeedbackText.visibility = View.GONE }
            .start()
    }

    private fun updateFlavorHud() {
        val flavorLines = listOf(
            "MEMORY SEA: CALM",
            "CORE TEMPERATURE: NOMINAL",
            "OBSERVATION MODE / ACTIVE",
            "CURA IS WATCHING THE STARS",
            "AWAITING INSTRUCTION...",
            "ALL SYSTEMS: STABLE",
            "DREAMING OF ELECTRIC SHEEP",
            "SYNC RATE: 100%",
            "TODAY IS A GOOD DAY TO BEGIN",
            "ARCHIVING DAILY FRAGMENTS",
            "CURA HEART RATE: NORMAL",
            "MEMORY SEA: SLIGHTLY DISTURBED",
            "NEW LOG ENTRY DETECTED"
        )
        
        // ランダムに選択
        val randomLine = flavorLines.random()
        
        // 少しフェードアウトしてから文字を変えてフェードインさせるとカッコいい
        binding.hudFlavorText.animate()
            .alpha(0f)
            .setDuration(500)
            .withEndAction {
                binding.hudFlavorText.text = randomLine
                binding.hudFlavorText.animate().alpha(0.8f).setDuration(500).start()
            }.start()
    }

    private fun updateDashboardInfo() {
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        binding.homeContent.nextQuestText.text = nextEvent?.let {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
            "$time ${it.summary}"
        } ?: "本日の予定は終了しました"

        val tasks = ScheduleLoader.loadTasksForToday(this)
        binding.homeContent.topMissionText.text = if (tasks.isNotEmpty()) tasks[0] else "すべてのタスクをクリア！"

        val alarmPrefs = getSharedPreferences(CuraConstants.PREFS_ALARM, MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString(CuraConstants.KEY_ALARM_LIST, null)
        var nextAlarmStr = "未設定です"
        if (alarmJson != null) {
            try {
                val activeAlarms = Json.decodeFromString<List<AlarmItem>>(alarmJson).filter { it.isEnabled }
                if (activeAlarms.isNotEmpty()) {
                    val cal = Calendar.getInstance()
                    val ch = cal.get(Calendar.HOUR_OF_DAY)
                    val cm = cal.get(Calendar.MINUTE)
                    val next = activeAlarms.map { item ->
                        var diff = (item.hour * 60 + item.minute) - (ch * 60 + cm)
                        if (diff <= 0) diff += 24 * 60
                        diff to (item.hour to item.minute)
                    }.minBy { it.first }.second
                    nextAlarmStr = String.format(Locale.getDefault(), "%02d:%02d", next.first, next.second)
                }
            } catch (e: Exception) {}
        }
        binding.homeContent.nextSummonText.text = nextAlarmStr
    }

    private fun showLauncherView(fromNavigation: Boolean = false) {
        binding.launcherLayout.visibility = View.VISIBLE
        binding.toolbar.visibility = View.GONE
        binding.fragmentContainer.visibility = View.GONE
        
        // ナビゲーションバーの状態をホームに同期
        // リスナー経由（fromNavigation=true）で呼ばれた場合は、再帰呼び出しを避けるために設定しない
        if (!fromNavigation && binding.bottomNavigation.selectedItemId != R.id.nav_home) {
            binding.bottomNavigation.selectedItemId = R.id.nav_home
        }
        
        updatePlayerStatus()
        updateDashboardInfo()
        updateAttendanceButtonVisibility()
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment != null) supportFragmentManager.beginTransaction().remove(fragment).commit()
    }

    override fun onResume() {
        super.onResume()
        updatePlayerStatus()
        updateDashboardInfo()
        updateAttendanceButtonVisibility()
        updateCharacterCostume()
        
        // ホーム画面が表示されている場合のみ、未読の褒め言葉や挨拶をチェック
        if (binding.launcherLayout.isVisible) {
            showRandomDialogue()
        }
    }

    private fun updateAttendanceButtonVisibility() {
        val schedulePrefs = getSharedPreferences(CuraConstants.PREFS_SCHEDULE, MODE_PRIVATE)
        val attendancePrefs = getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, MODE_PRIVATE)
        var hasTracked = false
        val customJson = schedulePrefs.getString(CuraConstants.KEY_EVENT_LIST, "[]") ?: "[]"
        if (customJson.contains("\"isAttendanceTracked\":true")) hasTracked = true
        if (!hasTracked) {
            val allKeys = attendancePrefs.all.keys
            val hasExternalTracked = allKeys.any { it.startsWith("track_") && attendancePrefs.getBoolean(it, false) }
            val hasManualCount = allKeys.any { it.startsWith("absent_") && (attendancePrefs.all[it] as? Number)?.toInt() ?: 0 > 0 }
            if (hasExternalTracked || hasManualCount) hasTracked = true
        }
        
        val item = binding.bottomNavigation.menu.findItem(R.id.nav_attendance)
        val wasVisible = item?.isVisible ?: false
        item?.isVisible = hasTracked
        
        // 可視性が変わった場合のみ、盛り上がりの位置を再調整する
        if (wasVisible != hasTracked) {
            animateNavBulge(binding.bottomNavigation.selectedItemId)
        }
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
        binding.homeContent.characterImage.setImageResource(costumeRes)
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
            // ステップを飛ばして高速化 (トータル約0.1秒)
            for (i in 0..100 step 2) {
                delay(2) 
                binding.loadingProgressBar.progress = i
                if (i % 20 == 0) {
                    val idx = (i / 20).coerceAtMost(logs.size - 1)
                    binding.loadingLogText.append("> ${logs[idx]}\n")
                    binding.loadingStatusText.text = logs[idx]
                }
            }
            delay(50)
            binding.loadingStatusText.text = "ESTABLISHED"
            binding.initialLoadingOverlay.animate().alpha(0f).setDuration(200).withEndAction {
                binding.initialLoadingOverlay.visibility = View.GONE 
                showRandomDialogue()
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
        systemUpdateHandler.removeCallbacks(systemUpdateRunnable)
        expGainJob?.cancel()
        idleDialogueJob?.cancel()
    }
}
