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

    // 読み込まれたキャラクターモデルの情報を保持する
    private var loadedModels: List<jp.voicevox.android.VoicevoxModelInfo> = emptyList()

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
            item.isEnabled = isEnabled
            if (item.isEnabled) {
                val audioFile = File(requireContext().filesDir, "${item.id}_alarm.wav")
                scheduleVoiceAlarm(item, audioFile.absolutePath)
                Toast.makeText(requireContext(), "アラームをONにしました", Toast.LENGTH_SHORT).show()
            } else {
                cancelVoiceAlarm(item)
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

        addAlarmFAB.setOnClickListener {
            showAddAlarmDialog()
        }

        addMandatoryFAB.setOnClickListener {
            showMandatoryAlarmDialog()
        }

        updateEmptyView()
        return view
    }

    private fun showMandatoryAlarmDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val client = LocalVoicevoxClient(requireContext())
            loadedModels = client.getModels()
            
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_mandatory_alarm, null)
            val eventSpinner = dialogView.findViewById<Spinner>(R.id.spinMandatoryEvent)
            val leadTimeInput = dialogView.findViewById<EditText>(R.id.editLeadTime)
            val speakerSpinner = dialogView.findViewById<Spinner>(R.id.spinMandatorySpeaker)
            
            // 重要：マニュアル版ダイアログにはスタイルSpinnerがない場合があるため、レイアウトを確認
            // もしレイアウトが共有でないなら、ここにも追加が必要ですが、一旦既存に合わせます。

            val events = ScheduleLoader.loadAllEventsForToday(requireContext(), Calendar.getInstance())
            val calendarTomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            val eventsTomorrow = ScheduleLoader.loadAllEventsForToday(requireContext(), calendarTomorrow)
            
            val combinedEvents = (events.map { it to false } + eventsTomorrow.map { it to true })
            val titles = combinedEvents.map { (event, isTomorrow) ->
                val prefix = if (isTomorrow) "[明日] " else "[今日] "
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.startTime))
                "$prefix$time ${event.summary}"
            }
            
            eventSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, titles).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            val uniqueNames = loadedModels.flatMap { m -> m.characters.map { it.name } }
                .filter { !it.contains("女声") && !it.contains("男声") && it != "WhiteCUL" }
                .distinct()
            speakerSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, uniqueNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            AlertDialog.Builder(requireContext())
                .setTitle("「絶対起きるアラーム」をセット")
                .setView(dialogView)
                .setPositiveButton("生成") { _, _ ->
                    val selectedIndex = eventSpinner.selectedItemPosition
                    if (selectedIndex == -1) return@setPositiveButton
                    
                    val (event, _) = combinedEvents[selectedIndex]
                    val leadTime = leadTimeInput.text.toString().toIntOrNull() ?: 30
                    val charName = uniqueNames[speakerSpinner.selectedItemPosition]
                    
                    // スタイルはデフォルト（最初のやつ）を使用
                    val model = loadedModels.firstOrNull { m -> m.characters.any { it.name == charName } }
                    val styleId = model?.characters?.firstOrNull { it.name == charName }?.talkStyles?.firstOrNull()?.id ?: 3

                    generateMandatoryAlarm(event, leadTime, styleId.toString(), charName)
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    private fun generateMandatoryAlarm(event: IcsEvent, leadTimeMinutes: Int, modelId: String, speakerName: String) {
        val alarmCal = Calendar.getInstance().apply {
            timeInMillis = event.startTime
            add(Calendar.MINUTE, -leadTimeMinutes)
        }
        val hour = alarmCal.get(Calendar.HOUR_OF_DAY)
        val minute = alarmCal.get(Calendar.MINUTE)
        val message = "${hour}時${minute}分を過ぎています。${event.summary}まであと${leadTimeMinutes}分です。起きてください。"
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val client = LocalVoicevoxClient(requireContext())
            val newId = UUID.randomUUID().toString()
            val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")
            if (client.createAudio(message, modelId, outputFile)) {
                val newItem = AlarmItem(newId, hour, minute, message, modelId.toIntOrNull() ?: 3, speakerName, true, false, true, emptyList())
                alarmList.add(newItem)
                saveAlarms()
                scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                alarmAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun showAddAlarmDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val client = LocalVoicevoxClient(requireContext())
            loadedModels = client.getModels()

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_alarm, null)
            currentDialogView = dialogView
            val timePreviewText = dialogView.findViewById<TextView>(R.id.dialogTimePreview)
            val speakerSpinner = dialogView.findViewById<Spinner>(R.id.dialogSpeakerSpinner)
            val styleSpinner = dialogView.findViewById<Spinner>(R.id.dialogStyleSpinner)
            
            val uniqueNames = loadedModels.flatMap { m -> m.characters.map { it.name } }
                .filter { !it.contains("女声") && !it.contains("男声") && it != "WhiteCUL" }
                .distinct()
            speakerSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, uniqueNames).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            var currentStyles = mutableListOf<Pair<String, Int>>() // modelId to styleId

            speakerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val name = uniqueNames[pos]
                    currentStyles.clear()
                    loadedModels.forEach { m -> 
                        m.characters.forEach { c -> 
                            if(c.name == name) c.talkStyles.forEach { s -> currentStyles.add(m.id to s.id) }
                        }
                    }
                    val styleNames = currentStyles.map { pair -> 
                        // スタイル名だけだと分かりにくい場合があるため、モデル名も含める（例：ずんだもん (ノーマル)）
                        val styleId = pair.second
                        var sName = "Unknown"
                        loadedModels.forEach { m -> m.characters.forEach { c -> c.styles.forEach { if(it.id == styleId) sName = it.name } } }
                        sName
                    }
                    styleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, styleNames).apply {
                        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

            val zIdx = uniqueNames.indexOf("ずんだもん").coerceAtLeast(0)
            speakerSpinner.setSelection(zIdx)

            dialogView.findViewById<Button>(R.id.dialogSelectTimeButton).setOnClickListener {
                TimePickerHelper.showWheelTimePicker(requireContext(), currentPickedHour, currentPickedMinute) { h, m ->
                    currentPickedHour = h; currentPickedMinute = m
                    timePreviewText.text = String.format(Locale.getDefault(), "設定時刻：%02d:%02d", h, m)
                }
            }

            dialogView.findViewById<Button>(R.id.btnPreviewVoice).setOnClickListener {
                val pos = styleSpinner.selectedItemPosition
                if(pos >= 0) checkLicenseAndRun(currentStyles[pos].first, currentStyles[pos].second) { m, s -> startPreviewGeneration(m, s) }
            }

            AlertDialog.Builder(requireContext()).setTitle("新規アラーム").setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val pos = styleSpinner.selectedItemPosition
                    if(pos >= 0) checkLicenseAndRun(currentStyles[pos].first, currentStyles[pos].second) { m, s -> startAlarmGeneration(m, s) }
                }.setNegativeButton("キャンセル", null).show()
        }
    }

    private fun checkLicenseAndRun(modelId: String, styleId: Int, action: (String, Int) -> Unit) {
        val client = LocalVoicevoxClient(requireContext())
        val charName = currentDialogView?.findViewById<Spinner>(R.id.dialogSpeakerSpinner)?.selectedItem.toString()

        viewLifecycleOwner.lifecycleScope.launch {
            if (client.isLicenseAccepted(modelId)) {
                action(modelId, styleId)
            } else {
                val commonTermsUrl = "https://voicevox.hiroshiba.jp/term/"
                val charTermsUrl = CuraTerms.getUrl(charName)

                val msg = StringBuilder()
                msg.append("「${charName}」の音声を使用するには、以下の利用規約に同意する必要があります。\n\n")
                msg.append("・VOICEVOX 音声モデル利用規約\n")
                if (charTermsUrl != commonTermsUrl) {
                    msg.append("・${charName} 利用規約\n")
                }
                msg.append("\n利用前に規約の内容を確認してください。")

                AlertDialog.Builder(requireContext())
                    .setTitle("利用規約への同意")
                    .setMessage(msg.toString())
                    .setNeutralButton("規約を見る") { _, _ ->
                        // 共通規約を開く
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(commonTermsUrl)))
                        // 個別規約があればそれも開く
                        if (charTermsUrl != commonTermsUrl) {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(charTermsUrl)))
                        }
                    }
                    .setPositiveButton("同意して続行") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            // 選択されたキャラに同意
                            client.acceptLicense(modelId)
                            
                            // 【追加】東北ずん子プロジェクト関連のキャラであれば、グループ全員に同意する
                            val zunkoFamily = listOf("ずんだもん", "四国めたん", "九州そら", "中国うさぎ", "東北イタコ", "東北きりたん", "東北ずん子", "あんこもん")
                            if (charName in zunkoFamily) {
                                loadedModels.forEach { model ->
                                    if (model.characters.any { it.name in zunkoFamily }) {
                                        client.acceptLicense(model.id)
                                    }
                                }
                            }

                            action(modelId, styleId)
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
    }

    private fun startPreviewGeneration(modelId: String, styleId: Int) {
        val dialogView = currentDialogView ?: return
        val message = dialogView.findViewById<EditText>(R.id.dialogMessageInput).text.toString().ifEmpty { "時間です。" }
        val previewBtn = dialogView.findViewById<Button>(R.id.btnPreviewVoice)
        
        // 生成中ポップアップを再導入（生成が終わるまで表示し続ける）
        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage("音声を生成中...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        // UI上でも無効化
        previewBtn.isEnabled = false
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val tempFile = File(requireContext().cacheDir, "preview.wav")
                if (LocalVoicevoxClient(requireContext()).createAudio("試聴です。$message", styleId.toString(), tempFile)) {
                    // 生成成功。再生開始
                    playPreview(tempFile)
                } else {
                    // 合成に失敗した場合はボタンを戻す
                    previewBtn.isEnabled = true
                }
            } catch (e: Exception) {
                previewBtn.isEnabled = true
            } finally {
                // 生成が終わったのでポップアップを閉じる
                progressDialog.dismiss()
            }
        }
    }

    private fun startAlarmGeneration(modelId: String, styleId: Int) {
        val dialogView = currentDialogView ?: return
        val message = dialogView.findViewById<EditText>(R.id.dialogMessageInput).text.toString().ifEmpty { "時間です。" }
        val charName = currentDialogView?.findViewById<Spinner>(R.id.dialogSpeakerSpinner)?.selectedItem.toString()
        val styleName = currentDialogView?.findViewById<Spinner>(R.id.dialogStyleSpinner)?.selectedItem.toString()
        
        val readTasks = dialogView.findViewById<CheckBox>(R.id.dialogReadTasksCheckBox).isChecked
        val vibrate = dialogView.findViewById<CheckBox>(R.id.dialogVibrateCheckBox).isChecked

        // 生成中ポップアップを表示
        val progressDialog = AlertDialog.Builder(requireContext())
            .setMessage("アラーム音声を生成中...")
            .setCancelable(false)
            .create()
        progressDialog.show()
        
        val newId = UUID.randomUUID().toString()
        val newItem = AlarmItem(newId, currentPickedHour, currentPickedMinute, message, styleId, "$charName ($styleName)", true, readTasks, vibrate, emptyList())

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")
                if (LocalVoicevoxClient(requireContext()).createAudio(message, styleId.toString(), outputFile)) {
                    alarmList.add(newItem)
                    saveAlarms()
                    scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                    alarmAdapter.notifyDataSetChanged()
                    updateEmptyView()
                }
            } finally {
                // 生成が終わった（成功・失敗問わず）のでポップアップを閉じる
                progressDialog.dismiss()
            }
        }
    }

    private fun playPreview(file: File) {
        val dialogView = currentDialogView ?: return
        val previewBtn = dialogView.findViewById<Button>(R.id.btnPreviewVoice)

        try {
            previewPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            previewPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener {
                    // 再生が終わったらボタンを再度有効にする
                    previewBtn.isEnabled = true
                }
                setOnErrorListener { _, _, _ ->
                    // エラー時も一応有効に戻す
                    previewBtn.isEnabled = true
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            android.util.Log.e("AlarmFragment", "Failed to play preview", e)
            previewBtn.isEnabled = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        previewPlayer?.release()
    }

    private fun updateEmptyView() {
        view?.findViewById<View>(R.id.emptyTextView)?.visibility = if (alarmList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveAlarms() {
        val jsonArray = JSONArray()
        alarmList.forEach { item ->
            jsonArray.put(JSONObject().apply {
                put("id", item.id); put("hour", item.hour); put("minute", item.minute); put("message", item.message)
                put("speakerId", item.speakerId); put("speakerName", item.speakerName); put("isEnabled", item.isEnabled)
                put("readTasks", item.readTasks); put("vibrate", item.vibrate); put("repeatDays", JSONArray(item.repeatDays))
            })
        }
        requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE).edit().putString("alarmListJSON", jsonArray.toString()).apply()
    }

    private fun loadAlarms() {
        alarmList.clear()
        val json = requireContext().getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE).getString("alarmListJSON", null)
        if (json != null) {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                val days = mutableListOf<Int>(); val dArr = obj.getJSONArray("repeatDays")
                for (j in 0 until dArr.length()) days.add(dArr.getInt(j))
                alarmList.add(AlarmItem(obj.getString("id"), obj.getInt("hour"), obj.getInt("minute"), obj.getString("message"), obj.getInt("speakerId"), obj.getString("speakerName"), obj.getBoolean("isEnabled"), obj.optBoolean("readTasks", false), obj.optBoolean("vibrate", true), days))
            }
        }
    }

    private fun scheduleVoiceAlarm(item: AlarmItem, audioPath: String) {
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(requireContext(), AlarmReceiver::class.java).apply { 
            putExtra("ALARM_ID", item.id)
            putExtra("AUDIO_FILE_PATH", audioPath) 
            putExtra("VIBRATE", item.vibrate) 
        }
        val pi = PendingIntent.getBroadcast(requireContext(), item.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, item.hour); set(Calendar.MINUTE, item.minute); set(Calendar.SECOND, 0); if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1) }
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
    }

    private fun cancelVoiceAlarm(item: AlarmItem) {
        val am = requireContext().getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(requireContext(), item.id.hashCode(), Intent(requireContext(), AlarmReceiver::class.java), PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (pi != null) am.cancel(pi)
    }
}
