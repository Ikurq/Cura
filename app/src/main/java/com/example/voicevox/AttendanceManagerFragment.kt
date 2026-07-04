package com.example.voicevox

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

class AttendanceManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val subjectList = mutableListOf<String>()
    
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_attendance_manager, container, false)
        recyclerView = view.findViewById(R.id.attendanceRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        
        loadSubjects()
        recyclerView.adapter = AttendanceAdapter(subjectList)
        
        return view
    }

    private fun loadSubjects() {
        subjectList.clear()
        
        // 1. Load from SchedulePrefs (Custom events)
        val schedulePrefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val customJson = schedulePrefs.getString("eventListJSON", null)
        if (customJson != null) {
            try {
                val jsonArray = org.json.JSONArray(customJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    if (obj.optBoolean("isAttendanceTracked", false)) {
                        val genre = obj.getString("genre")
                        if (!subjectList.contains(genre)) subjectList.add(genre)
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. Load from AttendancePrefs (Tracked External events)
        val attendancePrefs = requireContext().getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
        attendancePrefs.all.keys.filter { it.startsWith("track_") }.forEach { key ->
            if (attendancePrefs.getBoolean(key, false)) {
                val subject = key.removePrefix("track_")
                if (!subjectList.contains(subject)) subjectList.add(subject)
            }
        }

        subjectList.sort()
    }

    private inner class AttendanceAdapter(private val items: List<String>) : RecyclerView.Adapter<AttendanceViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AttendanceViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_attendance_count, parent, false)
            return AttendanceViewHolder(view)
        }

        override fun onBindViewHolder(holder: AttendanceViewHolder, position: Int) {
            val name = items[position]
            holder.txtName.text = name
            
            val prefs = requireContext().getSharedPreferences("AttendancePrefs", Context.MODE_PRIVATE)
            var count = prefs.getInt("absent_$name", 0)
            holder.txtCount.text = "${count}回"

            holder.btnMinus.setOnClickListener {
                if (count > 0) {
                    count--
                    prefs.edit().putInt("absent_$name", count).apply()
                    holder.txtCount.text = "${count}回"
                }
            }
            holder.btnPlus.setOnClickListener {
                count++
                prefs.edit().putInt("absent_$name", count).apply()
                holder.txtCount.text = "${count}回"
            }
        }

        override fun getItemCount() = items.size
    }

    private class AttendanceViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val txtName: TextView = view.findViewById(R.id.txtSubjectName)
        val txtCount: TextView = view.findViewById(R.id.txtAbsentCount)
        val btnMinus: Button = view.findViewById(R.id.btnMinus)
        val btnPlus: Button = view.findViewById(R.id.btnPlus)
    }
}
