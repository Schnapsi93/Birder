package com.yourname.birder

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity

class SpeciesMenuActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_species_menu)

        findViewById<Button>(R.id.btnSetSpeciesToTrain).setOnClickListener {
            startActivity(Intent(this, SpeciesSelectActivity::class.java))
        }

        findViewById<Button>(R.id.btnDownloadSpecies).setOnClickListener {
            startActivity(Intent(this, DownloadActivity::class.java))
        }

        findViewById<Button>(R.id.btnRemoveSpecies).setOnClickListener {
            startActivity(Intent(this, RemoveSpeciesActivity::class.java))
        }
    }
}