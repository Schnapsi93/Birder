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
    private lateinit var btnTtsLanguage: Button

    private val languageOptions = listOf("Slovenian", "English", "Scientific")
    private var currentLanguageIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etListeningDuration = findViewById(R.id.etListeningDuration)
        btnSave             = findViewById(R.id.btnSave)
        toggleHandsFree     = findViewById(R.id.toggleHandsFree)
        seekBarTtsVolume    = findViewById(R.id.seekBarTtsVolume)
        tvTtsVolumeValue    = findViewById(R.id.tvTtsVolumeValue)
        btnTtsLanguage      = findViewById(R.id.btnTtsLanguage)

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)

        etListeningDuration.setText(prefs.getInt("listening_duration_sec", 15).toString())
        toggleHandsFree.isChecked = prefs.getBoolean("hands_free_mode", false)

        val savedVolume = prefs.getInt("tts_volume", 10)
        seekBarTtsVolume.progress = savedVolume
        tvTtsVolumeValue.text = savedVolume.toString()

        seekBarTtsVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                tvTtsVolumeValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        val savedLanguage = prefs.getString("tts_language", "Slovenian") ?: "Slovenian"
        currentLanguageIndex = languageOptions.indexOf(savedLanguage).coerceAtLeast(0)
        updateLanguageButton()

        btnTtsLanguage.setOnClickListener {
            currentLanguageIndex = (currentLanguageIndex + 1) % languageOptions.size
            updateLanguageButton()
        }

        btnSave.setOnClickListener {
            val input = etListeningDuration.text.toString().trim()
            val seconds = input.toIntOrNull()
            when {
                seconds == null || input.isBlank() ->
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                seconds < 0 ->
                    Toast.makeText(this, "Minimum is 0 second", Toast.LENGTH_SHORT).show()
                seconds > 300 ->
                    Toast.makeText(this, "Maximum is 300 seconds (5 min)", Toast.LENGTH_SHORT).show()
                else -> {
                    prefs.edit()
                        .putInt("listening_duration_sec", seconds)
                        .putBoolean("hands_free_mode", toggleHandsFree.isChecked)
                        .putInt("tts_volume", seekBarTtsVolume.progress)
                        .putString("tts_language", languageOptions[currentLanguageIndex])
                        .apply()
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun updateLanguageButton() {
        btnTtsLanguage.text = "Language: ${languageOptions[currentLanguageIndex]}"
    }
}