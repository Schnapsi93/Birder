package com.yourname.birder

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val PERMISSION_CODE = 100
    private lateinit var btnRandomLearn: Button
    private lateinit var btnQuiz: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var btnSetSpecies: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnRandomLearn = findViewById(R.id.btnRandomLearn)
        btnQuiz = findViewById(R.id.btnQuiz)
        progressBar = findViewById(R.id.progressBar)

        btnSetSpecies = findViewById(R.id.btnSetSpecies)

        btnRandomLearn.setOnClickListener {
            checkPermissionThen { startRandomLearn() }
        }

        btnSetSpecies.setOnClickListener {
            startActivity(Intent(this, SpeciesSelectActivity::class.java))
        }

        btnQuiz.setOnClickListener {
            Toast.makeText(this, "Quiz Mode — coming soon!", Toast.LENGTH_SHORT).show()
        }

        findViewById<Button>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<Button>(R.id.btnQuiz).setOnClickListener {
            checkPermissionThen {
                if (BirdRepository.loadAllSongs(this).isEmpty()) {
                    Toast.makeText(this, "Load songs first via Random Learn", Toast.LENGTH_SHORT).show()
                } else {
                    startActivity(Intent(this, QuizActivity::class.java))
                }
            }
        }


    }

    private fun checkPermissionThen(action: () -> Unit) {
        when {
            // Android 11+ — request full storage manager access
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) {
                    action()
                } else {
                    // Send user to system settings to grant permission
                    Toast.makeText(
                        this,
                        "Please grant 'All files access' for Birder",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, PERMISSION_CODE)
                }
            }
            // Android 10 and below
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                if (ContextCompat.checkSelfPermission(
                        this, Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                    action()
                } else {
                    ActivityCompat.requestPermissions(
                        this,
                        arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                        PERMISSION_CODE
                    )
                }
            }
            else -> action()
        }
    }

    // Called when user returns from the system settings screen
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()) {
                startRandomLearn()
            } else {
                Toast.makeText(this,
                    "Storage permission is required to read bird songs",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startRandomLearn()
        }
    }

    private fun startRandomLearn() {
        btnRandomLearn.isEnabled = false
        btnQuiz.isEnabled = false
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            BirdRepository.clearCache()                          // force fresh load
            BirdRepository.loadShuffled(this@MainActivity)      // loads + caches shuffled

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnRandomLearn.isEnabled = true
                btnQuiz.isEnabled = true

                if (BirdRepository.loadAllSongs(this@MainActivity).isEmpty()) {
                    Toast.makeText(
                        this@MainActivity,
                        "No songs found in Music/Birder/bird_sounds/",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    val intent = Intent(this@MainActivity, PlayerActivity::class.java).apply {
                        putExtra("MODE", "random")
                        putExtra("START_INDEX", 0)
                    }
                    startActivity(intent)
                }
            }
        }
    }
}