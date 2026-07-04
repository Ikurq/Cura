package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*
import kotlin.math.pow

// --- 1. Permissions & Notifications ---
class SettingsPermissionsFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_permissions, container, false)
        setupPermissions(view)
        setupNotifications(view)
        return view
    }

    private fun setupPermissions(view: View) {
        view.findViewById<Button>(R.id.btnRequestOverlayPermission).setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        }
        view.findViewById<Button>(R.id.btnRequestBatteryPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
        view.findViewById<Button>(R.id.btnRequestFullScreenPermission).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}")))
        }
    }

    private fun setupNotifications(view: View) {
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val s1 = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchMandatoryReminder)
        val s2 = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchTaskNotification)
        val s3 = view.findViewById<com.google.android.material.materialswitch.MaterialSwitch>(R.id.switchEventNotification)

        s1.isChecked = prefs.getBoolean("mandatory_reminder", true)
        s1.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("mandatory_reminder", c).apply() }
        s2.isChecked = prefs.getBoolean("task_notification", true)
        s2.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("task_notification", c).apply() }
        s3.isChecked = prefs.getBoolean("event_notification", true)
        s3.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("event_notification", c).apply() }
    }
}

// --- 2. External Calendar ---
class SettingsCalendarFragment : Fragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_calendar, container, false)
        setupCalendar(view)
        return view
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
                        .setMessage("${name} を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            val newList = JSONArray()
                            for (j in 0 until list.length()) if (i != j) newList.put(list.getJSONObject(j))
                            prefs.edit().putString("calendarSourcesJSON", newList.toString()).apply()
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
                    prefs.edit().putString("calendarSourcesJSON", cur.toString()).apply()
                    refresh(); dialog.dismiss()
                }
            }
            dialog.show()
        }
        refresh()
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
                
                itemView.findViewById<View>(R.id.btnDeletePreset).setOnClickListener {
                    AlertDialog.Builder(requireContext()).setTitle("プリセットの削除")
                        .setMessage("${genre} を削除しますか？")
                        .setPositiveButton("削除") { _, _ ->
                            val newList = JSONArray()
                            for (j in 0 until list.length()) if (i != j) newList.put(list.getJSONObject(j))
                            prefs.edit().putString("presetListJSON", newList.toString()).apply()
                            refresh()
                        }.setNegativeButton("キャンセル", null).show()
                }
                container.addView(itemView)
            }
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
        val prefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val txtKey = view.findViewById<TextView>(R.id.txtCurrentApiKey)
        val edit = view.findViewById<EditText>(R.id.editApiKey)
        val btnApply = view.findViewById<Button>(R.id.btnApplyApiKey)
        txtKey.text = "現在のキー: ${prefs.getString("custom_api_key", "(デフォルト)")}"
        view.findViewById<Button>(R.id.btnOpenApiKeyPage).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://voicevox.su-shiki.com/api/")))
        }
        btnApply.setOnClickListener {
            val k = edit.text.toString().trim()
            if (k.isNotEmpty()) {
                prefs.edit().putString("custom_api_key", k).apply()
                txtKey.text = "現在のキー: $k"; edit.setText(""); Toast.makeText(context, "適用完了", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupStorage(view: View) {
        val txtSize = view.findViewById<TextView>(R.id.textCacheSize)
        val btnClear = view.findViewById<Button>(R.id.btnClearVoiceCache)
        fun update() {
            var size = 0L
            File(requireContext().filesDir, "voice_cache").let { if(it.exists()) it.listFiles()?.forEach { f -> size += f.length() } }
            requireContext().filesDir.listFiles()?.filter { it.name.endsWith(".wav") }?.forEach { size += it.length() }
            txtSize.text = String.format("使用量: %.2f MB", size / (1024.0 * 1024.0))
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
            prefs.edit().putLong("totalExp", (seek.progress - 1) * 100L).apply()
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
