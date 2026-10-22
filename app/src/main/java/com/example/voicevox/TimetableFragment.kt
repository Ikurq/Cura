package com.example.voicevox

import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.example.voicevox.databinding.DialogAddScheduleEventBinding
import com.example.voicevox.databinding.FragmentTimetableBinding
import com.example.voicevox.databinding.ItemScheduleCardBinding
import com.example.voicevox.databinding.ItemScheduleHeaderBinding
import com.google.android.material.chip.Chip
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

class TimetableFragment : Fragment() {

    private var _binding: FragmentTimetableBinding? = null
    private val binding get() = _binding!!

    enum class ViewMode { MONTH, DAY }
    private var currentViewMode = ViewMode.MONTH
    private lateinit var monthPagerAdapter: MonthPagerAdapter
    private var selectedMonthCal = Calendar.getInstance()

    private lateinit var pagerAdapter: SchedulePagerAdapter
    private var selectedDate = Calendar.getInstance()
    private var cachedIcsEvents = mutableListOf<IcsEvent>()
    private val customEvents = mutableListOf<ScheduleEvent>()

    enum class FilterType { ALL, EXTERNAL, TASK, COMPLETED }
    private var currentFilter = FilterType.ALL

    sealed class ScheduleDisplayItem {
        data class Header(val title: String) : ScheduleDisplayItem()
        data class Event(val item: ScheduleItem) : ScheduleDisplayItem()
        object EmptyPlaceholder : ScheduleDisplayItem()
    }

    data class ScheduleItem(
        val id: String,
        val time: String,
        val title: String,
        val type: String,
        val sortTime: Long,
        val location: String = "",
        val isCustom: Boolean = false,
        val isAttendanceTracked: Boolean = false,
        val attendanceStatus: String = "NONE",
        val startTimeMillis: Long = 0L
    )

    data class CalendarSource(val name: String, val url: String)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentTimetableBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val appPrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        val defaultView = appPrefs.getString(CuraConstants.KEY_DEFAULT_TIMETABLE_VIEW, CuraConstants.VIEW_MODE_MONTH)
        currentViewMode = if (defaultView == CuraConstants.VIEW_MODE_DAY) ViewMode.DAY else ViewMode.MONTH

        monthPagerAdapter = MonthPagerAdapter(this)
        binding.monthViewPager.adapter = monthPagerAdapter
        binding.monthViewPager.setCurrentItem(monthPagerAdapter.centerPosition, false)

        binding.monthViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedMonthCal = monthPagerAdapter.getMonthForPosition(position)
                if (currentViewMode == ViewMode.MONTH) {
                    updateDateText()
                }
            }
        })

        pagerAdapter = SchedulePagerAdapter(this)
        binding.timetableViewPager.adapter = pagerAdapter
        binding.timetableViewPager.setCurrentItem(pagerAdapter.centerPosition, false)

        binding.timetableViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedDate = pagerAdapter.getDateForPosition(position)
                if (currentViewMode == ViewMode.DAY) {
                    updateDateText()
                }
            }
        })

        applyViewMode(currentViewMode)
        
        binding.prevDayButton.setOnClickListener {
            if (currentViewMode == ViewMode.MONTH) {
                binding.monthViewPager.setCurrentItem(binding.monthViewPager.currentItem - 1, true)
            } else {
                binding.timetableViewPager.setCurrentItem(binding.timetableViewPager.currentItem - 1, true)
            }
        }

        binding.nextDayButton.setOnClickListener {
            if (currentViewMode == ViewMode.MONTH) {
                binding.monthViewPager.setCurrentItem(binding.monthViewPager.currentItem + 1, true)
            } else {
                binding.timetableViewPager.setCurrentItem(binding.timetableViewPager.currentItem + 1, true)
            }
        }

        binding.btnResetToToday.setOnClickListener {
            selectedDate = Calendar.getInstance()
            selectedMonthCal = Calendar.getInstance()
            binding.monthViewPager.setCurrentItem(monthPagerAdapter.centerPosition, true)
            binding.timetableViewPager.setCurrentItem(pagerAdapter.centerPosition, true)
            updateDateText()
        }

        binding.btnToggleViewMode.setOnClickListener {
            val nextMode = if (currentViewMode == ViewMode.MONTH) ViewMode.DAY else ViewMode.MONTH
            applyViewMode(nextMode)
        }

        binding.selectedDateText.setOnClickListener {
            showDatePicker()
        }

        binding.addScheduleEventFAB.setOnClickListener {
            showAddEventDialog()
        }

        binding.btnAttendanceStats.setOnClickListener {
            (activity as? MainActivity)?.let { main ->
                main.supportFragmentManager.beginTransaction()
                    .replace(R.id.fragmentContainer, AttendanceManagerFragment())
                    .addToBackStack(null)
                    .commit()
                main.findViewById<androidx.appcompat.widget.Toolbar>(R.id.toolbar).title = "出欠管理カウンター"
            }
        }

        loadIcsCache()
        loadCustomEvents()
        initialLoad()
        checkAttendanceFeature()

        binding.filterChipGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            currentFilter = when (checkedIds.firstOrNull()) {
                R.id.chipFilterExternal -> FilterType.EXTERNAL
                R.id.chipFilterTask -> FilterType.TASK
                R.id.chipFilterCompleted -> FilterType.COMPLETED
                else -> FilterType.ALL
            }
            notifyFragments()
        }
    }

    fun applyViewMode(mode: ViewMode) {
        currentViewMode = mode
        if (mode == ViewMode.MONTH) {
            binding.monthViewPager.visibility = View.VISIBLE
            binding.timetableViewPager.visibility = View.GONE
            binding.dayOfWeekHeaderLayout.visibility = View.VISIBLE
            binding.btnToggleViewMode.setImageResource(R.drawable.ic_mode_day)
            val monthPos = monthPagerAdapter.getPositionForDate(selectedDate)
            binding.monthViewPager.setCurrentItem(monthPos, false)
        } else {
            binding.monthViewPager.visibility = View.GONE
            binding.timetableViewPager.visibility = View.VISIBLE
            binding.dayOfWeekHeaderLayout.visibility = View.GONE
            binding.btnToggleViewMode.setImageResource(R.drawable.ic_mode_month)
            val dayPos = pagerAdapter.getPositionForDate(selectedDate)
            binding.timetableViewPager.setCurrentItem(dayPos, false)
        }
        updateDateText()
    }

    fun onMonthDayClicked(day: MonthDay) {
        val targetCal = Calendar.getInstance().apply {
            timeInMillis = day.startOfDayMillis
        }
        selectedDate = targetCal
        applyViewMode(ViewMode.DAY)
    }

    private fun checkAttendanceFeature() {
        val attendancePrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        val allKeys = attendancePrefs.all.keys
        
        val hasTrackedExternal = allKeys.any { it.startsWith("track_") && attendancePrefs.getBoolean(it, false) }
        val hasTrackedCustom = customEvents.any { it.isAttendanceTracked }
        val hasManualCount = allKeys.any { it.startsWith("absent_") && (attendancePrefs.all[it] as? Int ?: 0) > 0 }
        
        binding.btnAttendanceStats.visibility = if (hasTrackedExternal || hasTrackedCustom || hasManualCount) View.VISIBLE else View.GONE
    }

    private fun updateDateText() {
        if (currentViewMode == ViewMode.MONTH) {
            val year = selectedMonthCal.get(Calendar.YEAR)
            val month = selectedMonthCal.get(Calendar.MONTH)
            binding.selectedDateText.text = MonthCalendarHelper.formatYearMonthTitle(year, month)
        } else {
            val sdf = SimpleDateFormat("yyyy年M月d日 (E)", Locale.JAPAN)
            binding.selectedDateText.text = sdf.format(selectedDate.time)
        }
    }

    private fun showDatePicker() {
        val initialCal = if (currentViewMode == ViewMode.MONTH) selectedMonthCal else selectedDate
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val newDate = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                selectedDate = newDate
                selectedMonthCal = (newDate.clone() as Calendar)
                if (currentViewMode == ViewMode.MONTH) {
                    val position = monthPagerAdapter.getPositionForDate(newDate)
                    binding.monthViewPager.setCurrentItem(position, false)
                } else {
                    val position = pagerAdapter.getPositionForDate(newDate)
                    binding.timetableViewPager.setCurrentItem(position, false)
                }
                updateDateText()
            },
            initialCal.get(Calendar.YEAR),
            initialCal.get(Calendar.MONTH),
            initialCal.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun initialLoad() {
        val sources = loadCalendarSources()
        if (sources.isNotEmpty()) fetchAndCacheAllIcs(sources) else notifyFragments()
    }

    fun notifyFragments() {
        if (::pagerAdapter.isInitialized) pagerAdapter.refresh()
        if (::monthPagerAdapter.isInitialized) monthPagerAdapter.refresh()
    }

    private fun loadCustomEvents() {
        val prefs = requireContext().getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_EVENT_LIST, null) ?: return
        customEvents.clear()
        try {
            customEvents.addAll(Json.decodeFromString<List<ScheduleEvent>>(jsonString))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun saveCustomEvents() {
        val prefs = requireContext().getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val json = Json.encodeToString(customEvents)
        prefs.edit().putString(CuraConstants.KEY_EVENT_LIST, json).apply()
        notifyFragments()
    }

    private fun showAddEventDialog() {
        val ctx = requireContext()
        val dialogBinding = DialogAddScheduleEventBinding.inflate(LayoutInflater.from(ctx))
        
        dialogBinding.btnToggleAdvancedSettings.setOnClickListener {
            if (dialogBinding.layoutAdvancedSettings.visibility == View.GONE) {
                dialogBinding.layoutAdvancedSettings.visibility = View.VISIBLE
                dialogBinding.btnToggleAdvancedSettings.text = "追加の設定 ▲"
            } else {
                dialogBinding.layoutAdvancedSettings.visibility = View.GONE
                dialogBinding.btnToggleAdvancedSettings.text = "追加の設定 ▼"
            }
        }

        var selectedHour = 9
        var selectedMinute = 0

        dialogBinding.btnSelectEventTime.setOnClickListener {
            TimePickerHelper.showWheelTimePicker(ctx, selectedHour, selectedMinute) { h, m ->
                selectedHour = h
                selectedMinute = m
                dialogBinding.btnSelectEventTime.text = String.format(Locale.getDefault(), "時刻：%02d:%02d", h, m)
            }
        }

        // 繰り返し設定の初期化
        val sdfDate = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
        val dayOfWeekNames = arrayOf("日", "月", "火", "水", "木", "金", "土")
        val dayName = dayOfWeekNames[selectedDate.get(Calendar.DAY_OF_WEEK) - 1]
        dialogBinding.checkRepeatWeekly.text = "毎週${dayName}曜日に繰り返す"

        val repeatEndCal = ScheduleRepeatHelper.calculateFiscalYearEnd(selectedDate)
        fun updateRepeatEndDateText() {
            dialogBinding.btnSelectRepeatEndDate.text = String.format(Locale.getDefault(), "%s (タップで変更)", sdfDate.format(repeatEndCal.time))
        }
        updateRepeatEndDateText()

        dialogBinding.checkRepeatWeekly.setOnCheckedChangeListener { _, isChecked ->
            dialogBinding.layoutRepeatEndDetails.visibility = if (isChecked) View.VISIBLE else View.GONE
        }

        dialogBinding.btnSelectRepeatEndDate.setOnClickListener {
            android.app.DatePickerDialog(
                ctx,
                { _, y, m, d ->
                    repeatEndCal.set(Calendar.YEAR, y)
                    repeatEndCal.set(Calendar.MONTH, m)
                    repeatEndCal.set(Calendar.DAY_OF_MONTH, d)
                    repeatEndCal.set(Calendar.HOUR_OF_DAY, 23)
                    repeatEndCal.set(Calendar.MINUTE, 59)
                    repeatEndCal.set(Calendar.SECOND, 59)
                    repeatEndCal.set(Calendar.MILLISECOND, 999)
                    updateRepeatEndDateText()
                },
                repeatEndCal.get(Calendar.YEAR),
                repeatEndCal.get(Calendar.MONTH),
                repeatEndCal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }

        val schedulePrefs = ctx.getSharedPreferences(CuraConstants.PREFS_SCHEDULE, Context.MODE_PRIVATE)
        val presetJson = schedulePrefs.getString("presetListJSON", null)
        if (presetJson != null) {
            try {
                val presets = Json.decodeFromString<List<EventPreset>>(presetJson)
                presets.forEach { preset ->
                    val chip = Chip(ctx)
                    chip.text = preset.genre
                    chip.setOnClickListener {
                        dialogBinding.editEventGenre.setText(preset.genre)
                        dialogBinding.editEventLocation.setText(preset.location)
                        if (preset.hour != -1) {
                            selectedHour = preset.hour; selectedMinute = preset.minute
                            dialogBinding.btnSelectEventTime.text = String.format(Locale.getDefault(), "時刻：%02d:%02d", selectedHour, selectedMinute)
                        }
                    }
                    dialogBinding.presetChipGroup.addView(chip)
                }
            } catch (e: Exception) {}
        }

        AlertDialog.Builder(ctx)
            .setView(dialogBinding.root)
            .setPositiveButton("追加") { _, _ ->
                val genre = dialogBinding.editEventGenre.text.toString()
                if (genre.isEmpty()) return@setPositiveButton

                val cal = (selectedDate.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, selectedHour)
                    set(Calendar.MINUTE, selectedMinute)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }

                val eventsToAdd = if (dialogBinding.checkRepeatWeekly.isChecked) {
                    ScheduleRepeatHelper.generateWeeklyOccurrences(
                        summary = genre,
                        startCal = cal,
                        endCal = repeatEndCal,
                        location = dialogBinding.editEventLocation.text.toString(),
                        isAttendanceTracked = dialogBinding.checkTrackAttendance.isChecked
                    )
                } else {
                    listOf(
                        ScheduleEvent(
                            id = java.util.UUID.randomUUID().toString(),
                            summary = genre,
                            startTime = cal.timeInMillis,
                            location = dialogBinding.editEventLocation.text.toString(),
                            isAttendanceTracked = dialogBinding.checkTrackAttendance.isChecked
                        )
                    )
                }

                customEvents.addAll(eventsToAdd)
                saveCustomEvents()

                if (dialogBinding.checkSaveAsPreset.isChecked) {
                    val currentPresets = try {
                        Json.decodeFromString<List<EventPreset>>(schedulePrefs.getString("presetListJSON", "[]") ?: "[]").toMutableList()
                    } catch (e: Exception) { mutableListOf() }
                    
                    currentPresets.add(EventPreset(genre, dialogBinding.editEventLocation.text.toString(), selectedHour, selectedMinute))
                    schedulePrefs.edit().putString("presetListJSON", Json.encodeToString(currentPresets)).apply()
                }
                
                val toastMsg = if (eventsToAdd.size > 1) {
                    "予定を毎週繰り返しで追加しました（計" + eventsToAdd.size + "件）"
                } else {
                    "予定を追加しました"
                }
                Toast.makeText(ctx, toastMsg, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

        private fun fetchAndCacheAllIcs(sources: List<CalendarSource>) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val allEvents = mutableListOf<IcsEvent>()
            val jobs = sources.map { source ->
                launch {
                    try {
                        val lines = URL(source.url).openConnection().getInputStream().use { stream ->
                            BufferedReader(InputStreamReader(stream)).readLines()
                        }
                        synchronized(allEvents) { allEvents.addAll(IcsParser().parse(lines)) }
                    } catch (e: Exception) { e.printStackTrace() }
                }
            }
            jobs.joinAll()
            cachedIcsEvents.clear(); cachedIcsEvents.addAll(allEvents)
            saveIcsCache(allEvents)
            withContext(Dispatchers.Main) {
                notifyFragments()
            }
        }
    }

    private fun saveIcsCache(events: List<IcsEvent>) {
        val prefs = requireContext().getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
        val json = Json.encodeToString(events)
        prefs.edit().putString(CuraConstants.KEY_ICS_CACHE, json).apply()
    }

    private fun loadIcsCache() {
        val prefs = requireContext().getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_ICS_CACHE, null) ?: return
        cachedIcsEvents.clear()
        try {
            cachedIcsEvents.addAll(Json.decodeFromString<List<IcsEvent>>(jsonString))
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun loadCalendarSources(): List<CalendarSource> {
        val prefs = requireContext().getSharedPreferences(CuraConstants.PREFS_TIMETABLE, Context.MODE_PRIVATE)
        val jsonString = prefs.getString("calendarSourcesJSON", null) ?: return emptyList()
        val list = mutableListOf<CalendarSource>()
        try {
            val jsonArray = JSONArray(jsonString)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(CalendarSource(obj.getString("name"), obj.getString("url")))
            }
        } catch (e: Exception) {}
        return list
    }

    fun getRawEventsForDate(date: Calendar): List<ScheduleItem> {
        val rawEvents = mutableListOf<ScheduleItem>()
        loadTasksForDate(date, rawEvents)
        addCustomEventsForDate(date, rawEvents)
        addCachedIcsEventsForDate(date, rawEvents)
        addDeviceEventsForDate(date, rawEvents)

        val filteredEvents = when (currentFilter) {
            FilterType.EXTERNAL -> rawEvents.filter { it.type == "外部予定" || it.type == "予定" || it.type == "端末予定" }
            FilterType.TASK -> rawEvents.filter { it.type == "タスク" }
            FilterType.COMPLETED -> rawEvents.filter { it.type == "完了報告" }
            FilterType.ALL -> rawEvents.filter { it.type != "完了報告" }
        }

        return filteredEvents.sortedBy { it.sortTime }
    }

    fun getDailyData(date: Calendar): List<ScheduleDisplayItem> {
        val sortedEvents = getRawEventsForDate(date)
        val displayList = mutableListOf<ScheduleDisplayItem>()
        
        if (currentFilter == FilterType.ALL) {
            val amEvents = sortedEvents.filter { it.sortTime < 12 * 60 }
            val pmEvents = sortedEvents.filter { it.sortTime >= 12 * 60 }
            displayList.add(ScheduleDisplayItem.Header("午前 (AM)"))
            if (amEvents.isEmpty()) displayList.add(ScheduleDisplayItem.EmptyPlaceholder) else amEvents.forEach { displayList.add(ScheduleDisplayItem.Event(it)) }
            displayList.add(ScheduleDisplayItem.Header("午後 (PM)"))
            if (pmEvents.isEmpty()) displayList.add(ScheduleDisplayItem.EmptyPlaceholder) else pmEvents.forEach { displayList.add(ScheduleDisplayItem.Event(it)) }
        } else {
            val headerTitle = when(currentFilter) {
                FilterType.EXTERNAL -> "予定一覧"; FilterType.TASK -> "本日のタスク"; FilterType.COMPLETED -> "本日の実績"; else -> ""
            }
            displayList.add(ScheduleDisplayItem.Header(headerTitle))
            if (sortedEvents.isEmpty()) displayList.add(ScheduleDisplayItem.EmptyPlaceholder) else sortedEvents.forEach { displayList.add(ScheduleDisplayItem.Event(it)) }
        }
        return displayList
    }

    private fun addCustomEventsForDate(targetDate: Calendar, list: MutableList<ScheduleItem>) {
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        customEvents.filter { event ->
            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            cal.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR) && cal.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR)
        }.forEach { event ->
            val timeStr = sdfTime.format(Date(event.startTime))
            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
            var status = event.attendanceStatus
            if (event.isAttendanceTracked && status == "NONE" && event.startTime < System.currentTimeMillis()) status = "ATTEND"
            val displayType = if (event.id.startsWith("completed_task_")) "完了報告" else "予定"
            list.add(ScheduleItem(
                event.id, timeStr, event.summary, displayType, sortTime, event.location, true,
                isAttendanceTracked = event.isAttendanceTracked, attendanceStatus = status, startTimeMillis = event.startTime
            ))
        }
    }

    private fun loadTasksForDate(targetDate: Calendar, list: MutableList<ScheduleItem>) {
        val ctx = context ?: return
        val prefs = ctx.getSharedPreferences(CuraConstants.PREFS_TODO, Context.MODE_PRIVATE)
        val jsonString = prefs.getString(CuraConstants.KEY_TASK_LIST, null) ?: return
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        try {
            val tasks = Json.decodeFromString<List<TaskItem>>(jsonString)
            tasks.forEach { task ->
                val realDate = Calendar.getInstance().apply { timeInMillis = task.deadlineMillis }
                val isRealDay = realDate.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR) &&
                        realDate.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR)

                val targetDateCal = task.targetDeadlineMillis?.let { Calendar.getInstance().apply { timeInMillis = it } }
                val isTargetDay = targetDateCal != null &&
                        targetDateCal.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR) &&
                        targetDateCal.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR)

                if (isTargetDay && isRealDay) {
                    val timeStr = sdfTime.format(Date(task.targetDeadlineMillis!!))
                    val sortTime = targetDateCal!!.get(Calendar.HOUR_OF_DAY).toLong() * 60 + targetDateCal.get(Calendar.MINUTE)
                    list.add(ScheduleItem("", timeStr, "[目標・期限] ${task.title}", "タスク", sortTime))
                } else if (isTargetDay) {
                    val timeStr = sdfTime.format(Date(task.targetDeadlineMillis!!))
                    val sortTime = targetDateCal!!.get(Calendar.HOUR_OF_DAY).toLong() * 60 + targetDateCal.get(Calendar.MINUTE)
                    list.add(ScheduleItem("", timeStr, "[目標] ${task.title}", "タスク", sortTime))
                } else if (isRealDay) {
                    val timeStr = sdfTime.format(Date(task.deadlineMillis))
                    val sortTime = realDate.get(Calendar.HOUR_OF_DAY).toLong() * 60 + realDate.get(Calendar.MINUTE)
                    val label = if (task.targetDeadlineMillis != null) "[最終期限] ${task.title}" else task.title
                    list.add(ScheduleItem("", timeStr, label, "タスク", sortTime))
                }
            }
        } catch (e: Exception) {}
    }

    private fun addCachedIcsEventsForDate(targetDate: Calendar, list: MutableList<ScheduleItem>) {
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        val filtered = cachedIcsEvents.filter { event ->
            val eventCal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            eventCal.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR) && eventCal.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR)
        }
        val attendancePrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        filtered.forEach { event ->
            val timeStr = sdfTime.format(Date(event.startTime))
            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
            val isTracked = attendancePrefs.getBoolean("track_${event.summary}", false)
            val dayKey = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            var status = if (isTracked) attendancePrefs.getString("status_${event.summary}_$dayKey", "NONE") ?: "NONE" else "NONE"
            if (isTracked && status == "NONE" && event.startTime < System.currentTimeMillis()) status = "ATTEND"
            list.add(ScheduleItem(
                "ext_${event.summary}_${event.startTime}", timeStr, event.summary, "外部予定", sortTime, event.location, 
                isCustom = false, isAttendanceTracked = isTracked, attendanceStatus = status, startTimeMillis = event.startTime
            ))
        }
    }

    private fun addDeviceEventsForDate(targetDate: Calendar, list: MutableList<ScheduleItem>) {
        val appPrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_APP, Context.MODE_PRIVATE)
        if (!appPrefs.getBoolean("sync_device_calendar", false)) return

        val deviceEvents = DeviceCalendarLoader.loadDeviceEvents(requireContext(), targetDate)
        val sdfTime = SimpleDateFormat("HH:mm", Locale.getDefault())
        
        deviceEvents.forEach { event ->
            val timeStr = sdfTime.format(Date(event.startTime))
            val cal = Calendar.getInstance().apply { timeInMillis = event.startTime }
            val sortTime = cal.get(Calendar.HOUR_OF_DAY).toLong() * 60 + cal.get(Calendar.MINUTE)
            
            list.add(ScheduleItem(
                id = "dev_${event.summary}_${event.startTime}",
                time = timeStr,
                title = event.summary,
                type = "端末予定",
                sortTime = sortTime,
                location = event.location,
                isCustom = false,
                startTimeMillis = event.startTime
            ))
        }
    }

    fun showDeleteConfirmDialog(item: ScheduleItem) {
        val targetEvent = customEvents.find { it.id == item.id }
        if (targetEvent?.repeatGroupId != null) {
            val options = arrayOf(
                "この予定のみ削除",
                "これ以降の繰り返し予定を削除",
                "すべての繰り返し予定を削除"
            )
            AlertDialog.Builder(requireContext())
                .setTitle("予定の削除")
                .setItems(options) { _, which ->
                    when (which) {
                        0 -> {
                            customEvents.removeAll { it.id == item.id }
                            saveCustomEvents()
                            Toast.makeText(requireContext(), "予定を削除しました", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            val count = customEvents.count { it.repeatGroupId == targetEvent.repeatGroupId && it.startTime >= targetEvent.startTime }
                            customEvents.removeAll { it.repeatGroupId == targetEvent.repeatGroupId && it.startTime >= targetEvent.startTime }
                            saveCustomEvents()
                            Toast.makeText(requireContext(), "これ以降の予定（${count}件）を削除しました", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            val count = customEvents.count { it.repeatGroupId == targetEvent.repeatGroupId }
                            customEvents.removeAll { it.repeatGroupId == targetEvent.repeatGroupId }
                            saveCustomEvents()
                            Toast.makeText(requireContext(), "繰り返しの予定（${count}件）をすべて削除しました", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
        } else {
            AlertDialog.Builder(requireContext())
                .setTitle("予定の削除")
                .setMessage("「${item.title}」を削除しますか？")
                .setPositiveButton("削除") { _, _ ->
                    customEvents.removeAll { it.id == item.id }
                    saveCustomEvents()
                    Toast.makeText(requireContext(), "削除しました", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("キャンセル", null)
                .show()
        }
    }

    fun showExternalTrackingDialog(item: ScheduleItem) {
        val attendancePrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        val isTracked = attendancePrefs.getBoolean("track_${item.title}", false)

        AlertDialog.Builder(requireContext())
            .setTitle("出欠管理の連携")
            .setMessage("この外部予定（${item.title}）を出欠管理カウンターと連携させますか？")
            .setPositiveButton(if (isTracked) "連携解除" else "連携する") { _, _ ->
                attendancePrefs.edit().putBoolean("track_${item.title}", !isTracked).apply()
                notifyFragments()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    fun showAttendanceEditDialog(item: ScheduleItem) {
        val statuses = arrayOf("出席", "欠席", "遅刻", "早退", "未設定")
        val statusValues = arrayOf("ATTEND", "ABSENT", "LATE", "EARLY", "NONE")
        val currentIndex = statusValues.indexOf(item.attendanceStatus).coerceAtLeast(4)

        AlertDialog.Builder(requireContext())
            .setTitle("出欠状況の変更")
            .setSingleChoiceItems(statuses, currentIndex) { dialog, which ->
                updateAttendance(item, statusValues[which])
                dialog.dismiss()
            }
            .show()
    }

    fun updateAttendance(item: ScheduleItem, status: String) {
        if (item.isCustom) {
            val event = customEvents.find { it.id == item.id }
            if (event != null) {
                event.attendanceStatus = status
                saveCustomEvents()
            }
        } else {
            val attendancePrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)
            val cal = Calendar.getInstance().apply { timeInMillis = item.startTimeMillis }
            val dayKey = String.format("%04d-%02d-%02d", cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))
            attendancePrefs.edit().putString("status_${item.title}_$dayKey", status).apply()
            
            val currentAbsent = attendancePrefs.getInt("absent_${item.title}", 0)
            if (status == "ABSENT") {
                attendancePrefs.edit().putInt("absent_${item.title}", currentAbsent + 1).apply()
            } else if (item.attendanceStatus == "ABSENT") {
                attendancePrefs.edit().putInt("absent_${item.title}", (currentAbsent - 1).coerceAtLeast(0)).apply()
            }
            notifyFragments()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private inner class MonthPagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        val centerPosition = 10000
        private val baseCal = Calendar.getInstance()
        private var refreshId = 0L

        override fun getItemId(position: Int): Long = (position.toLong() shl 32) or (refreshId and 0xFFFFFFFFL)
        override fun containsItem(itemId: Long): Boolean = (itemId and 0xFFFFFFFFL) == (refreshId and 0xFFFFFFFFL)
        override fun getItemCount(): Int = centerPosition * 2

        override fun createFragment(position: Int): Fragment {
            val (year, month) = MonthCalendarHelper.getYearMonthForPosition(position, centerPosition, baseCal)
            return MonthPageFragment.newInstance(year, month)
        }

        fun getMonthForPosition(position: Int): Calendar {
            val (year, month) = MonthCalendarHelper.getYearMonthForPosition(position, centerPosition, baseCal)
            return (baseCal.clone() as Calendar).apply {
                set(Calendar.YEAR, year)
                set(Calendar.MONTH, month)
                set(Calendar.DAY_OF_MONTH, 1)
            }
        }

        fun getPositionForDate(date: Calendar): Int {
            return MonthCalendarHelper.getPositionForYearMonth(date.get(Calendar.YEAR), date.get(Calendar.MONTH), centerPosition, baseCal)
        }

        fun refresh() {
            refreshId++
            notifyDataSetChanged()
        }
    }

    private inner class SchedulePagerAdapter(fragment: Fragment) : FragmentStateAdapter(fragment) {
        val centerPosition = Int.MAX_VALUE / 2
        private var refreshId = 0L
        override fun getItemId(position: Int): Long = (position.toLong() shl 32) or (refreshId and 0xFFFFFFFFL)
        override fun containsItem(itemId: Long): Boolean = (itemId and 0xFFFFFFFFL) == (refreshId and 0xFFFFFFFFL)
        override fun getItemCount(): Int = Int.MAX_VALUE
        override fun createFragment(position: Int): Fragment {
            val date = getDateForPosition(position)
            return DailyScheduleFragment.newInstance(date.timeInMillis)
        }
        fun getDateForPosition(position: Int): Calendar {
            val date = Calendar.getInstance()
            date.add(Calendar.DAY_OF_YEAR, position - centerPosition)
            return date
        }
        fun getPositionForDate(date: Calendar): Int {
            val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            val target = (date.clone() as Calendar).apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
            return centerPosition + ((target.timeInMillis - today.timeInMillis) / (1000 * 60 * 60 * 24)).toInt()
        }
        fun refresh() { refreshId++; notifyDataSetChanged() }
    }
}

class DailyScheduleFragment : Fragment() {
    companion object {
        fun newInstance(timeInMillis: Long): DailyScheduleFragment = DailyScheduleFragment().apply { arguments = Bundle().apply { putLong("date_millis", timeInMillis) } }
    }
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: DailyAdapter
    private val displayList = mutableListOf<TimetableFragment.ScheduleDisplayItem>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.fragment_daily_schedule, container, false)
        recyclerView = view.findViewById(R.id.dailyScheduleRecyclerView)
        setupRecyclerView()
        refreshData()
        return view
    }

    private fun setupRecyclerView() {
        adapter = DailyAdapter(displayList,
            onItemLongClick = { item ->
                if (item.isCustom) (parentFragment as? TimetableFragment)?.showDeleteConfirmDialog(item)
                else if (item.type == "外部予定") (parentFragment as? TimetableFragment)?.showExternalTrackingDialog(item)
            },
            onAttendanceChanged = { item, status -> (parentFragment as? TimetableFragment)?.updateAttendance(item, status) },
            onItemClick = { item -> if (item.isAttendanceTracked) (parentFragment as? TimetableFragment)?.showAttendanceEditDialog(item) }
        )
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = adapter
    }

    fun refreshData() {
        val dateMillis = arguments?.getLong("date_millis") ?: return
        val date = Calendar.getInstance().apply { timeInMillis = dateMillis }
        val parent = parentFragment as? TimetableFragment ?: return
        displayList.clear(); displayList.addAll(parent.getDailyData(date))
        adapter.notifyDataSetChanged()
    }

    private inner class DailyAdapter(
        private val items: List<TimetableFragment.ScheduleDisplayItem>,
        private val onItemLongClick: (TimetableFragment.ScheduleItem) -> Unit,
        private val onAttendanceChanged: (TimetableFragment.ScheduleItem, String) -> Unit,
        private val onItemClick: (TimetableFragment.ScheduleItem) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private val TYPE_HEADER = 0; private val TYPE_EVENT = 1; private val TYPE_EMPTY = 2
        override fun getItemViewType(position: Int): Int = when (items[position]) {
            is TimetableFragment.ScheduleDisplayItem.Header -> TYPE_HEADER
            is TimetableFragment.ScheduleDisplayItem.Event -> TYPE_EVENT
            is TimetableFragment.ScheduleDisplayItem.EmptyPlaceholder -> TYPE_EMPTY
        }
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder = when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(ItemScheduleHeaderBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            TYPE_EVENT -> EventViewHolder(ItemScheduleCardBinding.inflate(LayoutInflater.from(parent.context), parent, false))
            else -> EmptyViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_schedule_empty_placeholder, parent, false))
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = items[position]) {
                is TimetableFragment.ScheduleDisplayItem.Header -> (holder as HeaderViewHolder).bind(item.title)
                is TimetableFragment.ScheduleDisplayItem.Event -> (holder as EventViewHolder).bind(item.item, onItemLongClick, onAttendanceChanged, onItemClick)
                is TimetableFragment.ScheduleDisplayItem.EmptyPlaceholder -> {}
            }
        }
        override fun getItemCount() = items.size
    }
    private class HeaderViewHolder(val binding: ItemScheduleHeaderBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) { binding.headerTitleText.text = title }
    }
    private class EventViewHolder(val binding: ItemScheduleCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: TimetableFragment.ScheduleItem, onLongClick: (TimetableFragment.ScheduleItem) -> Unit, onAttendanceChanged: (TimetableFragment.ScheduleItem, String) -> Unit, onItemClick: (TimetableFragment.ScheduleItem) -> Unit) {
            binding.scheduleTimeText.text = item.time; binding.scheduleTitleText.text = item.title
            binding.scheduleTypeText.text = if (item.location.isNotEmpty()) "${item.type} @ ${item.location}" else item.type
            binding.root.setOnClickListener { onItemClick(item) }
            binding.root.setOnLongClickListener { onLongClick(item); true }
        }
    }
    private class EmptyViewHolder(view: View) : RecyclerView.ViewHolder(view)
}
