package com.example.voicevox

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch

/**
 * アラームの詳細設定。祝日スキップと長期休暇モード。
 *
 * これらは文言だけ用意されていて、判定も画面も無い状態だった。
 * 実際の判定は共通ロジックの `AlarmPlanner` にあり、iOS 版と同じものが動く。
 */
class SettingsAlarmFragment : Fragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_settings_alarm, container, false)
        val settings = requireContext().cura.settings

        val skipHolidays = view.findViewById<MaterialSwitch>(R.id.switchSkipHolidays)
        val vacationMode = view.findViewById<MaterialSwitch>(R.id.switchVacationMode)

        skipHolidays.isChecked = settings.skipHolidays
        vacationMode.isChecked = settings.vacationMode

        // 設定を変えると「次にいつ鳴るか」が変わる。登録済みの予約は古い判断のまま
        // 残るので、その場で全部登録し直す。
        skipHolidays.setOnCheckedChangeListener { _, checked ->
            settings.skipHolidays = checked
            AlarmScheduler.rescheduleAll(requireContext())
        }
        vacationMode.setOnCheckedChangeListener { _, checked ->
            settings.vacationMode = checked
            AlarmScheduler.rescheduleAll(requireContext())
        }

        return view
    }
}
