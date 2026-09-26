package com.yourname.birder

import android.os.Bundle
import android.os.Environment
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.appcompat.app.AlertDialog

class DownloadActivity : AppCompatActivity() {

    private lateinit var etSpecies: EditText
    private lateinit var spinnerCountry: AutoCompleteTextView
    private lateinit var spinnerContinent: Spinner
    private lateinit var spinnerMaxPerSpecies: Spinner
    private lateinit var btnStartDownload: Button
    private lateinit var btnPatchMetadata: Button
    private lateinit var tvLog: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var progressBar: ProgressBar
    private lateinit var qualityGroup: LinearLayout

    private lateinit var btnSoundTypes: Button

    private val qualityCheckBoxes = mutableMapOf<String, CheckBox>()

    private val continents = listOf(
        "", "Europe", "North America", "South America",
        "Africa", "Asia", "Oceania", "Antarctica"
    )

    private val maxPerSpeciesOptions = listOf("1", "2", "3", "5", "10")

    private val birderRoot by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Birder/bird_sounds"
        )
    }

    private val allSoundTypes = listOf(
        "aberrant", "advertisement call", "agonistic call", "alarm call",
        "begging call", "bill clapping", "call", "calling song", "courtship song",
        "dawn song", "defensive call", "distress call", "disturbance song",
        "drumming", "duet", "echolocation", "feeding buzz", "female song",
        "flight call", "flight song", "imitation", "mating call",
        "mechanical sound", "nocturnal flight call", "release call",
        "rivalry song", "searching song", "social call", "song", "subsong",
        "territorial call"
    )

    private val defaultSoundTypes = setOf(
        "song", "call", "alarm call", "flight call", "subsong",
        "begging call", "drumming", "bill clapping", "nocturnal flight call"
    )

    private val selectedSoundTypes = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download)

        etSpecies          = findViewById(R.id.etSpecies)
        spinnerCountry     = findViewById(R.id.spinnerCountry)
        spinnerContinent   = findViewById(R.id.spinnerContinent)
        spinnerMaxPerSpecies = findViewById(R.id.spinnerMaxPerSpecies)
        btnStartDownload   = findViewById(R.id.btnStartDownload)
        btnPatchMetadata   = findViewById(R.id.btnPatchMetadata)
        tvLog              = findViewById(R.id.tvLog)
        scrollView         = findViewById(R.id.scrollView)
        progressBar        = findViewById(R.id.progressBar)
        qualityGroup       = findViewById(R.id.qualityGroup)

        setupContinentSpinner()
        setupMaxPerSpeciesSpinner()
        setupQualityCheckboxes()

        // Country autocomplete — user types freely
        spinnerCountry.setText("Slovenia")

        btnStartDownload.setOnClickListener { startDownload() }
        btnPatchMetadata.setOnClickListener { patchMetadata() }
        findViewById<Button>(R.id.btnTestApi).setOnClickListener {
            testApiConnection()
        }

        btnSoundTypes = findViewById(R.id.btnSoundTypes)
        selectedSoundTypes.addAll(defaultSoundTypes)
        updateSoundTypeButton()

        btnSoundTypes.setOnClickListener { showSoundTypeDialog() }
    }

    private fun setupContinentSpinner() {
        val adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, continents)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerContinent.adapter = adapter
        spinnerContinent.setSelection(continents.indexOf("Europe"))
    }

    private fun setupMaxPerSpeciesSpinner() {
        val adapter = ArrayAdapter(this,
            android.R.layout.simple_spinner_item, maxPerSpeciesOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerMaxPerSpecies.adapter = adapter
        spinnerMaxPerSpecies.setSelection(3)  // default 5 recordings
    }

    private fun setupQualityCheckboxes() {
        val spacing = (20 * resources.displayMetrics.density).toInt()

        listOf("A", "B", "C", "D", "E").forEach { q ->
            val cb = CheckBox(this).apply {
                text = q
                setTextColor(getColor(R.color.cream))
                isChecked = q in listOf("A", "B")
                tag = q

                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = spacing
                }
            }

            qualityCheckBoxes[q] = cb
            qualityGroup.addView(cb)
        }
    }

    private fun buildParams(): XenoCantoDownloader.DownloadParams {
        val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)



        val speciesText = etSpecies.text.toString().trim()
        val speciesList = if (speciesText.isBlank()) emptyList()
        else speciesText.split("\n", ",").map { it.trim() }.filter { it.isNotEmpty() }

        val selectedQualities = qualityCheckBoxes
            .filter { it.value.isChecked }
            .map { it.key }

        val maxRaw = spinnerMaxPerSpecies.selectedItem?.toString() ?: "3"
        val max = if (maxRaw == "unlimited") Int.MAX_VALUE else maxRaw.toIntOrNull() ?: 3

        return XenoCantoDownloader.DownloadParams(
            country       = spinnerCountry.text.toString().trim(),
            continent     = spinnerContinent.selectedItem?.toString() ?: "",
            speciesList   = speciesList,
            qualityFilter = selectedQualities,
            maxPerSpecies = max,
            soundTypes    = selectedSoundTypes.toList(),
            apiKey        = prefs.getString("xc_api_key", "") ?: ""
        )
    }

    private fun startDownload() {
        val params = buildParams()
        btnStartDownload.isEnabled = false
        btnPatchMetadata.isEnabled = false
        progressBar.visibility = View.VISIBLE
        tvLog.text = ""

        appendLog("Starting download...")
        appendLog("Country  : ${params.country.ifEmpty { "any" }}")
        appendLog("Continent: ${params.continent.ifEmpty { "any" }}")
        appendLog("Species  : ${if (params.speciesList.isEmpty()) "all" else params.speciesList.joinToString()}")
        appendLog("Quality  : ${params.qualityFilter.joinToString()}")
        appendLog("Max/species: ${if (params.maxPerSpecies == Int.MAX_VALUE) "unlimited" else params.maxPerSpecies}")
        appendLog("Output   : ${birderRoot.absolutePath}\n")

        CoroutineScope(Dispatchers.IO).launch {
            XenoCantoDownloader.download(
                birderRoot = birderRoot,
                params = params,
                callback = object : XenoCantoDownloader.DownloadCallback {
                    override fun onProgress(message: String) {
                        CoroutineScope(Dispatchers.Main).launch { appendLog(message) }
                    }
                    override fun onError(message: String) {
                        CoroutineScope(Dispatchers.Main).launch { appendLog("⚠ $message") }
                    }
                    override fun onComplete(downloaded: Int, skipped: Int) {
                        CoroutineScope(Dispatchers.Main).launch {
                            appendLog("\n✓ Done! Downloaded: $downloaded  Skipped: $skipped")

                            // Auto-patch Slovenian names if xlsx is available
                            val xlsxFile = File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                                "Birder/slovenian_names.xlsx"
                            )
                            val metadataFile = File(birderRoot, "metadata.json")

                            if (xlsxFile.exists() && metadataFile.exists()) {
                                appendLog("\nAuto-patching Slovenian names...")
                                CoroutineScope(Dispatchers.IO).launch {
                                    val result = MetadataPatcher.patch(metadataFile, xlsxFile)
                                    withContext(Dispatchers.Main) {
                                        if (result.error != null) {
                                            appendLog("⚠ Patch error: ${result.error}")
                                        } else {
                                            appendLog("✓ Slovenian names patched. Matched: ${result.matched}  Unmatched: ${result.unmatched}")
                                        }
                                        progressBar.visibility = View.GONE
                                        btnStartDownload.isEnabled = true
                                        btnPatchMetadata.isEnabled = true
                                        BirdRepository.clearCache()
                                    }
                                }
                            } else {
                                if (!xlsxFile.exists()) {
                                    appendLog("ℹ slovenian_names.xlsx not found — skipping auto-patch")
                                }
                                progressBar.visibility = View.GONE
                                btnStartDownload.isEnabled = true
                                btnPatchMetadata.isEnabled = true
                                BirdRepository.clearCache()
                            }
                        }
                    }
                }
            )
        }
    }

    private fun patchMetadata() {



        val metadataFile = File(birderRoot, "metadata.json")
        if (!metadataFile.exists()) {
            appendLog("⚠ metadata.json not found at ${birderRoot.absolutePath}")
            return
        }

        // Look for xlsx in Music/Birder/
        val xlsxFile = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Birder/slovenian_names.xlsx"
        )

        appendLog("metadata path: ${metadataFile.absolutePath}")
        appendLog("xlsx path    : ${xlsxFile.absolutePath}")

        if (!xlsxFile.exists()) {
            appendLog("⚠ slovenian_names.xlsx not found.")
            appendLog("  Place it at: Music/Birder/slovenian_names.xlsx")
            return
        }

        btnPatchMetadata.isEnabled = false
        progressBar.visibility = View.VISIBLE
        appendLog("\nPatching metadata with Slovenian names...")

        CoroutineScope(Dispatchers.IO).launch {
            val result = MetadataPatcher.patch(metadataFile, xlsxFile)

// Verify file on disk immediately after patch
            val verifyText = try {
                val verify = metadataFile.readText(Charsets.UTF_8)
                val checkJson = org.json.JSONObject(verify)
                val turdus = checkJson.optJSONObject("Turdus merula")
                val songs = turdus?.optJSONArray("song")
                val firstSong = songs?.optJSONObject(0)
                "slovenian_name in file: '${firstSong?.optString("slovenian_name")}'"
            } catch (e: Exception) {
                "verify failed: ${e.message}"
            }

            withContext(Dispatchers.Main) {
                progressBar.visibility = View.GONE
                btnPatchMetadata.isEnabled = true
                appendLog("metadata path: ${metadataFile.absolutePath}")
                if (result.error != null) {
                    appendLog("⚠ Error: ${result.error}")
                } else {
                    appendLog("✓ Patched! Matched: ${result.matched}  Unmatched: ${result.unmatched}")
                    appendLog(verifyText)
                    BirdRepository.clearCache()
                }
            }
        }
    }

    private fun appendLog(text: String) {
        tvLog.append("$text\n")
        scrollView.post { scrollView.fullScroll(View.FOCUS_DOWN) }
    }

    private fun testApiConnection() {
        appendLog("\n── API Test ─────────────────────────────")
        appendLog("Sending test query: 'Turdus merula cnt:Slovenia'")
        progressBar.visibility = View.VISIBLE

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val query = java.net.URLEncoder.encode(
                    "sp:\"Turdus merula\" cnt:\"Slovenia\" grp:\"birds\"", "UTF-8"
                )

                val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
                val apiKey = prefs.getString("xc_api_key", "")
                    ?.ifEmpty { "980fdbb8cd7dabb47f0ba306f9421ae9da41e09f" }
                    ?: "980fdbb8cd7dabb47f0ba306f9421ae9da41e09f"
                val url = "https://xeno-canto.org/api/3/recordings?query=$query&key=$apiKey"

                withContext(Dispatchers.Main) { appendLog("URL: $url") }

                val request = okhttp3.Request.Builder()
                    .url(url)

                    .build()

                val response = client.newCall(request).execute()

                val code = response.code
                val body = response.body?.string() ?: "empty body"

                withContext(Dispatchers.Main) {
                    appendLog("HTTP status: $code")
                    progressBar.visibility = View.GONE

                    if (code == 200) {
                        // Parse and show summary
                        try {
                            val json = org.json.JSONObject(body)
                            val numRecordings = json.optInt("numRecordings", -1)
                            val numSpecies    = json.optInt("numSpecies", -1)
                            val numPages      = json.optInt("numPages", -1)
                            val recordings    = json.optJSONArray("recordings")

                            appendLog("✓ API reachable!")
                            appendLog("  numRecordings : $numRecordings")
                            appendLog("  numSpecies    : $numSpecies")
                            appendLog("  numPages      : $numPages")

                            if (recordings != null && recordings.length() > 0) {
                                val first = recordings.getJSONObject(0)
                                appendLog("\nFirst result:")
                                appendLog("  id      : ${first.optString("id")}")
                                appendLog("  genus   : ${first.optString("gen")}")
                                appendLog("  species : ${first.optString("sp")}")
                                appendLog("  english : ${first.optString("en")}")
                                appendLog("  type    : ${first.optString("type")}")
                                appendLog("  quality : ${first.optString("q")}")
                                appendLog("  country : ${first.optString("cnt")}")
                                appendLog("  file    : https:${first.optString("file")}")
                            } else {
                                appendLog("⚠ recordings array is empty or missing")
                                appendLog("\nRaw body (first 500 chars):")
                                appendLog(body.take(500))
                            }
                        } catch (e: Exception) {
                            appendLog("⚠ JSON parse error: ${e.message}")
                            appendLog("\nRaw body (first 500 chars):")
                            appendLog(body.take(500))
                        }
                    } else {
                        appendLog("⚠ Unexpected HTTP code: $code")
                        appendLog("Raw body: ${body.take(300)}")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    progressBar.visibility = View.GONE
                    appendLog("✗ Connection failed: ${e.message}")
                    appendLog("  Check that INTERNET permission is in AndroidManifest.xml")
                    appendLog("  and the device has an active internet connection.")
                }
            }
        }
    }

    private fun showSoundTypeDialog() {
        val checkedItems = allSoundTypes.map { it in selectedSoundTypes }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("Select Sound Types")
            .setMultiChoiceItems(
                allSoundTypes.toTypedArray(),
                checkedItems
            ) { _, which, isChecked ->
                val type = allSoundTypes[which]
                if (isChecked) selectedSoundTypes.add(type)
                else selectedSoundTypes.remove(type)
                updateSoundTypeButton()
            }
            .setPositiveButton("OK", null)
            .setNegativeButton("Select All") { _, _ ->
                selectedSoundTypes.addAll(allSoundTypes)
                updateSoundTypeButton()
            }
            .setNeutralButton("Clear All") { _, _ ->
                selectedSoundTypes.clear()
                updateSoundTypeButton()
            }
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(R.color.cream))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(getColor(R.color.cream))
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL)
                    .setTextColor(getColor(android.R.color.holo_red_light))
            }
    }

    private fun updateSoundTypeButton() {
        btnSoundTypes.text = "Sound types: ${selectedSoundTypes.size}/${allSoundTypes.size} selected"
    }
}

