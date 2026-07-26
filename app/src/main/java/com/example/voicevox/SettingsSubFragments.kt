package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

// --- 1. Permissions & Notifications ---
class SettingsPermissionsFragment : Fragment() {
    
    private val requestCalendarLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            Toast.makeText(requireContext(), "カレンダー権限が許可されました", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(requireContext(), "カレンダー権限が拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_permissions, container, false)
        setupPermissions(view)
        setupNotifications(view)
        return view
    }

    private fun setupPermissions(view: View) {
        view.findViewById<Button>(R.id.btnRequestOverlayPermission).setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${requireContext().packageName}".toUri())
            startActivity(intent)
        }
        view.findViewById<Button>(R.id.btnRequestBatteryPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        view.findViewById<Button>(R.id.btnRequestFullScreenPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${requireContext().packageName}".toUri()))
        }
        view.findViewById<Button>(R.id.btnRequestCalendarPermission).setOnClickListener {
            requestCalendarLauncher.launch(android.Manifest.permission.READ_CALENDAR)
        }
    }

    private fun setupNotifications(view: View) {
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val s1 = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchMandatoryReminder)
        val s2 = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchTaskNotification)
        val s3 = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchEventNotification)

        s1.isChecked = prefs.getBoolean("mandatory_reminder", true)
        s1.setOnCheckedChangeListener { _, c -> prefs.edit { putBoolean("mandatory_reminder", c) } }
        s2.isChecked = prefs.getBoolean("task_notification", true)
        s2.setOnCheckedChangeListener { _, c -> prefs.edit { putBoolean("task_notification", c) } }
        s3.isChecked = prefs.getBoolean("event_notification", true)
        s3.setOnCheckedChangeListener { _, c -> prefs.edit { putBoolean("event_notification", c) } }
    }
}

// --- 2. External Calendar ---
class SettingsCalendarFragment : Fragment() {
    
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        val switch = view?.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSyncDeviceCalendar)
        if (isGranted) {
            saveSyncPreference(true)
            switch?.isChecked = true
        } else {
            saveSyncPreference(false)
            switch?.isChecked = false
            Toast.makeText(requireContext(), "カレンダーの権限が拒否されました", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_calendar, container, false)
        setupDeviceSync(view)
        setupCalendar(view)
        return view
    }

    private fun setupDeviceSync(view: View) {
        val syncSwitch = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchSyncDeviceCalendar)
        val menuSelect = view.findViewById<View>(R.id.menuSelectCalendars)
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        val isSyncEnabled = prefs.getBoolean("sync_device_calendar", false)
        syncSwitch.isChecked = isSyncEnabled
        menuSelect.visibility = if (isSyncEnabled) View.VISIBLE else View.GONE

        syncSwitch.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.READ_CALENDAR) 
                    == PackageManager.PERMISSION_GRANTED) {
                    saveSyncPreference(true)
                    menuSelect.visibility = View.VISIBLE
                } else {
                    syncSwitch.isChecked = false
                    requestPermissionLauncher.launch(android.Manifest.permission.READ_CALENDAR)
                }
            } else {
                saveSyncPreference(false)
                menuSelect.visibility = View.GONE
            }
        }

        menuSelect.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragmentContainer, SettingsCalendarSelectionFragment())
                .addToBackStack(null)
                .commit()
            (activity as? MainActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.title = "カレンダーの選択"
        }
    }

    private fun saveSyncPreference(enabled: Boolean) {
        requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit {
            putBoolean("sync_device_calendar", enabled)
        }
    }

    private fun setupCalendar(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.settingsCalendarListContainer)
        val btnAdd = view.findViewById<Button>(R.id.btnSettingsAddCalendar)
        val prefs = requireContext().getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)

        fun refresh() {
            container.removeAllViews()
            val list = JSONArray(prefs.getString("calendarSourcesJSON", "[]"))
            for (i in 0 until list.length()) {
                val obj = list.getJSONObject(i)
                val name = obj.getString("name")
                val url = obj.getString("url")
                
                val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_settings_calendar_card, container, false)
                itemView.findViewById<TextView>(R.id.txtCalendarName).text = name
                itemView.findViewById<TextView>(R.id.txtCalendarUrl).text = url
                
                itemView.findViewById<View>(R.id.btnDeleteCalendar).setOnClickListener {
                    AlertDialog.Builder(requireContext()).setTitle("カレンダーの削除")
                        .setMessage("$name を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            val newList = JSONArray()
                            for (j in 0 until list.length()) if (i != j) newList.put(list.getJSONObject(j))
                            prefs.edit { putString("calendarSourcesJSON", newList.toString()) }
                            refresh()
                        }.setNegativeButton("キャンセル", null).show()
                }
                container.addView(itemView)
            }
        }

        btnAdd.setOnClickListener {
            val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_edit_calendar_url, null)
            val nameIn = dialogView.findViewById<EditText>(R.id.editCalendarName)
            val urlIn = dialogView.findViewById<EditText>(R.id.editCalendarUrl)
            val btnSubmit = dialogView.findViewById<Button>(R.id.btnAddCalendar)
            dialogView.findViewById<View>(R.id.calendarListContainer)?.visibility = View.GONE
            val dialog = AlertDialog.Builder(requireContext()).setView(dialogView).create()
            btnSubmit.setOnClickListener {
                val n = nameIn.text.toString(); val u = urlIn.text.toString()
                if (n.isNotEmpty() && u.isNotEmpty()) {
                    val cur = JSONArray(prefs.getString("calendarSourcesJSON", "[]")).put(JSONObject().apply { put("name", n); put("url", u) })
                    prefs.edit { putString("calendarSourcesJSON", cur.toString()) }
                    refresh(); dialog.dismiss()
                }
            }
            dialog.show()
        }
        refresh()
    }
}

// --- 3. Voice & Storage ---
class SettingsVoiceFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_voice, container, false)
        setupApi(view)
        setupStorage(view)
        return view
    }

    private fun setupApi(view: View) {
        // 音声合成エンジンの状態を表示するだけ
    }

    private fun setupStorage(view: View) {
        val txtSize = view.findViewById<TextView>(R.id.textCacheSize)
        val btnClear = view.findViewById<Button>(R.id.btnClearVoiceCache)
        fun update() {
            var size = 0L
            File(requireContext().filesDir, "voice_cache").let { if(it.exists()) it.listFiles()?.forEach { f -> size += f.length() } }
            requireContext().filesDir.listFiles()?.filter { it.name.endsWith(".wav") }?.forEach { size += it.length() }
            txtSize.text = String.format(Locale.getDefault(), "使用量: %.2f MB", size / (1024.0 * 1024.0))
        }
        btnClear.setOnClickListener {
            AlertDialog.Builder(requireContext()).setTitle("削除").setMessage("全音声を削除？")
                .setPositiveButton("はい") { _, _ ->
                    File(requireContext().filesDir, "voice_cache").deleteRecursively()
                    requireContext().filesDir.listFiles()?.filter { it.name.endsWith(".wav") }?.forEach { it.delete() }
                    update(); Toast.makeText(context, "完了", Toast.LENGTH_SHORT).show()
                }.setNegativeButton("いいえ", null).show()
        }
        update()
    }
}

// --- 4. Dev & Character ---
class SettingsDevFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_dev, container, false)
        setupLevel(view)
        view.findViewById<Button>(R.id.btnExportBackup).setOnClickListener { Toast.makeText(context, "準備中", Toast.LENGTH_SHORT).show() }
        view.findViewById<Button>(R.id.btnImportBackup).setOnClickListener { Toast.makeText(context, "準備中", Toast.LENGTH_SHORT).show() }
        return view
    }

    private fun setupLevel(view: View) {
        val prefs = requireContext().getSharedPreferences("CharacterPrefs", Context.MODE_PRIVATE)
        val txtLv = view.findViewById<TextView>(R.id.txtDevLevelDisplay)
        val seek = view.findViewById<SeekBar>(R.id.seekDevLevel)
        val btn = view.findViewById<Button>(R.id.btnDevSetLevel)
        val curLv = (prefs.getLong("totalExp", 0L) / 100L).toInt() + 1
        seek.progress = curLv; txtLv.text = "現在のLv: $curLv"
        btn.setOnClickListener {
            prefs.edit { putLong("totalExp", (seek.progress - 1) * 100L) }
            txtLv.text = "現在のLv: ${seek.progress}"
            Toast.makeText(context, "設定完了", Toast.LENGTH_SHORT).show()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) { txtLv.text = "現在のLv: $p" }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }
}

// --- 5. Schedule Presets ---
class SettingsPresetsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_presets, container, false)
        setupPresets(view)
        return view
    }

    private fun setupPresets(view: View) {
        val container = view.findViewById<LinearLayout>(R.id.settingsPresetListContainer)
        val prefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        fun refresh() {
            container.removeAllViews()
            val list = JSONArray(prefs.getString("presetListJSON", "[]"))
            for (i in 0 until list.length()) {
                val obj = list.getJSONObject(i)
                val genre = obj.getString("genre")
                val location = obj.optString("location", "")
                val h = obj.optInt("hour", -1)
                val m = obj.optInt("minute", -1)
                
                val infoText = StringBuilder(location)
                if (h != -1) {
                    if (infoText.isNotEmpty()) infoText.append(" / ")
                    infoText.append(String.format(Locale.getDefault(), "%02d:%02d", h, m))
                }
                if (infoText.isEmpty()) infoText.append("詳細なし")

                val itemView = LayoutInflater.from(requireContext()).inflate(R.layout.item_settings_preset_card, container, false)
                itemView.findViewById<TextView>(R.id.txtPresetGenre).text = genre
                itemView.findViewById<TextView>(R.id.txtPresetInfo).text = infoText.toString()
                
                // 編集機能を追加 (カードタップ)
                itemView.setOnClickListener {
                    showEditPresetDialog(obj, i) { refresh() }
                }
                
                itemView.findViewById<View>(R.id.btnDeletePreset).setOnClickListener {
                    AlertDialog.Builder(requireContext()).setTitle("プリセットの削除")
                        .setMessage("$genre を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            val newList = JSONArray()
                            for (j in 0 until list.length()) if (i != j) newList.put(list.getJSONObject(j))
                            prefs.edit { putString("presetListJSON", newList.toString()) }
                            refresh()
                        }.setNegativeButton("キャンセル", null).show()
                }
                container.addView(itemView)
            }
        }
        refresh()
    }

    private fun showEditPresetDialog(originalObj: JSONObject, index: Int, onComplete: () -> Unit) {
        val prefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_schedule_event, null)
        val genreInput = dialogView.findViewById<EditText>(R.id.editEventGenre)
        val locationInput = dialogView.findViewById<EditText>(R.id.editEventLocation)
        val btnSelectTime = dialogView.findViewById<Button>(R.id.btnSelectEventTime)
        
        // プリセット編集に不要な要素を隠す
        dialogView.findViewById<View>(R.id.presetChipGroup)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.btnToggleAdvancedSettings)?.visibility = View.GONE
        dialogView.findViewById<View>(R.id.layoutAdvancedSettings)?.visibility = View.GONE

        // タイトルを「編集」に変更
        dialogView.findViewById<TextView>(android.R.id.text1)?.text = "プリセットの編集"

        // 初期値をセット
        genreInput.setText(originalObj.getString("genre"))
        locationInput.setText(originalObj.optString("location", ""))
        var selectedHour = originalObj.optInt("hour", 9)
        var selectedMinute = originalObj.optInt("minute", 0)
        btnSelectTime.text = String.format(Locale.getDefault(), "時刻：%02d:%02d", selectedHour, selectedMinute)

        btnSelectTime.setOnClickListener {
            TimePickerHelper.showWheelTimePicker(requireContext(), selectedHour, selectedMinute) { h, m ->
                selectedHour = h; selectedMinute = m
                btnSelectTime.text = String.format(Locale.getDefault(), "時刻：%02d:%02d", h, m)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("プリセットの編集")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val genre = genreInput.text.toString()
                if (genre.isEmpty()) return@setPositiveButton

                val list = JSONArray(prefs.getString("presetListJSON", "[]"))
                val newObj = JSONObject().apply {
                    put("genre", genre)
                    put("location", locationInput.text.toString())
                    put("hour", selectedHour)
                    put("minute", selectedMinute)
                }
                
                // 指定インデックスを差し替え
                val newList = JSONArray()
                for (i in 0 until list.length()) {
                    if (i == index) newList.put(newObj) else newList.put(list.getJSONObject(i))
                }
                
                prefs.edit { putString("presetListJSON", newList.toString()) }
                onComplete()
                Toast.makeText(requireContext(), "プリセットを更新しました", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

// --- 6. Device Calendar Selection ---
class SettingsCalendarSelectionFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_calendar_selection, container, false)
        val listContainer = view.findViewById<LinearLayout>(R.id.layoutCalendarSelectionList)
        setupSelectionList(listContainer)
        return view
    }

    private fun setupSelectionList(container: LinearLayout) {
        val calendars = DeviceCalendarLoader.getAllCalendars(requireContext())
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val selectedIdsJson = prefs.getString("selected_calendar_ids", "[]")
        val selectedIds = mutableSetOf<Long>()
        try {
            val arr = JSONArray(selectedIdsJson)
            for (i in 0 until arr.length()) selectedIds.add(arr.getLong(i))
        } catch (e: Exception) {}

        if (calendars.isEmpty()) {
            container.addView(TextView(requireContext()).apply {
                text = "同期可能なカレンダーが見つかりません"
                setPadding(16, 16, 16, 16)
            })
            return
        }

        calendars.forEach { cal ->
            val checkBox = CheckBox(requireContext()).apply {
                text = "${cal.name} (${cal.account})"
                isChecked = selectedIds.isEmpty() || selectedIds.contains(cal.id)
                setPadding(16, 24, 16, 24)
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(cal.id) else selectedIds.remove(cal.id)
                    saveIds(selectedIds)
                }
            }
            container.addView(checkBox)
        }
    }

    private fun saveIds(ids: Set<Long>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE).edit {
            putString("selected_calendar_ids", arr.toString())
        }
    }
}

// --- 7. HUD & Interface Settings ---
class SettingsHudFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_hud, container, false)
        setupHudSettings(view)
        return view
    }

    private fun setupHudSettings(view: View) {
        val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        
        val editName = view.findViewById<EditText>(R.id.editUserName)
        val btnSaveName = view.findViewById<Button>(R.id.btnSaveUserName)
        val switchPlayerLv = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchShowPlayerLevel)
        val switchCharLv = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchShowCharLevel)

        // 現在の値をセット
        editName.setText(appPrefs.getString("user_name", "PLAYER"))
        switchPlayerLv.isChecked = appPrefs.getBoolean("show_player_level", true)
        switchCharLv.isChecked = appPrefs.getBoolean("show_char_level", true)

        // 名前保存
        btnSaveName.setOnClickListener {
            val newName = editName.text.toString().trim()
            if (newName.isNotEmpty()) {
                if (newName.length <= 8) {
                    appPrefs.edit().putString("user_name", newName).apply()
                    Toast.makeText(requireContext(), "名前を更新しました", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "名前は8文字以内で入力してください", Toast.LENGTH_SHORT).show()
                }
            }
        }

        // 表示スイッチ
        switchPlayerLv.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("show_player_level", isChecked).apply()
        }
        switchCharLv.setOnCheckedChangeListener { _, isChecked ->
            appPrefs.edit().putBoolean("show_char_level", isChecked).apply()
        }
    }
}
