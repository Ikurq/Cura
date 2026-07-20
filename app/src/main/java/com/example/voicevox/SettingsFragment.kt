package com.example.voicevox

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import org.json.JSONArray

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings_main, container, false)

        val menuPresets = view.findViewById<View>(R.id.menuPresets)
        val menuDev = view.findViewById<View>(R.id.menuDev)

        // 開発者モードの状態をチェック
        val appPrefs = requireContext().getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        val isDevUnlocked = appPrefs.getBoolean("developer_mode_unlocked", false)
        menuDev.visibility = if (isDevUnlocked) View.VISIBLE else View.GONE
        
        // プリセットが空ならメニューを非表示にする
        val schedulePrefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val presetJson = schedulePrefs.getString("presetListJSON", "[]")
        val hasPresets = try {
            JSONArray(presetJson).length() > 0
        } catch (e: Exception) {
            false
        }
        
        menuPresets.visibility = if (hasPresets) View.VISIBLE else View.GONE

        view.findViewById<View>(R.id.menuPermissions).setOnClickListener {
            navigateTo(SettingsPermissionsFragment())
        }
        view.findViewById<View>(R.id.menuHud).setOnClickListener {
            navigateTo(SettingsHudFragment())
        }
        view.findViewById<View>(R.id.menuCalendar).setOnClickListener {
            navigateTo(SettingsCalendarFragment())
        }
        view.findViewById<View>(R.id.menuPresets).setOnClickListener {
            navigateTo(SettingsPresetsFragment())
        }
        view.findViewById<View>(R.id.menuVoice).setOnClickListener {
            navigateTo(SettingsVoiceFragment())
        }
        view.findViewById<View>(R.id.menuDev).setOnClickListener {
            navigateTo(SettingsDevFragment())
        }
        view.findViewById<View>(R.id.menuCredits).setOnClickListener {
            navigateTo(CreditsFragment())
        }

        return view
    }

    private fun navigateTo(fragment: Fragment) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.fragmentContainer, fragment)
            .addToBackStack(null)
            .commit()
        
        val title = when(fragment) {
            is SettingsPermissionsFragment -> "システム権限と通知"
            is SettingsCalendarFragment -> "外部カレンダーの設定"
            is SettingsPresetsFragment -> "スケジュールの設定"
            is SettingsVoiceFragment -> "音声合成とストレージ"
            is SettingsDevFragment -> "開発者オプション"
            is CreditsFragment -> "クレジット"
            else -> "設定"
        }
        (activity as? MainActivity)?.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar)?.title = title
    }
}
