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
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

class TaskFragment : Fragment() {

    private val taskList = ArrayList<TaskItem>()
    private lateinit var taskAdapter: TaskAdapter
    private lateinit var taskRecyclerView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var completeTasksFAB: View
    private lateinit var addTaskFAB: View

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_task, container, false)

        taskRecyclerView = view.findViewById(R.id.taskRecyclerView)
        emptyView = view.findViewById(R.id.taskEmptyView)
        completeTasksFAB = view.findViewById(R.id.completeTasksFAB)
        addTaskFAB = view.findViewById(R.id.addTaskFAB)

        loadTasks()
        sortTasks()

        taskAdapter = TaskAdapter(taskList, { task ->
            // 完了状態が変わった時の処理
            updateButtonsVisibility()
            saveTasks()
            // 状態変更時は即ソートせず、チェックを入れるだけにする（使い勝手のため）
            taskAdapter.notifyDataSetChanged()
        }, { task ->
            // 長押しで削除
            showDeleteConfirmDialog(task)
        })

        taskRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        taskRecyclerView.adapter = taskAdapter

        updateEmptyView()
        updateButtonsVisibility()

        addTaskFAB.setOnClickListener {
            showAddTaskDialog()
        }

        completeTasksFAB.setOnClickListener {
            performBatchCompletion()
        }

        return view
    }

    private fun updateButtonsVisibility() {
        val hasCompleted = taskList.any { it.isCompleted }
        completeTasksFAB.visibility = if (hasCompleted) View.VISIBLE else View.GONE
        addTaskFAB.visibility = if (hasCompleted) View.GONE else View.VISIBLE
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
                
                // --- スケジュールへの記録処理を追加 ---
                recordCompletedTasksToSchedule(selectedTasks)

                // --- EXP獲得処理を追加 ---
                addExpForTasks(selectedTasks)

                taskAdapter.notifyDataSetChanged()
                updateEmptyView()
                updateButtonsVisibility()
                Toast.makeText(requireContext(), "${completedCount}件のタスクを完了しました！", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun sortTasks() {
        taskList.sortWith(
            compareBy<TaskItem> { it.isCompleted } // 未完了(false)が先、完了(true)が後
                .thenByDescending { it.getCurrentPriority() }
                .thenBy { it.deadlineMillis }
        )
    }

    private fun updateEmptyView() {
        emptyView.visibility = if (taskList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddTaskDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_task, null)
        val titleInput = dialogView.findViewById<EditText>(R.id.dialogTaskTitleInput)
        val deadlineButton = dialogView.findViewById<Button>(R.id.dialogTaskDeadlineButton)
        val prioritySpinner = dialogView.findViewById<Spinner>(R.id.dialogTaskPrioritySpinner)
        
        val container = dialogView as LinearLayout
        
        // --- Enhanced: Date-Filtered Event Selection UI ---
        val calendar = Calendar.getInstance()
        var selectedDeadline = calendar.timeInMillis
        var selectedEventId: String? = null
        val sdf = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        val eventLabel = TextView(requireContext()).apply { 
            text = "予定と連動させる"
            setPadding(0, 16, 0, 8)
            setTextColor(resources.getColor(R.color.text_secondary, null))
        }
        val eventSpinner = Spinner(requireContext())
        
        container.addView(eventLabel)
        container.addView(eventSpinner)

        fun updateEventSpinner() {
            val eventList = loadAllEventsForDate(calendar)
            val eventTitles = mutableListOf("指定なし (手動入力)")
            eventTitles.addAll(eventList.map { 
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it.startTime))
                "[$time] ${it.summary}"
            })
            val eventAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, eventTitles)
            eventAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            eventSpinner.adapter = eventAdapter

            eventSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (position > 0) {
                        val event = eventList[position - 1]
                        selectedDeadline = event.startTime
                        selectedEventId = "${event.summary}_${event.startTime}"
                        deadlineButton.text = sdf.format(Date(selectedDeadline))
                        deadlineButton.isEnabled = false 
                    } else {
                        selectedEventId = null
                        deadlineButton.isEnabled = true
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        // ---------------------------------

        deadlineButton.text = sdf.format(Date(selectedDeadline))
        updateEventSpinner()

        deadlineButton.setOnClickListener {
            DatePickerDialog(requireContext(), { _, year, month, dayOfMonth ->
                calendar.set(Calendar.YEAR, year)
                calendar.set(Calendar.MONTH, month)
                calendar.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                
                updateEventSpinner() // Refresh events when date picked

                TimePickerDialog(requireContext(), { _, hourOfDay, minute ->
                    calendar.set(Calendar.HOUR_OF_DAY, hourOfDay)
                    calendar.set(Calendar.MINUTE, minute)
                    calendar.set(Calendar.SECOND, 0)
                    selectedDeadline = calendar.timeInMillis
                    deadlineButton.text = sdf.format(Date(selectedDeadline))
                }, calendar.get(Calendar.HOUR_OF_DAY), calendar.get(Calendar.MINUTE), true).show()
                
            }, calendar.get(Calendar.YEAR), calendar.get(Calendar.MONTH), calendar.get(Calendar.DAY_OF_MONTH)).show()
        }

        val priorities = listOf("1 (低)", "2", "3", "4", "5 (高)")
        val spinnerAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_item, priorities)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        prioritySpinner.adapter = spinnerAdapter
        prioritySpinner.setSelection(2) 

        AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .setPositiveButton("追加") { _, _ ->
                val title = titleInput.text.toString().trim()
                if (title.isNotEmpty()) {
                    val basePriority = prioritySpinner.selectedItemPosition + 1
                    val newTask = TaskItem(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        deadlineMillis = selectedDeadline,
                        basePriority = basePriority,
                        linkedEventId = selectedEventId,
                        isCompleted = false
                    )
                    taskList.add(newTask)
                    sortTasks()
                    taskAdapter.notifyDataSetChanged()
                    saveTasks()
                    updateEmptyView()
                } else {
                    Toast.makeText(requireContext(), "タイトルを入力してください", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun loadAllEventsForDate(targetDate: Calendar): List<IcsEvent> {
        val allEvents = mutableListOf<IcsEvent>()
        
        // 1. Load Custom Events
        val schedulePrefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val customJson = schedulePrefs.getString("eventListJSON", null)
        if (customJson != null) {
            try {
                val jsonArray = JSONArray(customJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                    if (isSameDay(cal, targetDate)) {
                        allEvents.add(IcsEvent(obj.getString("genre"), startTime, startTime, obj.getString("location")))
                    }
                }
            } catch (e: Exception) {}
        }

        // 2. Load External ICS from Cache
        val timetablePrefs = requireContext().getSharedPreferences("TimetablePrefs", Context.MODE_PRIVATE)
        val icsJson = timetablePrefs.getString("icsCacheJSON", null)
        if (icsJson != null) {
            try {
                val jsonArray = JSONArray(icsJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val startTime = obj.getLong("startTime")
                    val cal = Calendar.getInstance().apply { timeInMillis = startTime }
                    if (isSameDay(cal, targetDate)) {
                        allEvents.add(IcsEvent(
                            obj.getString("summary"),
                            startTime,
                            obj.getLong("endTime"),
                            obj.getString("location")
                        ))
                    }
                }
            } catch (e: Exception) {}
        }
        
        return allEvents.sortedBy { it.startTime }
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
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
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun recordCompletedTasksToSchedule(tasks: List<TaskItem>) {
        val prefs = requireContext().getSharedPreferences("SchedulePrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("eventListJSON", null) ?: "[]"
        val jsonArray = JSONArray(jsonString)
        
        val now = System.currentTimeMillis()
        
        tasks.forEach { task ->
            val obj = JSONObject().apply {
                put("id", "completed_task_${UUID.randomUUID()}")
                put("genre", "✅ ${task.title}") // タイトルにチェックマークを付けて区別
                put("startTime", now)
                put("location", "タスク完了")
                put("isAttendanceTracked", false)
                put("attendanceStatus", "NONE")
            }
            jsonArray.put(obj)
        }
        
        prefs.edit().putString("eventListJSON", jsonArray.toString()).apply()
        
        // スケジュール画面にも通知
        triggerNotificationRefresh()
    }

    private fun saveTasks() {
        val prefs = requireContext().getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val jsonArray = JSONArray()
        for (item in taskList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("deadlineMillis", item.deadlineMillis)
                put("basePriority", item.basePriority)
                put("linkedEventId", item.linkedEventId)
                put("isCompleted", item.isCompleted)
            }
            jsonArray.put(obj)
        }
        prefs.edit().putString("taskListJSON", jsonArray.toString()).apply()
        triggerNotificationRefresh() // Added
    }

    private fun triggerNotificationRefresh() {
        val intent = android.content.Intent(requireContext(), AlarmReceiver::class.java).apply {
            action = "SCHEDULE_NOTIFICATIONS"
        }
        requireContext().sendBroadcast(intent)
    }

    private fun addExpForTasks(tasks: List<TaskItem>) {
        var totalGain = 0L
        tasks.forEach { task ->
            // 基本EXP: 優先度1=20, 2=40, 3=60, 4=80, 5=100
            val baseExp = task.basePriority * 20L
            
            // 重要度1 (低) のものをこなすのは偉いのでレベルボーナス (+50)
            val bonus = if (task.basePriority == 1) 50L else 0L
            
            totalGain += (baseExp + bonus)
        }

        val prefs = requireContext().getSharedPreferences("PlayerPrefs", Context.MODE_PRIVATE)
        val currentExp = prefs.getLong("totalExp", 0L)
        prefs.edit().putLong("totalExp", currentExp + totalGain).apply()
    }

    private fun loadTasks() {
        val prefs = requireContext().getSharedPreferences("TodoPrefs", Context.MODE_PRIVATE)
        val jsonString = prefs.getString("taskListJSON", null)
        taskList.clear()
        if (jsonString != null) {
            try {
                val jsonArray = JSONArray(jsonString)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    taskList.add(
                        TaskItem(
                            obj.getString("id"),
                            obj.getString("title"),
                            obj.getLong("deadlineMillis"),
                            obj.getInt("basePriority"),
                            obj.optString("linkedEventId", ""),
                            obj.optBoolean("isCompleted", false)
                        )
                    )
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}