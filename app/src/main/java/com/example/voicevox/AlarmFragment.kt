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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlarmFragment : Fragment() {

    private val alarmList = ArrayList<AlarmItem>()
    private lateinit var alarmAdapter: AlarmAdapter

    /**
     * 選択できる声。取得済みの音声モデルに含まれるものだけが並ぶ。
     * ダイアログを開くたびに読み直す(設定画面でモデルを取得・削除できるため)。
     */
    private var voiceList: List<CuraVoicevox.Voice> = emptyList()

    private var previewPlayer: MediaPlayer? = null

    // ダイアログの状態保持用
    private var currentDialogView: View? = null
    private var currentPickedHour: Int = 7
    private var currentPickedMinute: Int = 0
    private var currentDayToggles: List<Pair<android.widget.ToggleButton, Int>> = emptyList()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_alarm, container, false)

        val recyclerView = view.findViewById<RecyclerView>(R.id.alarmRecyclerView)
        val addAlarmFAB = view.findViewById<View>(R.id.addAlarmFAB)
        val addMandatoryFAB = view.findViewById<View>(R.id.addMandatoryAlarmFAB)

        loadAlarms()

        alarmAdapter = AlarmAdapter(alarmList, { item, isEnabled ->
            // AlarmItem は共通ロジック側で不変なので、要素を差し替える
            val index = alarmList.indexOfFirst { it.id == item.id }
            val updated = item.copy(isEnabled = isEnabled)
            if (index >= 0) alarmList[index] = updated
            if (updated.isEnabled) {
                val audioFile = File(requireContext().filesDir, "${updated.id}_alarm.wav")
                scheduleVoiceAlarm(updated, audioFile.absolutePath)
                Toast.makeText(requireContext(), "アラームをONにしました", Toast.LENGTH_SHORT).show()
            } else {
                cancelVoiceAlarm(updated)
                Toast.makeText(requireContext(), "アラームをOFFにしました", Toast.LENGTH_SHORT).show()
            }
            saveAlarms()
        }, { item ->
            AlertDialog.Builder(requireContext())
                .setTitle("アラームの削除")
                .setMessage("このアラームを削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    cancelVoiceAlarm(item)
                    val audioFile = File(requireContext().filesDir, "${item.id}_alarm.wav")
                    if (audioFile.exists()) audioFile.delete()
                    alarmList.remove(item)
                    alarmAdapter.notifyDataSetChanged()
                    saveAlarms()
                    updateEmptyView()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        })

        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = alarmAdapter

        updateEmptyView()

        addAlarmFAB.setOnClickListener {
            showAddAlarmDialog()
        }

        addMandatoryFAB.setOnClickListener {
            showMandatoryAlarmDialog()
        }

        return view
    }

    /**
     * 取得済みの声を読み込む。1つも無ければ設定画面へ誘導して false を返す。
     * 端末内合成なので、モデルが無いと何も喋れない。
     */
    private fun ensureVoicesAvailable(): Boolean {
        voiceList = CuraVoicevox.availableVoices(requireContext())
        if (voiceList.isNotEmpty()) return true

        AlertDialog.Builder(requireContext())
            .setTitle("音声モデルがありません")
            .setMessage("アラームの音声は端末内で合成します。設定 ＞ 音声・ストレージ から、使いたいキャラクターの音声モデルを取得してください。")
            .setPositiveButton(R.string.ok, null)
            .show()
        return false
    }

    /** 声のスピナーを組む。前回選んだ声があればそれを初期選択にする。 */
    private fun bindSpeakerSpinner(spinner: Spinner) {
        val adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_item,
            voiceList.map { it.displayName },
        )
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter

        val lastUsed = requireContext()
            .getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .getInt("last_speaker_id", CuraVoicevox.DEFAULT_SPEAKER_ID)
        val index = voiceList.indexOfFirst { it.styleId == lastUsed }
        if (index >= 0) spinner.setSelection(index)
    }

    private fun rememberSpeaker(styleId: Int) {
        requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            .edit().putInt("last_speaker_id", styleId).apply()
    }

    /**
     * 合成結果をユーザーに伝える。モデル未取得のときだけ設定画面へ誘導する。
     * @return 成功していれば true。
     */
    private fun reportSynthesis(result: CuraVoicevox.SynthesisResult): Boolean {
        when (result) {
            is CuraVoicevox.SynthesisResult.Success -> return true
            is CuraVoicevox.SynthesisResult.ModelMissing ->
                AlertDialog.Builder(requireContext())
                    .setTitle("音声モデルが未取得です")
                    .setMessage("${result.characterName} の音声モデルがありません。設定 ＞ 音声・ストレージ から取得してください。")
                    .setPositiveButton(R.string.ok, null)
                    .show()
            is CuraVoicevox.SynthesisResult.UnknownVoice ->
                Toast.makeText(requireContext(), "この話者は現在利用できません", Toast.LENGTH_SHORT).show()
            is CuraVoicevox.SynthesisResult.Failed ->
                Toast.makeText(requireContext(), "音声の生成に失敗しました: ${result.cause.message}", Toast.LENGTH_LONG).show()
        }
        return false
    }

    private fun showMandatoryAlarmDialog() {
        if (!ensureVoicesAvailable()) return

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mandatory_alarm, null)
        val spinner = dialogView.findViewById<Spinner>(R.id.spinMandatoryEvent)
        val leadTimeInput = dialogView.findViewById<EditText>(R.id.editLeadTime)
        val speakerSpinner = dialogView.findViewById<Spinner>(R.id.spinMandatorySpeaker)
        val firstExtButton = dialogView.findViewById<Button>(R.id.btnSelectFirstExternal)

        val today = Calendar.getInstance()
        val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        
        val eventsToday = ScheduleLoader.loadAllEventsForToday(requireContext(), today)
        val eventsTomorrow = ScheduleLoader.loadAllEventsForToday(requireContext(), tomorrow)
        
        val combinedEvents = mutableListOf<Pair<IcsEvent, Boolean>>()
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

        bindSpeakerSpinner(speakerSpinner)

        firstExtButton.setOnClickListener {
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
                val voice = voiceList.getOrNull(speakerSpinner.selectedItemPosition)
                    ?: return@setPositiveButton
                rememberSpeaker(voice.styleId)
                generateMandatoryAlarm(event, leadTime, voice.styleId, voice.characterName)
            }
            .setNegativeButton("キャンセル", null)
            .show()
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
            val newId = UUID.randomUUID().toString()
            val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")

            val result = CuraVoicevox.synthesizeToFile(requireContext(), message, speakerId, outputFile)

            if (reportSynthesis(result)) {
                val newItem = AlarmItem(newId, hour, minute, message, speakerId, speakerName, true, false, true, emptyList())
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
                Toast.makeText(requireContext(), "「絶対起きるアラーム」をセットしました", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showAddAlarmDialog() {
        if (!ensureVoicesAvailable()) return

        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_alarm, null)
        currentDialogView = dialogView
        val timePreviewText = dialogView.findViewById<TextView>(R.id.dialogTimePreview)
        val selectTimeButton = dialogView.findViewById<Button>(R.id.dialogSelectTimeButton)
        val messageInput = dialogView.findViewById<EditText>(R.id.dialogMessageInput)
        val speakerSpinner = dialogView.findViewById<Spinner>(R.id.dialogSpeakerSpinner)
        val btnPreview = dialogView.findViewById<Button>(R.id.btnPreviewVoice)

        currentDayToggles = listOf(
            R.id.toggleSun to 1, R.id.toggleMon to 2, R.id.toggleTue to 3,
            R.id.toggleWed to 4, R.id.toggleThu to 5, R.id.toggleFri to 6, R.id.toggleSat to 7
        ).map { (id, day) -> dialogView.findViewById<android.widget.ToggleButton>(id) to day }

        currentPickedHour = 7
        currentPickedMinute = 0

        bindSpeakerSpinner(speakerSpinner)

        selectTimeButton.setOnClickListener {
            TimePickerHelper.showWheelTimePicker(requireContext(), currentPickedHour, currentPickedMinute) { h, m ->
                currentPickedHour = h
                currentPickedMinute = m
                timePreviewText.text = String.format(Locale.getDefault(), "設定時刻：%02d:%02d", h, m)
            }
        }

        // 合成は端末内で完結するので、通信量の警告はもう出さない
        btnPreview.setOnClickListener { startPreviewGeneration() }

        AlertDialog.Builder(requireContext())
            .setTitle("新しいアラームを追加")
            .setView(dialogView)
            .setPositiveButton("生成・保存") { _, _ -> startAlarmGeneration() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun startPreviewGeneration() {
        val dialogView = currentDialogView ?: return
        val btnPreview = dialogView.findViewById<Button>(R.id.btnPreviewVoice)
        val messageInput = dialogView.findViewById<EditText>(R.id.dialogMessageInput)
        val speakerSpinner = dialogView.findViewById<Spinner>(R.id.dialogSpeakerSpinner)

        val message = messageInput.text.toString().ifEmpty { "時間です。起きてください。" }
        val previewMessage = "これは試聴です。${message}"
        val voice = voiceList.getOrNull(speakerSpinner.selectedItemPosition) ?: return

        btnPreview.isEnabled = false
        btnPreview.text = "生成中"

        viewLifecycleOwner.lifecycleScope.launch {
            val tempFile = File(requireContext().cacheDir, "preview_voice.wav")
            val result = CuraVoicevox.synthesizeToFile(
                requireContext(), previewMessage, voice.styleId, tempFile
            )
            if (reportSynthesis(result)) playPreview(tempFile)

            btnPreview.isEnabled = true
            btnPreview.text = "試聴"
        }
    }

    private fun startAlarmGeneration() {
        val dialogView = currentDialogView ?: return
        val messageInput = dialogView.findViewById<EditText>(R.id.dialogMessageInput)
        val speakerSpinner = dialogView.findViewById<Spinner>(R.id.dialogSpeakerSpinner)
        val readTasksCheckBox = dialogView.findViewById<CheckBox>(R.id.dialogReadTasksCheckBox)
        val vibrateCheckBox = dialogView.findViewById<CheckBox>(R.id.dialogVibrateCheckBox)

        val message = messageInput.text.toString().ifEmpty { "時間です。起きてください。" }
        val voice = voiceList.getOrNull(speakerSpinner.selectedItemPosition) ?: return
        val speakerName = voice.characterName
        val speakerId = voice.styleId
        rememberSpeaker(speakerId)
        val readTasks = readTasksCheckBox.isChecked
        val vibrate = vibrateCheckBox.isChecked
        val repeatDays = currentDayToggles.filter { it.first.isChecked }.map { it.second }

        val newId = UUID.randomUUID().toString()
        val newItem = AlarmItem(newId, currentPickedHour, currentPickedMinute, message, speakerId, speakerName, true, readTasks, vibrate, repeatDays)

        val duplicate = alarmList.find { it.hour == currentPickedHour && it.minute == currentPickedMinute && it.speakerId == speakerId }
        if (duplicate != null) {
            cancelVoiceAlarm(duplicate)
            val oldFile = File(requireContext().filesDir, "${duplicate.id}_alarm.wav")
            if (oldFile.exists()) oldFile.delete()
            alarmList.remove(duplicate)
        }

        var finalMessage = "${currentPickedHour}時${currentPickedMinute}分を過ぎました。${message}"
        if (readTasks) {
            val tasks = ScheduleLoader.loadTasksForToday(requireContext())
            if (tasks.isNotEmpty()) {
                val sb = StringBuilder().append("。本日のタスクは、")
                tasks.forEach { sb.append(it).append("、") }
                sb.append("です。")
                finalMessage += sb.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")

            Toast.makeText(requireContext(), "ボイス生成中...", Toast.LENGTH_SHORT).show()

            val result = CuraVoicevox.synthesizeToFile(requireContext(), finalMessage, speakerId, outputFile)
            if (reportSynthesis(result)) {
                alarmList.add(newItem)
                alarmList.sortWith(compareBy({ it.hour }, { it.minute }))
                alarmAdapter.notifyDataSetChanged()
                saveAlarms()
                updateEmptyView()
                scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                Toast.makeText(requireContext(), "アラームを保存しました", Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun scheduleVoiceAlarm(item: AlarmItem, audioPath: String) =
        AlarmScheduler.schedule(requireContext(), item)

    private fun cancelVoiceAlarm(item: AlarmItem) =
        AlarmScheduler.cancel(requireContext(), item)
}
