package com.yourname.birder

import android.os.Bundle
import android.os.Environment
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

class RemoveSpeciesActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView
    private lateinit var btnRemoveSelected: Button
    private lateinit var btnToggleName: Button

    private var allSpecies: List<SpeciesItem> = emptyList()
    private var filteredSpecies: List<SpeciesItem> = emptyList()
    private val selectedSpecies = mutableSetOf<String>() // scientific names

    enum class NameMode { SLOVENIAN, COMMON, SCIENTIFIC }
    private var nameMode = NameMode.SLOVENIAN

    data class SpeciesItem(
        val scientificName: String,
        val commonName: String,
        val slovenianName: String,
        val songCount: Int
    ) {
        fun displayName(mode: NameMode): String = when (mode) {
            NameMode.SLOVENIAN  -> slovenianName.ifEmpty { commonName }
            NameMode.COMMON     -> commonName
            NameMode.SCIENTIFIC -> scientificName
        }
    }

    private val birderRoot by lazy {
        File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
            "Birder/bird_sounds"
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remove_species)

        listView        = findViewById(R.id.listView)
        etSearch        = findViewById(R.id.etSearch)
        tvCount         = findViewById(R.id.tvCount)
        btnRemoveSelected = findViewById(R.id.btnRemoveSelected)
        btnToggleName   = findViewById(R.id.btnToggleName)

        btnToggleName.setOnClickListener {
            nameMode = when (nameMode) {
                NameMode.SLOVENIAN  -> NameMode.COMMON
                NameMode.COMMON     -> NameMode.SCIENTIFIC
                NameMode.SCIENTIFIC -> NameMode.SLOVENIAN
            }
            updateToggleLabel()
            refreshList()
        }

        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        btnRemoveSelected.setOnClickListener {
            if (selectedSpecies.isEmpty()) {
                Toast.makeText(this, "No species selected", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            confirmRemoval()
        }

        updateToggleLabel()
        loadSpecies()
    }

    private fun updateToggleLabel() {
        btnToggleName.text = when (nameMode) {
            NameMode.SLOVENIAN  -> "Showing: Slovenian name"
            NameMode.COMMON     -> "Showing: Common name"
            NameMode.SCIENTIFIC -> "Showing: Scientific name"
        }
    }

    private fun loadSpecies() {
        CoroutineScope(Dispatchers.IO).launch {
            val songs = BirdRepository.loadAllSongs(this@RemoveSpeciesActivity)

            val speciesMap = linkedMapOf<String, SpeciesItem>()
            songs.forEach { song ->
                val existing = speciesMap[song.scientificName]
                if (existing == null) {
                    speciesMap[song.scientificName] = SpeciesItem(
                        scientificName = song.scientificName,
                        commonName     = song.commonName,
                        slovenianName  = song.slovenianName,
                        songCount      = 1
                    )
                } else {
                    speciesMap[song.scientificName] = existing.copy(
                        songCount = existing.songCount + 1
                    )
                }
            }

            allSpecies     = speciesMap.values.sortedBy { it.displayName(nameMode) }
            filteredSpecies = allSpecies.toMutableList()

            withContext(Dispatchers.Main) {
                updateCount()
                refreshList()
            }
        }
    }

    private fun filterList(query: String) {
        filteredSpecies = if (query.isBlank()) {
            allSpecies.toMutableList()
        } else {
            allSpecies.filter {
                it.scientificName.contains(query, ignoreCase = true) ||
                        it.commonName.contains(query, ignoreCase = true) ||
                        it.slovenianName.contains(query, ignoreCase = true)
            }
        }
        refreshList()
    }

    private fun refreshList() {
        filteredSpecies = filteredSpecies.sortedBy { it.displayName(nameMode) }

        val displayNames = filteredSpecies.map {
            "${it.displayName(nameMode)}  (${it.songCount} recordings)"
        }

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            displayNames
        )

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // Restore checked state
        filteredSpecies.forEachIndexed { index, item ->
            listView.setItemChecked(index, item.scientificName in selectedSpecies)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val sciName = filteredSpecies[position].scientificName
            if (listView.isItemChecked(position)) {
                selectedSpecies.add(sciName)
            } else {
                selectedSpecies.remove(sciName)
            }
            updateCount()
        }
    }

    private fun updateCount() {
        tvCount.text = "${selectedSpecies.size} species selected for removal"
        btnRemoveSelected.isEnabled = selectedSpecies.isNotEmpty()
    }

    private fun confirmRemoval() {
        val names = selectedSpecies.joinToString("\n") { sciName ->
            allSpecies.find { it.scientificName == sciName }
                ?.displayName(nameMode) ?: sciName
        }

        AlertDialog.Builder(this)
            .setTitle("Remove ${selectedSpecies.size} species?")
            .setMessage("This will permanently delete all audio files and metadata for:\n\n$names")
            .setPositiveButton("Delete") { _, _ -> removeSelected() }
            .setNegativeButton("Cancel", null)
            .create()
            .also { dialog ->
                dialog.show()
                dialog.window?.setBackgroundDrawableResource(R.drawable.dialog_bg)
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setTextColor(getColor(android.R.color.holo_red_light))
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setTextColor(getColor(R.color.cream))
            }
    }

    private fun removeSelected() {
        btnRemoveSelected.isEnabled = false
        tvCount.text = "Removing..."

        CoroutineScope(Dispatchers.IO).launch {
            var deletedFolders = 0
            var deletedFiles = 0

            // 1. Delete folders from disk
            for (sciName in selectedSpecies) {
                val speciesDir = File(birderRoot, sciName)
                if (speciesDir.exists() && speciesDir.isDirectory) {
                    val files = speciesDir.walkBottomUp().toList()
                    files.forEach { it.delete() }
                    deletedFiles += files.count { it.isFile }
                    deletedFolders++
                }
            }

            // 2. Remove from metadata.json
            val metadataFile = File(birderRoot, "metadata.json")
            if (metadataFile.exists()) {
                try {
                    val metadata = JSONObject(metadataFile.readText(Charsets.UTF_8))
                    for (sciName in selectedSpecies) {
                        metadata.remove(sciName)
                    }
                    metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            this@RemoveSpeciesActivity,
                            "Warning: could not update metadata.json: ${e.message}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }

            // 3. Remove from disabled species prefs so no ghost entries remain
            val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
            val disabled = prefs.getStringSet("disabled_species", emptySet())
                ?.toMutableSet() ?: mutableSetOf()
            disabled.removeAll(selectedSpecies)
            prefs.edit().putStringSet("disabled_species", disabled).apply()

            // 4. Clear cache so app rescans
            BirdRepository.clearCache()

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@RemoveSpeciesActivity,
                    "Removed $deletedFolders species ($deletedFiles files deleted)",
                    Toast.LENGTH_LONG
                ).show()

                selectedSpecies.clear()
                loadSpecies()
            }
        }
    }
}