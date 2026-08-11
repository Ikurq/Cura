package com.example.voicevox

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.edit
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.voicevox.databinding.DialogAddTaskBinding
import com.example.voicevox.databinding.FragmentTaskBinding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class TaskFragment : Fragment() {

    private var _binding: FragmentTaskBinding? = null
    private val binding get() = _binding!!

    private val taskList = ArrayList<TaskItem>()
    private lateinit var taskAdapter: TaskAdapter

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTaskBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadTasks()
        sortTasks()

        taskAdapter = TaskAdapter(taskList, {
            updateButtonsVisibility()
            saveTasks()
            taskAdapter.notifyDataSetChanged()
        }, { task ->
            showDeleteConfirmDialog(task)
        })

        binding.taskRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
        }

        updateEmptyView()
        updateButtonsVisibility()

        binding.addTaskFAB.setOnClickListener { showAddTaskDialog() }
        binding.completeTasksFAB.setOnClickListener { performBatchCompletion() }
    }

    private fun updateButtonsVisibility() {
        val hasCompleted = taskList.any { it.isCompleted }
        binding.completeTasksFAB.visibility = if (hasCompleted) View.VISIBLE else View.GONE
        binding.addTaskFAB.visibility = if (hasCompleted) View.GONE else View.VISIBLE
    }

    private fun performBatchCompletion() {
        val selectedTasks = taskList.filter { it.isCompleted }
        val completedCount = selectedTasks.size
        if (completedCount == 0) return

        AlertDialog.Builder(requireContext())
            .setTitle("タスクの完了")
            .setMessage("${completedCount}件のタスクを完了としてマークし、リストから消去しますか？")
            .setPositiveButton("完了") { _, _ ->
                taskList.removeAll { it.isCompleted }
                saveTasks()
                recordCompletedTasksToSchedule(selectedTasks)
                addExpForTasks(selectedTasks)

                taskAdapter.notifyDataSetChanged()
                updateEmptyView()
                updateButtonsVisibility()
                Toast.makeText(requireContext(), "${completedCount}件のタスクを完了しました！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sortTasks() {
        taskList.sortWith(
            compareBy<TaskItem> { it.isCompleted }
                .thenByDescending { it.getCurrentPriority() }
                .thenBy { it.deadlineMillis }
        )
    }

    private fun updateEmptyView() {
        binding.taskEmptyView.visibility = if (taskList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddTaskDialog() {
        val ctx = context ?: return
        val dialogBinding = DialogAddTaskBinding.inflate(LayoutInflater.from(ctx))
        
        val calendar = Calendar.getInstance()
        var selectedDeadline = calendar.timeInMillis
        var selectedEventId: String? = null
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        val eventLabel = TextView(ctx).apply {
            text = "予定と連動させる"
            setPadding(0, 16, 0, 8)
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        val eventSpinner = Spinner(ctx)
        (dialogBinding.root as LinearLayout).addView(eventLabel)
        (dialogBinding.root as LinearLayout).addView(eventSpinner)

        fun updateEventSpinner() {
            val eventList = ScheduleLoader.loadAllEventsForToday(ctx, calendar)
            val eventTitles = mutableListOf("指定なし (手動入力)")
            eventTitles.addAll(eventList.map {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
                "[$time] ${it.summary}"
            })
            eventSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, eventTitles).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (position > 0) {
                        val event = eventList[position - 1]
                        selectedDeadline = event.startTime
                        selectedEventId = "${event.summary}_${event.startTime}"
                        dialogBinding.dialogTaskDeadlineButton.text = sdf.format(Date(selectedDeadline))
                        dialogBinding.dialogTaskDeadlineButton.isEnabled = false
                    } else {
                        selectedEventId = null
                        dialogBinding.dialogTaskDeadlineButton.isEnabled = true
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }

        dialogBinding.dialogTaskDeadlineButton.text = sdf.format(Date(selectedDeadline))
        updateEventSpinner()

        dialogBinding.dialogTaskDeadlineButton.setOnClickListener {
            DatePickerDialog(ctx, { _, year, month, day ->
                calendar.set(year, month, day)
                updateEventSpinner()
                TimePickerDialog(ctx, { _, h, m ->
                    calendar.set(Calendar.HOUR_OF_DAY, h)
                    calendar.set(Calendar.MINUTE, m)
                    calendar.set(Calendar.SECOND, 0)
                    selectedDeadline = calendar.timeInMillis
                    dialogBinding.dialogTaskDeadlineButton.text = sdf.format(Date(selectedDeadline))
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        val priorities = listOf("1 (低)", "2", "3", "4", "5 (高)")
        dialogBinding.dialogTaskPrioritySpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, priorities).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        dialogBinding.dialogTaskPrioritySpinner.setSelection(2)

        AlertDialog.Builder(ctx)
            .setView(dialogBinding.root)
            .setPositiveButton("追加") { _, _ ->
                val title = dialogBinding.dialogTaskTitleInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    val basePriority = dialogBinding.dialogTaskPrioritySpinner.selectedItemPosition + 1
                    val newTask = TaskItem(UUID.randomUUID().toString(), title, selectedDeadline, basePriority, selectedEventId, false)
                    taskList.add(newTask)
                    sortTasks()
                    taskAdapter.notifyDataSetChanged()
                    saveTasks()
                    updateEmptyView()
                } else {
                    Toast.makeText(ctx, "タイトルを入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDeleteConfirmDialog(task: TaskItem) {
        AlertDialog.Builder(requireContext())
            .setTitle("タスクの削除")
            .setMessage("「${task.title}」を削除しますか？")
            .setPositiveButton("削除") { _, _ ->
                taskList.remove(task)
                taskAdapter.notifyDataSetChanged()
                saveTasks()
                updateEmptyView()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun recordCompletedTasksToSchedule(tasks: List<TaskItem>) {
        val prefs = requireContext().getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_EVENT_LIST, null) ?: "[]"
        val jsonArray = JSONArray(jsonString)
        val now = System.currentTimeMillis()

        tasks.forEach { task ->
            jsonArray.put(JSONObject().apply {
                put("id", "completed_task_${UUID.randomUUID()}")
                put("genre", "✅ ${task.title}")
                put("startTime", now)
                put("location", "タスク完了")
                put("isAttendanceTracked", false)
                put("attendanceStatus", "NONE")
            })
        }
        prefs.edit().putString(CuraConstants.KEY_EVENT_LIST, jsonArray.toString()).apply()
        triggerNotificationRefresh()
    }

    private fun saveTasks() {
        val json = Json.encodeToString(taskList)
        requireContext().getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
            .edit().putString(CuraConstants.KEY_TASK_LIST, json).apply()
        triggerNotificationRefresh()
    }

    private fun loadTasks() {
        taskList.clear()
        val jsonStr = requireContext().getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
            .getString(CuraConstants.KEY_TASK_LIST, null)
        if (jsonStr != null) {
            try {
                taskList.addAll(Json.decodeFromString<List<TaskItem>>(jsonStr))
            } catch (e: Exception) {}
        }
    }

    private fun triggerNotificationRefresh() {
        val intent = android.content.Intent(requireContext(), AlarmReceiver::class.java).apply { action = "SCHEDULE_NOTIFICATIONS" }
        requireContext().sendBroadcast(intent)
    }

    private fun addExpForTasks(tasks: List<TaskItem>) {
        val completedCount = tasks.size
        var totalGain = 0L
        tasks.forEach { task ->
            totalGain += (task.basePriority * 20L) + (if (task.basePriority == 1) 50L else 0L)
        }
        val ctx = requireContext()
        
        // 1. 累計タスク完了数を更新
        val playerPrefs = ctx.getSharedPreferences(CuraConstants.PREFS_PLAYER, Context.MODE_PRIVATE)
        val currentTaskCount = playerPrefs.getInt(CuraConstants.KEY_COMPLETED_TASK_COUNT, 0)
        
        // 保留中の経験値として保存
        val charPrefs = ctx.getSharedPreferences(CuraConstants.PREFS_CHARACTER, Context.MODE_PRIVATE)
        val currentPending = charPrefs.getLong(CuraConstants.KEY_PENDING_EXP, 0L)
        
        playerPrefs.edit {
            putInt(CuraConstants.KEY_COMPLETED_TASK_COUNT, currentTaskCount + completedCount)
            putBoolean(CuraConstants.KEY_PENDING_TASK_PRAISE, true)
        }

        charPrefs.edit {
            putLong(CuraConstants.KEY_PENDING_EXP, currentPending + totalGain)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
