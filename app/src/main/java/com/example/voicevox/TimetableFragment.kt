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

        pagerAdapter = SchedulePagerAdapter(this)
        binding.timetableViewPager.adapter = pagerAdapter
        binding.timetableViewPager.setCurrentItem(pagerAdapter.centerPosition, false)

        updateDateText()
        
        binding.prevDayButton.setOnClickListener {
            binding.timetableViewPager.setCurrentItem(binding.timetableViewPager.currentItem - 1, true)
        }

        binding.nextDayButton.setOnClickListener {
            binding.timetableViewPager.setCurrentItem(binding.timetableViewPager.currentItem + 1, true)
        }

        binding.btnResetToToday.setOnClickListener {
            binding.timetableViewPager.setCurrentItem(pagerAdapter.centerPosition, true)
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

        binding.timetableViewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                selectedDate = pagerAdapter.getDateForPosition(position)
                updateDateText()
            }
        })

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

    private fun checkAttendanceFeature() {
        val attendancePrefs = requireContext().getSharedPreferences(CuraConstants.PREFS_ATTENDANCE, Context.MODE_PRIVATE)
        val allKeys = attendancePrefs.all.keys
        
        val hasTrackedExternal = allKeys.any { it.startsWith("track_") && attendancePrefs.getBoolean(it, false) }
        val hasTrackedCustom = customEvents.any { it.isAttendanceTracked }
        val hasManualCount = allKeys.any { it.startsWith("absent_") && (attendancePrefs.all[it] as? Int ?: 0) > 0 }
        
        binding.btnAttendanceStats.visibility = if (hasTrackedExternal || hasTrackedCustom || hasManualCount) View.VISIBLE else View.GONE
    }

    private fun updateDateText() {
        val sdf = SimpleDateFormat("yyyy年M月d日 (E)", Locale.JAPAN)
        binding.selectedDateText.text = sdf.format(selectedDate.time)
    }

    private fun showDatePicker() {
        android.app.DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val newDate = Calendar.getInstance().apply {
                    set(year, month, dayOfMonth)
                }
                val position = pagerAdapter.getPositionForDate(newDate)
                binding.timetableViewPager.setCurrentItem(position, false)
            },
            selectedDate.get(Calendar.YEAR),
            selectedDate.get(Calendar.MONTH),
            selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun initialLoad() {
        val sources = loadCalendarSources()
        if (sources.isNotEmpty()) fetchAndCacheAllIcs(sources) else notifyFragments()
    }

    fun notifyFragments() {
        pagerAdapter.refresh()
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

                val newEvent = ScheduleEvent(
                    id = java.util.UUID.randomUUID().toString(),
                    summary = genre,
                    startTime = cal.timeInMillis,
                    location = dialogBinding.editEventLocation.text.toString(),
                    isAttendanceTracked = dialogBinding.checkTrackAttendance.isChecked
                )

                customEvents.add(newEvent)
                saveCustomEvents()

                if (dialogBinding.checkSaveAsPreset.isChecked) {
                    val currentPresets = try {
                        Json.decodeFromString<List<EventPreset>>(schedulePrefs.getString("presetListJSON", "[]") ?: "[]").toMutableList()
                    } catch (e: Exception) { mutableListOf() }
                    
                    currentPresets.add(EventPreset(genre, dialogBinding.editEventLocation.text.toString(), selectedHour, selectedMinute))
                    schedulePrefs.edit().putString("presetListJSON", Json.encodeToString(currentPresets)).apply()
                }
                
                Toast.makeText(ctx, "予定を追加しました", Toast.LENGTH_SHORT).show()
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

    fun getDailyData(date: Calendar): List<ScheduleDisplayItem> {
        val rawEvents = mutableListOf<ScheduleItem>()
        loadTasksForDate(date, rawEvents)
        addCustomEventsForDate(date, rawEvents)
        addCachedIcsEventsForDate(date, rawEvents)
        addDeviceEventsForDate(date, rawEvents)

        val filteredEvents = when (currentFilter) {
            FilterType.EXTERNAL -> rawEvents.filter { it.type == "📅 外部予定" || it.type == "🎓 予定" || it.type == "📱 端末予定" }
            FilterType.TASK -> rawEvents.filter { it.type == "📝 タスク" }
            FilterType.COMPLETED -> rawEvents.filter { it.type == "✅ 完了報告" }
            FilterType.ALL -> rawEvents.filter { it.type != "✅ 完了報告" }
        }

        val sortedEvents = filteredEvents.sortedBy { it.sortTime }
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
                FilterType.EXTERNAL -> "予定・授業一覧"; FilterType.TASK -> "本日のタスク"; FilterType.COMPLETED -> "本日の実績"; else -> ""
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
            val displayType = if (event.id.startsWith("completed_task_")) "✅ 完了報告" else "🎓 予定"
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
                val taskDate = Calendar.getInstance().apply { timeInMillis = task.deadlineMillis }
                if (taskDate.get(Calendar.YEAR) == targetDate.get(Calendar.YEAR) && taskDate.get(Calendar.DAY_OF_YEAR) == targetDate.get(Calendar.DAY_OF_YEAR)) {
                    val timeStr = sdfTime.format(Date(task.deadlineMillis))
                    val sortTime = taskDate.get(Calendar.HOUR_OF_DAY).toLong() * 60 + taskDate.get(Calendar.MINUTE)
                    list.add(ScheduleItem("", timeStr, task.title, "📝 タスク", sortTime))
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
                "ext_${event.summary}_${event.startTime}", timeStr, event.summary, "📅 外部予定", sortTime, event.location, 
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
                type = "📱 端末予定",
                sortTime = sortTime,
                location = event.location,
                isCustom = false,
                startTimeMillis = event.startTime
            ))
        }
    }

    fun showDeleteConfirmDialog(item: ScheduleItem) {
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
                else if (item.type == "📅 外部予定") (parentFragment as? TimetableFragment)?.showExternalTrackingDialog(item)
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
