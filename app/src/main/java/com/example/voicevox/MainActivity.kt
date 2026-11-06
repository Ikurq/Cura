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
    private var breathingAnimator: android.animation.AnimatorSet? = null

    private val powerConnectionReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            when (intent?.action) {
                android.content.Intent.ACTION_POWER_CONNECTED -> {
                    showDialogueTextBubble("外部電源を検知しました。エネルギー充填を開始します、マスター。")
                }
                android.content.Intent.ACTION_POWER_DISCONNECTED -> {
                    showDialogueTextBubble("供給が停止されました。内部バッテリーでの駆動に移行します。")
                }
            }
        }
    }

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
        ScheduleEventHelper.performAutoMigration(this)
        updateAttendanceButtonVisibility()
        applyCharacterTheme(CharacterThemeManager.getCurrentTheme(this))

        // アニメーション適用
        setupCyberAnimations()
        startCuraBreathingAnimation()

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

        // 充電状態の監視登録
        val filter = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_POWER_CONNECTED)
            addAction(android.content.Intent.ACTION_POWER_DISCONNECTED)
        }
        registerReceiver(powerConnectionReceiver, filter)

        // ナビゲーション設定
        setupNavigation()
        setupCharacterDialogue()
        handleNavigationIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handleNavigationIntent(it) }
    }

    private fun handleNavigationIntent(intent: Intent) {
        if (intent.getStringExtra("OPEN_TAB") == "TASKS") {
            binding.bottomNavigation.selectedItemId = R.id.nav_tasks
        }
    }

    private fun stopAllAlarms() {
        val intent = Intent(this, AlarmService::class.java)
        stopService(intent)
    }

    private fun setupCyberAnimations() {
        // 枠の点滅（現在はすべて停止）
    }

    private fun startCuraBreathingAnimation() {
        val image = binding.homeContent.characterImage
        
        // より繊細に、よりゆっくりと (0.8%の拡大、5秒周期)
        val scaleX = ObjectAnimator.ofFloat(image, View.SCALE_X, 1.0f, 1.008f)
        val scaleY = ObjectAnimator.ofFloat(image, View.SCALE_Y, 1.0f, 1.008f)
        
        breathingAnimator = android.animation.AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 5000 // 5秒かけてゆっくり吸う
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    val reverseScaleX = ObjectAnimator.ofFloat(image, View.SCALE_X, 1.008f, 1.0f)
                    val reverseScaleY = ObjectAnimator.ofFloat(image, View.SCALE_Y, 1.008f, 1.0f)
                    
                    android.animation.AnimatorSet().apply {
                        playTogether(reverseScaleX, reverseScaleY)
                        duration = 5000 // 5秒かけてゆっくり吐く
                        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                        addListener(object : android.animation.AnimatorListenerAdapter() {
                            override fun onAnimationEnd(animation: android.animation.Animator) {
                                startCuraBreathingAnimation()
                            }
                        })
                        start()
                    }
                }
            })
            start()
        }
    }

    private fun applyCharacterFontTheme(theme: CharacterTheme) {
        val fontConfig = theme.fontConfig
        val headerTypeface = try {
            val customRes = fontConfig.customFontRes
            if (customRes != null) {
                androidx.core.content.res.ResourcesCompat.getFont(this, customRes)
                    ?: android.graphics.Typeface.create(fontConfig.headerFontName, if (fontConfig.isBoldHeader) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            } else {
                android.graphics.Typeface.create(fontConfig.headerFontName, if (fontConfig.isBoldHeader) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        } catch (e: Exception) {
            android.graphics.Typeface.DEFAULT_BOLD
        }

        val bodyTypeface = try {
            val customRes = fontConfig.customFontRes
            if (customRes != null) {
                androidx.core.content.res.ResourcesCompat.getFont(this, customRes)
                    ?: android.graphics.Typeface.create(fontConfig.bodyFontName, android.graphics.Typeface.NORMAL)
            } else {
                android.graphics.Typeface.create(fontConfig.bodyFontName, android.graphics.Typeface.NORMAL)
            }
        } catch (e: Exception) {
            android.graphics.Typeface.DEFAULT
        }

        // キャラクター名・見出し・セリフ等にヘッダー書体を適用
        binding.welcomeTitle.typeface = headerTypeface
        binding.hudFlavorText.typeface = headerTypeface
        binding.homeContent.dialogueText.typeface = headerTypeface
        binding.homeContent.dialogueText.letterSpacing = fontConfig.letterSpacing

        // 基本UI・カード内テキスト等にボディ書体を適用
        binding.homeContent.topMissionText.typeface = bodyTypeface
        binding.homeContent.nextQuestText.typeface = bodyTypeface
        binding.homeContent.nextSummonText.typeface = bodyTypeface
        binding.homeContent.charLevelText.typeface = bodyTypeface
        binding.homeContent.charExpText.typeface = bodyTypeface
    }

    private fun applyCharacterVisualTheme(theme: CharacterTheme) {
        val density = resources.displayMetrics.density

        // 1. 全体背景グラデーションの適用
        try {
            val startColor = android.graphics.Color.parseColor(theme.gradientStartHex)
            val endColor = android.graphics.Color.parseColor(theme.gradientEndHex)
            val gradientDrawable = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(startColor, endColor)
            )
            binding.mainRootLayout.background = gradientDrawable
        } catch (e: Exception) {
            // Ignore color parse error
        }

        // 2. カードの枠線カラーと角丸の適用（視認性向上アクセントカラー）
        try {
            val signatureColor = android.graphics.Color.parseColor(theme.signatureColorHex)
            val rosePink = androidx.core.content.ContextCompat.getColor(this, R.color.cyber_pink)
            val cyan = androidx.core.content.ContextCompat.getColor(this, R.color.cyber_cyan)
            val mintGreen = androidx.core.content.ContextCompat.getColor(this, R.color.cyber_lime)
            val cornerRadius = theme.cornerRadiusDp * density

            binding.welcomeTitle.setTextColor(signatureColor)
            binding.welcomeTitle.text = "${theme.id.uppercase()}_SYSTEM: ACTIVE"
            binding.homeContent.dialogueSpeakerLabel.text = ":: ${theme.id.uppercase()}"
            binding.homeContent.dialogueSpeakerLabel.setTextColor(signatureColor)

            // TODAY TASK: 明るめのローズピンク
            binding.homeContent.panelTasks.strokeColor = rosePink
            binding.homeContent.panelTasks.radius = cornerRadius

            // TIMETABLE: 明るめのシアン
            binding.homeContent.panelSchedule.strokeColor = cyan
            binding.homeContent.panelSchedule.radius = cornerRadius

            // WAKE_ALARM: 明るめのミントグリーン
            binding.homeContent.panelAlarm.strokeColor = mintGreen
            binding.homeContent.panelAlarm.radius = cornerRadius

            // CURA LEVEL: 明るめのシアン
            binding.homeContent.charGrowthLayout.strokeColor = cyan
            binding.homeContent.charGrowthLayout.radius = cornerRadius

            // DIALOGUE BUBBLE: キャラクターシンボル色（アンティークゴールド）
            binding.homeContent.dialogueBubble.strokeColor = signatureColor
            binding.homeContent.dialogueBubble.radius = cornerRadius
        } catch (e: Exception) {
            // Ignore color parse error
        }
    }

    private fun applyCharacterNavTheme(theme: CharacterTheme) {
        val navStyle = theme.navStyle
        val density = resources.displayMetrics.density

        // 1. インジケーター（バルジ）とナビバーの背景色・正円形状・境界線・シャドウを適用
        try {
            val navBgColor = android.graphics.Color.parseColor(navStyle.navBackgroundColorHex)
            val strokeColor = android.graphics.Color.parseColor(navStyle.strokeColorHex ?: navStyle.activeItemColorHex)
            val strokeWidthPx = (navStyle.strokeWidthDp * density).toInt()

            binding.navBulgeCustomShape.visibility = android.view.View.GONE
            binding.navBulgeCircle.radius = 35f * density // 常に美しい正円固定
            binding.navBulgeCircle.setCardBackgroundColor(navBgColor)
            binding.navBulgeCircle.strokeColor = strokeColor
            binding.navBulgeCircle.strokeWidth = strokeWidthPx
            binding.navBulgeCircle.cardElevation = 6f * density

            // Android P (API 28) 以上のカラーシャドウ対応（アクセントカラーのほのかなグロー光彩）
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                binding.navBulgeCircle.outlineSpotShadowColor = strokeColor
                binding.navBulgeCircle.outlineAmbientShadowColor = strokeColor
                binding.navBaseBar.outlineSpotShadowColor = strokeColor
                binding.navBaseBar.outlineAmbientShadowColor = strokeColor
            }

            binding.navBaseBar.backgroundTintList = android.content.res.ColorStateList.valueOf(navBgColor)
            binding.navBaseBar.elevation = 8f * density
        } catch (e: Exception) {
            // Ignore color parse error
        }

        // 2. ボトムナビのアイコン・文字の選択状態カラーを変更
        try {
            val activeColor = android.graphics.Color.parseColor(navStyle.activeItemColorHex)
            val inactiveColor = android.graphics.Color.parseColor(navStyle.inactiveItemColorHex)
            val colorStates = android.content.res.ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(-android.R.attr.state_checked)
                ),
                intArrayOf(activeColor, inactiveColor)
            )
            binding.bottomNavigation.itemIconTintList = colorStates
            binding.bottomNavigation.itemTextColor = colorStates
        } catch (e: Exception) {
            // Ignore fallback
        }

        // 3. アイコンの適用
        val menu = binding.bottomNavigation.menu
        menu.findItem(R.id.nav_home)?.setIcon(theme.navIcons.homeIconRes)
        menu.findItem(R.id.nav_alarm)?.setIcon(theme.navIcons.alarmIconRes)
        menu.findItem(R.id.nav_tasks)?.setIcon(theme.navIcons.taskIconRes)
        menu.findItem(R.id.nav_timetable)?.setIcon(theme.navIcons.timetableIconRes)
        menu.findItem(R.id.nav_attendance)?.setIcon(theme.navIcons.attendanceIconRes)
    }

    private fun applyCharacterTheme(theme: CharacterTheme) {
        applyCharacterNavTheme(theme)
        applyCharacterFontTheme(theme)
        applyCharacterVisualTheme(theme)
        updateCharacterCostume()
        binding.bottomNavigation.post {
            animateNavBulge(binding.bottomNavigation.selectedItemId)
        }
    }

    private fun setupNavigation() {
        val currentTheme = CharacterThemeManager.getCurrentTheme(this)
        applyCharacterTheme(currentTheme)
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
        binding.bottomNavigation.post {
            animateNavBulge(binding.bottomNavigation.selectedItemId)
        }


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
            val menuView = nav.getChildAt(0) as? android.view.ViewGroup ?: return@post
            menuView.clipChildren = false
            menuView.clipToPadding = false

            // 可視アイテムビューのリストを構築
            val visibleViews = mutableListOf<android.view.View>()
            for (i in 0 until menuView.childCount) {
                val itemView = menuView.getChildAt(i)
                if (itemView.visibility == android.view.View.VISIBLE) {
                    visibleViews.add(itemView)
                }
            }

            // 実際のItemViewの物理中心座標を取得（レイアウト誤差やピクセル丸めによるズレをゼロにする）
            val targetView = if (index in 0 until visibleViews.size) visibleViews[index] else null
            val targetCenterX = if (targetView != null && targetView.width > 0) {
                targetView.left + (targetView.width / 2f)
            } else {
                val itemWidth = nav.width.toFloat() / visibleCount.coerceAtLeast(1)
                (itemWidth * index) + (itemWidth / 2f)
            }
            val targetX = targetCenterX - (bulge.width / 2f)
            
            // 図形のスライド移動
            bulge.animate()
                .translationX(targetX)
                .setDuration(300)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

            // アイコン自体の持ち上げ（BottomNavigationViewの各子ビューに対して）
            val density = resources.displayMetrics.density
            val liftY = -28f * density
            for (i in 0 until visibleViews.size) {
                val itemView = visibleViews[i] as? android.view.ViewGroup ?: continue
                itemView.clipChildren = false
                itemView.clipToPadding = false

                if (itemView.childCount > 0) {
                    val icon = itemView.getChildAt(0)
                    if (icon is android.view.ViewGroup) {
                        icon.clipChildren = false
                        icon.clipToPadding = false
                    }

                    if (i == index) {
                        icon.animate().translationY(liftY).setDuration(300).start()
                        // 文字（通常は2番目の子要素）を取得して上に移動
                        if (itemView.childCount > 1) {
                            val label = itemView.getChildAt(1)
                            label.animate().translationY(0f).setDuration(250).start()
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

    private fun updatePlayerStatus(animate: Boolean = false) {
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
        val theme = CharacterThemeManager.getCurrentTheme(this)
        binding.homeContent.charLevelText.text = "${theme.id.uppercase()} Lv.$lv"
        binding.homeContent.charExpProgressBar.max = required.toInt()
        
        if (animate) {
            // 現在のプログレスから新しい値までアニメーション
            val startProgress = binding.homeContent.charExpProgressBar.progress
            val endProgress = current.toInt()
            
            android.animation.ValueAnimator.ofInt(startProgress, endProgress).apply {
                duration = 1000 // 1秒かけて増加
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { animator ->
                    val animatedValue = animator.animatedValue as Int
                    binding.homeContent.charExpProgressBar.progress = animatedValue
                    binding.homeContent.charExpText.text = "$animatedValue/${required.toInt()}"
                }
                start()
            }
        } else {
            binding.homeContent.charExpProgressBar.progress = current.toInt()
            binding.homeContent.charExpText.text = "$current/$required"
        }

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
        updatePlayerStatus(animate = true)
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
        
        val charLogs = CuraMessageManager.getSystemLogs(this).filter { it.isNotBlank() }
        val pool = if (charLogs.isNotEmpty()) charLogs else flavorLines
        val randomLine = pool.random()
        val hudText = binding.hudFlavorText
        
        // コルーチンでグリッチ演出を実行
        lifecycleScope.launch(Dispatchers.Main) {
            // 1. フェードアウト
            hudText.animate().alpha(0.3f).setDuration(200).start()
            delay(200)
            
            // 2. グリッチ（ランダムな文字の乱舞）
            val noiseChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*()_+-=[]{}|;:,.<>?"
            repeat(5) { // 5回高速で書き換え
                val noise = (1..randomLine.length).map { noiseChars.random() }.joinToString("")
                hudText.text = noise
                delay(60)
            }
            
            // 3. 定着 & フェードイン
            hudText.text = randomLine
            hudText.animate().alpha(0.8f).setDuration(300).start()
        }
    }

    private fun updateDashboardInfo() {
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        binding.homeContent.nextQuestText.text = nextEvent?.let {
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
            "$time ${it.summary}"
        } ?: getString(R.string.dashboard_no_schedule)

        val tasks = ScheduleLoader.loadTasksForToday(this)
        binding.homeContent.topMissionText.text = if (tasks.isNotEmpty()) tasks[0] else getString(R.string.dashboard_all_tasks_done)

        val alarmPrefs = getSharedPreferences(CuraConstants.PREFS_ALARM, MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString(CuraConstants.KEY_ALARM_LIST, null)
        var nextAlarmStr = getString(R.string.dashboard_not_set)
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
        applyCharacterTheme(CharacterThemeManager.getCurrentTheme(this))
        checkPendingExp() // ホームに戻った瞬間にもチェック
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment != null) supportFragmentManager.beginTransaction().remove(fragment).commit()
    }

    override fun onResume() {
        super.onResume()
        
        // アラーム鳴動チェック：サービスが動いていたら停止画面へ強制遷移（救済ロジック）
        if (isServiceRunning(this, AlarmService::class.java)) {
            val intent = Intent(this, AlarmAlertActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                AlarmService.currentAlarmId?.let { putExtra("ALARM_ID", it) }
            }
            startActivity(intent)
            return
        }

        updatePlayerStatus()
        updateDashboardInfo()
        updateAttendanceButtonVisibility()
        applyCharacterTheme(CharacterThemeManager.getCurrentTheme(this))
        
        // ホーム画面が表示されている場合のみ、未読の褒め言葉や挨拶をチェック
        if (binding.launcherLayout.isVisible) {
            checkPendingExp()
            showRandomDialogue()
        }
    }

    private fun checkPendingExp() {
        val charPrefs = getSharedPreferences(CuraConstants.PREFS_CHARACTER, MODE_PRIVATE)
        val pending = charPrefs.getLong(CuraConstants.KEY_PENDING_EXP, 0L)
        
        if (pending > 0) {
            lifecycleScope.launch {
                // ホーム画面に戻ってから少し「間」を置く (0.8秒)
                delay(800)
                
                // ストックされていた経験値を放出
                charPrefs.edit {
                    putLong(CuraConstants.KEY_PENDING_EXP, 0L)
                }
                addSessionExp(pending.toInt())
            }
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
        val currentTheme = CharacterThemeManager.getCurrentTheme(this)
        if (currentTheme.id != "cura") {
            binding.homeContent.characterImage.setImageResource(currentTheme.standingImageRes)
            return
        }
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

    private fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in activityManager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        breathingAnimator?.cancel()
        unregisterReceiver(powerConnectionReceiver)
        systemUpdateHandler.removeCallbacks(systemUpdateRunnable)
        expGainJob?.cancel()
        idleDialogueJob?.cancel()
    }
}