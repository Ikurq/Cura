package com.example.voicevox

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.voicevox.databinding.ItemMonthCalendarCellBinding
import java.util.Calendar

class MonthPageFragment : Fragment() {

    companion object {
        fun newInstance(year: Int, month: Int): MonthPageFragment = MonthPageFragment().apply {
            arguments = Bundle().apply {
                putInt("year", year)
                putInt("month", month)
            }
        }
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: MonthPageAdapter
    private val dayList = mutableListOf<MonthDay>()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_month_page, container, false)
        recyclerView = view.findViewById(R.id.monthRecyclerView)
        setupRecyclerView()
        refreshData()
        return view
    }

    private fun setupRecyclerView() {
        adapter = MonthPageAdapter(
            dayList,
            getEvents = { targetCal ->
                (parentFragment as? TimetableFragment)?.getRawEventsForDate(targetCal) ?: emptyList()
            },
            onDayClick = { day ->
                (parentFragment as? TimetableFragment)?.onMonthDayClicked(day)
            }
        )
        recyclerView.layoutManager = GridLayoutManager(requireContext(), 7)
        recyclerView.adapter = adapter
    }

    fun refreshData() {
        val year = arguments?.getInt("year") ?: return
        val month = arguments?.getInt("month") ?: return
        dayList.clear()
        dayList.addAll(MonthCalendarHelper.generateMonthDays(year, month))
        if (::adapter.isInitialized) {
            adapter.notifyDataSetChanged()
        }
    }
}

class MonthPageAdapter(
    private val days: List<MonthDay>,
    private val getEvents: (Calendar) -> List<TimetableFragment.ScheduleItem>,
    private val onDayClick: (MonthDay) -> Unit
) : RecyclerView.Adapter<MonthPageAdapter.MonthDayViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MonthDayViewHolder {
        val binding = ItemMonthCalendarCellBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return MonthDayViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MonthDayViewHolder, position: Int) {
        holder.bind(days[position], getEvents, onDayClick)
    }

    override fun getItemCount(): Int = days.size

    class MonthDayViewHolder(val binding: ItemMonthCalendarCellBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(
            day: MonthDay,
            getEvents: (Calendar) -> List<TimetableFragment.ScheduleItem>,
            onDayClick: (MonthDay) -> Unit
        ) {
            val ctx = binding.root.context
            binding.textDayNumber.text = "${day.dayOfMonth}"

            if (day.isToday) {
                binding.textDayNumber.setBackgroundResource(R.drawable.bg_today_badge)
                binding.textDayNumber.setTextColor(Color.parseColor("#020617"))
            } else {
                binding.textDayNumber.background = null
                if (!day.isCurrentMonth) {
                    binding.textDayNumber.setTextColor(ContextCompat.getColor(ctx, R.color.text_tertiary))
                } else if (day.dayOfWeek == Calendar.SUNDAY) {
                    binding.textDayNumber.setTextColor(Color.parseColor("#F43F5E"))
                } else if (day.dayOfWeek == Calendar.SATURDAY) {
                    binding.textDayNumber.setTextColor(Color.parseColor("#38BDF8"))
                } else {
                    binding.textDayNumber.setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                }
            }

            val targetCal = Calendar.getInstance().apply {
                timeInMillis = day.startOfDayMillis
            }
            val events = getEvents(targetCal)

            binding.layoutEventChips.removeAllViews()
            val maxChips = 2
            val displayEvents = events.take(maxChips)

            val inflater = LayoutInflater.from(ctx)
            displayEvents.forEach { event ->
                val chip = inflater.inflate(R.layout.item_month_calendar_chip, binding.layoutEventChips, false) as TextView
                chip.text = event.title
                when (event.type) {
                    "完了報告" -> chip.setTextColor(Color.parseColor("#10B981"))
                    "タスク" -> chip.setTextColor(Color.parseColor("#FBBF24"))
                    else -> chip.setTextColor(ContextCompat.getColor(ctx, R.color.cyber_cyan))
                }
                binding.layoutEventChips.addView(chip)
            }

            if (events.size > maxChips) {
                binding.textMoreEvents.visibility = View.VISIBLE
                binding.textMoreEvents.text = "+${events.size - maxChips}"
            } else {
                binding.textMoreEvents.visibility = View.GONE
            }

            binding.root.setOnClickListener {
                onDayClick(day)
            }
        }
    }
}
