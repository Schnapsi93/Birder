package com.yourname.birder

import android.util.Log
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object MetadataPatcher {

    private const val TAG = "MetadataPatcher"

    fun loadSlovenianNames(xlsxFile: File): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val wb = WorkbookFactory.create(xlsxFile)
            val sheet = wb.getSheetAt(0)
            var rowCount = 0
            sheet.forEachIndexed { rowIdx, row ->
                if (rowIdx == 0) return@forEachIndexed  // skip header
                val sciCell = row.getCell(8)
                val sloCell = row.getCell(9)
                val sci = sciCell?.toString()?.trim() ?: return@forEachIndexed
                val slo = sloCell?.toString()?.trim() ?: ""
                if (sci.isNotEmpty()) {
                    map[normalise(sci)] = slo
                    rowCount++
                }
            }
            wb.close()
            Log.d(TAG, "Loaded $rowCount entries from xlsx")

            // Log first 5 entries to verify
            map.entries.take(5).forEach { (k, v) ->
                Log.d(TAG, "  xlsx entry: '$k' -> '$v'")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read xlsx: ${e.message}")
        }
        return map
    }

    fun patch(metadataFile: File, xlsxFile: File): PatchResult {
        val sloMap = loadSlovenianNames(xlsxFile)
        if (sloMap.isEmpty()) return PatchResult(0, 0, "Could not read xlsx file — 0 entries loaded")

        Log.d(TAG, "Loaded ${sloMap.size} Slovenian names from xlsx")

        val metadataText = try {
            metadataFile.readText(Charsets.UTF_8)
        } catch (e: Exception) {
            return PatchResult(0, 0, "Could not read metadata.json: ${e.message}")
        }

        val metadata = try {
            JSONObject(metadataText)
        } catch (e: Exception) {
            return PatchResult(0, 0, "Could not parse metadata.json: ${e.message}")
        }

        var matched = 0
        var unmatched = 0

        val sciNames = metadata.keys().asSequence().toList()
        Log.d(TAG, "Species in metadata: ${sciNames.size}")

        // Build entirely new root object
        val newMetadata = JSONObject()

        for (sciName in sciNames) {
            val normKey = normalise(sciName)
            val slo = sloMap[normKey]

            Log.d(TAG, "Looking up '$sciName' as '$normKey' -> ${slo ?: "NOT FOUND"}")

            if (slo != null && slo.isNotEmpty()) matched++ else unmatched++

            val speciesData = metadata.getJSONObject(sciName)
            val soundTypes = speciesData.keys().asSequence().toList()

            // Build new species object
            val newSpeciesData = JSONObject()

            for (soundType in soundTypes) {
                val recordings = speciesData.optJSONArray(soundType)
                if (recordings == null) {
                    newSpeciesData.put(soundType, JSONArray())
                    continue
                }

                val newArray = JSONArray()

                for (i in 0 until recordings.length()) {
                    val rec = recordings.optJSONObject(i)
                    if (rec == null) continue

                    // Build new recording object field by field
                    val newRec = JSONObject()
                    val keys = rec.keys().asSequence().toList()
                    var sloInserted = false

                    for (key in keys) {
                        // Skip old slovenian_name — we will reinsert with correct value
                        if (key == "slovenian_name") continue

                        newRec.put(key, rec.get(key))

                        if (key == "common_name" && !sloInserted) {
                            newRec.put("slovenian_name", slo ?: "")
                            sloInserted = true
                            Log.d(TAG, "  Inserted slovenian_name='$slo' after common_name")
                        }
                    }
                    if (!sloInserted) {
                        newRec.put("slovenian_name", slo ?: "")
                    }

                    newArray.put(newRec)
                }

                newSpeciesData.put(soundType, newArray)
            }

            newMetadata.put(sciName, newSpeciesData)
        }

        // Verify before writing
        val outputText = newMetadata.toString(2)
        Log.d(TAG, "Output contains '${sloMap.values.firstOrNull()}': ${outputText.contains(sloMap.values.firstOrNull() ?: "")}")
        Log.d(TAG, "Output length: ${outputText.length} chars")

        return try {
            val backup = File(metadataFile.parent, "metadata.json.bak")
            metadataFile.copyTo(backup, overwrite = true)
            metadataFile.writeText(outputText, Charsets.UTF_8)

            // Readback verify
            val readBack = JSONObject(metadataFile.readText(Charsets.UTF_8))
            val firstSpecies = readBack.keys().asSequence().firstOrNull()
            val firstRec = firstSpecies
                ?.let { readBack.getJSONObject(it) }
                ?.keys()?.asSequence()?.firstOrNull()
                ?.let { readBack.getJSONObject(firstSpecies).optJSONArray(it) }
                ?.optJSONObject(0)
            Log.d(TAG, "Readback slovenian_name: '${firstRec?.optString("slovenian_name")}'")
            Log.d(TAG, "File size after write: ${metadataFile.length()} bytes")

            PatchResult(matched, unmatched, null)
        } catch (e: Exception) {
            PatchResult(matched, unmatched, "Failed to save: ${e.message}")
        }
    }

    private fun normalise(name: String): String =
        name.lowercase().replace(".", "").replace(Regex("\\s+"), " ").trim()

    data class PatchResult(val matched: Int, val unmatched: Int, val error: String?)
}