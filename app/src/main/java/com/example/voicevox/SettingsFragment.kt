package com.example.voicevox

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.example.voicevox.databinding.FragmentSettingsBinding
import kotlin.math.pow

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupPermissions()
        setupDevSettings()
    }

    private fun setupPermissions() {
        binding.btnRequestOverlayPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        }

        binding.btnRequestBatteryPermission.setOnClickListener {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            startActivity(intent)
        }

        binding.btnRequestFullScreenPermission.setOnClickListener {
            // Full screen intent permission is handled in manifest, but on some devices you might want to open settings
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${requireContext().packageName}"))
            startActivity(intent)
        }
    }

    private fun setupDevSettings() {
        val charPrefs = requireContext().getSharedPreferences("CharacterPrefs", Context.MODE_PRIVATE)
        val charTotalExp = charPrefs.getLong("totalExp", 0L)

        fun calculateLevel(exp: Long): Int {
            return (exp / 100L).toInt() + 1
        }

        val currentLv = calculateLevel(charTotalExp)
        binding.seekDevLevel.progress = currentLv
        binding.txtDevLevelDisplay.text = "現在のLv: $currentLv"

        binding.btnDevSetLevel.setOnClickListener {
            val targetLevel = binding.seekDevLevel.progress
            
            // 1レベル100EXPの固定制
            val targetExp = (targetLevel - 1) * 100L

            charPrefs.edit().putLong("totalExp", targetExp).apply()
            
            Toast.makeText(requireContext(), "キャラレベルを $targetLevel に設定しました", Toast.LENGTH_SHORT).show()
            binding.txtDevLevelDisplay.text = "現在のLv: $targetLevel"
        }

        binding.seekDevLevel.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                binding.txtDevLevelDisplay.text = "現在のLv: $progress"
            }
            override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
