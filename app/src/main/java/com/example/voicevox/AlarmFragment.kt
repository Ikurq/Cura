package com.example.voicevox

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voicevox.databinding.DialogAddAlarmBinding
import com.example.voicevox.databinding.DialogMandatoryAlarmBinding
import com.example.voicevox.databinding.FragmentAlarmBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class AlarmFragment : Fragment() {

    companion object {
        private const val DEFAULT_STYLE_ID = 3
    }

    private var _binding: FragmentAlarmBinding? = null
    private val binding get() = _binding!!

    private val alarmList = ArrayList<AlarmItem>()
    private lateinit var alarmAdapter: AlarmAdapter

    private var loadedModels: List<jp.voicevox.android.VoicevoxModelInfo> = emptyList()
    private var previewPlayer: MediaPlayer? = null

    private var currentPickedHour: Int = 7
    private var currentPickedMinute: Int = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAlarmBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadAlarms()

        alarmAdapter = AlarmAdapter(alarmList, { item, isEnabled ->
            toggleAlarm(item, isEnabled)
        }, { item ->
            showDeleteConfirmation(item)
        })

        binding.alarmRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = alarmAdapter
        }

        binding.addAlarmFAB.setOnClickListener { showAddAlarmDialog() }
        binding.addMandatoryAlarmFAB.setOnClickListener { showMandatoryAlarmDialog() }

        updateEmptyView()
    }

    private fun showMandatoryAlarmDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            loadedModels = CuraVoicevox.getModels(ctx)
            
            val dialogBinding = DialogMandatoryAlarmBinding.inflate(LayoutInflater.from(ctx))
            
            val events = ScheduleLoader.loadAllEventsForToday(ctx, Calendar.getInstance())
            val calendarTomorrow = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, 1) }
            val eventsTomorrow = ScheduleLoader.loadAllEventsForToday(ctx, calendarTomorrow)
            
            val combinedEvents = (events.map { it to false } + eventsTomorrow.map { it to true })
            val titles = combinedEvents.map { (event, isTomorrow) -> formatEventTitle(event, isTomorrow) }
            
            dialogBinding.spinMandatoryEvent.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, titles).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            val uniqueNames = getFilteredCharacterNames()
            setupSpeakerSpinner(dialogBinding.spinMandatorySpeaker, uniqueNames)

            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.dialog_mandatory_alarm_title))
                .setView(dialogBinding.root)
                .setPositiveButton(getString(R.string.dialog_generate)) { _, _ ->
                    val selectedIndex = dialogBinding.spinMandatoryEvent.selectedItemPosition
                    if (selectedIndex == -1) return@setPositiveButton
                    
                    val (event, _) = combinedEvents[selectedIndex]
                    val leadTime = dialogBinding.editLeadTime.text.toString().toIntOrNull() ?: 30
                    val charName = uniqueNames[dialogBinding.spinMandatorySpeaker.selectedItemPosition]
                    
                    val model = loadedModels.find { m -> m.characters.any { it.name == charName } }
                    val styleId = model?.characters?.find { it.name == charName }?.talkStyles?.firstOrNull()?.id ?: DEFAULT_STYLE_ID

                    checkLicenseAndRun(charName, model?.id ?: "", styleId) { m, s ->
                        generateMandatoryAlarm(event, leadTime, m, charName)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
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
        
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            val ctx = context ?: return@launch
            val template = AlarmTemplateManager.getMandatoryAlarmTemplate(ctx)
            val message = String.format(Locale.getDefault(), template, hour, minute, event.summary, leadTimeMinutes)
            val newId = UUID.randomUUID().toString()
            val outputFile = File(ctx.filesDir, "${newId}_alarm.wav")
            
            if (CuraVoicevox.createAudio(ctx, message, modelId, outputFile)) {
                val newItem = AlarmItem(newId, hour, minute, message, modelId.toIntOrNull() ?: DEFAULT_STYLE_ID, speakerName, true, false, true, emptyList(), isOneShot = true)
                alarmList.add(newItem)
                saveAlarms()
                scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                alarmAdapter.notifyDataSetChanged()
                updateEmptyView()
            }
        }
    }

    private fun showAddAlarmDialog() {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            loadedModels = CuraVoicevox.getModels(ctx)

            val dialogBinding = DialogAddAlarmBinding.inflate(LayoutInflater.from(ctx))
            val uniqueNames = getFilteredCharacterNames()
            setupSpeakerSpinner(dialogBinding.dialogSpeakerSpinner, uniqueNames)

            val currentStyles = mutableListOf<Pair<String, Int>>() // modelId to styleId

            dialogBinding.dialogSpeakerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    updateStyleSpinner(dialogBinding.dialogStyleSpinner, uniqueNames[pos], currentStyles)
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }

            val zIdx = uniqueNames.indexOf("ずんだもん").coerceAtLeast(0)
            dialogBinding.dialogSpeakerSpinner.setSelection(zIdx)

            dialogBinding.dialogSelectTimeButton.setOnClickListener {
                TimePickerHelper.showWheelTimePicker(ctx, currentPickedHour, currentPickedMinute) { h, m ->
                    currentPickedHour = h; currentPickedMinute = m
                    dialogBinding.dialogTimePreview.text = String.format(Locale.getDefault(), "設定時刻：%02d:%02d", h, m)
                }
            }

            dialogBinding.btnPreviewVoice.setOnClickListener {
                val pos = dialogBinding.dialogStyleSpinner.selectedItemPosition
                if(pos >= 0) {
                    val charName = dialogBinding.dialogSpeakerSpinner.selectedItem.toString()
                    checkLicenseAndRun(charName, currentStyles[pos].first, currentStyles[pos].second) { m, s -> 
                        startPreviewGeneration(dialogBinding, m, s) 
                    }
                }
            }

            AlertDialog.Builder(ctx).setTitle(getString(R.string.dialog_new_alarm_title)).setView(dialogBinding.root)
                .setPositiveButton(getString(R.string.dialog_save)) { _, _ ->
                    val pos = dialogBinding.dialogStyleSpinner.selectedItemPosition
                    if(pos >= 0) {
                        val charName = dialogBinding.dialogSpeakerSpinner.selectedItem.toString()
                        checkLicenseAndRun(charName, currentStyles[pos].first, currentStyles[pos].second) { m, s -> 
                            startAlarmGeneration(dialogBinding, m, s) 
                        }
                    }
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
    }

    private fun checkLicenseAndRun(charName: String, modelId: String, styleId: Int, action: (String, Int) -> Unit) {
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            if (CuraVoicevox.isLicenseAccepted(ctx, modelId)) {
                action(modelId, styleId)
            } else {
                val commonTermsUrl = CuraTerms.getCommonUrl(ctx)
                val charTermsUrl = CuraTerms.getUrl(ctx, charName)

                val msg = StringBuilder()
                msg.append("「${charName}」の音声を使用するには、以下の利用規約に同意する必要があります。\n\n")
                msg.append("・VOICEVOX 音声モデル利用規約\n")
                if (charTermsUrl != commonTermsUrl && charTermsUrl.isNotEmpty()) {
                    msg.append("・${charName} 利用規約\n")
                }
                msg.append("\n利用前に規約の内容を確認してください。")

                AlertDialog.Builder(ctx)
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
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private suspend fun acceptLicenseGroup(charName: String, modelId: String) {
        val ctx = context ?: return
        CuraVoicevox.acceptLicense(ctx, modelId)
        val familyMembers = CuraTerms.getGroupMembers(ctx, charName)
        if (familyMembers.isNotEmpty()) {
            loadedModels.forEach { model ->
                if (model.characters.any { it.name in familyMembers }) {
                    CuraVoicevox.acceptLicense(ctx, model.id)
                }
            }
        }
    }

    private fun startPreviewGeneration(dialogBinding: DialogAddAlarmBinding, modelId: String, styleId: Int) {
        val message = dialogBinding.dialogMessageInput.text.toString().ifEmpty { "時間です。" }
        val previewBtn = dialogBinding.btnPreviewVoice
        
        val progressDialog = showLoadingDialog(getString(R.string.generating_audio))
        previewBtn.isEnabled = false
        
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            try {
                val template = AlarmTemplateManager.getPreviewTemplate(ctx)
                val previewMessage = String.format(Locale.getDefault(), template, message)
                val tempFile = File(ctx.cacheDir, "preview.wav")
                if (CuraVoicevox.createAudio(ctx, previewMessage, styleId.toString(), tempFile)) {
                    playPreview(tempFile, previewBtn)
                } else {
                    previewBtn.isEnabled = true
                }
            } catch (e: Exception) {
                previewBtn.isEnabled = true
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    private fun startAlarmGeneration(dialogBinding: DialogAddAlarmBinding, modelId: String, styleId: Int) {
        val message = dialogBinding.dialogMessageInput.text.toString().ifEmpty { "時間です。" }
        val charName = dialogBinding.dialogSpeakerSpinner.selectedItem.toString()
        val styleName = dialogBinding.dialogStyleSpinner.selectedItem.toString()
        
        val readTasks = dialogBinding.dialogReadTasksCheckBox.isChecked
        val vibrate = dialogBinding.dialogVibrateCheckBox.isChecked

        val progressDialog = showLoadingDialog(getString(R.string.generating_alarm_audio))
        val newId = UUID.randomUUID().toString()
        val newItem = AlarmItem(newId, currentPickedHour, currentPickedMinute, message, styleId, "$charName ($styleName)", true, readTasks, vibrate, emptyList(), isOneShot = false)

        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            try {
                val outputFile = File(ctx.filesDir, "${newId}_alarm.wav")
                if (CuraVoicevox.createAudio(ctx, message, styleId.toString(), outputFile)) {
                    alarmList.add(newItem)
                    saveAlarms()
                    scheduleVoiceAlarm(newItem, outputFile.absolutePath)
                    alarmAdapter.notifyDataSetChanged()
                    updateEmptyView()
                }
            } finally {
                progressDialog.dismiss()
            }
        }
    }

    private fun playPreview(file: File, previewBtn: Button) {
        try {
            previewPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            previewPlayer = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { it.start() }
                setOnCompletionListener { previewBtn.isEnabled = true }
                setOnErrorListener { _, _, _ ->
                    previewBtn.isEnabled = true
                    false
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            previewBtn.isEnabled = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        previewPlayer?.release()
        previewPlayer = null
        _binding = null
    }

    private fun updateEmptyView() {
        binding.emptyTextView.visibility = if (alarmList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun saveAlarms() {
        val json = Json.encodeToString(alarmList)
        requireContext().getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
            .edit().putString(CuraConstants.KEY_ALARM_LIST, json).apply()
        
        // ウィジェットを更新
        AlarmWidgetProvider.triggerUpdate(requireContext())
    }

    private fun loadAlarms() {
        alarmList.clear()
        val jsonStr = requireContext().getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
            .getString(CuraConstants.KEY_ALARM_LIST, null)
        if (jsonStr != null) {
            try {
                val loaded: List<AlarmItem> = Json.decodeFromString(jsonStr)
                alarmList.addAll(loaded)
            } catch (e: Exception) {
                android.util.Log.e("AlarmFragment", "Failed to load alarms", e)
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
        val cal = Calendar.getInstance().apply { 
            set(Calendar.HOUR_OF_DAY, item.hour)
            set(Calendar.MINUTE, item.minute)
            set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
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
            .setPositiveButton(getString(R.string.delete)) { _, _ -> deleteAlarm(item) }
            .setNegativeButton(android.R.string.cancel, null)
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
        val ctx = context ?: return
        stylesOutputList.clear()
        val modelAndChar = loadedModels.firstNotNullOfOrNull { model ->
            model.characters.find { it.name == characterName }?.let { model.id to it }
        } ?: return
        val (modelId, character) = modelAndChar
        val styleNames = character.talkStyles.map { style ->
            stylesOutputList.add(modelId to style.id)
            character.styles.find { it.id == style.id }?.name ?: "Unknown"
        }
        styleSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, styleNames).apply {
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
