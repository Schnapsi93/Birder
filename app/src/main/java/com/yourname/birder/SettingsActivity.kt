package com.yourname.birder

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    private lateinit var etListeningDuration: EditText
    private lateinit var btnSave: Button
    private lateinit var toggleHandsFree: ToggleButton

    private lateinit var seekBarTtsVolume: SeekBar
    private lateinit var tvTtsVolumeValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etListeningDuration = findViewById(R.id.etListeningDuration)
        btnSave = findViewById(R.id.btnSave)
        toggleHandsFree = findViewById(R.id.toggleHandsFree)

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)

        // Load saved values
        val savedDuration = prefs.getInt("listening_duration_sec", 8)
        etListeningDuration.setText(savedDuration.toString())

        val savedHandsFree = prefs.getBoolean("hands_free_mode", false)
        toggleHandsFree.isChecked = savedHandsFree

        btnSave.setOnClickListener {
            val input = etListeningDuration.text.toString().trim()
            val seconds = input.toIntOrNull()


            when {
                seconds == null || input.isBlank() ->
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                seconds < 1 ->
                    Toast.makeText(this, "Minimum is 1 second", Toast.LENGTH_SHORT).show()
                seconds > 300 ->
                    Toast.makeText(this, "Maximum is 300 seconds (5 min)", Toast.LENGTH_SHORT).show()
                else -> {
                    prefs.edit()
                        .putInt("listening_duration_sec", seconds)
                        .putBoolean("hands_free_mode", toggleHandsFree.isChecked)
                        .putInt("tts_volume", seekBarTtsVolume.progress)
                        .apply()
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }

        seekBarTtsVolume = findViewById(R.id.seekBarTtsVolume)
        tvTtsVolumeValue = findViewById(R.id.tvTtsVolumeValue)

        val savedTtsVolume = prefs.getInt("tts_volume", 10)
        seekBarTtsVolume.progress = savedTtsVolume
        tvTtsVolumeValue.text = savedTtsVolume.toString()

        seekBarTtsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTtsVolumeValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })
    }
}