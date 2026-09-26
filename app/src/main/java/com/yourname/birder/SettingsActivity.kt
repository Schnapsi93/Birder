package com.yourname.birder

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.view.Gravity
import android.widget.TextView

class SettingsActivity : AppCompatActivity() {

    private lateinit var etListeningDuration: EditText
    private lateinit var btnSave: Button
    private lateinit var toggleHandsFree: ToggleButton
    private lateinit var seekBarTtsVolume: SeekBar
    private lateinit var tvTtsVolumeValue: TextView
    private lateinit var btnTtsLanguage: Button
    private lateinit var btnAdvancedSettings: Button
    private lateinit var layoutAdvanced: LinearLayout
    private lateinit var etApiKey: EditText

    private val languageOptions = listOf("Slovenian", "English", "Scientific")
    private var currentLanguageIndex = 0
    private var advancedUnlocked = false

    private val ADVANCED_PASSWORD = "1234"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etListeningDuration = findViewById(R.id.etListeningDuration)
        btnSave             = findViewById(R.id.btnSave)
        toggleHandsFree     = findViewById(R.id.toggleHandsFree)
        seekBarTtsVolume    = findViewById(R.id.seekBarTtsVolume)
        tvTtsVolumeValue    = findViewById(R.id.tvTtsVolumeValue)
        btnTtsLanguage      = findViewById(R.id.btnTtsLanguage)
        btnAdvancedSettings = findViewById(R.id.btnAdvancedSettings)
        layoutAdvanced      = findViewById(R.id.layoutAdvanced)
        etApiKey            = findViewById(R.id.etApiKey)

        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)

        // Load standard settings
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

        // Load advanced settings
        etApiKey.setText(prefs.getString("xc_api_key", ""))

        // Advanced settings button — always hidden until password entered
        advancedUnlocked = false
        layoutAdvanced.visibility = View.GONE

        btnAdvancedSettings.setOnClickListener {
            if (advancedUnlocked) {
                // Already unlocked — toggle visibility
                layoutAdvanced.visibility =
                    if (layoutAdvanced.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            } else {
                showPasswordDialog()
            }
        }

        btnSave.setOnClickListener {
            val input = etListeningDuration.text.toString().trim()
            val seconds = input.toIntOrNull()
            when {
                seconds == null || input.isBlank() ->
                    Toast.makeText(this, "Please enter a valid number", Toast.LENGTH_SHORT).show()
                seconds < 0 ->
                    Toast.makeText(this, "Minimum is 0 seconds", Toast.LENGTH_SHORT).show()
                seconds > 300 ->
                    Toast.makeText(this, "Maximum is 300 seconds (5 min)", Toast.LENGTH_SHORT).show()
                else -> {
                    val editor = prefs.edit()
                        .putInt("listening_duration_sec", seconds)
                        .putBoolean("hands_free_mode", toggleHandsFree.isChecked)
                        .putInt("tts_volume", seekBarTtsVolume.progress)
                        .putString("tts_language", languageOptions[currentLanguageIndex])

                    // Save advanced settings only if unlocked
                    if (advancedUnlocked) {
                        val apiKey = etApiKey.text.toString().trim()
                        editor.putString("xc_api_key", apiKey)
                    }

                    editor.apply()
                    Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun showPasswordDialog() {
        val passwordInput = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Enter password"
            setTextColor(getColor(R.color.cream))
            setHintTextColor(getColor(R.color.green_light))
            setPadding(48, 32, 48, 32)
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            letterSpacing = 0.25f
            backgroundTintList = android.content.res.ColorStateList.valueOf(
                getColor(R.color.green_mid)
            )
        }

        val titleView = TextView(this).apply {
            text = "🔐 Advanced Settings"
            setTextColor(getColor(R.color.cream))
            textSize = 20f
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 16)
        }

        val dialog = AlertDialog.Builder(this)
            .setCustomTitle(titleView)

            .setView(passwordInput)
            .setPositiveButton("Unlock", null)
            .setNegativeButton("Cancel", null)
            .create()

        dialog.also {
            it.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
            // Force keyboard to open
            it.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE
            )
        }

        dialog.setOnShowListener {
            // Center title
            dialog.findViewById<TextView>(
                resources.getIdentifier("alertTitle", "id", "android")
            )?.apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }

            // Center message
            dialog.findViewById<TextView>(android.R.id.message)?.apply {
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
            }

            passwordInput.requestFocus()

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                setTextColor(getColor(R.color.cream))
                setOnClickListener {
                    val entered = passwordInput.text.toString()
                    if (entered == ADVANCED_PASSWORD) {
                        advancedUnlocked = true
                        layoutAdvanced.visibility = View.VISIBLE
                        btnAdvancedSettings.text = "⚙️ Advanced Settings 🔓"
                        // Dismiss keyboard
                        val imm = getSystemService(INPUT_METHOD_SERVICE)
                                as android.view.inputmethod.InputMethodManager
                        imm.hideSoftInputFromWindow(passwordInput.windowToken, 0)
                        dialog.dismiss()
                        Toast.makeText(this@SettingsActivity,
                            "Advanced settings unlocked", Toast.LENGTH_SHORT).show()
                    } else {
                        passwordInput.text.clear()
                        passwordInput.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#b71c1c")
                            )
                        Toast.makeText(this@SettingsActivity,
                            "Incorrect password", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                .setTextColor(getColor(R.color.cream))
        }

        dialog.show()
    }

    private fun updateLanguageButton() {
        btnTtsLanguage.text = "Language: ${languageOptions[currentLanguageIndex]}"
    }

    override fun onResume() {
        super.onResume()
        // Always hide advanced settings when returning to screen
        advancedUnlocked = false
        layoutAdvanced.visibility = View.GONE
        btnAdvancedSettings.text = "⚙️ Advanced Settings"
    }
}