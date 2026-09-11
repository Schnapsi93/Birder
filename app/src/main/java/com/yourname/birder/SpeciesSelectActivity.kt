package com.yourname.birder

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpeciesSelectActivity : AppCompatActivity() {

    private lateinit var listView: ListView
    private lateinit var btnToggleName: Button
    private lateinit var etSearch: EditText
    private lateinit var tvCount: TextView

    private var allSpecies: List<SpeciesItem> = emptyList()
    private var filteredSpecies: List<SpeciesItem> = emptyList()

    enum class NameMode { SLOVENIAN, COMMON, SCIENTIFIC }
    private var nameMode = NameMode.SLOVENIAN

    data class SpeciesItem(
        val scientificName: String,
        val commonName: String,
        val slovenianName: String,
        var enabled: Boolean = true
    ) {
        fun displayName(mode: NameMode): String = when (mode) {
            NameMode.SLOVENIAN  -> slovenianName.ifEmpty { commonName }
            NameMode.COMMON     -> commonName
            NameMode.SCIENTIFIC -> scientificName
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_species_select)

        listView      = findViewById(R.id.listView)
        btnToggleName = findViewById(R.id.btnToggleName)
        etSearch      = findViewById(R.id.etSearch)
        tvCount       = findViewById(R.id.tvCount)

        // Cycle through three modes on each tap
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

        listView.setOnItemLongClickListener { _, _, _, _ ->
            val allChecked = filteredSpecies.all { it.enabled }
            filteredSpecies.forEach { it.enabled = !allChecked }
            refreshList()
            Toast.makeText(
                this,
                if (allChecked) "All deselected" else "All selected",
                Toast.LENGTH_SHORT
            ).show()
            true
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
            val songs = BirdRepository.loadAllSongs(this@SpeciesSelectActivity)
            val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
            val savedDisabled = prefs.getStringSet("disabled_species", emptySet()) ?: emptySet()

            val speciesMap = linkedMapOf<String, SpeciesItem>()
            songs.forEach { song ->
                if (!speciesMap.containsKey(song.scientificName)) {
                    speciesMap[song.scientificName] = SpeciesItem(
                        scientificName = song.scientificName,
                        commonName     = song.commonName,
                        slovenianName  = song.slovenianName,
                        enabled        = song.scientificName !in savedDisabled
                    )
                }
            }

            // Sort alphabetically by current display mode
            allSpecies = speciesMap.values
                .sortedBy { it.displayName(nameMode) }
            filteredSpecies = allSpecies.toMutableList()

            withContext(Dispatchers.Main) {
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
        // Re-sort by current mode each time display changes
        filteredSpecies = filteredSpecies.sortedBy { it.displayName(nameMode) }

        val displayNames = filteredSpecies.map { it.displayName(nameMode) }

        val enabledCount = allSpecies.count { it.enabled }
        tvCount.text = "$enabledCount / ${allSpecies.size} species selected"

        val adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            displayNames
        )

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        filteredSpecies.forEachIndexed { index, item ->
            listView.setItemChecked(index, item.enabled)
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            filteredSpecies[position].enabled = listView.isItemChecked(position)
            val enabledTotal = allSpecies.count { it.enabled }
            tvCount.text = "$enabledTotal / ${allSpecies.size} species selected"
            savePrefs()
        }
    }

    private fun savePrefs() {
        val disabledSet = allSpecies
            .filter { !it.enabled }
            .map { it.scientificName }
            .toSet()
        getSharedPreferences("birder_prefs", MODE_PRIVATE)
            .edit()
            .putStringSet("disabled_species", disabledSet)
            .apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        savePrefs()
    }
}