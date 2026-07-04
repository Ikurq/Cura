package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

class AttendanceManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val subjectList = mutableListOf<SubjectStats>()
    
    data class SubjectStats(
        val name: String,
        var totalScheduled: Int = 0,
        var attended: Int = 0,
        var absent: Int = 0,
        var late: Int = 0,
        val absentDates: MutableList<String> = mutableListOf()
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_attendance_manager, container, false)
        recyclerView = view.findViewById(R.id.attendanceRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        loadStatistics()
        recyclerView.adapter = AttendanceAdapter(subjectList)
        
        return view
    }

    private fun loadStatistics() {
        subjectList.clear()
        
        val occurrenceDays = mutableMapOf<String, MutableSet<String>>()
        val statusByDay = mutableMapOf<String, MutableMap<String, String>>()

        fun registerOccurrence(name: String, timeMillis: Long, status: String) {
            val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
            val dayKey = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            
            occurrenceDays.getOrPut(name) { mutableSetOf() }.add(dayKey)
            
            val dayStatuses = statusByDay.getOrPut(name) { mutableMapOf() }
            val currentStatus = dayStatuses[dayKey] ?: "NONE"
            
            if (status == "ABSENT") {
                dayStatuses[dayKey] = "ABSENT"
            } else if (status == "LATE" && currentStatus != "ABSENT") {
                dayStatuses[dayKey] = "LATE"
            } else if (status == "ATTEND" && currentStatus == "NONE") {
                dayStatuses[dayKey] = "ATTEND"
            }
        }

        // 1. Process Custom Events
        val schedulePrefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val customJson = schedulePrefs.getString("eventListJSON", null)
        if (customJson != null) {
            try {
                val jsonArray = JSONArray(customJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optBoolean("isAttendanceTracked", false)) {
                        val name = obj.getString("genre")
                        val time = obj.getLong("startTime")
                        val status = obj.optString("attendanceStatus", "NONE")
                        registerOccurrence(name, time, status)
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. Process External Events
        val timetablePrefs = requireContext().getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)
        val attendancePrefs = requireContext().getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
        val icsJson = timetablePrefs.getString("icsCacheJSON", null)
        if (icsJson != null) {
            try {
                val jsonArray = JSONArray(icsJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val name = obj.getString("summary")
                    if (attendancePrefs.getBoolean("track_$name", false)) {
                        val time = obj.getLong("startTime")
                        val cal = Calendar.getInstance().apply { timeInMillis = time }
                        val dayKey = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                        val status = attendancePrefs.getString("status_${name}_$dayKey", "NONE") ?: "NONE"
                        registerOccurrence(name, time, status)
                    }
                }
            } catch (e: Exception) {}
        }

        occurrenceDays.keys.forEach { name ->
            val stats = SubjectStats(name)
            stats.totalScheduled = occurrenceDays[name]?.size ?: 0
            
            val days = statusByDay[name]
            days?.forEach { (day, status) ->
                when (status) {
                    "ATTEND" -> stats.attended++
                    "ABSENT" -> {
                        stats.absent++
                        stats.absentDates.add(day)
                    }
                    "LATE" -> stats.late++
                }
            }
            
            val manualAbsent = attendancePrefs.getInt("absent_$name", 0)
            if (manualAbsent > stats.absent) {
                stats.absent = manualAbsent
            }

            stats.absentDates.sortDescending()
            subjectList.add(stats)
        }
        subjectList.sortBy { it.name }
    }

    private fun showAbsentHistoryDialog(stats: SubjectStats) {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_attendance_detail, null)
        val container = dialogView.findViewById<LinearLayout>(R.id.detailContainer)
        
        if (stats.absentDates.isEmpty()) {
            val emptyText = TextView(requireContext()).apply {
                text = "記録された欠席日はありません。\n(手動カウンターのみの可能性があります)"
                setPadding(16, 16, 16, 16)
            }
            container.addView(emptyText)
        } else {
            stats.absentDates.forEach { date ->
                val dateText = TextView(requireContext()).apply {
                    text = "・$date (欠席)"
                    textSize = 16f
                    setPadding(16, 8, 16, 8)
                }
                container.addView(dateText)
            }
        }

        AlertDialog.Builder(requireContext())
            .setTitle("${stats.name} の欠席履歴")
            .setView(dialogView)
            .setPositiveButton("閉じる", null)
            .show()
    }

    private inner class AttendanceAdapter(private val items: List<SubjectStats>) : RecyclerView.Adapter<AttendanceViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_attendance_count, parent, false)
            return AttendanceViewHolder(view)
        }

        override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
            val stats = items[position]
            holder.txtName.text = stats.name
            holder.txtTotal.text = stats.totalScheduled.toString()
            holder.txtAttend.text = stats.attended.toString()
            holder.txtLate.text = stats.late.toString()
            holder.txtAbsent.text = stats.absent.toString()

            holder.itemView.setOnClickListener {
                showAbsentHistoryDialog(stats)
            }

            val attendancePrefs = requireContext().getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)

            holder.btnMinus.setOnClickListener {
                if (stats.absent > 0) {
                    stats.absent--
                    attendancePrefs.edit().putInt("absent_${stats.name}", stats.absent).apply()
                    holder.txtAbsent.text = stats.absent.toString()
                }
            }
            holder.btnPlus.setOnClickListener {
                stats.absent++
                attendancePrefs.edit().putInt("absent_${stats.name}", stats.absent).apply()
                holder.txtAbsent.text = stats.absent.toString()
            }
        }

        override fun getItemCount() = items.size
    }

    private class AttendanceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtSubjectName)
        val txtTotal: TextView = view.findViewById(R.id.txtTotalCount)
        val txtAttend: TextView = view.findViewById(R.id.txtAttendCount)
        val txtLate: TextView = view.findViewById(R.id.txtLateCount)
        val txtAbsent: TextView = view.findViewById(R.id.txtAbsentCount)
        val btnMinus: Button = view.findViewById(R.id.btnMinus)
        val btnPlus: Button = view.findViewById(R.id.btnPlus)
    }
}
