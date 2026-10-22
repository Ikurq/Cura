package com.example.voicevox

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.fragment.app.Fragment

class SettingsUsabilityFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_settings_usability, container, false)

        val radioGroup = view.findViewById<RadioGroup>(R.id.radioGroupDefaultView)
        val radioMonth = view.findViewById<RadioButton>(R.id.radioDefaultMonth)
        val radioDay = view.findViewById<RadioButton>(R.id.radioDefaultDay)

        val appPrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        val currentDefault = appPrefs.getString(CuraConstants.KEY_DEFAULT_TIMETABLE_VIEW, CuraConstants.VIEW_MODE_MONTH)

        if (currentDefault == CuraConstants.VIEW_MODE_DAY) {
            radioDay.isChecked = true
        } else {
            radioMonth.isChecked = true
        }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            val newMode = if (checkedId == R.id.radioDefaultDay) {
                CuraConstants.VIEW_MODE_DAY
            } else {
                CuraConstants.VIEW_MODE_MONTH
            }
            appPrefs.edit().putString(CuraConstants.KEY_DEFAULT_TIMETABLE_VIEW, newMode).apply()
        }

        return view
    }
}
