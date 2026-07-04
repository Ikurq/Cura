package com.example.voicevox

import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.tabs.TabLayoutMediator

class TutorialActivity : AppCompatActivity() {

    private lateinit var viewPager: ViewPager2
    private lateinit var btnNext: Button
    private lateinit var btnSkip: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tutorial)

        viewPager = findViewById(R.id.tutorialViewPager)
        btnNext = findViewById(R.id.btnNext)
        btnSkip = findViewById(R.id.btnSkip)

        val slides = listOf(
            TutorialSlide(
                "単位の守護神へようこそ",
                "このアプリは、あなたの生活習慣と大切な『単位』を守るための総合サポートツールです。",
                R.mipmap.ic_launcher
            ),
            TutorialSlide(
                "VOICEVOXアラーム",
                "お気に入りのキャラがあなたの名前を呼び、今日の予定を読み上げて起こしてくれます。",
                android.R.drawable.ic_lock_idle_alarm
            ),
            TutorialSlide(
                "スマートな出欠管理",
                "授業の出欠を自動・手動で管理。同じ授業は自動で統合され、累計欠席数も一目で分かります。",
                android.R.drawable.btn_star_big_on
            ),
            TutorialSlide(
                "タスクとスケジュール",
                "期限の迫ったタスクや外部カレンダーの予定をシームレスに確認。もう予定を忘れることはありません。",
                android.R.drawable.ic_menu_today
            )
        )

        val adapter = TutorialAdapter(slides)
        viewPager.adapter = adapter

        TabLayoutMediator(findViewById(R.id.tutorialTabLayout), viewPager) { _, _ -> }.attach()

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                if (position == slides.size - 1) {
                    btnNext.text = "はじめる"
                } else {
                    btnNext.text = "次へ"
                }
            }
        })

        btnNext.setOnClickListener {
            if (viewPager.currentItem < slides.size - 1) {
                viewPager.currentItem += 1
            } else {
                finishTutorial()
            }
        }

        btnSkip.setOnClickListener {
            finishTutorial()
        }
    }

    private fun finishTutorial() {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tutorial_finished", true).apply()
        finish()
    }

    data class TutorialSlide(val title: String, val description: String, val imageRes: Int)

    private inner class TutorialAdapter(private val slides: List<TutorialSlide>) :
        RecyclerView.Adapter<TutorialViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TutorialViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_tutorial_slide, parent, false)
            return TutorialViewHolder(view)
        }

        override fun onBindViewHolder(holder: TutorialViewHolder, position: Int) {
            val slide = slides[position]
            holder.title.text = slide.title
            holder.description.text = slide.description
            holder.image.setImageResource(slide.imageRes)
        }

        override fun getItemCount() = slides.size
    }

    private class TutorialViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.txtTutorialTitle)
        val description: TextView = view.findViewById(R.id.txtTutorialDescription)
        val image: ImageView = view.findViewById(R.id.imgTutorial)
    }
}
