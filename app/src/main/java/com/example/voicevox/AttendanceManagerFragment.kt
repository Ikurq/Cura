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

typealias SubjectStats = com.example.voicevox.core.model.SubjectStats

class AttendanceManagerFragment : Fragment() {

    private lateinit var recyclerView: RecyclerView
    private val subjectList = mutableListOf<SubjectStats>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_attendance_manager, container, false)
        recyclerView = view.findViewById(R.id.attendanceRecyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        loadStatistics()
        recyclerView.adapter = AttendanceAdapter(subjectList)

        return view
    }

    /**
     * 集計は共通ロジック([com.example.voicevox.core.attendance.AttendanceService])へ委譲。
     * 1日に複数コマある科目を1件へ畳み込む規則も、手動カウンターによる補正も
     * そちらにあり、iOS 版と同じコードが動く。
     */
    private fun loadStatistics() {
        subjectList.clear()
        subjectList.addAll(requireContext().cura.attendance.summary())
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

            holder.btnMinus.setOnClickListener { adjustAbsent(position, -1) }
            holder.btnPlus.setOnClickListener { adjustAbsent(position, +1) }
        }

        override fun getItemCount() = items.size

        /** 記録漏れの補正。集計値を下回る調整はできない。 */
        private fun adjustAbsent(position: Int, delta: Int) {
            val stats = items[position]
            requireContext().cura.attendance.adjustManualAbsent(stats.name, delta)
            loadStatistics()
            notifyItemChanged(position)
        }
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
