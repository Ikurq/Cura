package com.example.voicevox

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlarmFragment : Fragment() {

    private val alarmList = ArrayList<AlarmItem>()
    private lateinit var alarmAdapter: AlarmAdapter

    private val characterList = listOf(
        "四国めたん" to 2,
        "ずんだもん" to 3,
        "春日部つむぎ" to 8,
        "雨晴はう" to 10,
        "波音リツ" to 9,
        "玄野武宏" to 11,
        "白上虎太郎" to 12,
        "青山龍星" to 13,
        "冥鳴ひまり" to 14,
        "九州そら" to 16,
        "もち子さん" to 20,
        "剣崎雌雄" to 21,
        "WhiteCUL" to 23,
        "後鬼" to 27,
        "No.7" to 29,
        "ちび式じい" to 42,
        "櫻歌ミコ" to 43,
        "小夜/SAYO" to 46,
        "ナースロボ＿タイプＴ" to 47,
        "†聖騎士 紅桜†" to 51,
        "雀松朱司" to 52,
        "麒ヶ島宗麟" to 53,
        "春歌ナナ" to 54,
        "猫使アル" to 55,
        "猫使ビィ" to 58,
        "中国うさぎ" to 61,
        "栗田まろん" to 67,
        "藍駄ホキ" to 68,
        "満別花丸" to 69,
        "琴詠ニア" to 70
    )

    private var previewPlayer: android.media.MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_alarm, container, false)

        val emptyView = view.findViewById<View>(R.id.emptyTextView)
        val alarmRecyclerView = view.findViewById<RecyclerView>(R.id.alarmRecyclerView)
        val addAlarmFAB = view.findViewById<View>(R.id.addAlarmFAB)
        val addMandatoryFAB = view.findViewById<View>(R.id.addMandatoryAlarmFAB)

        // 💾 保存されているアラームの読み込み
        loadAlarms()
        emptyView.visibility = if (alarmList.isEmpty()) View.VISIBLE else View.GONE

        // 📇 RecyclerViewの設定
        alarmAdapter = AlarmAdapter(
            alarmList,
            onSwitchChanged = { item, isChecked ->
                item.isEnabled = isChecked
                saveAlarms()
                if (isChecked) {
                    // スイッチがONになったら再スケジュール（音声ファイルがあれば流用、今回は簡易的に再生成を促すか既存パスを登録）
                    val file = File(requireContext().filesDir, "${item.id}_alarm.wav")
                    if (file.exists()) {
                        scheduleVoiceAlarm(item, file.absolutePath)
                    } else {
                        Toast.makeText(requireContext(), "音声ファイルを生成するため、アラームを再設定してください", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    cancelVoiceAlarm(item)
                }
            },
            onItemLongClicked = { item ->
                AlertDialog.Builder(requireContext())
                    .setTitle("アラームの削除")
                    .setMessage("このアラームを削除しますか？")
                    .setPositiveButton("削除") { _, _ ->
                        cancelVoiceAlarm(item)
                        val file = File(requireContext().filesDir, "${item.id}_alarm.wav")
                        if (file.exists()) file.delete()
                        alarmList.remove(item)
                        alarmAdapter.notifyDataSetChanged()
                        saveAlarms()
                        updateEmptyView()
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        )

        alarmRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        alarmRecyclerView.adapter = alarmAdapter

        // ➕ 浮かぶプラスボタンを押した時に、入力ダイアログを表示
        addAlarmFAB.setOnClickListener {
            showAddAlarmDialog()
        }

        addMandatoryFAB.setOnClickListener {
            showMandatoryAlarmDialog()
        }

        return view
    }

    private fun showMandatoryAlarmDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mandatory_alarm, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinMandatoryEvent)
        val leadTimeInput = dialogView.findViewById<EditText>(R.id.editLeadTime)
        val speakerSpinner = dialogView.findViewById<Spinner>(R.id.spinMandatorySpeaker)
        val firstExtButton = dialogView.findViewById<Button>(R.id.btnSelectFirstExternal)

        val today = Calendar.getInstance()
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        
        val eventsToday = ScheduleLoader.loadAllEventsForToday(requireContext(), today)
        val eventsTomorrow = ScheduleLoader.loadAllEventsForToday(requireContext(), tomorrow)
        
        val combinedEvents = mutableListOf<Pair<IcsEvent, Boolean>>() // Event to IsTomorrow
        eventsToday.forEach { combinedEvents.add(it to false) }
        eventsTomorrow.forEach { combinedEvents.add(it to true) }

        val titles = combinedEvents.map { (event, isTomorrow) ->
            val prefix = if (isTomorrow) "[明日] " else "[今日] "
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.startTime))
            "$prefix$time ${event.summary}"
        }
        
        val eventAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, titles)
        eventAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = eventAdapter

        // キャラクタースピナーの初期化
        val speakerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, characterList.map { it.first })
        speakerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        speakerSpinner.adapter = speakerAdapter
        // デフォルトはずんだもん(index 1)を選択
        speakerSpinner.setSelection(1)

        firstExtButton.setOnClickListener {
            // Select first today, if none, select first tomorrow
            if (combinedEvents.isNotEmpty()) {
                spinner.setSelection(0)
            } else {
                Toast.makeText(requireContext(), "予定が見つかりません", Toast.LENGTH_SHORT).show()
            }
        }

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("生成してセット") { _, _ ->
                val selectedIndex = spinner.selectedItemPosition
                if (selectedIndex == -1) return@setPositiveButton
                
                val (event, _) = combinedEvents[selectedIndex]
                val leadTime = leadTimeInput.text.toString().toIntOrNull() ?: 30

                val selectedSpeakerPos = speakerSpinner.selectedItemPosition
                val speakerName = characterList[selectedSpeakerPos].first
                val speakerId = characterList[selectedSpeakerPos].second
                
                generateMandatoryAlarm(event, leadTime, speakerId, speakerName)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun generateMandatoryAlarm(event: IcsEvent, leadTimeMinutes: Int, speakerId: Int, speakerName: String) {
        val alarmCal = Calendar.getInstance().apply {
            timeInMillis = event.startTime
            add(Calendar.MINUTE, -leadTimeMinutes)
        }

        val hour = alarmCal.get(Calendar.HOUR_OF_DAY)
        val minute = alarmCal.get(Calendar.MINUTE)

        val message = "${hour}時${minute}分を過ぎています。本日の予定である${event.summary}まであと${leadTimeMinutes}分を切っています。起きてください。"
        
        Toast.makeText(requireContext(), "特別なアラーム音声を生成中...", Toast.LENGTH_LONG).show()
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val client = WebVoicevoxClient()
            val newId = UUID.randomUUID().toString()
            val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")
            
            val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            val apiKey = appPrefs.getString("custom_api_key", null)

            val success = withContext(Dispatchers.IO) {
                client.createAlarmAudio(message, speakerId, outputFile, apiKey)
            }
            
            if (success) {
                val newItem = AlarmItem(
                    id = newId,
                    hour = hour,
                    minute = minute,
                    message = message,
                    speakerId = speakerId,
                    speakerName = speakerName,
                    isEnabled = true,
                    readTasks = false,
                    vibrate = true,
                    repeatDays = emptyList()
                )

                // 重複チェック: 同じ時間、同じボイスのアラームがあれば削除
                val duplicate = alarmList.find { it.hour == hour && it.minute == minute && it.speakerId == speakerId }
                if (duplicate != null) {
                    cancelVoiceAlarm(duplicate)
                    val oldFile = File(requireContext().filesDir, "${duplicate.id}_alarm.wav")
                    if (oldFile.exists()) oldFile.delete()
                    alarmList.remove(duplicate)
                }
                
                alarmList.add(newItem)
                alarmList.sortWith(compareBy({ it.hour }, { it.minute }))
                alarmAdapter.notifyDataSetChanged()
                saveAlarms()
                updateEmptyView()

                scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                
                val timeStr = String.format(Locale.getDefault(), "%d:%02d", hour, minute)
                Toast.makeText(requireContext(), "「絶対起きるアラーム」を${timeStr}にセットしました", Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(requireContext(), "音声生成に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ➕ アラーム新規追加用のカスタムダイアログを表示する
    private fun showAddAlarmDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_alarm, null)
        val timePreviewText = dialogView.findViewById<TextView>(R.id.dialogTimePreview)
        val selectTimeButton = dialogView.findViewById<Button>(R.id.dialogSelectTimeButton)
        val messageInput = dialogView.findViewById<EditText>(R.id.dialogMessageInput)
        val speakerSpinner = dialogView.findViewById<Spinner>(R.id.dialogSpeakerSpinner)
        val btnPreview = dialogView.findViewById<Button>(R.id.btnPreviewVoice)
        val readTasksCheckBox = dialogView.findViewById<CheckBox>(R.id.dialogReadTasksCheckBox)
        val vibrateCheckBox = dialogView.findViewById<CheckBox>(R.id.dialogVibrateCheckBox)

        val dayToggles = listOf(
            R.id.toggleSun to 1, R.id.toggleMon to 2, R.id.toggleTue to 3,
            R.id.toggleWed to 4, R.id.toggleThu to 5, R.id.toggleFri to 6, R.id.toggleSat to 7
        ).map { (id, day) -> dialogView.findViewById<android.widget.ToggleButton>(id) to day }

        var pickedHour = 7
        var pickedMinute = 0

        // スピナーの初期化
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, characterList.map { it.first })
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        speakerSpinner.adapter = spinnerAdapter

        selectTimeButton.setOnClickListener {
            TimePickerHelper.showWheelTimePicker(requireContext(), pickedHour, pickedMinute) { h, m ->
                pickedHour = h
                pickedMinute = m
                timePreviewText.text = String.format(Locale.getDefault(), "設定時刻：%02d:%02d", h, m)
            }
        }

        btnPreview.setOnClickListener {
            val message = messageInput.text.toString().ifEmpty { "時間です。起きてください。" }
            val previewMessage = "これは試聴です。${message}"
            val selectedPosition = speakerSpinner.selectedItemPosition
            val speakerId = characterList[selectedPosition].second
            
            btnPreview.isEnabled = false
            btnPreview.text = "生成中"
            
            viewLifecycleOwner.lifecycleScope.launch {
                val client = WebVoicevoxClient()
                val tempFile = File(requireContext().cacheDir, "preview_voice.wav")
                
                val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                val apiKey = appPrefs.getString("custom_api_key", null)

                val success = withContext(Dispatchers.IO) {
                    client.createAlarmAudio(previewMessage, speakerId, tempFile, apiKey)
                }
                
                if (success) {
                    playPreview(tempFile)
                } else {
                    Toast.makeText(requireContext(), "プレビューの生成に失敗しました", Toast.LENGTH_SHORT).show()
                }
                btnPreview.isEnabled = true
                btnPreview.text = "試聴"
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("新しいアラームを追加")
            .setView(dialogView)
            .setPositiveButton("生成・保存") { dialog, _ ->
                val message = messageInput.text.toString().ifEmpty { "時間です。起きてください。" }
                val selectedPosition = speakerSpinner.selectedItemPosition
                val speakerName = characterList[selectedPosition].first
                val speakerId = characterList[selectedPosition].second
                val readTasks = readTasksCheckBox.isChecked
                val vibrate = vibrateCheckBox.isChecked
                val repeatDays = dayToggles.filter { it.first.isChecked }.map { it.second }

                val newId = UUID.randomUUID().toString()
                val newItem = AlarmItem(newId, pickedHour, pickedMinute, message, speakerId, speakerName, true, readTasks, vibrate, repeatDays)

                // 重複チェック: 同じ時間、同じボイスのアラームがあれば古い方を削除する
                val duplicate = alarmList.find { it.hour == pickedHour && it.minute == pickedMinute && it.speakerId == speakerId }
                if (duplicate != null) {
                    cancelVoiceAlarm(duplicate)
                    val oldFile = File(requireContext().filesDir, "${duplicate.id}_alarm.wav")
                    if (oldFile.exists()) oldFile.delete()
                    alarmList.remove(duplicate)
                }

                // ボイス生成と保存の非同期処理
                // 時刻を告げる文章を追加
                var finalMessage = "${pickedHour}時${pickedMinute}分を過ぎました。${message}"

                if (readTasks) {
                    val tasks = ScheduleLoader.loadTasksForToday(requireContext())
                    if (tasks.isNotEmpty()) {
                        val sb = StringBuilder().append("。本日のタスクは、")
                        for (t in tasks) sb.append(t).append("、")
                        sb.append("です。")
                        finalMessage += sb.toString()
                    }
                }

                viewLifecycleOwner.lifecycleScope.launch {
                    val client = WebVoicevoxClient()
                    val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")

                    val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
                    val apiKey = appPrefs.getString("custom_api_key", null)

                    Toast.makeText(requireContext(), "ボイス生成中...", Toast.LENGTH_SHORT).show()

                    if (client.createAlarmAudio(finalMessage, speakerId, outputFile, apiKey)) {
                        alarmList.add(newItem)
                        // 時刻順にソートする綺麗な処理
                        alarmList.sortWith(compareBy({ it.hour }, { it.minute }))
                        alarmAdapter.notifyDataSetChanged()
                        saveAlarms()
                        updateEmptyView()

                        scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                        Toast.makeText(requireContext(), "アラームを保存しました", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "音声の生成に失敗しました", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun playPreview(file: File) {
        previewPlayer?.release()
        previewPlayer = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnPreparedListener { it.start() }
            prepareAsync()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewPlayer?.release()
        previewPlayer = null
    }

    private fun updateEmptyView() {
        val emptyView = view?.findViewById<View>(R.id.emptyTextView)
        emptyView?.visibility = if (alarmList.isEmpty()) View.VISIBLE else View.GONE
    }

    // 💾 アラーム一覧をJSONデータとして保存
    private fun saveAlarms() {
        val prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (item in alarmList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("hour", item.hour)
                put("minute", item.minute)
                put("message", item.message)
                put("speakerId", item.speakerId)
                put("speakerName", item.speakerName)
                put("isEnabled", item.isEnabled)
                put("readTasks", item.readTasks)
                put("vibrate", item.vibrate)
                put("repeatDays", JSONArray(item.repeatDays))
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("alarmListJSON", jsonArray.toString()).apply()
    }

    // 📂 アラーム一覧の読み込み
    private fun loadAlarms() {
        val prefs = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("alarmListJSON", null)
        alarmList.clear()
        if (jsonString != null) {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                alarmList.add(
                    AlarmItem(
                        obj.getString("id"),
                        obj.getInt("hour"),
                        obj.getInt("minute"),
                        obj.getString("message"),
                        obj.getInt("speakerId"),
                        obj.getString("speakerName"),
                        obj.getBoolean("isEnabled"),
                        obj.getBoolean("readTasks"),
                        obj.optBoolean("vibrate", true),
                        ArrayList<Int>().apply {
                            val daysArr = obj.optJSONArray("repeatDays")
                            if (daysArr != null) {
                                for (j in 0 until daysArr.length()) add(daysArr.getInt(j))
                            }
                        }
                    )
                )
            }
        }
    }

    private fun scheduleVoiceAlarm(item: AlarmItem, audioPath: String) {
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        
        // --- Added: Permission Check ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (!alarmManager.canScheduleExactAlarms()) {
                android.util.Log.e("AlarmFragment", "Cannot schedule exact alarms! Permission missing.")
            }
        }

        val intent = Intent(requireContext(), AlarmReceiver::class.java).apply {
            action = "ALARM_TRIGGER"
            putExtra("AUDIO_FILE_PATH", audioPath)
            putExtra("ALARM_ID", item.id)
            putExtra("VIBRATE", item.vibrate)
        }
        
        val pendingIntent = PendingIntent.getBroadcast(
            requireContext(),
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }

        val now = System.currentTimeMillis()
        
        if (item.repeatDays.isEmpty()) {
            // 繰り返しなし：今日が過ぎていれば明日
            if (calendar.timeInMillis <= now) {
                calendar.add(Calendar.DAY_OF_YEAR, 1)
            }
        } else {
            // 繰り返しあり：設定された曜日のうち、現在から最も近い未来の時刻を探す
            var minDiff = Long.MAX_VALUE
            var targetCalendar: Calendar? = null
            
            for (day in item.repeatDays) {
                val tempCal = calendar.clone() as Calendar
                tempCal.set(Calendar.DAY_OF_WEEK, day)
                
                // もし設定した曜日が「今日」で、すでに時刻が過ぎている、
                // あるいは設定した曜日が「今日より前」の場合は、1週間後をチェック
                if (tempCal.timeInMillis <= now) {
                    tempCal.add(Calendar.WEEK_OF_YEAR, 1)
                }
                
                val diff = tempCal.timeInMillis - now
                if (diff < minDiff) {
                    minDiff = diff
                    targetCalendar = tempCal
                }
            }
            targetCalendar?.let { calendar.timeInMillis = it.timeInMillis }
        }

        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
    }

    private fun cancelVoiceAlarm(item: AlarmItem) {
        val alarmManager = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(requireContext(), AlarmReceiver::class.java).apply {
            action = "ALARM_TRIGGER"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            requireContext(),
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }
}