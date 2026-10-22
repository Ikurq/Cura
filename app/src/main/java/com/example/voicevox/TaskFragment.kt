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
        }, { task ->
            showTaskDialog(task)
        })

        binding.taskRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = taskAdapter
        }

        updateEmptyView()
        updateButtonsVisibility()

        binding.addTaskFAB.setOnClickListener { showTaskDialog() }
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
                .thenBy { it.getEffectiveDeadline() }
                .thenBy { it.deadlineMillis }
        )
    }

    private fun updateEmptyView() {
        binding.taskEmptyView.visibility = if (taskList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showTaskDialog(taskToEdit: TaskItem? = null) {
        val ctx = context ?: return
        val dialogBinding = DialogAddTaskBinding.inflate(LayoutInflater.from(ctx))
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        val calendar = Calendar.getInstance()
        var selectedRealDeadline = taskToEdit?.deadlineMillis ?: calendar.timeInMillis
        var selectedTargetDeadline: Long? = taskToEdit?.targetDeadlineMillis
        var selectedEventId: String? = taskToEdit?.linkedEventId

        val isEdit = taskToEdit != null
        dialogBinding.dialogTaskHeaderTitle.text = if (isEdit) "タスクの編集" else "新しいタスクを追加"
        if (isEdit) {
            dialogBinding.dialogTaskTitleInput.setText(taskToEdit.title)
        }

        // --- 目標デッドライン設定 ---
        fun updateTargetDeadlineUi() {
            if (selectedTargetDeadline != null) {
                dialogBinding.dialogTaskTargetDeadlineButton.text = sdf.format(Date(selectedTargetDeadline!!))
                dialogBinding.dialogTaskClearTargetDeadlineButton.visibility = View.VISIBLE
            } else {
                dialogBinding.dialogTaskTargetDeadlineButton.text = "未設定 (タップして設定)"
                dialogBinding.dialogTaskClearTargetDeadlineButton.visibility = View.GONE
            }
        }
        updateTargetDeadlineUi()

        dialogBinding.dialogTaskTargetDeadlineButton.setOnClickListener {
            val targetCal = Calendar.getInstance().apply {
                if (selectedTargetDeadline != null) timeInMillis = selectedTargetDeadline!!
                else timeInMillis = selectedRealDeadline
            }
            DatePickerDialog(ctx, { _, year, month, day ->
                targetCal.set(year, month, day)
                TimePickerDialog(ctx, { _, h, m ->
                    targetCal.set(Calendar.HOUR_OF_DAY, h)
                    targetCal.set(Calendar.MINUTE, m)
                    targetCal.set(Calendar.SECOND, 0)
                    selectedTargetDeadline = targetCal.timeInMillis
                    updateTargetDeadlineUi()
                }, targetCal.get(Calendar.HOUR_OF_DAY), targetCal.get(Calendar.MINUTE), true).show()
            }, targetCal.get(Calendar.YEAR), targetCal.get(Calendar.MONTH), targetCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        dialogBinding.dialogTaskClearTargetDeadlineButton.setOnClickListener {
            selectedTargetDeadline = null
            updateTargetDeadlineUi()
        }

        // --- 本当のデッドライン設定 ---
        dialogBinding.dialogTaskDeadlineButton.text = sdf.format(Date(selectedRealDeadline))

        val eventLabel = TextView(ctx).apply {
            text = "予定と連動させる"
            setPadding(0, 16, 0, 8)
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        val eventSpinner = Spinner(ctx)
        (dialogBinding.root as LinearLayout).addView(eventLabel)
        (dialogBinding.root as LinearLayout).addView(eventSpinner)

        val realCal = Calendar.getInstance().apply { timeInMillis = selectedRealDeadline }

        fun updateEventSpinner() {
            val eventList = ScheduleLoader.loadAllEventsForToday(ctx, realCal)
            val eventTitles = mutableListOf("指定なし (手動入力)")
            eventTitles.addAll(eventList.map {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
                "[$time] ${it.summary}"
            })
            eventSpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, eventTitles).apply {
                setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            }

            var initialSelection = 0
            if (selectedEventId != null) {
                val idx = eventList.indexOfFirst { "${it.summary}_${it.startTime}" == selectedEventId }
                if (idx != -1) initialSelection = idx + 1
            }
            eventSpinner.setSelection(initialSelection)

            eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, position: Int, id: Long) {
                    if (position > 0) {
                        val event = eventList[position - 1]
                        selectedRealDeadline = event.startTime
                        selectedEventId = "${event.summary}_${event.startTime}"
                        dialogBinding.dialogTaskDeadlineButton.text = sdf.format(Date(selectedRealDeadline))
                        dialogBinding.dialogTaskDeadlineButton.isEnabled = false
                    } else {
                        selectedEventId = null
                        dialogBinding.dialogTaskDeadlineButton.isEnabled = true
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        updateEventSpinner()

        dialogBinding.dialogTaskDeadlineButton.setOnClickListener {
            DatePickerDialog(ctx, { _, year, month, day ->
                realCal.set(year, month, day)
                updateEventSpinner()
                TimePickerDialog(ctx, { _, h, m ->
                    realCal.set(Calendar.HOUR_OF_DAY, h)
                    realCal.set(Calendar.MINUTE, m)
                    realCal.set(Calendar.SECOND, 0)
                    selectedRealDeadline = realCal.timeInMillis
                    dialogBinding.dialogTaskDeadlineButton.text = sdf.format(Date(selectedRealDeadline))
                }, realCal.get(Calendar.HOUR_OF_DAY), realCal.get(Calendar.MINUTE), true).show()
            }, realCal.get(Calendar.YEAR), realCal.get(Calendar.MONTH), realCal.get(Calendar.DAY_OF_MONTH)).show()
        }

        val priorities = listOf("1 (低)", "2", "3", "4", "5 (高)")
        dialogBinding.dialogTaskPrioritySpinner.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_item, priorities).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        val defaultPriorityIndex = if (taskToEdit != null) (taskToEdit.basePriority - 1).coerceIn(0, 4) else 2
        dialogBinding.dialogTaskPrioritySpinner.setSelection(defaultPriorityIndex)

        AlertDialog.Builder(ctx)
            .setView(dialogBinding.root)
            .setPositiveButton(if (isEdit) "保存" else "追加") { _, _ ->
                val title = dialogBinding.dialogTaskTitleInput.text.toString().trim()
                if (title.isEmpty()) {
                    Toast.makeText(ctx, "タイトルを入力してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                if (selectedTargetDeadline != null && selectedTargetDeadline!! > selectedRealDeadline) {
                    Toast.makeText(ctx, "目標デッドラインは本当のデッドライン以前の日時を指定してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val basePriority = dialogBinding.dialogTaskPrioritySpinner.selectedItemPosition + 1

                if (isEdit) {
                    val index = taskList.indexOfFirst { it.id == taskToEdit.id }
                    if (index != -1) {
                        taskList[index] = taskToEdit.copy(
                            title = title,
                            deadlineMillis = selectedRealDeadline,
                            targetDeadlineMillis = selectedTargetDeadline,
                            basePriority = basePriority,
                            linkedEventId = selectedEventId
                        )
                    }
                } else {
                    val newTask = TaskItem(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        deadlineMillis = selectedRealDeadline,
                        basePriority = basePriority,
                        linkedEventId = selectedEventId,
                        isCompleted = false,
                        targetDeadlineMillis = selectedTargetDeadline
                    )
                    taskList.add(newTask)
                }

                sortTasks()
                taskAdapter.notifyDataSetChanged()
                saveTasks()
                updateEmptyView()
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
                put("genre", "[完了] ${task.title}")
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