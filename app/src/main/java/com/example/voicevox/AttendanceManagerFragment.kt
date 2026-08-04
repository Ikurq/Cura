package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicevox.databinding.DialogAttendanceDetailBinding
import com.example.voicevox.databinding.FragmentAttendanceManagerBinding
import com.example.voicevox.databinding.ItemAttendanceCountBinding
import kotlinx.serialization.json.Json
import org.json.JSONArray
import java.util.Calendar
import java.util.Locale

class AttendanceManagerFragment : Fragment() {

    private var _binding: FragmentAttendanceManagerBinding? = null
    private val binding get() = _binding!!

    private val subjectList = mutableListOf<SubjectStats>()
    private lateinit var attendanceAdapter: AttendanceAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAttendanceManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadStatistics()
        
        attendanceAdapter = AttendanceAdapter(subjectList) { stats ->
            showAbsentHistoryDialog(stats)
        }

        binding.attendanceRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = attendanceAdapter
        }
    }

    private fun loadStatistics() {
        subjectList.clear()
        val ctx = context ?: return
        
        val occurrenceDays = mutableMapOf<String, MutableSet<String>>()
        val statusByDay = mutableMapOf<String, MutableMap<String, AttendanceStatus>>()

        fun registerOccurrence(name: String, timeMillis: Long, status: AttendanceStatus) {
            val cal = Calendar.getInstance().apply { timeInMillis = timeMillis }
            val dayKey = String.format(Locale.US, "%04d-%02d-%02d", 
                cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            
            occurrenceDays.getOrPut(name) { mutableSetOf() }.add(dayKey)
            
            val dayStatuses = statusByDay.getOrPut(name) { mutableMapOf() }
            val currentStatus = dayStatuses[dayKey] ?: AttendanceStatus.NONE
            
            when {
                status == AttendanceStatus.ABSENT -> dayStatuses[dayKey] = AttendanceStatus.ABSENT
                status == AttendanceStatus.LATE && currentStatus != AttendanceStatus.ABSENT -> {
                    dayStatuses[dayKey] = AttendanceStatus.LATE
                }
                status == AttendanceStatus.ATTEND && currentStatus == AttendanceStatus.NONE -> {
                    dayStatuses[dayKey] = AttendanceStatus.ATTEND
                }
            }
        }

        // 1. Process Custom Events
        val schedulePrefs = ctx.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        schedulePrefs.getString(CuraConstants.KEY_EVENT_LIST, null)?.let { jsonStr ->
            try {
                val events = Json.decodeFromString<List<IcsEvent>>(jsonStr)
                events.filter { it.isAttendanceTracked }.forEach { event ->
                    registerOccurrence(event.summary, event.startTime, AttendanceStatus.fromString(event.attendanceStatus))
                }
            } catch (e: Exception) {}
        }

        // 2. Process External Events
        val timetablePrefs = ctx.getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
        val attendancePrefs = ctx.getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        timetablePrefs.getString(CuraConstants.KEY_ICS_CACHE, null)?.let { jsonStr ->
            try {
                val events = Json.decodeFromString<List<IcsEvent>>(jsonStr)
                events.forEach { event ->
                    if (attendancePrefs.getBoolean("track_${event.summary}", false)) {
                        val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
                        val dayKey = String.format(Locale.US, "%04d-%02d-%02d", 
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
                        val statusStr = attendancePrefs.getString("status_${event.summary}_$dayKey", "NONE") ?: "NONE"
                        registerOccurrence(event.summary, event.startTime, AttendanceStatus.fromString(statusStr))
                    }
                }
            } catch (e: Exception) {}
        }

        occurrenceDays.keys.forEach { name ->
            val stats = SubjectStats(name)
            stats.totalScheduled = occurrenceDays[name]?.size ?: 0
            
            statusByDay[name]?.forEach { (day, status) ->
                when (status) {
                    AttendanceStatus.ATTEND -> stats.attended++
                    AttendanceStatus.ABSENT -> {
                        stats.absent++
                        stats.absentDates.add(day)
                    }
                    AttendanceStatus.LATE -> stats.late++
                    else -> {}
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
        val ctx = context ?: return
        val dialogBinding = DialogAttendanceDetailBinding.inflate(LayoutInflater.from(ctx))
        
        if (stats.absentDates.isEmpty()) {
            val emptyText = TextView(ctx).apply {
                text = "記録された欠席日はありません。\n(手動カウンターのみの可能性があります)"
                setPadding(16, 16, 16, 16)
            }
            dialogBinding.detailContainer.addView(emptyText)
        } else {
            stats.absentDates.forEach { date ->
                val dateText = TextView(ctx).apply {
                    text = "・$date (欠席)"
                    textSize = 16f
                    setPadding(16, 8, 16, 8)
                }
                dialogBinding.detailContainer.addView(dateText)
            }
        }

        AlertDialog.Builder(ctx)
            .setTitle("${stats.name} の欠席履歴")
            .setView(dialogBinding.root)
            .setPositiveButton("閉じる", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class AttendanceAdapter(
        private val items: List<SubjectStats>,
        private val onItemClick: (SubjectStats) -> Unit
    ) : RecyclerView.Adapter<AttendanceAdapter.ViewHolder>() {

        class ViewHolder(val binding: ItemAttendanceCountBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val binding = ItemAttendanceCountBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val stats = items[position]
            val binding = holder.binding

            binding.txtSubjectName.text = stats.name
            binding.txtTotalCount.text = stats.totalScheduled.toString()
            binding.txtAttendCount.text = stats.attended.toString()
            binding.txtLateCount.text = stats.late.toString()
            binding.txtAbsentCount.text = stats.absent.toString()

            binding.root.setOnClickListener { onItemClick(stats) }

            val ctx = binding.root.context
            val attendancePrefs = ctx.getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)

            binding.btnMinus.setOnClickListener {
                if (stats.absent > 0) {
                    stats.absent--
                    attendancePrefs.edit().putInt("absent_${stats.name}", stats.absent).apply()
                    binding.txtAbsentCount.text = stats.absent.toString()
                }
            }
            binding.btnPlus.setOnClickListener {
                stats.absent++
                attendancePrefs.edit().putInt("absent_${stats.name}", stats.absent).apply()
                binding.txtAbsentCount.text = stats.absent.toString()
            }
        }

        override fun getItemCount() = items.size
    }
}
