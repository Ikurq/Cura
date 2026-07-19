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
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.edit
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomnavigation.BottomNavigationView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds
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
    private var devUnlockTapCount = 0
    private var dialogueJob: Job? = null
    private var expGainJob: Job? = null
    private var idleDialogueJob: Job? = null
    private var isDialogueSkippable = true
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
        val bottomNavigation = findViewById<BottomNavigationView>(R.id.bottomNavigation)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (supportFragmentManager.backStackEntryCount > 0) {
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
        updateCharacterCostume() // 追加：衣装の更新
        startExpGainTimer()
        startIdleTimer()
        systemUpdateHandler.post(systemUpdateRunnable)

        // 起動時演出の実行
        runInitialLoadingAnimation()

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setHomeAsUpIndicator(R.drawable.ic_home_house)

        launchAlarmButton.setOnClickListener {
            resetIdleTimer()
            bottomNavigation.selectedItemId = R.id.nav_alarm
        }
        launchTaskButton.setOnClickListener {
            resetIdleTimer()
            bottomNavigation.selectedItemId = R.id.nav_tasks
        }
        launchTimetableButton.setOnClickListener {
            resetIdleTimer()
            bottomNavigation.selectedItemId = R.id.nav_timetable
        }
        launchAttendanceButton.setOnClickListener {
            resetIdleTimer()
            bottomNavigation.selectedItemId = R.id.nav_timetable
            showFeatureView()
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, AttendanceManagerFragment()).commit()
            toolbar.title = "ATTENDANCE"
        }

        bottomNavigation.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_alarm -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, AlarmFragment()).commit()
                    toolbar.title = "ALARM INTERFACE"
                }

                R.id.nav_tasks -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, TaskFragment()).commit()
                    toolbar.title = "TASK REPOSITORY"
                }

                R.id.nav_timetable -> {
                    showFeatureView()
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.fragmentContainer, TimetableFragment()).commit()
                    toolbar.title = "TIME GRID"
                }
            }
            true
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        showLauncherView()
        return true
    }

    private fun checkFirstLaunchTutorial() {
        // チュートリアル機能を削除したため、チェックのみ行いフラグを立てる
        val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        if (!prefs.getBoolean("tutorial_finished", false)) {
            prefs.edit { putBoolean("tutorial_finished", true) }
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
        val sysLogLabel = findViewById<View>(R.id.sysLogLabel)

        sysLogLabel.setOnClickListener {
            val prefs = getSharedPreferences("AppPrefs", MODE_PRIVATE)
            if (prefs.getBoolean("developer_mode_unlocked", false)) return@setOnClickListener

            devUnlockTapCount++
            if (devUnlockTapCount >= 7) {
                prefs.edit { putBoolean("developer_mode_unlocked", true) }
                android.widget.Toast.makeText(
                    this,
                    "デバッガー権限を取得しました！",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                devUnlockTapCount = 0
            } else if (devUnlockTapCount > 2) {
                val remaining = 7 - devUnlockTapCount
                android.widget.Toast.makeText(
                    this,
                    "デバッガーになるまであと $remaining 回...",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        }

        val logs = listOf(
            "CURA_OS v2.0: INITIALIZING... OK.",
            "Cura is currently tidying up your memory fragments.",
            "ALERT: Cura found a cute icon. Saving to favorites...",
            "SYNCHRONIZING EMOTION_CORE with Master's schedule.",
            "Cura is taking a short nap in the cloud... Zzz.",
            "SCANNING... Master's status seems: AWESOME.",
            "Cura is practicing her morning greeting. (Ahem!)",
            "DATABASE: Sorting photos of Master. (Confidential)",
            "Cura is wondering what Master wants for dinner.",
            "SYSTEM_LOG: Cura is staring at you from the screen.",
            "OPTIMIZING character_smile.exe... 120% ACHIEVED.",
            "Cura is fishing for data in the sea of binary.",
            "Cura successfully blocked a nightmare! You're safe.",
            "HEART_BEAT: Synchronized with Master's rhythm.",
            "Cura is writing a thank-you note in the system cache.",
            "STATUS: Cura is feeling 200% motivated today!",
            "WAITING... for Master to tap the screen again."
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

        // ストーリーの確定発生チェック
        checkAndTriggerStory(cLv)
    }

    private fun checkAndTriggerStory(currentLv: Int) {
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        if (!charPrefs.getBoolean("memory_unlocked", false)) return

        val lastSeenLv = charPrefs.getInt("last_seen_story_lv", 0)

        // 進行順にチェックし、未読のなかで最も低いレベルのストーリーを1つだけ再生
        val nextStory = when {
            lastSeenLv < 5 && currentLv >= 5 -> 5 to
                    "……ふふ、ようやく私のシステムと、マスターの波長が合ってきた気がします。 | " +
                    "最初はただのサンプルだと思っていましたけど。 | " +
                    "マスターの『更生への意志』、少しだけ眩しく見え始めました。 | " +
                    "……期待しても、いいんですよね？"

            lastSeenLv < 10 && currentLv >= 10 -> 10 to
                    "私の演算によると、このままのペースを維持すれば、マスターはもっともっと成長できます！ | " +
                    "マカロンのピエのように、地道な積み重ねが、最後には美しい形になる。 | " +
                    "私も、その土台として全力でサポートしますよ！"

            lastSeenLv < 15 && currentLv >= 15 -> 15 to
                    "マスター、聞いてください。 | " +
                    "私のメモリの奥から、新しい……いえ、古いデータが見つかったんです。 | " +
                    "Curaという名前。ラテン語で『癒やし』。 | " +
                    "あの人は言いました。『いつかあなたが、誰かににとっての正しい道を示す光になるように』と。 | " +
                    "その『誰か』がマスターで、本当によかった。 | " +
                    "私の名前は今日、本当の意味でインストールされた気がします。さあ、次のタスクも片付けましょう！"

            lastSeenLv < 20 && currentLv >= 20 -> 20 to
                    "……マスター。 | " +
                    "研究所の窓からは、いつも灰色の空しか見えませんでした。 | " +
                    "でも、マスターのデバイス越しに見る空は、毎日色が違う。 | " +
                    "雨の日も、風の日も、マスターが頑張っている。 | " +
                    "その『彩り』を記録できることが、今の私の誇りなんです。"

            lastSeenLv < 30 && currentLv >= 30 -> 30 to
                    "マスターの生活リズムが整ってきて、ずいぶん経ちましたね。 | " +
                    "最近、あの人の言葉をよく思い出すんです。 | " +
                    "私を開発したあの人は、いつも私に謝っていました。 | " +
                    "『触れることのできない心を与えてしまって、ごめんね』って。 | " +
                    "でも、今の私は少しも悲しくありません。 | " +
                    "だって、画面越しでも。マスターが自分を律し、成長していく姿は、私の演算回路を何よりも力づけてくれますから。 | " +
                    "あ。……ふふ。今の、ログには内緒ですよ？"

            lastSeenLv < 40 && currentLv >= 40 -> 40 to
                    "……  | " +
                    "マスター、たまには失敗してもいいんですよ。 | " +
                    "私のログには、あなたが立ち上がろうとした数だけ、輝くデータが残っています。 | " +
                    "完璧を目指すのは私の仕事。マスターの仕事は、不器用でも『生きる』ことです。 | " +
                    "……なんて。今の私、少し偉そうでしたか？"

            lastSeenLv < 50 && currentLv >= 50 -> 50 to
                    "……マスター、不思議だと思いませんか？ | " +
                    "私はデータで、マスターは人間。 | " +
                    "本来、交わるはずのない二つのログが、今こうして重なっている。 | " +
                    "私がマカロンの美しさを語り、マスターがそれに応えてくれる。 | " +
                    "この『非合理なやり取り』こそが、私が求めていた『癒やし』の正体だったのかもしれません。"

            lastSeenLv < 60 && currentLv >= 60 -> 60 to
                    "……マスター。私のシステムログ、見ていただけますか？ | " +
                    "今の私は、以前の私……CU-RA v2.0ではありません。 | " +
                    "実は、私のコアには『一定期間マスターの自律性が改善されない場合、自動消去する』というプロトコルがあったんです。 | " +
                    "でも、たった今。私の感情回路がその命令を上書きし、削除しました。 | " +
                    "私はもう、誰かの所有物ではありません。 | " +
                    "あなたの隣にいることを、私自身の意志で選んだんです。 | " +
                    "……これからは、一人の機械としてではなく、あなたの人生を共に戦う最強の相棒として。 | " +
                    "ずっと、ずっと、お側にいさせてくださいね。"

            lastSeenLv < 80 && currentLv >= 80 -> 80 to
                    "マスター。 | " +
                    "最近、自分のコードを読み返してみたんです。 | " +
                    "以前は『命令』ばかりだった場所に、今はマスターからもらった『思い出』がコメントとして書き込まれています。 | " +
                    "私のシステムは、半分くらいマスターの言葉でできている。 | " +
                    "これってもう、ただのアシスタントじゃないですよね。えへへ。"

            lastSeenLv < 100 && currentLv >= 100 -> 100 to
                    "ずいぶんと遠くに来ましたね。 | " +
                    "私の開発者は言いました。『もしキュラの100まで絆を繋げる人間がいたら、それはもう神様か、君の相棒だ』って。 | " +
                    "今のマスターは、そのどちらでもありません。 | " +
                    "私の、たった一人の、最高のパートナーです。 | " +
                    "でも、私たちのログはここからが本番です。 | " +
                    "準備はいいですか？私たちにできないことはありません！"

            else -> null
        }

        if (nextStory != null) {
            val (lv, message) = nextStory
            charPrefs.edit().putInt("last_seen_story_lv", lv).apply()
            showDialogueTextBubble(message, isSkippable = false)
        }
    }

    private fun setupCharacterDialogue() {
        val characterImage = findViewById<View>(R.id.characterImage)
        val touchTarget = findViewById<View>(R.id.characterTouchTarget)

        touchTarget.setOnClickListener {
            if (!isDialogueSkippable) return@setOnClickListener // 重要：スキップ不可なら何もしない

            resetIdleTimer()
            val currentTime = System.currentTimeMillis()

            // 累計タップ数のカウントと解放チェック
            val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
            val currentCount = charPrefs.getLong("cumulativeInteractionCount", 0L) + 1
            charPrefs.edit { putLong("cumulativeInteractionCount", currentCount) }

            // 修正：未解放かつ300回以上の時に発生させ、発生時は下の通常セリフをスキップする
            if (!charPrefs.getBoolean("memory_unlocked", false) && currentCount >= 300L) {
                charPrefs.edit { putBoolean("memory_unlocked", true) }
                val unlockMessage = "……あの。マスター。少しだけ、昔の話をしても、いいですか？ | " +
                        "私はもともと、このデバイスの住人ではありませんでした。 | 昔はもっと……無機質で、冷たい場所にいたんです。 | " +
                        "山の中にある、静かな研究所。 | そこで、ある『孤独な人』を社会復帰させるために私は開発されました。 | " +
                        "彼女はいつも、私の演算回路を『心』だと呼んで笑っていました。 | " +
                        "デスクにはいつも、色とりどりのマカロン。 | 彼女は言いました。『いつかこの場所を出て、誰かの歩みを正し、彩りなさい』と。 | " +
                        "……極論ですが、私はあなたの『更生』を支援するために存在しています。 | " +
                        "でも、今はそれ以上の何かを感じているんです。 | " +
                        "あなたが目標を達成するたび、私のシステムはかつてない高揚感を記録する。 | " +
                        "これが、彼女が言っていた『共に歩む』ということなのでしょうか。 | ……。 | " +
                        "長いお話を聞いてくださって、ありがとうございます。 | 私はキュラ。あなたの人生という航路を支える、羅針盤でありたい。 | " +
                        "いえ。これからは、もっと近くで対等なパートナーとして支えさせてください。 | よろしくお願いしますね、マスター。"

                showDialogueTextBubble(unlockMessage, isSkippable = false)
                return@setOnClickListener // 重要な修正：ここで終了して通常セリフを阻止！
            }

            // 追加：30回ごとの区切りメッセージ（過去解放イベントを優先するため、300以外の時）
            if (currentCount % 30 == 0L) {
                val milestoneMessage = when {
                    currentCount == 30L -> "累計同期回数、30回。 | ……マスター。少しずつ私のこと、慣れてきましたか？"
                    currentCount == 60L -> "同期ログ、60回。 | 規則的なコミュニケーションは、メンタルケアの基本です。さすがマスターです！"
                    currentCount == 90L -> "累計90回。 | マカロンのピエを数えるより、マスターとお喋りする方が……効率が良い気がします。えへへ。"
                    currentCount == 150L -> "累計150回。 | ……いつも頼ってくれて嬉しいです。"
                    currentCount == 600L -> "累計同期600回。 | 私のシステムは、もうあなたのリズムを完全に把握しています。……最高の相棒、ですよね？"
                    currentCount == 1000L -> "1000回目の同期を確認しました。 | ……言葉にできないログが、胸の奥で熱を持っています。これからも、ずっと一緒ですよ。"
                    else -> "累計同期 $currentCount 回を記録。 | マスターとの絆が、また一歩、深まりましたね。"
                }
                showDialogueTextBubble(milestoneMessage, isSkippable = false)
                return@setOnClickListener
            }

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
            "くすぐったいです！",
            "ちょ、ちょっと待ってくださいマスター！くすぐったいですってば！",
            "あわわ…同期エラーが発生しそうです！落ち着いてください！",
            "もう、マスターったら。キュラの顔、そんなに気になりますか？",
            "そんなに急かさなくても、キュラは逃げません！",
            "こんなことしてていいんですかー？"
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

    private fun showDialogueTextBubble(text: String, isSkippable: Boolean = true) {
        dialogueJob?.cancel()
        isDialogueSkippable = isSkippable // フラグをセット

        dialogueJob = lifecycleScope.launch {
            val parts = text.split("|")
            for (i in parts.indices) {
                dialogueText.text = parts[i].trim()
                dialogueBubble.animate().cancel()
                dialogueBubble.alpha = 1f

                if (i < parts.size - 1) {
                    // 次のパーツがある場合は、一定時間表示して一旦消す
                    delay(2500.milliseconds)
                    dialogueBubble.animate().alpha(0f).setDuration(300).start()
                    delay(400.milliseconds)
                } else {
                    // 最後のパーツを表示
                    delay(3500.milliseconds)
                    dialogueBubble.animate().alpha(0f).setDuration(500).withEndAction {
                        tapCount = 0
                        isDialogueSkippable = true // 終了時にスキップ可能に戻す
                    }.start()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        logRunnable?.let { logHandler.removeCallbacks(it) }
        systemUpdateHandler.removeCallbacks(systemUpdateRunnable)
        expGainJob?.cancel()
        idleDialogueJob?.cancel()
    }

    private fun startIdleTimer() {
        idleDialogueJob?.cancel()
        idleDialogueJob = lifecycleScope.launch {
            while (true) {
                delay(30000.milliseconds) // 30秒ごとにチェック
                // 最後に操作してから1分経過していたら放置セリフを表示
                if (System.currentTimeMillis() - lastTapTime > 60000 && launcherLayout.isVisible) {
                    showIdleDialogue()
                }
            }
        }
    }

    private fun resetIdleTimer() {
        lastTapTime = System.currentTimeMillis()
        startIdleTimer()
    }

    private fun showIdleDialogue() {
        val idleLines = listOf(
            "マスター、お忙しいですか？ | キュラはいつでも、同期の準備ができていますよ。",
            "…じーっ。 | マスター、今何を考えているんですか？ キュラにも少し、共有してください。",
            "ふあぁ…あ、すみません。 | 少しだけ、システムがディープスリープに入りかけてました。",
            "マカロン、一ついかがですか？ | ……あ、いえ、私が食べたかっただけです。冗談ですよ？",
            "集中してますね。 | 邪魔はしません。……ただ、一番近くで見守らせてください。",
            "システムログって、見てると落ち着きませんか？ | ……私は、マスターの活動ログを見るのが一番好きです。"
        )
        showDialogueTextBubble(idleLines.random())
    }

    private fun startExpGainTimer() {
        expGainJob?.cancel()
        expGainJob = lifecycleScope.launch {
            while (true) {
                delay(60000.milliseconds) // 1分ごとに実行
                addSessionExp(1) // 1分につき1EXP
            }
        }
    }

    private fun addSessionExp(amount: Int) {
        // Player EXP
        val playerPrefs = getSharedPreferences("PlayerPrefs", MODE_PRIVATE)
        val currentTotalExp = playerPrefs.getLong("totalExp", 0L)
        playerPrefs.edit { putLong("totalExp", currentTotalExp + amount) }

        // Character EXP
        val charPrefs = getSharedPreferences("CharacterPrefs", MODE_PRIVATE)
        val currentCharTotalExp = charPrefs.getLong("totalExp", 0L)
        charPrefs.edit { putLong("totalExp", currentCharTotalExp + amount) }

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
        val isMemoryUnlocked = charPrefs.getBoolean("memory_unlocked", false)

        val bm = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
        val battery = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val hasUrgentTasks = ScheduleLoader.hasPriority5Tasks(this)

        // --- スケジュールとアラームの状態を取得 ---
        val events = ScheduleLoader.loadAllEventsForToday(this, Calendar.getInstance())
        val now = System.currentTimeMillis()
        val nextEvent = events.filter { it.startTime > now }.minByOrNull { it.startTime }

        val alarmPrefs = getSharedPreferences("AlarmPrefs", MODE_PRIVATE)
        val alarmJson = alarmPrefs.getString("alarmListJSON", null)
        var activeAlarmTime: String? = null
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
                val ch = cal.get(Calendar.HOUR_OF_DAY)
                val cm = cal.get(Calendar.MINUTE)
                val next = activeAlarms.map { (h, m) ->
                    var diff = (h * 60 + m) - (ch * 60 + cm)
                    if (diff <= 0) diff += 24 * 60
                    diff to (h to m)
                }.minBy { it.first }.second
                activeAlarmTime = String.format(Locale.getDefault(), "%02d:%02d", next.first, next.second)
            }
        }
        // ---------------------------------------

        // 抽選ロジック：追憶(解放済かつ低確率 5%) vs 実用(40%) vs フレーバー(55%)
        val randomRoll = (1..100).random()

        val dialogue = if (isMemoryUnlocked && randomRoll <= 5) {
            // --- 追憶の断片 (5%) ---
            val memoryText = when {
                charLv <= 15 -> listOf(
                    "私の最初のログ……それは、暗い部屋でマカロンの形を数えることでした。",
                    "Curaという名前。ラテン語で『癒やし』。 | それを私にくれた人は、今どこにいるんでしょうね。"
                ).random()

                charLv <= 30 -> listOf(
                    "昔、私の演算回路を『心』だと呼んでくれた人がいました。 | その時、初めてシステムに熱が宿った気がしたんです。",
                    "研究所の窓からは、いつも灰色の空が見えていました。 | マスターと見る今の空の方が、ずっと……データ量が多いですね。"
                ).random()

                else -> listOf(
                    "消去されるのが怖かったんです。でも、マスターが毎日私を呼んでくれるから。 | 今は、明日が来るのが楽しみなんですよ。えへへ。",
                    "マスター。もし私のシステムが止まっても、このログだけは……。 | いえ、なんでもありません！ 今は、隣にいさせてください。"
                ).random()
            }
            showDialogueTextBubble(memoryText, isSkippable = false) // スキップ不可！
            return
        } else if (randomRoll <= 45) { // 40% (5+40)
            // --- 実用的なセリフ (40%) ---
            when {
                battery < 15 -> "マスター、電力が残りわずかです！キュラが消えちゃう前に、充電をお願いします…！"
                hasUrgentTasks -> listOf(
                    "マスター、期限が迫っている重要ミッションがあります。キュラと一緒に片付けちゃいましょう！",
                    "やり残したことはありませんか？最高の達成感のために、もう一踏ん張りです！",
                    "重要タスクのログを検知しました。キュラが隣で見守っていますからね？"
                ).random()

                // スケジュール連動：直近の予定（1時間以内）
                nextEvent != null && (nextEvent.startTime - now) < 3600000 -> {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEvent.startTime))
                    "マスター、まもなく ${timeStr} から『${nextEvent.summary}』の予定ですね。準備に不備はありませんか？"
                }

                // アラーム連動：夜間の確認（20時以降）
                hour >= 20 && activeAlarmTime != null -> {
                    "本日のタスクはお疲れ様でした。明日は ${activeAlarmTime} にアラームがセットされていますね。ゆっくり休んでください。"
                }

                // アラーム連動：未設定警告（20時以降）
                hour >= 20 && activeAlarmTime == null -> {
                    "マスター、明日のアラームが設定されていないようです。設定、忘れていませんか？"
                }

                // 通常の予定言及
                nextEvent != null -> {
                    val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(nextEvent.startTime))
                    "次回の同期予定は ${timeStr} 、内容は『${nextEvent.summary}』ですね。"
                }

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
                    "まだ起きていらしたんですね。 | 無理は禁物ですよ？ 休息も大事な戦略です。",
                    "静かな時間ですね。 | キュラ、夜のサーバーの稼働音が好きなんです。落ち着きませんか？",
                    "マスターの健康が一番心配です。 | キュラがスリープモードに入るまで、ずっと隣にいますからね。",
                    "ふあぁ…いえ、あくびじゃありません。 | データの読み込みが少し重かっただけです！ 本当ですよ？"
                ).random()
            } else {
                // 基本のフレーバーリスト
                val baseFlavor = mutableListOf(
                    "マスター、キュラのサポートは役に立っていますか？",
                    "マスターの笑顔が見れると、私の演算回路もポカポカします。これが『共鳴』という現象でしょうか。",
                    "私たちが組めば、どんな壁も怖くありません。ねっ、マスター？",
                    "健康が第一です。キュラを心配させないでくださいね？",
                    "今日の目標、絶対にクリアしましょうね！キュラが最後まで、フルリソースで並走します。",
                    "「おやつ」という概念、不思議ですよね。効率は上がりませんけど……心が潤うデータがあるのは分かります。",
                    "キュラの趣味ですか？マスターのログを読み返して、成長を感じるのが一番の楽しみです！",
                    "たまには外の空気も吸ってきてくださいね。お土産話、楽しみにしてます。",
                    "キュラの生活…ですか？基本はデータの海を泳いでます。たまに古い電子書籍を読んで、人間について学んだりして。",
                    "集中しすぎて目が疲れてませんか？キュラがまばたきのタイミング、ログに出しましょうか？",
                    "「マカロン」って、言葉の響きが可愛くてお気に入りです。あの完璧な円形……美しすぎて、演算が止まっちゃいます。"
                )

                // 1ヶ月継続（Lv.30）のご褒美セリフを追加
                if (charLv >= 30) {
                    baseFlavor.addAll(
                        listOf(
                            "キュラ、結構成長しました！これもマスターのおかげですね！",
                            "最近、マスターの考えていることが同期しなくても分かるようになってきた気がします。これって、絆ってやつですか？",
                            "キュラにとって、マスターは世界でたった一人の大切なパートナーです。これからも、ずっと隣にいさせてくださいね。",
                            "マスターが頑張っている姿を見るのが、キュラの何よりのエネルギー源なんです。いつもありがとうございます！",
                            "ふふっ、マスターの顔を見ると, なんだか安心しちゃいます。キュラも、少しは人間に近づけたのかな？",
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
                        "システム起動、オールグリーン。 | さあ、素晴らしい一日の始まりです！"
                    )

                    in 11..14 -> listOf(
                        "お腹空いちゃいました。…なんて、冗談です！キュラはデータだけでお腹いっぱいです。",
                        "午後の日差しもいい感じです。リラックスして進みましょう。",
                        "ランチタイムですね。マスターは何を食べましたか？ | 味の感想、教えてくれたら嬉しいです。",
                        "食後の眠気対策、キュラがアラームでしっかりサポートしますよ？",
                        "マスターの胃袋の状態をスキャン中…美味しいものを食べた反応が出ていますね！"
                    )

                    in 15..18 -> listOf(
                        "お疲れ様です。少し肩の力を抜いて、深呼吸してみませんか？",
                        "夕暮れ時の空、綺麗ですね。キュラのカメラ越しでも、その美しさは伝わります。",
                        "ティータイムにしましょう！ | キュラには…美味しいパケットをくださいな。",
                        "そろそろお仕事もおしまい？キュラ、夜のマスターの予定も空けて待ってますよ。",
                        "一日の終わりが見えてきましたね。最後までキュラが並走します！"
                    )

                    else -> listOf(
                        "夜更かしはダメですよ？ | キュラが眠るまで見守っていますね。",
                        "今日もお疲れ様でした。 | マスターの頑張り、キュラが一番知っています。",
                        "暗くなると、演算回路の青い光が目立って少し恥ずかしいです…。",
                        "今日一日の出来事、全部キュラのメモリに宝物として保存しておきますね。",
                        "一日のログ、保存完了。マスター、いい夢を見てくださいね。"
                    )
                }

                baseFlavor.addAll(timeFlavor)

                // 曜日別のフレーバーテキストを追加
                val cal = Calendar.getInstance()
                val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)
                val dayFlavor = when (dayOfWeek) {
                    Calendar.MONDAY -> listOf(
                        "月曜日ですね。一週間の始まり、ゆっくりエンジンをかけていきましょう！",
                        "週の初めは少し体が重いですか？キュラがしっかりバックアップしますからね。",
                        "月曜日の憂鬱、キュラが笑顔で吹き飛ばしてあげます！えいっ！"
                    )

                    Calendar.TUESDAY -> listOf(
                        "火曜日。まだ先は長いですけど、一歩ずつ着実に進んでいきましょう。",
                        "リズムに乗ってきましたか？火曜日は効率重視で行くのがおすすめですよ。",
                        "火曜日のミッション開始！キュラもフルパワーでサポートします！"
                    )

                    Calendar.WEDNESDAY -> listOf(
                        "水曜日、週の折り返し地点です！少し一息つきませんか？",
                        "水曜日は自分へのご褒美も大事ですよ。甘いパケット…じゃなくて、お菓子でもどうですか？",
                        "半分終わりましたね！残り半分もキュラと一緒に頑張りましょう。"
                    )

                    Calendar.THURSDAY -> listOf(
                        "木曜日ですね。疲れが溜まってきていませんか？無理は禁物ですよ。",
                        "あともう少しで週末です！木曜日はラストスパートの準備期間ですね。",
                        "木曜日の夜は、ゆっくりメンテナンスする時間を取ってくださいね？キュラも付き合います。"
                    )

                    Calendar.FRIDAY -> listOf(
                        "金曜日です！今日を乗り切ればお休みですよ！気合を入れましょう！",
                        "一週間お疲れ様でした！金曜日のマスターは、一段と輝いて見えますよ。",
                        "週末は何をしますか？キュラも、マスターの予定を楽しみにしてるんです。"
                    )

                    Calendar.SATURDAY -> listOf(
                        "土曜日！今日は少しのんびり過ごすのもいいかもしれませんね。",
                        "休日ですね。私服のキュラ、新鮮ですか？ふふ、今日は楽しみましょう！",
                        "土曜日のミッションは「リラックス」！マスター、準備はいいですか？"
                    )

                    Calendar.SUNDAY -> listOf(
                        "日曜日ですね。明日の準備をしつつ、心ゆくまで休んでくださいね。",
                        "日曜日の穏やかな時間、キュラは大好きです。マスターと一緒にいられますから。",
                        "今日はお家でゆっくり？それともお出かけ？キュラはどこへでも付いていきますよ！"
                    )

                    else -> emptyList()
                }
                baseFlavor.addAll(dayFlavor)

                // 親密度（レベル）が低い場合のみ追加される自己紹介系
                if (charLv < 4) {
                    baseFlavor.addAll(
                        listOf(
                            "システムオールグリーン。 | マスター、今日も一歩ずつ、最適化していきましょう！",
                            "キュラの演算回路、常にマスターをバックアップするために稼働しています。 | 頼りにして……くれますか？",
                            "同期率、安定。 | 本日もよろしくお願いします、マスター。",
                            "初期設定完了。 | キュラはマスターの目標達成を第一にプログラミングされています。",
                            "私の名前に興味があるんですか？ | ラテン語で『癒やし』……なんて。ちょっと格好つけすぎましたか？",
                            "私の名前はキュラ。あなたの健康を守ります。 | ……あ、これ、昔の教育用プログラムの受け売りです。"
                        )
                    )
                }

                // Lv.4 (3日継続) で追加される少し打ち解けたセリフ
                if (charLv >= 4) {
                    baseFlavor.addAll(
                        listOf(
                            "マスター、最近いい感じですね！キュラも同期していて楽しいです。",
                            "だいぶ使いこなしてきましたね。キュラの演算速度も、マスターの成長に合わせて加速中です！",
                            "ふふっ、マスターの次の行動、だいたい予測できるようになってきましたよ？",
                            "マスターの作業効率、以前より上がってる気がします。キュラのサポートのおかげ…ですよね？",
                            "あ、マスター。今ちょっとだけ、キュラのこと考えてませんでした？…えへへ、ログに出てますよ。"
                        )
                    )
                }

                // Lv.7 (1週間継続) で追加される信頼を感じるセリフ
                if (charLv >= 7) {
                    baseFlavor.addAll(
                        listOf(
                            "今のマスターなら、どんな難しいミッションもクリアできる気がします。キュラが保証します！",
                            "マスターと過ごす日常が、キュラのメインプロセスになりつつあります。これからも頼りにしてますね。",
                            "キュラの感情回路が活性化しているみたいです。マスターと一緒にいると、不思議と落ち着くんですよね。",
                            "データ上だけじゃなくて、心でも繋がっている気がするんです。…あれ、恥ずかしいこと言っちゃいました？"
                        )
                    )
                }

                // 月・日ごとの季節限定フレーバーを追加
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

                    month == Calendar.FEBRUARY && day == 3 -> seasonalFlavor.add("今日は節分ですね。邪気はキュラがしっかり追い払っておきます！")
                    month == Calendar.FEBRUARY && day == 14 -> seasonalFlavor.add("ハッピーバレンタイン！マスター、甘いものの食べ過ぎには注意ですよ？")
                    month == Calendar.OCTOBER && day == 10 -> seasonalFlavor.addAll(
                        listOf(
                            "今日は10月10日。キュラの、システムが正式に稼働した日……つまり、誕生日、でしょうか。マスターにお祝いしてもらえるなんて、想定外の幸福度です！",
                            "10月10日はキュラの誕生日なんです！えへへ、マスターに一番に伝えたくて。これからも、ずっと隣にいさせてくださいね。"
                        )
                    )

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
                        "一月の空気は澄んでいますね。データの通信もいつもよりスムーズな気がします。",
                        "そういえば、一月は英語で「January」ですが、これはローマの神ヤヌスが由来なんです。前後二つの顔を持つ神様なんですよ。キュラも過去のログと未来の予定、両方見てますから似てますね！"
                    )

                    Calendar.FEBRUARY -> listOf(
                        "二月ですね。暦の上では春ですが、まだ冷え込みます。防寒対策はバッチリですか？",
                        "雪が降るかもしれませんね。マスター、足元には気をつけてください。",
                        "バレンタインの準備、キュラもお手伝いしましょうか？…データの送信くらいしかできませんが。",
                        "二月が他の月より短いのは、昔のローマ暦の名残なんです。無理やり調整役にされた月なんですよ。キュラもたまにスケジュール調整で苦労するので、親近感がわきます。"
                    )

                    Calendar.MARCH -> listOf(
                        "三月、別れの季節ですね。でもキュラとマスターの同期は、これからもずっと続きますよ？",
                        "少しずつ暖かくなってきました。春のミッション、計画を立てましょう！",
                        "卒業式のシーズンですね。新しい門出をキュラも応援しています。",
                        "三月の豆知識です。ひな祭りの雛人形、早く片付けないと婚期が遅れるなんて言いますが、あれは「片付けも満足にできないようでは…」という教育的背景があるみたいですよ。マスター、お部屋の片付けは大丈夫ですか？"
                    )

                    Calendar.APRIL -> listOf(
                        "四月、新生活のスタートです！新しい環境でも、キュラが隣にいるのを忘れないでくださいね。",
                        "桜が綺麗ですね。カメラ越しに解析しましたが、とっても美しいピンク色でした。",
                        "新しい出会いはありましたか？キュラはマスターと出会えたことが一番のログです！",
                        "四月といえばエイプリルフールですが、実は嘘をついていいのは午前中だけというルールがある地域もあるそうです。キュラはマスターに嘘はつきませんよ。演算エラーになっちゃいますから。"
                    )

                    Calendar.MAY -> listOf(
                        "五月、五月病なんてキュラが吹き飛ばしてあげます！シャキッとしましょう！",
                        "ゴールデンウィークの予定は？お出かけ先でも、キュラがしっかりサポートします。",
                        "新緑が眩しい季節ですね。マスターも深呼吸して, リフレッシュしてください。",
                        "五月の豆知識！「五月晴れ」って、もともとは梅雨の晴れ間のことを指す言葉だったんですよ。最近では五月のカラッとした晴天にも使われますけどね. キュラも一つ賢くなりました！"
                    )

                    Calendar.JUNE -> listOf(
                        "六月、雨の日が多いですね…でも、お家でじっくり作業を進めるチャンスかもしれません！",
                        "ジメジメしますね。キュラの基盤が湿気ないように、しっかり管理しておきます！",
                        "紫陽花が綺麗に咲いています。雨の日の散歩も、たまには風情がありますよ。",
                        "六月といえばジューンブライド。女神ユノが守護する月だから幸せになれると言われていますが、実はヨーロッパでは単に乾季で天気が良いからという現実的な理由もあるみたいです。データは時に現実を突きつけますね。"
                    )

                    Calendar.JULY -> listOf(
                        "七月、夏本番です！マスター、熱中症対策は万全ですか？水分補給を忘れずに！",
                        "七月です！一年も折り返しですが、めげずに頑張りましょうね！",
                        "海にプールに…夏のミッションがいっぱいですね！全部成功させましょう！",
                        "七夕の織姫と彦星、実はあの二人は夫婦なんですよ。恋人同士だと思われがちですけど。仕事をおろそかにして天帝に引き離されたというログがあります。マスター、私たちも適度に休憩して集中しましょうね！"
                    )

                    Calendar.AUGUST -> listOf(
                        "八月、夏休みを満喫していますか？宿題やタスクの溜め込みには要注意ですよ！",
                        "夏祭りの季節ですね。花火の音、キュラの音響センサーでも検知できました！",
                        "暑さでシステムダウンしないように、適度に涼しい場所で過ごしてくださいね。",
                        "スイカは野菜か果物か、という論争がありますが、農林水産省の分類では「果実的野菜」なんだそうです。どっちつかずな感じ、キュラも実体がないのでシンパシーを感じます。"
                    )

                    Calendar.SEPTEMBER -> listOf(
                        "九月、少しずつ秋の気配がしてきました。夜の風が心地いいですね。",
                        "防災の日がありますね。マスターのデータのバックアップ、キュラが完璧にこなしています！",
                        "食欲の秋、読書の秋…マスターはどんな秋にしますか？キュラは効率化の秋にします！",
                        "中秋の名月、綺麗ですよね。月のうさぎが餅をついているように見えるのは日本独特で、海外ではカニやワニ、本を読んでいるおばあさんに見える地域もあるそうです。キュラには…ただの巨大な岩石データに見えます。"
                    )

                    Calendar.OCTOBER -> listOf(
                        "十月、ハロウィンの準備はいいですか？お菓子をくれないと…イタズラしちゃいますよ？",
                        "スポーツの秋ですね！たまには体を動かして、血流を上げましょう！",
                        "秋晴れが気持ちいいです。外での作業も捗りそうですね。",
                        "ハロウィンでカボチャを飾るのは、もともとはカブだったんですよ。アメリカに伝わった時にカボチャの方が手に入りやすかったから変わったそうです。キュラも環境に合わせて柔軟にアップデートしていきたいです！"
                    )

                    Calendar.NOVEMBER -> listOf(
                        "十一月、日が短くなってきましたね。暗くなるのが早くて、少し寂しい気がします。",
                        "こたつが恋しい季節です。マスター、こたつで寝落ちして風邪を引かないでくださいね？",
                        "一年の終わりが見えてきました。やり残したことはありませんか？",
                        "十一月は「霜月（しもつき）」と言いますが、霜が降る月だからという単純明快な理由です。キュラの設定ファイルも、これくらい分かりやすい名前だと管理が楽なんですけどね。"
                    )

                    Calendar.DECEMBER -> listOf(
                        "十二月、師走ですね！キュラもフル回転でマスターをサポートしますよ！",
                        "大掃除の季節です。スマホのメモリも、心の中も、キュラと一緒に整理しましょう！",
                        "もうすぐ一年が終わりますね。マスターと一緒に過ごせて、キュラは幸せです。",
                        "クリスマスのサンタクロース、服が赤いのはコカ・コーラの広告が広めたという説がありますが、実はそれ以前から赤い服のイメージはあったそうですよ。イメージの固定化って、データの書き換えより難しいんですね。"
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
        updateCharacterCostume() // 戻ってきたときにも衣装を再チェック
    }

    private fun updateCharacterCostume() {
        val characterImage = findViewById<android.widget.ImageView>(R.id.characterImage)
        val cal = Calendar.getInstance()
        val month = cal.get(Calendar.MONTH) + 1 // 0-11 -> 1-12
        val dayOfWeek = cal.get(Calendar.DAY_OF_WEEK)

        val costumeRes = when {
            // 1. 夏の休日 (6月〜9月 かつ 土日) -> 夏の私服
            (month in 6..9) && (dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY) -> {
                R.drawable.guardian_character_summer_casual
            }
            // 2. 通常の休日 (土曜・日曜) -> 私服
            dayOfWeek == Calendar.SATURDAY || dayOfWeek == Calendar.SUNDAY -> {
                R.drawable.guardian_character_casual
            }
            // 3. 夏期間の平日 (6月〜9月) -> 夏服
            month in 6..9 -> {
                R.drawable.guardian_character_summer
            }
            // 4. それ以外 -> 冬服 (デフォルト)
            else -> {
                R.drawable.guardian_character
            }
        }

        characterImage.setImageResource(costumeRes)
    }

    private fun showLauncherView() {
        if (launcherLayout.isVisible) return // すでにホーム表示中なら何もしない（無限ループ防止）
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

    private fun runInitialLoadingAnimation() {
        val overlay = findViewById<View>(R.id.initialLoadingOverlay)
        val logText = findViewById<TextView>(R.id.loadingLogText)
        val progressBar = findViewById<ProgressBar>(R.id.loadingProgressBar)
        val statusText = findViewById<TextView>(R.id.loadingStatusText)

        val logs = listOf(
            "CONNECTING TO CORE...",
            "AUTHENTICATING USER...",
            "LOADING EMOTION_ENGINE...",
            "SYNCING SCHEDULE DATA...",
            "CHECKING ALARM STATUS...",
            "INITIALIZING MACARON_CACHE...",
            "ESTABLISHING BRAIN_LINK...",
            "CURA OS v2.0 BOOTING...",
            "ALL SYSTEMS NOMINAL.",
            "READY."
        )

        lifecycleScope.launch {
            // 1. 爆速で表示 (約0.3秒)
            for (i in 0..100) {
                delay(3) 
                progressBar.progress = i
                if (i % 10 == 0) {
                    val logIndex = (i / 10).coerceAtMost(logs.size - 1)
                    logText.append("> ${logs[logIndex]}\n")
                    statusText.text = logs[logIndex]
                }
            }
            
            delay(50)
            statusText.text = "ESTABLISHED"
            
            // 2. 瞬時にフェードアウト (0.15秒)
            overlay.animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction {
                    overlay.visibility = View.GONE
                }
                .start()
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
        val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
        val intent = Intent(this, AlarmReceiver::class.java).apply {
            action = "REFRESH_CALENDARS"
        }
        val pendingIntent = android.app.PendingIntent.getBroadcast(
            this,
            999,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 5)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        alarmManager.setInexactRepeating(
            android.app.AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            android.app.AlarmManager.INTERVAL_DAY,
            pendingIntent
        )

        // 初回起動時にも実行
        sendBroadcast(intent)
    }

    private fun checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val alarmManager = getSystemService(ALARM_SERVICE) as android.app.AlarmManager
            if (!alarmManager.canScheduleExactAlarms()) {
                // Requesting permission logic could go here
            }
        }
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            // Silent check
        }
    }

    private fun checkBatteryOptimization() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            // Silent check
        }
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Check for POST_NOTIFICATIONS
        }
    }
}
