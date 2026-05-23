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
    private var showScientific = false

    data class SpeciesItem(
        val scientificName: String,
        val commonName: String,
        var enabled: Boolean = true
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_species_select)

        listView = findViewById(R.id.listView)
        btnToggleName = findViewById(R.id.btnToggleName)
        etSearch = findViewById(R.id.etSearch)
        tvCount = findViewById(R.id.tvCount)

        // Toggle scientific/common name display
        btnToggleName.setOnClickListener {
            showScientific = !showScientific
            btnToggleName.text = if (showScientific) "Show Common Name" else "Show Scientific Name"
            refreshList()
        }

        // Search filter
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { filterList(s.toString()) }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        // Select all / deselect all on long press
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

        loadSpecies()
    }

    private fun loadSpecies() {
        CoroutineScope(Dispatchers.IO).launch {
            // Load songs from cache (already loaded by MainActivity)
            val songs = BirdRepository.loadAllSongs(this@SpeciesSelectActivity)

            // Get saved enabled species from prefs
            val prefs = getSharedPreferences("birder_prefs", MODE_PRIVATE)
            val savedDisabled = prefs.getStringSet("disabled_species", emptySet()) ?: emptySet()

            // Build unique species list
            val speciesMap = linkedMapOf<String, SpeciesItem>()
            songs.forEach { song ->
                if (!speciesMap.containsKey(song.scientificName)) {
                    speciesMap[song.scientificName] = SpeciesItem(
                        scientificName = song.scientificName,
                        commonName = song.commonName,
                        enabled = song.scientificName !in savedDisabled
                    )
                }
            }

            // Sort alphabetically by scientific name
            allSpecies = speciesMap.values.sortedBy { it.scientificName }
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
                        it.commonName.contains(query, ignoreCase = true)
            }
        }
        refreshList()
    }

    private fun refreshList() {
        val displayNames = filteredSpecies.map {
            if (showScientific) it.scientificName else it.commonName
        }
        val checkedStates = filteredSpecies.map { it.enabled }.toBooleanArray()

        val enabledCount = allSpecies.count { it.enabled }
        tvCount.text = "$enabledCount / ${allSpecies.size} species selected"

        val adapter = object : ArrayAdapter<String>(
            this,
            android.R.layout.simple_list_item_multiple_choice,
            displayNames
        ) {}

        listView.adapter = adapter
        listView.choiceMode = ListView.CHOICE_MODE_MULTIPLE

        // Apply check states
        filteredSpecies.forEachIndexed { index, item ->
            listView.setItemChecked(index, item.enabled)
        }

        // Handle check/uncheck
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