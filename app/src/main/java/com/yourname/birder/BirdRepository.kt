package com.yourname.birder

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File

object BirdRepository {

    private const val TAG = "BirdRepository"

    private val birderRoot = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "Birder/bird_sounds"
    )

    // Call this once from Application or MainActivity and cache it
    private var cachedSongs: List<BirdSong>? = null
    private var cachedShuffled: List<BirdSong>? = null

    fun loadAllSongs(context: Context): List<BirdSong> {
        cachedSongs?.let { return it }  // return cache if already loaded

        Log.d(TAG, "Looking in: ${birderRoot.absolutePath}")
        Log.d(TAG, "Folder exists: ${birderRoot.exists()}")
        Log.d(TAG, "Folder readable: ${birderRoot.canRead()}")

        val metadataFile = File(birderRoot, "metadata.json")
        Log.d(TAG, "Metadata exists: ${metadataFile.exists()}")

        // Use ContentResolver to read the file — avoids EACCES on Android 10+
        val metadata = try {
            val uri = Uri.fromFile(metadataFile)
            val text = context.contentResolver
                .openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
            if (text != null) JSONObject(text) else JSONObject()
        } catch (e: Exception) {
            Log.e(TAG, "ContentResolver failed, trying direct read: ${e.message}")
            // Fallback: direct read
            try {
                JSONObject(metadataFile.readText(Charsets.UTF_8))
            } catch (e2: Exception) {
                Log.e(TAG, "Direct read also failed: ${e2.message}")
                JSONObject()
            }
        }

        val songs = mutableListOf<BirdSong>()
        val speciesDirs = birderRoot.listFiles() ?: return emptyList()
        Log.d(TAG, "Items in root: ${speciesDirs.size}")

        speciesDirs.forEach { speciesDir ->
            if (!speciesDir.isDirectory) return@forEach

            val scientificName = speciesDir.name
            val speciesMeta = if (metadata.has(scientificName))
                metadata.getJSONObject(scientificName)
            else {
                Log.w(TAG, "No metadata for: $scientificName")
                null
            }

            speciesDir.listFiles()?.forEach { soundTypeDir ->
                if (!soundTypeDir.isDirectory) return@forEach
                val soundType = soundTypeDir.name
                val soundMetaArray = speciesMeta?.optJSONArray(soundType)

                soundTypeDir.listFiles()
                    ?.filter {
                        val name = it.name.lowercase()
                        name.endsWith(".mp3") || name.endsWith(".wav") }
                    ?.sortedBy { it.name }
                    ?.forEachIndexed { index, mp3File ->
                        val entry = soundMetaArray?.optJSONObject(index)
                        songs.add(BirdSong(
                            file = mp3File,
                            scientificName = entry?.optString("scientific_name")
                                ?.takeIf { it.isNotEmpty() } ?: scientificName,
                            commonName = entry?.optString("common_name")
                                ?.takeIf { it.isNotEmpty() } ?: scientificName,
                            soundType = soundType,
                            quality = entry?.optString("quality") ?: "-",
                            recordist = entry?.optString("recordist") ?: "-",
                            country = entry?.optString("country") ?: "-",
                            date = entry?.optString("date") ?: "-",
                            xcUrl = entry?.optString("xc_url") ?: ""
                        ))
                    }
            }
        }

        Log.d(TAG, "Total songs loaded: ${songs.size}")
        cachedSongs = songs
        cachedShuffled = songs.shuffled()   // pre-shuffle once on load
        return songs
    }

    //fun loadShuffled(context: Context): List<BirdSong> = loadAllSongs(context).shuffled()
    fun loadShuffled(context: Context): List<BirdSong> {
        if (cachedShuffled == null) loadAllSongs(context)

        // Read disabled species from prefs
        val prefs = context.getSharedPreferences("birder_prefs", Context.MODE_PRIVATE)
        val disabledSpecies = prefs.getStringSet("disabled_species", emptySet()) ?: emptySet()

        cachedShuffled = (cachedSongs ?: emptyList())
            .filter { it.scientificName !in disabledSpecies }
            .shuffled()

        return cachedShuffled ?: emptyList()
    }

    //fun clearCache() { cachedSongs = null }
    fun clearCache() {
        cachedSongs = null
        cachedShuffled = null
    }
}