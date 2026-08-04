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

    companion object {
        private const val DEFAULT_STYLE_ID = 3
        private const val PREFS_NAME = "AlarmPrefs"
        private const val KEY_ALARM_LIST = "alarmListJSON"
    }

    private val alarmList = ArrayList<AlarmItem>()
    private lateinit var alarmAdapter: AlarmAdapter

    // 読み込まれたキャラクターモデルの情報を保持する
    private var loadedModels: List<jp.voicevox.android.VoicevoxModelInfo> = emptyList()

    private var previewPlayer: MediaPlayer? = null

    // ダイアログの状態保持用
    private var currentDialogView: View? = null
    private var currentPickedHour: Int = 7
    private var currentPickedMinute: Int = 0

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
            toggleAlarm(item, isEnabled)
        }, { item ->
            showDeleteConfirmation(item)
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
            loadedModels = CuraVoicevox.getModels(requireContext())
            
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
            val titles = combinedEvents.map { (event, isTomorrow) -> formatEventTitle(event, isTomorrow) }
            
            eventSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, titles).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            val uniqueNames = getFilteredCharacterNames()
            setupSpeakerSpinner(speakerSpinner, uniqueNames)

            AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.dialog_mandatory_alarm_title))
                .setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_generate)) { _, _ ->
                    val selectedIndex = eventSpinner.selectedItemPosition
                    if (selectedIndex == -1) return@setPositiveButton
                    
                    val (event, _) = combinedEvents[selectedIndex]
                    val leadTime = leadTimeInput.text.toString().toIntOrNull() ?: 30
                    val charName = uniqueNames[speakerSpinner.selectedItemPosition]
                    
                    // スタイルはデフォルトを使用
                    val model = loadedModels.firstOrNull { m -> m.characters.any { it.name == charName } }
                    val styleId = model?.characters?.firstOrNull { it.name == charName }?.talkStyles?.firstOrNull()?.id ?: DEFAULT_STYLE_ID

                    checkLicenseAndRun(charName, model?.id ?: "", styleId) { m, s ->
                        generateMandatoryAlarm(event, leadTime, m, charName)
                    }
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
        
        val template = AlarmTemplateManager.getMandatoryAlarmTemplate(requireContext())
        val message = String.format(Locale.getDefault(), template, hour, minute, event.summary, leadTimeMinutes)
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val newId = UUID.randomUUID().toString()
            val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")
            if (CuraVoicevox.createAudio(requireContext(), message, modelId, outputFile)) {
                val newItem = AlarmItem(newId, hour, minute, message, modelId.toIntOrNull() ?: DEFAULT_STYLE_ID, speakerName, true, false, true, emptyList())
                alarmList.add(newItem)
                saveAlarms()
                scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                alarmAdapter.notifyDataSetChanged()
            }
        }
    }

    private fun showAddAlarmDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            loadedModels = CuraVoicevox.getModels(requireContext())

            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_alarm, null)
            currentDialogView = dialogView
            val timePreviewText = dialogView.findViewById<TextView>(R.id.dialogTimePreview)
            val speakerSpinner = dialogView.findViewById<Spinner>(R.id.dialogSpeakerSpinner)
            val styleSpinner = dialogView.findViewById<Spinner>(R.id.dialogStyleSpinner)
            
            val uniqueNames = getFilteredCharacterNames()
            setupSpeakerSpinner(speakerSpinner, uniqueNames)

            val currentStyles = mutableListOf<Pair<String, Int>>() // modelId to styleId

            speakerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    updateStyleSpinner(styleSpinner, uniqueNames[pos], currentStyles)
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
                if(pos >= 0) {
                    val charName = speakerSpinner.selectedItem.toString()
                    checkLicenseAndRun(charName, currentStyles[pos].first, currentStyles[pos].second) { m, s -> startPreviewGeneration(m, s) }
                }
            }

            AlertDialog.Builder(requireContext()).setTitle(getString(R.string.dialog_new_alarm_title)).setView(dialogView)
                .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                    val pos = styleSpinner.selectedItemPosition
                    if(pos >= 0) {
                        val charName = speakerSpinner.selectedItem.toString()
                        checkLicenseAndRun(charName, currentStyles[pos].first, currentStyles[pos].second) { m, s -> startAlarmGeneration(m, s) }
                    }
                }.setNegativeButton("キャンセル", null).show()
        }
    }

    private fun checkLicenseAndRun(charName: String, modelId: String, styleId: Int, action: (String, Int) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            if (CuraVoicevox.isLicenseAccepted(requireContext(), modelId)) {
                action(modelId, styleId)
            } else {
                val commonTermsUrl = CuraTerms.getCommonUrl(requireContext())
                val charTermsUrl = CuraTerms.getUrl(requireContext(), charName)

                val msg = StringBuilder()
                msg.append("「${charName}」の音声を使用するには、以下の利用規約に同意する必要があります。\n\n")
                msg.append("・VOICEVOX 音声モデル利用規約\n")
                if (charTermsUrl != commonTermsUrl && charTermsUrl.isNotEmpty()) {
                    msg.append("・${charName} 利用規約\n")
                }
                msg.append("\n利用前に規約の内容を確認してください。")

                AlertDialog.Builder(requireContext())
                    .setTitle("利用規約への同意")
                    .setMessage(msg.toString())
                    .setNeutralButton("規約を見る") { _, _ ->
                        startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(commonTermsUrl)))
                        if (charTermsUrl != commonTermsUrl && charTermsUrl.isNotEmpty()) {
                            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(charTermsUrl)))
                        }
                    }
                    .setPositiveButton("同意して続行") { _, _ ->
                        viewLifecycleOwner.lifecycleScope.launch {
                            acceptLicenseGroup(charName, modelId)
                            action(modelId, styleId)
                        }
                    }
                    .setNegativeButton("キャンセル", null)
                    .show()
            }
        }
    }

    private suspend fun acceptLicenseGroup(charName: String, modelId: String) {
        // 選択されたキャラに同意
        CuraVoicevox.acceptLicense(requireContext(), modelId)
        
        // グループ全員に同意する（JSONから取得）
        val familyMembers = CuraTerms.getGroupMembers(requireContext(), charName)
        if (familyMembers.isNotEmpty()) {
            loadedModels.forEach { model ->
                if (model.characters.any { it.name in familyMembers }) {
                    CuraVoicevox.acceptLicense(requireContext(), model.id)
                }
            }
        }
    }

    private fun startPreviewGeneration(modelId: String, styleId: Int) {
        val dialogView = currentDialogView ?: return
        val message = dialogView.findViewById<EditText>(R.id.dialogMessageInput).text.toString().ifEmpty { "時間です。" }
        val previewBtn = dialogView.findViewById<Button>(R.id.btnPreviewVoice)
        
        // 生成中ポップアップを表示
        val progressDialog = showLoadingDialog(getString(R.string.generating_audio))
        
        // UI上でも無効化
        previewBtn.isEnabled = false
        
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val template = AlarmTemplateManager.getPreviewTemplate(requireContext())
                val previewMessage = String.format(Locale.getDefault(), template, message)
                val tempFile = File(requireContext().cacheDir, "preview.wav")
                if (CuraVoicevox.createAudio(requireContext(), previewMessage, styleId.toString(), tempFile)) {
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
        val progressDialog = showLoadingDialog(getString(R.string.generating_alarm_audio))
        
        val newId = UUID.randomUUID().toString()
        val newItem = AlarmItem(newId, currentPickedHour, currentPickedMinute, message, styleId, "$charName ($styleName)", true, readTasks, vibrate, emptyList())

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val outputFile = File(requireContext().filesDir, "${newId}_alarm.wav")
                if (CuraVoicevox.createAudio(requireContext(), message, styleId.toString(), outputFile)) {
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

    override fun onDestroyView() {
        super.onDestroyView()
        previewPlayer?.release()
        previewPlayer = null
        currentDialogView = null
    }

    override fun onDestroy() {
        super.onDestroy()
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
        requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_ALARM_LIST, jsonArray.toString()).apply()
    }

    private fun loadAlarms() {
        alarmList.clear()
        val json = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_ALARM_LIST, null)
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

    private fun toggleAlarm(item: AlarmItem, isEnabled: Boolean) {
        item.isEnabled = isEnabled
        if (item.isEnabled) {
            val audioFile = File(requireContext().filesDir, "${item.id}_alarm.wav")
            scheduleVoiceAlarm(item, audioFile.absolutePath)
            Toast.makeText(requireContext(), getString(R.string.toast_alarm_on), Toast.LENGTH_SHORT).show()
        } else {
            cancelVoiceAlarm(item)
            Toast.makeText(requireContext(), getString(R.string.toast_alarm_off), Toast.LENGTH_SHORT).show()
        }
        saveAlarms()
    }

    private fun showDeleteConfirmation(item: AlarmItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.dialog_delete_alarm_title))
            .setMessage(getString(R.string.dialog_delete_alarm_msg))
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                deleteAlarm(item)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteAlarm(item: AlarmItem) {
        cancelVoiceAlarm(item)
        val audioFile = File(requireContext().filesDir, "${item.id}_alarm.wav")
        if (audioFile.exists()) audioFile.delete()
        alarmList.remove(item)
        alarmAdapter.notifyDataSetChanged()
        saveAlarms()
        updateEmptyView()
    }

    private fun formatEventTitle(event: IcsEvent, isTomorrow: Boolean): String {
        val prefix = if (isTomorrow) "[明日] " else "[今日] "
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(event.startTime))
        return "$prefix$time ${event.summary}"
    }

    private fun getFilteredCharacterNames(): List<String> {
        return loadedModels.flatMap { m -> m.characters.map { it.name } }
            .filter { !it.contains("女声") && !it.contains("男声") && it != "WhiteCUL" }
            .distinct()
    }

    private fun setupSpeakerSpinner(spinner: Spinner, names: List<String>) {
        spinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, names).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun updateStyleSpinner(styleSpinner: Spinner, characterName: String, stylesOutputList: MutableList<Pair<String, Int>>) {
        stylesOutputList.clear()
        val styleNames = mutableListOf<String>()

        loadedModels.forEach { model ->
            model.characters.forEach { character ->
                if (character.name == characterName) {
                    character.talkStyles.forEach { style ->
                        stylesOutputList.add(model.id to style.id)
                        val sName = character.styles.find { it.id == style.id }?.name ?: "Unknown"
                        styleNames.add(sName)
                    }
                }
            }
        }

        styleSpinner.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, styleNames).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
    }

    private fun showLoadingDialog(message: String): AlertDialog {
        return AlertDialog.Builder(requireContext())
            .setMessage(message)
            .setCancelable(false)
            .show()
    }
}
