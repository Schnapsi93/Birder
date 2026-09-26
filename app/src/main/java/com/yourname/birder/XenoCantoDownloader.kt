package com.yourname.birder

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object XenoCantoDownloader {

    private const val TAG = "XenoCantoDownloader"
    private const val API_BASE = "https://xeno-canto.org/api/3/recordings"

    private const val DEFAULT_API_KEY = "980fdbb8cd7dabb47f0ba306f9421ae9da41e09f"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    data class DownloadParams(
        val country: String = "",
        val continent: String = "",
        val speciesList: List<String> = emptyList(),
        val qualityFilter: List<String> = listOf("A", "B"),
        val maxPerSpecies: Int = 3,
        val soundTypes: List<String> = listOf(
            "song", "call", "alarm call", "flight call", "subsong",
            "begging call", "drumming", "bill clapping", "nocturnal flight call"),
        val apiKey: String = ""
    )

    data class Recording(
        val xcId: String,
        val scientificName: String,
        val commonName: String,
        val soundType: String,
        val quality: String,
        val length: String,
        val recordist: String,
        val country: String,
        val date: String,
        val fileUrl: String,
        val license: String,
        val xcUrl: String
    )

    interface DownloadCallback {
        fun onProgress(message: String)
        fun onError(message: String)
        fun onComplete(downloaded: Int, skipped: Int)
    }

    /**
     * Build xeno-canto query string from params.
     * Mirrors the Python script's query construction.
     */
    private fun buildQuery(params: DownloadParams, species: String = ""): String {
        val parts = mutableListOf<String>()

        // Species: use sp: tag with full scientific name
        if (species.isNotBlank()) parts.add("sp:\"$species\"")

        // Country
        if (params.country.isNotBlank()) parts.add("cnt:\"${params.country}\"")

        // Continent
        if (params.continent.isNotBlank()) parts.add("area:\"${params.continent}\"")

        // Group
        parts.add("grp:\"birds\"")

        return parts.joinToString(" ")
    }

    /**
     * Fetch all recording metadata for a query (handles pagination).
     */
    private fun fetchRecordings(query: String, apiKey: String, callback: DownloadCallback): List<Recording> {
        val results = mutableListOf<Recording>()
        var page = 1
        var totalPages = 1

        while (page <= totalPages) {
            val url = "$API_BASE?query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page&key=$apiKey"


            try {
                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()
                val body = response.body?.string() ?: break
                val json = JSONObject(body)

                totalPages = json.optInt("numPages", 1)
                val recordings = json.optJSONArray("recordings") ?: break

                for (i in 0 until recordings.length()) {
                    val r = recordings.getJSONObject(i)
                    val quality = r.optString("q", "E")
                    results.add(Recording(
                        xcId           = r.optString("id"),
                        scientificName = r.optString("gen") + " " + r.optString("sp"),
                        commonName     = r.optString("en"),
                        soundType      = r.optString("type").lowercase()
                            .split(",").first().trim()
                            .ifEmpty { "call" },
                        quality        = r.optString("q"),
                        length         = r.optString("length"),
                        recordist      = r.optString("rec"),
                        country        = r.optString("cnt"),
                        date           = r.optString("date"),
                        fileUrl        = r.optString("file"),   // v3: no longer needs "https:" prefix
                        license        = r.optString("lic"),
                        xcUrl          = "https://xeno-canto.org/" + r.optString("id")
                    ))
                }
                page++
            } catch (e: Exception) {
                callback.onError("Network error: ${e.message}")
                break
            }
        }
        return results
    }

    /**
     * Main download function — call from a background thread.
     */
    fun download(
        birderRoot: File,
        params: DownloadParams,
        callback: DownloadCallback
    ) {
        val apiKey = params.apiKey.ifEmpty { DEFAULT_API_KEY }

        var totalDownloaded = 0
        var totalSkipped = 0
        val allRecordings = mutableListOf<Recording>()

        val speciesToProcess = if (params.speciesList.isNotEmpty()) {
            params.speciesList
        } else {
            listOf("")   // empty string = query by country/continent only
        }

        for (species in speciesToProcess) {
            val query = buildQuery(params, species)
            callback.onProgress("Querying: $query")

            val recordings = fetchRecordings(query, apiKey, callback)
            allRecordings.addAll(recordings)
            callback.onProgress("Found ${recordings.size} recordings for: ${species.ifEmpty { "all species" }}")

            val filtered = recordings.filter {
                it.quality in params.qualityFilter &&
                        (params.soundTypes.isEmpty() || it.soundType in params.soundTypes)
            }

            val bySpecies = filtered.groupBy { it.scientificName }

            for ((sciName, speciesRecs) in bySpecies) {
                // Enforce total limit per species across all sound types
                val sorted = speciesRecs.sortedBy { "ABCDE".indexOf(it.quality) }
                val toDownload = if (params.maxPerSpecies == Int.MAX_VALUE)
                    sorted
                else
                    sorted.take(params.maxPerSpecies)

                callback.onProgress("$sciName: ${toDownload.size} recordings selected from ${speciesRecs.size} available")

                // Group by sound type for folder structure
                val byType = toDownload.groupBy { it.soundType }

                for ((soundType, recs) in byType) {
                    val speciesDir = File(birderRoot, "$sciName/$soundType")
                    speciesDir.mkdirs()

                    for (rec in recs) {
                        val fileName = "XC${rec.xcId}.mp3"
                        val outFile = File(speciesDir, fileName)

                        if (outFile.exists()) {
                            callback.onProgress("Skipping existing: $fileName")
                            totalSkipped++
                            continue
                        }

                        callback.onProgress("Downloading: ${rec.commonName} - ${rec.soundType} (XC${rec.xcId})")

                        try {
                            val request = Request.Builder()
                                .url(rec.fileUrl)
                                .build()
                            val response = client.newCall(request).execute()
                            response.body?.byteStream()?.use { input ->
                                outFile.outputStream().use { output ->
                                    input.copyTo(output)
                                }
                            }
                            totalDownloaded++
                            callback.onProgress("✓ Saved: $fileName")
                        } catch (e: Exception) {
                            callback.onError("Failed to download XC${rec.xcId}: ${e.message}")
                            outFile.delete()
                        }
                    }
                }
            }
        }

        // Update metadata.json
        callback.onProgress("Updating metadata.json...")
        updateMetadata(birderRoot, allRecordings, callback)

        callback.onComplete(totalDownloaded, totalSkipped)
    }

    /**
     * Rebuild metadata.json by scanning all folders and reading existing data.
     */
    private fun updateMetadata(birderRoot: File, allDownloaded: List<Recording>, callback: DownloadCallback) {
        val metadataFile = File(birderRoot, "metadata.json")

        // Load existing metadata if present
        val metadata = if (metadataFile.exists()) {
            try {
                JSONObject(metadataFile.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                callback.onProgress("⚠ Could not read existing metadata, creating fresh.")
                JSONObject()
            }
        } else {
            JSONObject()
        }

        // Group recordings by scientific name
        val bySpecies = allDownloaded.groupBy { it.scientificName }

        for ((sciName, recordings) in bySpecies) {
            // Get or create species entry
            val speciesObj = if (metadata.has(sciName))
                metadata.getJSONObject(sciName)
            else
                JSONObject()

            // Group by sound type
            val byType = recordings.groupBy { it.soundType }

            for ((soundType, recs) in byType) {
                // Get existing array for this sound type or create new
                val existing = if (speciesObj.has(soundType))
                    speciesObj.getJSONArray(soundType)
                else
                    org.json.JSONArray()

                // Collect existing xc_ids to avoid duplicates
                val existingIds = mutableSetOf<String>()
                for (i in 0 until existing.length()) {
                    val entry = existing.optJSONObject(i)
                    if (entry != null) existingIds.add(entry.optString("xc_id"))
                }

                // Add new recordings
                for (rec in recs) {
                    if (rec.xcId in existingIds) continue
                    val entry = JSONObject().apply {
                        put("xc_id",           rec.xcId)
                        put("scientific_name", rec.scientificName)
                        put("common_name",     rec.commonName)
                        put("slovenian_name",  "")   // patched later via xlsx
                        put("quality",         rec.quality)
                        put("length",          rec.length)
                        put("recordist",       rec.recordist)
                        put("country",         rec.country)
                        put("date",            rec.date)
                        put("license",         rec.license)
                        put("xc_url",          rec.xcUrl)
                    }
                    existing.put(entry)
                }

                speciesObj.put(soundType, existing)
            }

            // Ensure standard sound type keys exist even if empty
            listOf("aberrant",
                "advertisement call",
                "agonistic call",
                "alarm call",
                "begging call",
                "call",
                "calling song",
                "courtship song",
                "dawn song",
                "defensive call",
                "distress call",
                "disturbance song",
                "drumming",
                "duet",
                "echolocation",
                "feeding buzz",
                "female song",
                "flight call",
                "flight song",
                "imitation",
                "mating call",
                "mechanical sound",
                "nocturnal flight call",
                "release call",
                "rivalry song",
                "searching song",
                "social call",
                "song",
                "subsong",
                "territorial call").forEach { type ->
                if (!speciesObj.has(type)) speciesObj.put(type, org.json.JSONArray())
            }

            metadata.put(sciName, speciesObj)
        }

        // Write to file
        try {
            metadataFile.writeText(metadata.toString(2), Charsets.UTF_8)
            callback.onProgress("✓ metadata.json saved with ${metadata.length()} species.")
        } catch (e: Exception) {
            callback.onError("Failed to write metadata.json: ${e.message}")
        }
    }
}