package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

class SettingsFragment : Fragment() {

    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { performExport(it) }
    }

    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { performImport(it) }
    }

    override fun onResume() {
        super.onResume()
        updatePermissionUI()
    }

    private fun updatePermissionUI() {
        val view = view ?: return
        val btnOverlay = view.findViewById<Button>(R.id.btnRequestOverlayPermission) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (Settings.canDrawOverlays(requireContext())) {
                btnOverlay.text = "許可済み"
                btnOverlay.isEnabled = false
            } else {
                btnOverlay.text = "設定"
                btnOverlay.isEnabled = true
            }
        } else {
            btnOverlay.visibility = View.GONE
        }

        // --- Battery Optimization ---
        val btnBattery = view.findViewById<Button>(R.id.btnRequestBatteryPermission)
        if (btnBattery != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = requireContext().getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
                if (pm.isIgnoringBatteryOptimizations(requireContext().packageName)) {
                    btnBattery.text = "許可済み"
                    btnBattery.isEnabled = false
                } else {
                    btnBattery.text = "設定"
                    btnBattery.isEnabled = true
                }
            } else {
                btnBattery.visibility = View.GONE
            }
        }

        // --- Full Screen Intent UI ---
        val btnFullScreen = view.findViewById<Button>(R.id.btnRequestFullScreenPermission) ?: return
        val layoutFullScreen = view.findViewById<View>(R.id.layoutFullScreenPermission) ?: return

        if (Build.VERSION.SDK_INT >= 34) {
            val nm =
                requireContext().getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (nm.canUseFullScreenIntent()) {
                btnFullScreen.text = "許可済み"
                btnFullScreen.isEnabled = false
            } else {
                btnFullScreen.text = "設定"
                btnFullScreen.isEnabled = true
            }
        } else {
            layoutFullScreen.visibility = View.GONE
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings, container, false)

        val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val schedulePrefs =
            requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val timetablePrefs =
            requireContext().getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)

        // --- Overlay Permission ---
        val btnOverlay = view.findViewById<Button>(R.id.btnRequestOverlayPermission)
        btnOverlay.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${requireContext().packageName}")
                    )
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                    startActivity(intent)
                }
            }
        }

        // --- Full Screen Intent Permission ---
        val btnFullScreen = view.findViewById<Button>(R.id.btnRequestFullScreenPermission)
        btnFullScreen.setOnClickListener {
            if (Build.VERSION.SDK_INT >= 34) {
                val intent = Intent(
                    "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT",
                    Uri.parse("package:${requireContext().packageName}")
                )
                startActivity(intent)
            }
        }

        // --- Battery Optimization Permission ---
        val btnBattery = view.findViewById<Button>(R.id.btnRequestBatteryPermission)
        btnBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${requireContext().packageName}")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            }
        }
        updatePermissionUI()

        // --- Notification Switches ---
        val switchMandatory = view.findViewById<MaterialSwitch>(R.id.switchMandatoryReminder)
        val switchTask = view.findViewById<MaterialSwitch>(R.id.switchTaskNotification)
        val switchEvent = view.findViewById<MaterialSwitch>(R.id.switchEventNotification)

        if (switchMandatory != null) {
            switchMandatory.isChecked = appPrefs.getBoolean("mandatory_reminder", true)
            switchMandatory.setOnCheckedChangeListener { _, isChecked ->
                appPrefs.edit().putBoolean("mandatory_reminder", isChecked).apply()
            }
        }
        if (switchTask != null) {
            switchTask.isChecked = appPrefs.getBoolean("task_notification", true)
            switchTask.setOnCheckedChangeListener { _, isChecked ->
                appPrefs.edit().putBoolean("task_notification", isChecked).apply()
            }
        }
        if (switchEvent != null) {
            switchEvent.isChecked = appPrefs.getBoolean("event_notification", true)
            switchEvent.setOnCheckedChangeListener { _, isChecked ->
                appPrefs.edit().putBoolean("event_notification", isChecked).apply()
            }
        }

        // --- Alarm Advanced Settings ---
        val switchSkipHolidays = view.findViewById<MaterialSwitch>(R.id.switchSkipHolidays)
        val switchVacationMode = view.findViewById<MaterialSwitch>(R.id.switchVacationMode)

        if (switchSkipHolidays != null) {
            switchSkipHolidays.isChecked = appPrefs.getBoolean("skip_holidays", false)
            switchSkipHolidays.setOnCheckedChangeListener { _, isChecked ->
                appPrefs.edit().putBoolean("skip_holidays", isChecked).apply()
            }
        }
        if (switchVacationMode != null) {
            switchVacationMode.isChecked = appPrefs.getBoolean("vacation_mode", false)
            switchVacationMode.setOnCheckedChangeListener { _, isChecked ->
                appPrefs.edit().putBoolean("vacation_mode", isChecked).apply()
            }
        }

        // --- Calendar Management ---
        val calendarContainer = view.findViewById<LinearLayout>(R.id.settingsCalendarListContainer)
        val addCalendarBtn = view.findViewById<Button>(R.id.btnSettingsAddCalendar)

        fun refreshCalendarList() {
            if (calendarContainer == null) return
            calendarContainer.removeAllViews()
            val sources = loadCalendarSources(timetablePrefs)
            sources.forEach { source ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_manage_calendar, calendarContainer, false)
                itemView.findViewById<TextView>(R.id.calendarNameText).text = source.name
                itemView.findViewById<TextView>(R.id.calendarUrlText).text = source.url
                itemView.findViewById<View>(R.id.btnDeleteCalendar).setOnClickListener {
                    val updated = sources.filter { it != source }
                    saveCalendarSources(timetablePrefs, updated)
                    refreshCalendarList()
                }
                calendarContainer.addView(itemView)
            }
        }

        addCalendarBtn?.setOnClickListener {
            showAddCalendarDialog {
                refreshCalendarList()
            }
        }

        // --- Preset Management ---
        val presetContainer = view.findViewById<LinearLayout>(R.id.settingsPresetListContainer)

        fun refreshPresetList() {
            if (presetContainer == null) return
            presetContainer.removeAllViews()
            val presets = loadPresets(schedulePrefs)
            presets.forEach { preset ->
                val itemView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.item_manage_preset, presetContainer, false)
                itemView.findViewById<TextView>(R.id.presetGenreText).text = preset.genre

                val timeStr = if (preset.hour != -1) String.format(
                    "%02d:%02d",
                    preset.hour,
                    preset.minute
                ) else "時刻なし"
                itemView.findViewById<TextView>(R.id.presetDetailText).text =
                    "$timeStr @ ${preset.location}"

                itemView.findViewById<View>(R.id.btnDeletePreset).setOnClickListener {
                    val updated = presets.filter { it != preset }
                    savePresets(schedulePrefs, updated)
                    refreshPresetList()
                }
                presetContainer.addView(itemView)
            }
        }

        refreshCalendarList()
        refreshPresetList()
        updateStorageUI(view)
        updateApiKeyUI(view, appPrefs)

        // --- Backup & Restore ---
        view.findViewById<Button>(R.id.btnExportBackup).setOnClickListener {
            val fileName = "VoiceVox2_Backup_${System.currentTimeMillis()}.json"
            exportLauncher.launch(fileName)
        }

        view.findViewById<Button>(R.id.btnImportBackup).setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("バックアップの復元")
                .setMessage("現在の全ての設定と記録が上書きされます。よろしいですか？")
                .setPositiveButton("復元") { _, _ ->
                    importLauncher.launch(arrayOf("application/json"))
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        return view
    }

    private fun updateStorageUI(view: View?) {
        val root = view ?: this.view ?: return
        val textCacheSize = root.findViewById<TextView>(R.id.textCacheSize) ?: return
        val btnClear = root.findViewById<Button>(R.id.btnClearVoiceCache) ?: return

        val cacheDir = File(requireContext().filesDir, "voice_cache")
        val sizeBytes = getDirectorySize(cacheDir)
        val sizeMB = sizeBytes.toDouble() / (1024 * 1024)
        
        textCacheSize.text = String.format("使用量: %.1f MB", sizeMB)

        btnClear.setOnClickListener {
            AlertDialog.Builder(requireContext())
                .setTitle("キャッシュの削除")
                .setMessage("保存されている音声キャッシュをすべて削除しますか？\n(次回再生時に再生成が必要になり、APIを消費します)")
                .setPositiveButton("削除") { _, _ ->
                    deleteDirectory(cacheDir)
                    updateStorageUI(root)
                    Toast.makeText(requireContext(), "キャッシュを削除しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    private fun updateApiKeyUI(root: View, prefs: android.content.SharedPreferences) {
        val txtCurrent = root.findViewById<TextView>(R.id.txtCurrentApiKey) ?: return
        val btnOpen = root.findViewById<Button>(R.id.btnOpenApiKeyPage) ?: return
        val editKey = root.findViewById<EditText>(R.id.editApiKey) ?: return
        val btnApply = root.findViewById<Button>(R.id.btnApplyApiKey) ?: return

        val currentKey = prefs.getString("custom_api_key", null)
        if (currentKey.isNullOrEmpty()) {
            txtCurrent.text = "現在のキー: (デフォルト使用中)"
        } else {
            val masked = if (currentKey.length > 4) {
                currentKey.take(2) + "****" + currentKey.takeLast(2)
            } else "****"
            txtCurrent.text = "現在のキー: $masked"
        }

        btnOpen.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://voicevox.su-shiki.com/su-shikiapis/"))
            startActivity(intent)
        }

        btnApply.setOnClickListener {
            val newKey = editKey.text.toString().trim()
            if (newKey.isNotEmpty()) {
                prefs.edit().putString("custom_api_key", newKey).apply()
                editKey.text.clear()
                updateApiKeyUI(root, prefs)
                Toast.makeText(requireContext(), "APIキーを適用しました", Toast.LENGTH_SHORT).show()
            } else {
                prefs.edit().remove("custom_api_key").apply()
                updateApiKeyUI(root, prefs)
                Toast.makeText(requireContext(), "デフォルトのキーに戻しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getDirectorySize(directory: File): Long {
        var size: Long = 0
        directory.listFiles()?.forEach { file ->
            size += if (file.isDirectory) getDirectorySize(file) else file.length()
        }
        return size
    }

    private fun deleteDirectory(directory: File) {
        if (!directory.exists()) return
        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) deleteDirectory(file) else file.delete()
        }
    }

    private fun loadCalendarSources(prefs: android.content.SharedPreferences): List<TimetableFragment.CalendarSource> {
        val jsonString = prefs.getString("calendarSourcesJSON", null) ?: return emptyList()
        val list = mutableListOf<TimetableFragment.CalendarSource>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    TimetableFragment.CalendarSource(
                        obj.getString("name"),
                        obj.getString("url")
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun saveCalendarSources(
        prefs: android.content.SharedPreferences,
        sources: List<TimetableFragment.CalendarSource>
    ) {
        val jsonArray = JSONArray()
        for (source in sources) {
            jsonArray.put(JSONObject().apply {
                put("name", source.name)
                put("url", source.url)
            })
        }
        prefs.edit().putString("calendarSourcesJSON", jsonArray.toString()).apply()
    }

    private fun showAddCalendarDialog(onAdded: () -> Unit) {
        val dialogView =
            LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_calendar_url, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.calendarListContainer)
        val nameInput = dialogView.findViewById<EditText>(R.id.editCalendarName)
        val urlInput = dialogView.findViewById<EditText>(R.id.editCalendarUrl)
        val addButton = dialogView.findViewById<Button>(R.id.btnAddCalendar)

        // Hide the current list in the dialog since it's now in main settings
        container.visibility = View.GONE

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("完了", null) // Handle logic on Add button
            .setNegativeButton("キャンセル", null)
            .show().also { dialog ->
                addButton.setOnClickListener {
                    val name = nameInput.text.toString()
                    val url = urlInput.text.toString()
                    if (name.isNotEmpty() && url.isNotEmpty() && url.endsWith(".ics")) {
                        val prefs = requireContext().getSharedPreferences(
                            "TimetablePrefs",
                            Context.MODE_PRIVATE
                        )
                        val sources = loadCalendarSources(prefs).toMutableList()
                        sources.add(TimetableFragment.CalendarSource(name, url))
                        saveCalendarSources(prefs, sources)
                        onAdded()
                        dialog.dismiss()
                    } else {
                        Toast.makeText(
                            requireContext(),
                            "有効な名前とICS URLを入力してください",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
    }

    private fun loadPresets(prefs: android.content.SharedPreferences): List<EventPreset> {
        val jsonString = prefs.getString("presetListJSON", null) ?: return emptyList()
        val list = mutableListOf<EventPreset>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    EventPreset(
                        obj.getString("genre"),
                        obj.getString("location"),
                        obj.optInt("hour", -1),
                        obj.optInt("minute", -1)
                    )
                )
            }
        } catch (e: Exception) {
        }
        return list
    }

    private fun savePresets(prefs: android.content.SharedPreferences, presets: List<EventPreset>) {
        val jsonArray = JSONArray()
        for (p in presets) {
            jsonArray.put(JSONObject().apply {
                put("genre", p.genre)
                put("location", p.location)
                put("hour", p.hour)
                put("minute", p.minute)
            })
        }
        prefs.edit().putString("presetListJSON", jsonArray.toString()).apply()
    }

    private fun performExport(uri: Uri) {
        try {
            val root = JSONObject()
            val context = requireContext()

            val prefsMap = mapOf(
                "AlarmPrefs" to context.getSharedPreferences("AlarmPrefs", Context.MODE_PRIVATE),
                "TodoPrefs" to context.getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE),
                "SchedulePrefs" to context.getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE),
                "TimetablePrefs" to context.getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE),
                "AttendancePrefs" to context.getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE),
                "AppPrefs" to context.getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
            )

            val dataObj = JSONObject()
            prefsMap.forEach { (name, prefs) ->
                val pObj = JSONObject()
                prefs.all.forEach { (key, value) ->
                    pObj.put(key, value)
                }
                dataObj.put(name, pObj)
            }

            root.put("version", 1)
            root.put("timestamp", System.currentTimeMillis())
            root.put("data", dataObj)

            context.contentResolver.openOutputStream(uri)?.use { os ->
                OutputStreamWriter(os).use { writer ->
                    writer.write(root.toString(2))
                }
            }
            Toast.makeText(context, "バックアップを保存しました", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun performImport(uri: Uri) {
        try {
            val context = requireContext()
            val content = context.contentResolver.openInputStream(uri)?.use { isStream ->
                InputStreamReader(isStream).readText()
            } ?: return

            val root = JSONObject(content)
            val dataObj = root.getJSONObject("data")

            dataObj.keys().forEach { prefName ->
                val pObj = dataObj.getJSONObject(prefName)
                val prefs = context.getSharedPreferences(prefName, Context.MODE_PRIVATE)
                val editor = prefs.edit()
                editor.clear()
                
                pObj.keys().forEach { key ->
                    when (val value = pObj.get(key)) {
                        is Boolean -> editor.putBoolean(key, value)
                        is Int -> editor.putInt(key, value)
                        is Long -> editor.putLong(key, value)
                        is Float -> editor.putFloat(key, value)
                        is String -> editor.putString(key, value)
                        else -> editor.putString(key, value.toString())
                    }
                }
                editor.apply()
            }

            Toast.makeText(context, "復元が完了しました。反映のためにアプリを再起動してください。", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(requireContext(), "復元に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
