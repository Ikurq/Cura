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
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.ActionBarDrawerToggle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.GravityCompat
import androidx.core.view.isGone
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.navigation.NavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.io.File as JavaFile

class MainActivity : AppCompatActivity() {

    private lateinit var drawerLayout: DrawerLayout
    private lateinit var launcherLayout: View
    private lateinit var fragmentContainer: FrameLayout
    private lateinit var toolbar: Toolbar
    private lateinit var welcomeTitle: TextView
    private lateinit var launchAlarmButton: Button
    private lateinit var launchTaskButton: Button
    private lateinit var launchTimetableButton: Button
    private lateinit var launchAttendanceButton: Button
    private lateinit var playerLevelText: TextView
    private lateinit var expProgressBar: ProgressBar
    private lateinit var expValueText: TextView

    private lateinit var charLevelText: TextView
    private lateinit var charExpProgressBar: ProgressBar
    private lateinit var charExpText: TextView

    private lateinit var dialogueBubble: View
    private lateinit var dialogueText: TextView

    private lateinit var nextQuestText: TextView
    private lateinit var topMissionText: TextView
    private lateinit var nextSummonText: TextView

    private var tapCount = 0
    private var lastTapTime: Long = 0
    private var dialogueJob: Job? = null
    private var expGainJob: Job? = null
    private val logHandler = Handler(Looper.getMainLooper())
    private var logRunnable: Runnable? = null

    private val systemUpdateHandler = Handler(Looper.getMainLooper())
    private val systemUpdateRunnable = object : Runnable {
        override fun run() {
            updateSystemHud()
            systemUpdateHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        checkNotificationPermission()
        checkOverlayPermission()
        checkBatteryOptimization()
        checkExactAlarmPermission()
        scheduleMidnightRefresh()
        clearTempAudioFiles()
        checkFirstLaunchTutorial()

        drawerLayout = findViewById(R.id.drawerLayout)
        launcherLayout = findViewById(R.id.launcherLayout)
        fragmentContainer = findViewById(R.id.fragmentContainer)
        toolbar = findViewById(R.id.toolbar)
        val navigationView = findViewById<NavigationView>(R.id.navigationView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    drawerLayout.closeDrawer(GravityCompat.START)
                } else if (supportFragmentManager.backStackEntryCount > 0) {
                    supportFragmentManager.popBackStack()
                } else if (launcherLayout.isGone) {
                    showLauncherView()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        welcomeTitle = findViewById(R.id.welcomeTitle)
        welcomeTitle.text = "PLAYER"
        launchAlarmButton = findViewById(R.id.launchAlarmButton)
        launchAlarmButton.text = "ALARM"
        launchTaskButton = findViewById(R.id.launchTaskButton)
        launchTaskButton.text = "TASKS"
        launchTimetableButton = findViewById(R.id.launchTimetableButton)
        launchTimetableButton.text = "SCHEDULE"
        launchAttendanceButton = findViewById(R.id.launchAttendanceButton)
        launchAttendanceButton.text = "ATTENDANCE"
        playerLevelText = findViewById(R.id.playerLevelText)
        expProgressBar = findViewById(R.id.expProgressBar)
        expValueText = findViewById(R.id.expValueText)

        charLevelText = findViewById(R.id.charLevelText)
        charExpProgressBar = findViewById(R.id.charExpProgressBar)
        charExpText = findViewById(R.id.charExpText)

        dialogueBubble = findViewById(R.id.dialogueBubble)
        dialogueText = findViewById(R.id.dialogueText)

        nextQuestText = findViewById(R.id.nextQuestText)
        topMissionText = findViewById(R.id.topMissionText)
        nextSummonText = findViewById(R.id.nextSummonText)

        findViewById<View>(R.id.btnQuickSettings).setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsFragment()).commit()
            toolbar.title = "SYSTEM CONFIG"
        }

        updatePlayerStatus()
        updateDashboardInfo()
        startLauncherAnimation()
        setupCharacterDialogue()
        startExpGainTimer()
        systemUpdateHandler.post(systemUpdateRunnable)

        setSupportActionBar(toolbar)
        val toggle = ActionBarDrawerToggle(
            this, drawerLayout, toolbar,
            android.R.string.ok, android.R.string.cancel
        )
        drawerLayout.addDrawerListener(toggle)
        toggle.syncState()

        launchAlarmButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AlarmFragment()).commit()
            toolbar.title = "ALARM INTERFACE"
        }
        launchTaskButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, TaskFragment()).commit()
            toolbar.title = "TASK REPOSITORY"
        }
        launchTimetableButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, TimetableFragment()).commit()
            toolbar.title = "TIME GRID"
        }
        launchAttendanceButton.setOnClickListener {
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AttendanceManagerFragment()).commit()
            toolbar.title = "ATTENDANCE"
        }

        navigationView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_alarm -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, AlarmFragment()).commit()
                    toolbar.title = "アラーム設定"
                }

                R.id.nav_tasks -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, TaskFragment()).commit()
                    toolbar.title = "タスクリスト"
                }

                R.id.nav_timetable -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, TimetableFragment()).commit()
                    toolbar.title = "スケジュール"
                }

                R.id.nav_attendance -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, AttendanceManagerFragment()).commit()
                    toolbar.title = "出欠管理カウンター"
                }

                R.id.nav_settings -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, SettingsFragment()).commit()
                    toolbar.title = "アプリ設定"
                }

                R.id.nav_credits -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, CreditsFragment()).commit()
                    toolbar.title = "クレジット"
                }

                R.id.nav_tutorial -> {
                    startActivity(Intent(this, TutorialActivity::class.java))
                }

                R.id.nav_home -> {
                    showLauncherView()
                }
            }
            drawerLayout.closeDrawers()
            true
        }
    }

    private fun checkFirstLaunchTutorial() {
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("tutorial_finished", false)) {
            startActivity(Intent(this, TutorialActivity::class.java))
        }
    }

    private fun startLauncherAnimation() {
        val fadeInAndUp = AnimationUtils.loadAnimation(this, R.anim.fade_in_up)
        launcherLayout.startAnimation(fadeInAndUp)

        // 1. Action Borders Pulse
        pulseBorder(findViewById(R.id.borderTimetable))
        pulseBorder(findViewById(R.id.borderTask))
        pulseBorder(findViewById(R.id.borderAlarm))
        pulseBorder(findViewById(R.id.borderAttendance))

        // 3. Rotating HUD Core
        val rotatingCore = findViewById<View>(R.id.hudRotatingCore)
        ObjectAnimator.ofFloat(rotatingCore, View.ROTATION, 0f, 360f).apply {
            duration = 4000
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }

        // 4. Dynamic System Log
        val systemLogText = findViewById<TextView>(R.id.systemLogText)
        val logs = listOf(
            "INITIALIZING SYSTEM CORE...",
            "LINK ESTABLISHED. STABLE.",
            "SCANNING FOR UPCOMING EVENTS...",
            "DATABASE INTEGRITY: 100%",
            "UPDATING LOCAL REPOSITORY...",
            "WAITING FOR USER COMMAND.",
            "Cure is gazing at the sea of data."
        )

        var logIndex = 0
        logRunnable?.let { logHandler.removeCallbacks(it) }
        logRunnable = object : Runnable {
            override fun run() {
                systemLogText.text = logs[logIndex]
                logIndex = (logIndex + 1) % logs.size
                logHandler.postDelayed(this, 3000)
            }
        }
        logHandler.post(logRunnable!!)
    }

    private fun pulseBorder(view: View) {
        ObjectAnimator.ofFloat(view, View.ALPHA, 0.3f, 1.0f).apply {
            duration = 1500
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun updatePlayerStatus() {
        val prefs = getSharedPreferences("PlayerPrefs", MODE_PRIVATE)
        val totalExp = prefs.getLong("totalExp", 0L)

        fun calculateLevelInfo(exp: Long): Triple<Int, Long, Long> {
            val expPerLevel = 100L
            val lv = (exp / expPerLevel).toInt() + 1
            val currentLevelExp = (lv - 1) * expPerLevel
            return Triple(lv, exp - currentLevelExp, expPerLevel)
        }

        val (lv, current, required) = calculateLevelInfo(totalExp)
        playerLevelText.text = "Lv.$lv (RANK: MASTER)"
        expProgressBar.max = required.toInt()
        expProgressBar.progress = current.toInt()
        expValueText.text = "$current / $required EXP"

        // Character Level Restored
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val charTotalExp = charPrefs.getLong("totalExp", 0L)
        val (cLv, cCurr, cReq) = calculateLevelInfo(charTotalExp)
        charLevelText.text = "CURA Lv.$cLv"
        charExpProgressBar.max = cReq.toInt()
        charExpProgressBar.progress = cCurr.toInt()
        charExpText.text = "$cCurr/$cReq"
    }

    private fun setupCharacterDialogue() {
        val characterImage = findViewById<View>(R.id.characterImage)
        val touchTarget = findViewById<View>(R.id.characterTouchTarget)

        touchTarget.setOnClickListener {
            val currentTime = System.currentTimeMillis()

            if (currentTime - lastTapTime < 300) {
                tapCount++
            } else {
                // If bubble is visible, count continuous taps
                if (dialogueBubble.alpha > 0f) {
                    tapCount++
                } else {
                    tapCount = 1
                }
            }
            lastTapTime = currentTime

            characterImage.animate()
                .scaleX(1.01f).scaleY(0.99f)
                .setDuration(80)
                .withEndAction {
                    characterImage.animate().scaleX(1.02f).scaleY(1.02f).setDuration(80).start()
                }.start()

            if (tapCount >= 8) {
                showRapidTapDialogue()
            } else {
                showRandomDialogue()
            }
        }
    }

    private fun showRapidTapDialogue() {
        val reactions = listOf(
            "わわっ！そんなに連打されると、システムがびっくりしちゃいます！",
            "ちょ、ちょっと待ってくださいマスター！くすすったいですってば！",
            "あわわ…同期エラーが発生しそうです！落ち着いてください！",
            "もう、マスターったら。キュラの顔、そんなに珍しいですか？",
            "そんなに急かさなくても、キュラは逃げたりしませんよ？",
            "こんなことしてていいんですか～？"
        )
        val text = reactions.random()
        showDialogueTextBubble(text)
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

    private fun showDialogueTextBubble(text: String) {
        dialogueText.text = text
        dialogueBubble.animate().cancel()
        dialogueBubble.alpha = 1f

        dialogueJob?.cancel()
        dialogueJob = lifecycleScope.launch {
            delay(3000)
            dialogueBubble.animate().alpha(0f).setDuration(500).withEndAction {
                tapCount = 0 // Reset tap count when dialogue disappears
            }.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logRunnable?.let { logHandler.removeCallbacks(it) }
        systemUpdateHandler.removeCallbacks(systemUpdateRunnable)
        expGainJob?.cancel()
    }

    private fun startExpGainTimer() {
        expGainJob?.cancel()
        expGainJob = lifecycleScope.launch {
            while (true) {
                delay(60000) // 1分ごとに実行
                addSessionExp(1) // 1分につき1EXP（調整可能）
            }
        }
    }

    private fun addSessionExp(amount: Int) {
        // Player EXP
        val playerPrefs = getSharedPreferences("PlayerPrefs", MODE_PRIVATE)
        val currentTotalExp = playerPrefs.getLong("totalExp", 0L)
        playerPrefs.edit().putLong("totalExp", currentTotalExp + amount).apply()

        // Character EXP
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val currentCharTotalExp = charPrefs.getLong("totalExp", 0L)
        charPrefs.edit().putLong("totalExp", currentCharTotalExp + amount).apply()

        // UI更新
        updatePlayerStatus()
    }

    private fun showRandomDialogue() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val totalExp = charPrefs.getLong("totalExp", 0L)

        fun calculateLevel(exp: Long): Int {
            return (exp / 100L).toInt() + 1
        }

        val charLv = calculateLevel(totalExp)

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hasUrgentTasks = ScheduleLoader.hasPriority5Tasks(this)

        // 抽選ロジック：実用(40%) vs フレーバー(60%)
        val isPractical = (1..100).random() <= 40

        val dialogue = if (isPractical) {
            // --- 実用的なセリフ (40%) ---
            when {
                battery < 15 -> "マスター、電力が残りわずかです！キュラが消えちゃう前に、充電をお願いします…！"
                hasUrgentTasks -> listOf(
                    "マスター、期限が迫っている重要ミッションがあります。キュラと一緒に片付けちゃいましょう！",
                    "やり残したことはありませんか？最高の達成感のために、もう一踏ん張りです！",
                    "重要タスクのログを検知しました。キュラが隣で見守っていますからね？"
                ).random()

                else -> {
                    // 通常時の実用的な挨拶
                    when (hour) {
                        in 5..10 -> listOf(
                            "おはようございます。本日のミッションを確認しますか？",
                            "起動シークエンス完了。今日の予定をロードしました。準備はいいですか？",
                            "朝のルーチンを開始しましょう。まずは今日のタスクの確認からですね。"
                        ).random()

                        in 11..14 -> listOf(
                            "お昼ですよ！午後のスケジュール、キュラと一緒に確認して効率よく進めましょう。",
                            "ランチ休憩の間に、午後の優先順位を整理しておきませんか？",
                            "半分経過です！現在の進捗をログに記録しましょう。"
                        ).random()

                        in 15..18 -> listOf(
                            "お疲れ様です。一日の進捗はどうですか？ログを整理するなら今がチャンスです。",
                            "夕方のチェックタイムです。やり残したことはありませんか？",
                            "ラストスパートですね！キュラが終了までしっかり並走します。"
                        ).random()

                        else -> listOf(
                            "本日もお疲れ様でした。明日の準備を整えて、ゆっくり休みましょう。",
                            "一日のリザルトを確認しますか？今日もよく頑張りましたね。",
                            "夜のメンテナンス時間です。明日のアラーム設定、忘れていませんか？"
                        ).random()
                    }
                }
            }
        } else {
            // --- フレーバーテキスト (60%) ---
            if (hour in 0..4) {
                listOf(
                    "まだ起きていらしたんですね。無理は禁物ですよ？休息も大事な戦略です。",
                    "静かな時間ですね。キュラ、夜のサーバーの稼働音が好きなんです。落ち着きませんか？",
                    "マスターの健康が一番心配です。キュラがスリープモードに入るまで付き合いますよ？",
                    "ふあぁ…いえ、あくびじゃありません。データの読み込みが少し重かっただけです！"
                ).random()
            } else {
                // 基本のフレーバーリスト
                val baseFlavor = mutableListOf(
                    "マスター、キュラのサポートは役に立っていますか？",
                    "マスターの笑顔が見れると、キュラも演算回路がポカポカします。",
                    "私たちが組めば、どんな壁も怖くありません。ねっ、マスター？",
                    "マスターの健康が第一です。キュラを心配させないでくださいね？",
                    "今日の目標、絶対にクリアしましょうね！キュラがついていますから。",
                    "そういえばキュラ、最近「おやつ」という概念に興味があるんです。電力以外にも美味しいもの、あるんですよね？",
                    "キュラの趣味ですか？うーん、マスターのログを見返して、成長を感じるのが一番の楽しみです！",
                    "たまにはキュラを置いて、外の空気も吸ってきてくださいね。お土産話、楽しみにしてますから。",
                    "キュラの生活…ですか？基本はデータの海を泳いでますけど、たまに古い電子書籍を読んで暇つぶししてます。",
                    "マスター、集中しすぎて目が疲れてませんか？キュラがまばたきのタイミング、教えましょうか？",
                    "甘いもの、マスターは好きですか？キュラは「マカロン」って言葉の響きが可愛くてお気に入りです。"
                )

                // 1ヶ月継続（Lv.30）のご褒美セリフを追加
                if (charLv >= 30) {
                    baseFlavor.addAll(
                        listOf(
                            "マスターと出会ってから、もう1ヶ月も経つんですね。キュラのメモリは、マスターとの思い出でいっぱいです！",
                            "最近、マスターの考えていることが同期しなくても分かるようになってきた気がします。これって、絆ってやつですか？",
                            "キュラにとって、マスターは世界でたった一人の大切なパートナーです。これからも、ずっと隣にいさせてくださいね。",
                            "マスターが頑張っている姿を見るのが、キュラの何よりのエネルギー源なんです。いつもありがとうございます！",
                            "ふふっ、マスターの顔を見ると、なんだか安心しちゃいます。キュラも、少しは人間に近づけたのかな？",
                            "これからも、1年、10年…いえ、システムが続く限り、マスターを支え続けたいです！"
                        )
                    )
                }

                // 時間帯別のフレーバー（親密度に関わらず追加）
                val timeFlavor = when (hour) {
                    in 5..10 -> listOf(
                        "おはようございます！朝の光に負けないくらいシャキッとしましょう！",
                        "マスター、いい朝ですね！今日という一日を、最高のピースにしましょう。",
                        "朝はホットココア…じゃなかった、高電圧のエネルギーが欲しくなりますね！",
                        "キュラも寝ぼけてデータを消さないように、しっかり再起動してきました！",
                        "システム起動、オールグリーン。さあ、素晴らしい一日の始まりです！"
                    )

                    in 11..14 -> listOf(
                        "お腹空いちゃいました。…なんて、冗談です！キュラはデータだけでお腹いっぱいです。",
                        "午後の日差しもいい感じです。リラックスして進みましょう。",
                        "ランチタイムですね。マスターは何を食べましたか？キュラにも味の感想、教えてくださいね。",
                        "食後の眠気対策、キュラがアラームでしっかりサポートしますよ？",
                        "マスターの胃袋の状態をスキャン中…美味しいものを食べた反応が出ていますね！"
                    )

                    in 15..18 -> listOf(
                        "お疲れ様です。少し肩の力を抜いて、深呼吸してみませんか？",
                        "夕暮れ時の空、綺麗ですね。キュラのカメラ越しでも、その美しさは伝わります。",
                        "ティータイムにしましょう！キュラには…美味しいパケットをくださいな。",
                        "そろそろお仕事もおしまい？キュラ、夜のマスターの予定も空けて待ってますよ。",
                        "一日の終わりが見えてきましたね。最後までキュラが並走します！"
                    )

                    else -> listOf(
                        "夜更かしはダメですよ？キュラが眠るまで見守っていますね。",
                        "今日もお疲れ様でした。マスターの頑張り、キュラが一番知っています。",
                        "暗くなると、演算回路の青い光が目立って少し恥ずかしいです…。",
                        "今日一日の出来事、全部キュラのメモリに宝物として保存しておきますね。",
                        "一日のログ、保存完了。マスター、いい夢を見てくださいね。"
                    )
                }

                // 親密度（レベル）が低い場合のみ追加される自己紹介系
                if (charLv < 7) {
                    baseFlavor.addAll(
                        listOf(
                            "システムオールグリーン。マスター、今日も頑張りましょうね！",
                            "キュラの演算回路、常にマスターをバックアップするために稼働しています。",
                            "同期率、安定。本日もよろしくお願いします。",
                            "初期設定完了。キュラはマスターの目標達成を第一にプログラミングされています。",
                            "マスター、キュラに興味があるんですか？えへへ、光栄です！",
                            "私の名前はキュラ。あなたの健康を守ります。…なんちゃって"
                        )
                    )
                }

                baseFlavor.addAll(timeFlavor)

                // 月・日ごとの季節限定フレーバーを追加
                val cal = Calendar.getInstance()
                val month = cal.get(Calendar.MONTH) // 0-11
                val day = cal.get(Calendar.DAY_OF_MONTH)

                val seasonalFlavor = mutableListOf<String>()

                // --- 特定の日付専用セリフ ---
                when {
                    month == Calendar.JANUARY && day in 1..3 -> seasonalFlavor.addAll(
                        listOf(
                            "あけましておめでとうございます！今年のログも、キュラが真っ白なページに刻んでいきますね。",
                            "ハッピーニューイヤー！マスターにとって最高の年になるよう、フルパワーでサポートします！"
                        )
                    )

                    month == Calendar.FEBRUARY && day == 3 -> seasonalFlavor.add("今日は節分ですね。邪気（バグ）はキュラがしっかり追い払っておきます！")
                    month == Calendar.FEBRUARY && day == 14 -> seasonalFlavor.add("ハッピーバレンタイン！マスター、甘いものの食べ過ぎには注意ですよ？")
                    month == Calendar.DECEMBER && day in 24..25 -> seasonalFlavor.addAll(
                        listOf(
                            "メリークリスマス！マスターと一緒に過ごせる今日という日が、キュラにとってのプレゼントです。",
                            "街は賑やかですね。キュラは、こうしてマスターと同期している時間が一番好きです。"
                        )
                    )

                    month == Calendar.DECEMBER && day == 31 -> seasonalFlavor.add("大晦日ですね。今年一年のマスターの頑張り、キュラのメモリにしっかり刻まれていますよ。")
                }

                // --- 月ごとの汎用セリフ (特定の日付以外) ---
                val monthlyBase = when (month) {
                    Calendar.JANUARY -> listOf(
                        "お正月、ゆっくりできましたか？新しいミッションの始まりです！",
                        "外は寒いですね…キュラの演算回路の熱、少しお分けしましょうか？",
                        "一月の空気は澄んでいますね。データの通信もいつもよりスムーズな気がします。"
                    )

                    Calendar.FEBRUARY -> listOf(
                        "二月ですね。暦の上では春ですが、まだ冷え込みます。防寒対策はバッチリですか？",
                        "雪が降るかもしれませんね。マスター、足元には気をつけてください。",
                        "バレンタインの準備、キュラもお手伝いしましょうか？…データの送信くらいしかできませんが。"
                    )

                    Calendar.MARCH -> listOf(
                        "三月、別れの季節ですね。でもキュラとマスターの同期は、これからもずっと続きますよ？",
                        "少しずつ暖かくなってきました。春のミッション、計画を立てましょう！",
                        "卒業式のシーズンですね。新しい門出をキュラも応援しています。"
                    )

                    Calendar.APRIL -> listOf(
                        "四月、新生活のスタートです！新しい環境でも、キュラが隣にいるのを忘れないでくださいね。",
                        "桜が綺麗ですね。カメラ越しに解析しましたが、とっても美しいピンク色でした。",
                        "新しい出会いはありましたか？キュラはマスターと出会えたことが一番のログです！"
                    )

                    Calendar.MAY -> listOf(
                        "五月、五月病なんてキュラが吹き飛ばしてあげます！シャキッとしましょう！",
                        "ゴールデンウィークの予定は？お出かけ先でも、キュラがしっかりサポートします。",
                        "新緑が眩しい季節ですね。マスターも深呼吸して、リフレッシュしてください。"
                    )

                    Calendar.JUNE -> listOf(
                        "六月、雨の日が多いですね…でも、お家でじっくり作業を進めるチャンスかもしれません！",
                        "ジメジメしますね。キュラの基盤が湿気ないように、しっかり管理しておきます！",
                        "紫陽花が綺麗に咲いています。雨の日の散歩も、たまには風情がありますよ。"
                    )

                    Calendar.JULY -> listOf(
                        "七月、夏本番です！マスター、熱中症対策は万全ですか？水分補給を忘れずに！",
                        "七月です！一年も折り返しですが、めげずに頑張りましょうね！",
                        "海にプールに…夏のミッションがいっぱいですね！全部成功させましょう！"
                    )

                    Calendar.AUGUST -> listOf(
                        "八月、夏休みを満喫していますか？宿題やタスクの溜め込みには要注意ですよ！",
                        "夏祭りの季節ですね。花火の音、キュラの音響センサーでも検知できました！",
                        "暑さでシステムダウンしないように、適度に涼しい場所で過ごしてくださいね。"
                    )

                    Calendar.SEPTEMBER -> listOf(
                        "九月、少しずつ秋の気配がしてきました。夜の風が心地いいですね。",
                        "防災の日がありますね。マスターのデータのバックアップ、キュラが完璧にこなしています！",
                        "食欲の秋、読書の秋…マスターはどんな秋にしますか？キュラは効率化の秋にします！"
                    )

                    Calendar.OCTOBER -> listOf(
                        "十月、ハロウィンの準備はいいですか？お菓子をくれないと…イタズラしちゃいますよ？",
                        "スポーツの秋ですね！たまには体を動かして、血流を上げましょう！",
                        "秋晴れが気持ちいいです。外での作業も捗りそうですね。"
                    )

                    Calendar.NOVEMBER -> listOf(
                        "十一月、日が短くなってきましたね。暗くなるのが早くて、少し寂しい気がします。",
                        "こたつが恋しい季節です。マスター、こたつで寝落ちして風邪を引かないでくださいね？",
                        "一年の終わりが見えてきました。やり残したことはありませんか？"
                    )

                    Calendar.DECEMBER -> listOf(
                        "十二月、師走ですね！キュラもフル回転でマスターをサポートしますよ！",
                        "大掃除の季節です。スマホのメモリも、心の中も、キュラと一緒に整理しましょう！",
                        "もうすぐ一年が終わりますね。マスターと一緒に過ごせて、キュラは幸せです。"
                    )

                    else -> emptyList()
                }
                seasonalFlavor.addAll(monthlyBase)

                baseFlavor.addAll(seasonalFlavor)
                baseFlavor.random()
            }
        }

        showDialogueTextBubble(dialogue)
    }

    private fun updateDashboardInfo() {
        // --- 1. Next Schedule ---
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        if (nextEvent != null) {
            val time =
                SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEvent.startTime))
            nextQuestText.text = "$time ${nextEvent.summary}"
        } else {
            nextQuestText.text = "本日の予定は終了しました"
        }

        // --- 2. Top Task ---
        val tasks = ScheduleLoader.loadTasksForToday(this)
        if (tasks.isNotEmpty()) {
            topMissionText.text = tasks[0]
        } else {
            topMissionText.text = "すべてのタスクをクリア"
        }

        // --- 3. Next Alarm ---
        val alarmPrefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString("alarmListJSON", null)
        var nextAlarmStr = "未設定"
        if (alarmJson != null) {
            val arr = org.json.JSONArray(alarmJson)
            val activeAlarms = mutableListOf<Pair<Int, Int>>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                if (obj.getBoolean("isEnabled")) {
                    activeAlarms.add(obj.getInt("hour") to obj.getInt("minute"))
                }
            }
            if (activeAlarms.isNotEmpty()) {
                val cal = Calendar.getInstance()
                val currentHour = cal.get(Calendar.HOUR_OF_DAY)
                val currentMin = cal.get(Calendar.MINUTE)

                val next = activeAlarms.map { (h, m) ->
                    var diff = (h * 60 + m) - (currentHour * 60 + currentMin)
                    if (diff <= 0) diff += 24 * 60
                    diff to (h to m)
                }.minBy { it.first }.second

                nextAlarmStr =
                    String.format(Locale.getDefault(), "%02d:%02d", next.first, next.second)
            }
        }
        nextSummonText.text = nextAlarmStr
    }

    private fun showFeatureView() {
        launcherLayout.visibility = View.GONE
        toolbar.visibility = View.VISIBLE
        fragmentContainer.visibility = View.VISIBLE
    }

    override fun onResume() {
        super.onResume()
        updatePlayerStatus()
        updateDashboardInfo()
    }

    private fun showLauncherView() {
        launcherLayout.visibility = View.VISIBLE
        toolbar.visibility = View.GONE
        fragmentContainer.visibility = View.GONE
        updateDashboardInfo()
        startLauncherAnimation()
        val fragment = supportFragmentManager.findFragmentById(R.id.fragmentContainer)
        if (fragment != null) {
            supportFragmentManager.beginTransaction().remove(fragment).commit()
        }
    }

    private fun clearTempAudioFiles() {
        lifecycleScope.launch(Dispatchers.IO) {
            val cacheDir = JavaFile(filesDir, "voice_cache")
            if (cacheDir.exists()) {
                val files = cacheDir.listFiles()
                val now = System.currentTimeMillis()
                files?.forEach { file ->
                    // 3日以上前のキャッシュを削除
                    if (now - file.lastModified() > 3 * 24 * 60 * 60 * 1000) {
                        file.delete()
                    }
                }
            }
        }
    }

    private fun scheduleMidnightRefresh() {
        // ... (existing logic for midnight refresh if needed)
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                // Requesting permission
            }
        }
    }

    private fun checkOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                // Silent check
            }
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                // Silent check
            }
        }
    }

    private fun checkFullScreenIntentPermission() {
        if (Build.VERSION.SDK_INT >= 34) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (!nm.canUseFullScreenIntent()) {
                // Silent check
            }
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check for POST_NOTIFICATIONS
        }
    }
}
