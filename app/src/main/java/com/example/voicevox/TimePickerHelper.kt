package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.NumberPicker
import android.widget.TextView

object TimePickerHelper {

    fun showWheelTimePicker(
        context: Context,
        initialHour: Int,
        initialMinute: Int,
        title: String = "時刻を選択",
        neutralButtonText: String? = null,
        onNeutralClicked: (() -> Unit)? = null,
        onTimeSelected: (hour: Int, minute: Int) -> Unit
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.dialog_wheel_time_picker, null)
        val hourPicker = view.findViewById<NumberPicker>(R.id.hourPicker)
        val minutePicker = view.findViewById<NumberPicker>(R.id.minutePicker)
        val titleText = view.findViewById<TextView>(R.id.pickerTitle)

        titleText.text = title

        // Configure Hour Picker
        hourPicker.minValue = 0
        hourPicker.maxValue = 23
        hourPicker.value = initialHour
        hourPicker.setFormatter { String.format("%02d", it) }

        // Configure Minute Picker
        minutePicker.minValue = 0
        minutePicker.maxValue = 59
        minutePicker.value = initialMinute
        minutePicker.setFormatter { String.format("%02d", it) }

        val builder = AlertDialog.Builder(context)
            .setView(view)
            .setPositiveButton("決定") { _, _ ->
                onTimeSelected(hourPicker.value, minutePicker.value)
            }
            .setNegativeButton("キャンセル", null)

        if (neutralButtonText != null) {
            builder.setNeutralButton(neutralButtonText) { _, _ ->
                onNeutralClicked?.invoke()
            }
        }

        builder.show()
    }
}
